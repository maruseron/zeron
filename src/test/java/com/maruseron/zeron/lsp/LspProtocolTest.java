package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LspProtocolTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void servesDiagnosticsAndNavigationOverStdioProtocol() throws Exception {
        final var projectRoot = temporaryFolder.newFolder().toPath();
        final var sourceRoot = Files.createDirectories(projectRoot.resolve("src"));
        final var source = """
                fn greet(): Int = 1;
                fn main() {
                    greet();
                }
                """;
        final var entry = sourceRoot.resolve("main.zn");
        Files.writeString(entry, source);
        Files.writeString(sourceRoot.resolve("closed.zn"), "fn broken( {\n");
        final var manifest = projectRoot.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                entry = "src/main.zn"
                source-roots = ["src"]
                output = "dist"

                [platform]
                stdlib = "source"
                """);

        final var clientToServer = new PipedOutputStream();
        final var serverInput = new PipedInputStream(clientToServer, 65536);
        final var serverToClient = new PipedOutputStream();
        final var clientInput = new PipedInputStream(serverToClient, 65536);
        final var executor = Executors.newFixedThreadPool(2);
        final var client = new RecordingClient();
        final var server = new ZeronLanguageServer(ProjectAnalysisWorkspace.open(manifest));
        try {
            final var serverLauncher = LSPLauncher.createServerLauncher(
                    server, serverInput, serverToClient);
            server.connect(serverLauncher.getRemoteProxy());
            executor.submit(() -> serverLauncher.startListening().get());

            final var clientLauncher = LSPLauncher.createClientLauncher(client, clientInput, clientToServer);
            final var remote = clientLauncher.getRemoteProxy();
            executor.submit(() -> clientLauncher.startListening().get());

            final var initialized = remote.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
            assertEquals("utf-16", initialized.getCapabilities().getPositionEncoding());
            assertNotNull(initialized.getCapabilities().getCodeActionProvider());
            remote.initialized();
            final var uri = entry.toUri().toString();
            remote.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "zeron", 1, source)));

            final var initialDiagnostics = client.diagnostics.poll(5, TimeUnit.SECONDS);
            assertNotNull(initialDiagnostics);
            assertEquals(uri, initialDiagnostics.getUri());
            assertTrue(initialDiagnostics.getDiagnostics().isEmpty());
            assertFalse(client.diagnostics.stream().anyMatch(
                    params -> params.getUri().equals(sourceRoot.resolve("closed.zn").toUri().toString())));

            final var useOffset = source.lastIndexOf("greet");
            final var usePosition = DocumentCoordinates.positionAt(source, useOffset);
            final var completion = remote.getTextDocumentService()
                    .completion(new CompletionParams(new TextDocumentIdentifier(uri),
                            DocumentCoordinates.positionAt(source, source.indexOf("greet();") + 3)))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(completion.isRight());
            assertTrue(completion.getRight().getItems().stream()
                    .anyMatch(item -> item.getLabel().equals("greet()")));

            final var definition = remote.getTextDocumentService()
                    .definition(new DefinitionParams(new TextDocumentIdentifier(uri), usePosition))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(definition.isLeft());
            assertEquals(entry.toUri().toString(), definition.getLeft().getFirst().getUri());
            assertEquals(0, definition.getLeft().getFirst().getRange().getStart().getLine());

            final var invalid = """
                    fn greet(): Int = 1;
                    fn main() {
                        let value: String = "valid";
                        value;
                    }
                    fn broken(): Int = "wrong";
                    """;
            remote.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 2),
                    List.of(new TextDocumentContentChangeEvent(invalid))));
            final var invalidDiagnostics = waitForVersion(client.diagnostics, uri, 2);
            assertFalse(invalidDiagnostics.getDiagnostics().isEmpty());

            remote.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 1),
                    List.of(new TextDocumentContentChangeEvent(source))));
            final var stillInvalid = remote.getTextDocumentService()
                    .hover(new org.eclipse.lsp4j.HoverParams(new TextDocumentIdentifier(uri),
                            DocumentCoordinates.positionAt(invalid, invalid.lastIndexOf("value;"))))
                    .get(5, TimeUnit.SECONDS);
            assertNotNull(stillInvalid);
            assertTrue(stillInvalid.getContents().getRight().getValue().contains("String"));

            final var invalidLiteralStart = invalid.indexOf("\"wrong\"");
            final var rangedChange = new TextDocumentContentChangeEvent();
            rangedChange.setRange(DocumentCoordinates.rangeAt(invalid,
                    invalidLiteralStart, invalidLiteralStart + "\"wrong\"".length()));
            rangedChange.setText("2");
            remote.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 3), List.of(rangedChange)));
            final var correctedDiagnostics = waitForVersion(client.diagnostics, uri, 3);
            assertNotNull(correctedDiagnostics);
            assertTrue(correctedDiagnostics.getDiagnostics().isEmpty());

            remote.getTextDocumentService().didClose(new org.eclipse.lsp4j.DidCloseTextDocumentParams(
                    new TextDocumentIdentifier(uri)));
            final var clearedDiagnostics = waitForClear(client.diagnostics, uri);
            assertNotNull(clearedDiagnostics);
            assertTrue(clearedDiagnostics.getDiagnostics().isEmpty());
        } finally {
            server.close();
            clientToServer.close();
            serverToClient.close();
            serverInput.close();
            clientInput.close();
            executor.shutdownNow();
        }
    }

    private static PublishDiagnosticsParams waitForVersion(
            final BlockingQueue<PublishDiagnosticsParams> queue, final String uri, final int version)
            throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final var params = queue.poll(100, TimeUnit.MILLISECONDS);
            if (params != null && params.getUri().equals(uri)
                    && params.getVersion() != null && params.getVersion() == version) return params;
        }
        return null;
    }

    private static PublishDiagnosticsParams waitForClear(
            final BlockingQueue<PublishDiagnosticsParams> queue, final String uri)
            throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final var params = queue.poll(100, TimeUnit.MILLISECONDS);
            if (params != null && params.getUri().equals(uri)
                    && params.getDiagnostics().isEmpty()) return params;
        }
        return null;
    }

    private static final class RecordingClient implements LanguageClient {
        private final BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();

        @Override public void telemetryEvent(final Object object) {}
        @Override public void publishDiagnostics(final PublishDiagnosticsParams params) {
            diagnostics.add(params);
        }
        @Override public void showMessage(final org.eclipse.lsp4j.MessageParams params) {}
        @Override public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
                final org.eclipse.lsp4j.ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(final org.eclipse.lsp4j.MessageParams params) {}
    }
}
