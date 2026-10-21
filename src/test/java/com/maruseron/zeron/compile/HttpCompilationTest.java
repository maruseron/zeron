package com.maruseron.zeron.compile;

import com.sun.net.httpserver.HttpServer;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HttpCompilationTest {
    @Test
    public void bindsAndBundlesTheHttpRuntimeWithoutUserClasspathConfiguration() throws Exception {
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-http-");
        final var packageName = "zeron.net";
        final var unit = parse("HttpProbe.zn", """
                package zeron.net;
                external fn httpValidateUri(value: String?): Int;
                public fn validate(value: String): Int = httpValidateUri(value);
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".HttpProbe", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            assertTrue(Files.exists(output.resolve(Path.of("zeron", "runtime", "HttpSupport.class"))));
            assertTrue(Files.exists(output.resolve(Path.of("zeron", "runtime",
                    "HttpSupport$TimedBodyInputStream.class"))));
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                final var probe = loader.loadClass(packageName + ".HttpProbe");
                assertEquals(0, probe.getMethod("validate", String.class)
                        .invoke(null, "https://example.com/path"));
                assertEquals(2, probe.getMethod("validate", String.class)
                        .invoke(null, "ftp://example.com/path"));

                final var support = loader.loadClass("zeron.runtime.HttpSupport");
                final var client = support.getMethod("createClient", long.class)
                        .invoke(null, 1_000_000_000L);
                try {
                    final var outcome = support.getMethod("send", Object.class, String.class,
                                    String.class, Object[].class, byte[].class, long.class, long.class)
                            .invoke(null, client,
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/", "GET",
                                    new Object[0], null, 2_000_000_000L, 1_000_000_000L);
                    assertTrue((Boolean) support.getMethod("sendSucceeded", Object.class)
                            .invoke(null, outcome));
                    final var response = support.getMethod("sendResponse", Object.class)
                            .invoke(null, outcome);
                    final var version = support.getMethod("responseVersion", Object.class)
                            .invoke(null, response);
                    assertTrue(List.of("HTTP/1.1", "HTTP/2").contains(version));
                    support.getMethod("responseDispose", Object.class).invoke(null, response);
                } finally {
                    support.getMethod("clientDispose", Object.class).invoke(null, client);
                }
            } finally {
                server.stop(0);
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
