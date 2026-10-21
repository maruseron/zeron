package zeron.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HttpSupportTest {
    @Test
    public void validatesUrisMethodsAndHeaders() {
        assertEquals(HttpSupport.URI_VALID, HttpSupport.validateUri("https://example.com/path"));
        assertEquals(HttpSupport.URI_INVALID, HttpSupport.validateUri("/relative/path"));
        assertEquals(HttpSupport.URI_UNSUPPORTED_SCHEME, HttpSupport.validateUri("ftp://example.com"));
        assertEquals(HttpSupport.URI_MISSING_HOST, HttpSupport.validateUri("http:///path"));
        assertEquals(HttpSupport.URI_FRAGMENT_NOT_ALLOWED,
                HttpSupport.validateUri("http://example.com/path#fragment"));
        assertEquals(HttpSupport.URI_INVALID, HttpSupport.validateUri("http://[invalid"));

        assertTrue(HttpSupport.validateMethod("PATCH"));
        assertFalse(HttpSupport.validateMethod("bad method"));
        assertEquals(HttpSupport.HEADER_VALID, HttpSupport.validateHeader("X-Trace", "safe"));
        assertEquals(HttpSupport.HEADER_INVALID_NAME, HttpSupport.validateHeader("Bad Name", "safe"));
        assertEquals(HttpSupport.HEADER_INVALID_VALUE, HttpSupport.validateHeader("X-Trace", "bad\r\nvalue"));
        assertEquals(HttpSupport.HEADER_RESTRICTED, HttpSupport.validateHeader("Host", "example.com"));
    }

    @Test
    public void sendsBufferedRequestsAndLeavesRedirectsForTheCaller() throws Exception {
        final var received = new AtomicReference<String>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.set(exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("X-Trace")
                    + "|" + body);
            exchange.getResponseHeaders().add("X-Reply", "received");
            final var response = "echo:" + body.getBytes(StandardCharsets.UTF_8).length;
            final var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/echo");
            final var bytes = "redirect".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        final var client = HttpSupport.createClient(1_000_000_000L);
        try {
            final var baseUri = "http://127.0.0.1:" + server.getAddress().getPort();
            final var outcome = HttpSupport.send(client, baseUri + "/echo", "POST",
                    new Object[]{"X-Trace", "unit-test"},
                    "payload".getBytes(StandardCharsets.UTF_8), 2_000_000_000L, 1_000_000_000L);
            assertTrue(HttpSupport.sendSucceeded(outcome));
            final var response = HttpSupport.sendResponse(outcome);
            assertEquals(201, HttpSupport.responseStatus(response));
            assertTrue(HttpSupport.responseVersion(response).equals("HTTP/1.1")
                    || HttpSupport.responseVersion(response).equals("HTTP/2"));
            assertTrue(hasHeader(HttpSupport.responseHeaders(response), "X-reply", "received"));
            assertEquals("POST|unit-test|payload", received.get());
            assertEquals("echo:7", readBody(response));
            assertNull(HttpSupport.responseDispose(response));

            final var redirectOutcome = HttpSupport.send(client, baseUri + "/redirect", "GET",
                    new Object[0], null, 2_000_000_000L, 1_000_000_000L);
            assertTrue(HttpSupport.sendSucceeded(redirectOutcome));
            final var redirect = HttpSupport.sendResponse(redirectOutcome);
            assertEquals(302, HttpSupport.responseStatus(redirect));
            assertTrue(hasHeader(HttpSupport.responseHeaders(redirect), "Location", "/echo"));
            assertEquals("redirect", readBody(redirect));
            assertNull(HttpSupport.responseDispose(redirect));
        } finally {
            HttpSupport.clientDispose(client);
            server.stop(0);
        }
    }

    @Test
    public void reportsResponseBodyIdleTimeoutAsAnIoFailure() throws Exception {
        final var releaseBody = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try {
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                releaseBody.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(2);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (final java.io.IOException ignored) {
                // The client closes the body when the idle timeout expires.
            } finally {
                exchange.close();
            }
        });
        server.start();

        final var client = HttpSupport.createClient(1_000_000_000L);
        try {
            final var uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
            final var outcome = HttpSupport.send(client, uri, "GET", new Object[0], null,
                    2_000_000_000L, 50_000_000L);
            if (!HttpSupport.sendSucceeded(outcome)) {
                fail(HttpSupport.sendFailureMessage(outcome));
            }
            final var response = HttpSupport.sendResponse(outcome);
            assertEquals(1, HttpSupport.responseRead(response, new byte[4], 0, 4));
            assertEquals(-2,
                    HttpSupport.responseRead(response, new byte[4], 0, 4));
            final var failure = HttpSupport.responseReadFailure(response);
            assertTrue(failure.toString(), failure instanceof SocketTimeoutException);
            HttpSupport.responseDispose(response);
        } finally {
            releaseBody.countDown();
            HttpSupport.clientDispose(client);
            server.stop(0);
        }
    }

    private static String readBody(final Object response) {
        final var buffer = new byte[32];
        final var output = new ByteArrayOutputStream();
        int count;
        while ((count = HttpSupport.responseRead(response, buffer, 0, buffer.length)) > 0) {
            output.write(buffer, 0, count);
        }
        assertEquals(-1, count);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static boolean hasHeader(final Object[] pairs, final String name, final String value) {
        assertEquals(0, pairs.length & 1);
        for (var index = 0; index < pairs.length; index += 2) {
            if (((String) pairs[index]).equalsIgnoreCase(name) && value.equals(pairs[index + 1])) {
                return true;
            }
        }
        return false;
    }
}
