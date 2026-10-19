package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.Severity;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

final class ZeronTextDocumentService implements TextDocumentService, AutoCloseable {
    private record OpenDocument(int version, String text, String uri) {}
    private record RevisionSnapshot(long revision, ProjectAnalysisWorkspace.Snapshot snapshot) {}

    private final ProjectAnalysisWorkspace workspace;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(task -> {
        final var thread = new Thread(task, "zeron-lsp-analysis");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong revision = new AtomicLong();
    private final Set<String> reportedUnassociatedDiagnostics = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Path, OpenDocument> openDocuments = new HashMap<>();
    private final Object lock = new Object();
    private volatile CompletableFuture<RevisionSnapshot> currentAnalysis;
    private volatile Future<?> currentAnalysisTask;
    private volatile long currentAnalysisRevision = -1;
    private volatile LanguageClient client;

    ZeronTextDocumentService(final ProjectAnalysisWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace);
    }

    void connect(final LanguageClient client) {
        this.client = client;
    }

    @Override
    public void didOpen(final DidOpenTextDocumentParams params) {
        final var document = params.getTextDocument();
        final Path path;
        try {
            path = documentPath(document.getUri());
        } catch (final IllegalArgumentException exception) {
            log("Ignoring unsupported document URI: " + exception.getMessage());
            return;
        }
        if (!isProjectSource(path)) {
            log("Ignoring document outside configured Zeron source roots: " + document.getUri());
            return;
        }
        synchronized (lock) {
            openDocuments.put(path, new OpenDocument(document.getVersion(), document.getText(),
                    document.getUri()));
            scheduleAnalysis();
        }
    }

    @Override
    public void didChange(final DidChangeTextDocumentParams params) {
        final var identifier = params.getTextDocument();
        final Path path;
        try {
            path = documentPath(identifier.getUri());
        } catch (final IllegalArgumentException exception) {
            log("Ignoring change for unsupported document URI: " + exception.getMessage());
            return;
        }
        synchronized (lock) {
            final var open = openDocuments.get(path);
            if (open == null) return;
            final var nextVersion = identifier.getVersion();
            if (nextVersion == null || nextVersion <= open.version()) return;
            var text = open.text();
            for (final var change : params.getContentChanges()) {
                text = applyChange(text, change);
            }
            openDocuments.put(path, new OpenDocument(nextVersion, text, identifier.getUri()));
            scheduleAnalysis();
        }
    }

    @Override
    public void didClose(final DidCloseTextDocumentParams params) {
        final Path path;
        try {
            path = documentPath(params.getTextDocument().getUri());
        } catch (final IllegalArgumentException exception) {
            log("Ignoring close for unsupported document URI: " + exception.getMessage());
            return;
        }
        final var uri = params.getTextDocument().getUri();
        synchronized (lock) {
            openDocuments.remove(path);
            scheduleAnalysis();
        }
        final var languageClient = client;
        if (languageClient != null) {
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(final DidSaveTextDocumentParams params) {
        try {
            final var path = documentPath(params.getTextDocument().getUri());
            synchronized (lock) {
                if (openDocuments.containsKey(path)) scheduleAnalysis();
            }
        } catch (final IllegalArgumentException exception) {
            log("Ignoring save for unsupported document URI: " + exception.getMessage());
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            final CompletionParams params) {
        return withSnapshot(params.getTextDocument().getUri(), snapshot -> {
            final var path = documentPath(params.getTextDocument().getUri());
            return Either.forRight(LspFeatures.completion(snapshot, path, params.getPosition()));
        }, Either.forRight(new CompletionList(false, List.of())));
    }

    @Override
    public CompletableFuture<Hover> hover(final HoverParams params) {
        return withSnapshot(params.getTextDocument().getUri(), snapshot -> {
            final var path = documentPath(params.getTextDocument().getUri());
            return LspFeatures.hover(snapshot, path, params);
        }, null);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>,
            List<? extends org.eclipse.lsp4j.LocationLink>>> definition(final DefinitionParams params) {
        return withSnapshot(params.getTextDocument().getUri(), snapshot -> {
            final var path = documentPath(params.getTextDocument().getUri());
            return LspFeatures.definition(snapshot, path, params);
        }, Either.forLeft(List.of()));
    }

    private <T> CompletableFuture<T> withSnapshot(final String uri,
                                                  final SnapshotOperation<T> operation,
                                                  final T empty) {
        final Path path;
        try {
            path = documentPath(uri);
        } catch (final IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(empty);
        }
        final var analysis = analysisForCurrentRevision();
        final var response = new CompletableFuture<T>();
        analysis.whenComplete((result, failure) -> {
            if (failure != null) {
                final var cause = failure.getCause() == null ? failure : failure.getCause();
                if (cause instanceof CancellationException) {
                    response.cancel(false);
                } else {
                    log("Project analysis failed: " + cause.getMessage());
                    response.complete(empty);
                }
                return;
            }
            if (result.revision() != revision.get() || !isOpen(path)) {
                response.complete(empty);
                return;
            }
            try {
                response.complete(operation.apply(result.snapshot()));
            } catch (final RuntimeException | Error exception) {
                response.completeExceptionally(exception);
            }
        });
        return response;
    }

    private CompletableFuture<RevisionSnapshot> scheduleAnalysis() {
        final long requestedRevision;
        synchronized (lock) {
            requestedRevision = revision.incrementAndGet();
            return startAnalysis(requestedRevision);
        }
    }

    private CompletableFuture<RevisionSnapshot> analysisForCurrentRevision() {
        synchronized (lock) {
            final var requestedRevision = revision.get();
            final var current = currentAnalysis;
            if (current != null && currentAnalysisRevision == requestedRevision) {
                return current;
            }
            return startAnalysis(requestedRevision);
        }
    }

    private CompletableFuture<RevisionSnapshot> startAnalysis(final long requestedRevision) {
        cancelCurrentAnalysis();
        final Map<Path, ProjectAnalysisWorkspace.Overlay> overlays;
        final var copy = new HashMap<Path, ProjectAnalysisWorkspace.Overlay>();
        openDocuments.forEach((path, document) -> copy.put(path,
                new ProjectAnalysisWorkspace.Overlay(document.version(), document.text())));
        overlays = Map.copyOf(copy);
        final var future = new CompletableFuture<RevisionSnapshot>();
        final var task = new FutureTask<Void>(() -> {
            try {
                future.complete(new RevisionSnapshot(requestedRevision, workspace.analyze(overlays)));
            } catch (final IOException | RuntimeException | Error exception) {
                future.completeExceptionally(exception);
                throw exception;
            }
            return null;
        });
        currentAnalysis = future;
        currentAnalysisTask = task;
        currentAnalysisRevision = requestedRevision;
        future.whenComplete((result, failure) -> {
            if (failure != null) {
                if (requestedRevision == revision.get()) {
                    final var cause = failure.getCause() == null ? failure : failure.getCause();
                    log("Project analysis failed: " + cause.getMessage());
                }
            } else if (requestedRevision == revision.get()) {
                publishDiagnostics(result.snapshot());
            }
        });
        analysisExecutor.execute(task);
        return future;
    }

    private void cancelCurrentAnalysis() {
        final var previous = currentAnalysis;
        if (previous == null) return;
        previous.cancel(true);
        final var task = currentAnalysisTask;
        if (task != null) task.cancel(true);
    }

    private void publishDiagnostics(final ProjectAnalysisWorkspace.Snapshot snapshot) {
        final var languageClient = client;
        if (languageClient == null) return;
        for (final var diagnostic : snapshot.unassociatedDiagnostics()) {
            final var message = diagnostic.code() + ": " + diagnostic.primarySpan().sourcePath()
                    + ": " + diagnostic.message();
            if (reportedUnassociatedDiagnostics.add(message)) log("Project analysis: " + message);
        }
        final List<Map.Entry<Path, OpenDocument>> documents;
        synchronized (lock) {
            documents = List.copyOf(openDocuments.entrySet());
        }
        for (final var entry : documents) {
            final var path = entry.getKey();
            final var text = snapshot.sourceText().get(path);
            if (text == null) continue;
            final var diagnostics = snapshot.diagnostics().getOrDefault(path, List.of()).stream()
                    .map(diagnostic -> toLspDiagnostic(diagnostic, text))
                    .toList();
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(
                    entry.getValue().uri(), diagnostics, entry.getValue().version()));
        }
    }

    private static org.eclipse.lsp4j.Diagnostic toLspDiagnostic(final Diagnostic diagnostic,
                                                                 final String text) {
        final var span = diagnostic.primarySpan();
        final var start = span.start().offset();
        final var end = span.end().offset();
        final Range range;
        if (start != null && end != null) {
            range = DocumentCoordinates.rangeAt(text, start, end);
        } else {
            range = new Range(
                    new Position(span.start().line() - 1,
                            Math.max(0, Objects.requireNonNullElse(span.start().column(), 1) - 1)),
                    new Position(span.end().line() - 1,
                            Math.max(0, Objects.requireNonNullElse(span.end().column(), 1) - 1)));
        }
        final var result = new org.eclipse.lsp4j.Diagnostic(range, diagnostic.message());
        result.setCode(diagnostic.code().value());
        result.setSource("zeron");
        result.setSeverity(switch (diagnostic.severity()) {
            case ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error;
            case WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning;
            case INFO -> org.eclipse.lsp4j.DiagnosticSeverity.Information;
        });
        return result;
    }

    private static String applyChange(final String source,
                                      final TextDocumentContentChangeEvent change) {
        final var range = change.getRange();
        if (range == null) return change.getText();
        final var start = DocumentCoordinates.offsetAt(source, range.getStart());
        final var end = DocumentCoordinates.offsetAt(source, range.getEnd());
        if (end < start) throw new IllegalArgumentException("Text change range end precedes start.");
        return source.substring(0, start) + change.getText() + source.substring(end);
    }

    private Path documentPath(final String uri) {
        final var parsed = URI.create(uri);
        if (!"file".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException("Only file URIs are supported: " + uri);
        }
        return Path.of(parsed).toAbsolutePath().normalize();
    }

    private boolean isProjectSource(final Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().endsWith(".zn")
                && workspace.sourceRoots().stream().anyMatch(path::startsWith);
    }

    private boolean isOpen(final Path path) {
        synchronized (lock) {
            return openDocuments.containsKey(path);
        }
    }

    private void log(final String message) {
        final var languageClient = client;
        if (languageClient != null) {
            languageClient.logMessage(new MessageParams(MessageType.Error, message));
        } else {
            System.err.println(message);
        }
    }

    @Override
    public void close() {
        cancelCurrentAnalysis();
        analysisExecutor.shutdownNow();
    }

    @FunctionalInterface
    private interface SnapshotOperation<T> {
        T apply(ProjectAnalysisWorkspace.Snapshot snapshot);
    }
}
