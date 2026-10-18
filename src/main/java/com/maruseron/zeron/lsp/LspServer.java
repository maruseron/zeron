package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

public final class LspServer {
    private LspServer() {}

    public static int run(final Path manifest) {
        final ProjectAnalysisWorkspace workspace;
        try {
            workspace = ProjectAnalysisWorkspace.open(manifest);
        } catch (final IOException | IllegalArgumentException exception) {
            System.err.println("Unable to start Zeron language server: " + exception.getMessage());
            return 1;
        }

        final var server = new ZeronLanguageServer(workspace);
        final var launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        try {
            launcher.startListening().get();
            return 0;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (final ExecutionException exception) {
            System.err.println("Zeron language server stopped after a protocol error: "
                    + exception.getCause().getMessage());
            return 1;
        } finally {
            server.close();
        }
    }
}
