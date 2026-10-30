package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;

final class ZeronLanguageServer implements LanguageServer, LanguageClientAware, AutoCloseable {
    private final ZeronTextDocumentService textDocumentService;
    private LanguageClient client;

    ZeronLanguageServer(final ProjectAnalysisWorkspace workspace) {
        textDocumentService = new ZeronTextDocumentService(workspace);
    }

    @Override
    public void connect(final LanguageClient client) {
        this.client = client;
        textDocumentService.connect(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
        final var capabilities = new ServerCapabilities();
        capabilities.setPositionEncoding(PositionEncodingKind.UTF16);
        final var synchronization = new TextDocumentSyncOptions();
        synchronization.setOpenClose(true);
        synchronization.setChange(TextDocumentSyncKind.Incremental);
        capabilities.setTextDocumentSync(synchronization);
        final var completion = new CompletionOptions();
        completion.setResolveProvider(false);
        completion.setTriggerCharacters(java.util.List.of("."));
        capabilities.setCompletionProvider(completion);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setCodeActionProvider(Either.forLeft(true));
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        close();
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override public void didChangeConfiguration(
                    final org.eclipse.lsp4j.DidChangeConfigurationParams params) {}
            @Override public void didChangeWatchedFiles(
                    final org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
        };
    }

    @Override
    public void close() {
        textDocumentService.close();
    }
}
