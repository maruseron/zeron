package zeron.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

public final class HttpSupport {
    public static final int URI_VALID = 0;
    public static final int URI_INVALID = 1;
    public static final int URI_UNSUPPORTED_SCHEME = 2;
    public static final int URI_MISSING_HOST = 3;
    public static final int URI_FRAGMENT_NOT_ALLOWED = 4;

    public static final int HEADER_VALID = 0;
    public static final int HEADER_INVALID_NAME = 1;
    public static final int HEADER_INVALID_VALUE = 2;
    public static final int HEADER_RESTRICTED = 3;

    private static final int READ_EOF = -1;
    private static final int READ_FAILED = -2;
    private static final Pattern METHOD_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final List<String> RESTRICTED_HEADERS = List.of(
            "connection", "content-length", "expect", "host", "proxy-connection",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private HttpSupport() {}

    public static int validateUri(final String text) {
        final URI uri;
        try {
            uri = new URI(text);
        } catch (java.net.URISyntaxException exception) {
            return URI_INVALID;
        }
        final var scheme = uri.getScheme();
        if (scheme == null) return URI_INVALID;
        if (!(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return URI_UNSUPPORTED_SCHEME;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) return URI_MISSING_HOST;
        if (uri.getFragment() != null) return URI_FRAGMENT_NOT_ALLOWED;
        return URI_VALID;
    }

    public static boolean validateMethod(final String method) {
        return METHOD_TOKEN.matcher(method).matches();
    }

    public static int validateHeader(final String name, final String value) {
        final var syntax = validateHeaderSyntax(name, value);
        if (syntax != HEADER_VALID) return syntax;
        if (RESTRICTED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return HEADER_RESTRICTED;
        }
        return HEADER_VALID;
    }

    public static boolean validateResponseHeaderPairs(final Object[] entries) {
        if ((entries.length & 1) != 0) return false;
        for (var index = 0; index < entries.length; index += 2) {
            if (validateHeaderSyntax((String) entries[index], (String) entries[index + 1])
                    != HEADER_VALID) return false;
        }
        return true;
    }

    private static int validateHeaderSyntax(final String name, final String value) {
        if (name.isEmpty() || !METHOD_TOKEN.matcher(name).matches()) return HEADER_INVALID_NAME;
        for (var index = 0; index < value.length(); index++) {
            final var character = value.charAt(index);
            if ((character < 0x20 && character != '\t') || character == 0x7f || character > 0xff) {
                return HEADER_INVALID_VALUE;
            }
        }
        return HEADER_VALID;
    }

    public static Object[] emptyHeaders() {
        return new Object[0];
    }

    public static Object[] appendHeader(final Object[] entries, final String name, final String value) {
        final var result = Arrays.copyOf(entries, entries.length + 2);
        result[entries.length] = name;
        result[entries.length + 1] = value;
        return result;
    }

    public static Object[] copyHeaderPairs(final Object[] entries) {
        return Arrays.copyOf(entries, entries.length);
    }

    public static Object[] headerValues(final Object[] entries, final String name) {
        final var values = new ArrayList<String>();
        for (var index = 0; index + 1 < entries.length; index += 2) {
            if (((String) entries[index]).equalsIgnoreCase(name)) {
                values.add((String) entries[index + 1]);
            }
        }
        return values.toArray();
    }

    public static Object createClient(final long connectTimeoutNanos) {
        final var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofNanos(connectTimeoutNanos))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
        return new ClientHandle(client);
    }

    public static Object send(final Object client,
                              final String uri,
                              final String method,
                              final Object[] headerEntries,
                              final byte[] body,
                              final long responseTimeoutNanos,
                              final long bodyReadIdleTimeoutNanos) {
        try {
            final var builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofNanos(responseTimeoutNanos));
            for (var index = 0; index + 1 < headerEntries.length; index += 2) {
                builder.header((String) headerEntries[index], (String) headerEntries[index + 1]);
            }
            final var requestBody = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(Arrays.copyOf(body, body.length));
            builder.method(method, requestBody);
            final var response = ((ClientHandle) client).client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            final var bodyStream = new TimedBodyInputStream(
                    response.body(), bodyReadIdleTimeoutNanos);
            return new SendOutcome(new ResponseHandle(response, bodyStream), null);
        } catch (HttpTimeoutException exception) {
            return SendOutcome.failed("Timeout", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SendOutcome.failed("Interrupted", exception);
        } catch (IOException exception) {
            return SendOutcome.failed(failureKind(exception), exception);
        }
    }

    public static boolean sendSucceeded(final Object outcome) {
        return ((SendOutcome) outcome).response != null;
    }

    public static String sendFailureKind(final Object outcome) {
        return ((SendOutcome) outcome).failure.kind;
    }

    public static String sendFailureMessage(final Object outcome) {
        return ((SendOutcome) outcome).failure.cause.getMessage() == null
                ? ((SendOutcome) outcome).failure.kind
                : ((SendOutcome) outcome).failure.cause.getMessage();
    }

    public static Object sendFailureCause(final Object outcome) {
        return ((SendOutcome) outcome).failure.cause;
    }

    public static Object sendResponse(final Object outcome) {
        return ((SendOutcome) outcome).response;
    }

    public static boolean isResponseHandle(final Object response) {
        return response instanceof ResponseHandle;
    }

    public static int responseStatus(final Object response) {
        return ((ResponseHandle) response).response.statusCode();
    }

    public static String responseVersion(final Object response) {
        final var version = ((ResponseHandle) response).response.version();
        if (version == HttpClient.Version.HTTP_1_1) return "HTTP/1.1";
        if (version == HttpClient.Version.HTTP_2) return "HTTP/2";
        return "UNSUPPORTED";
    }

    public static Object[] responseHeaders(final Object response) {
        final var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(((ResponseHandle) response).response.headers().map());
        final var entries = new ArrayList<String>();
        headers.forEach((name, values) -> values.forEach(value -> {
            entries.add(name);
            entries.add(value);
        }));
        return entries.toArray();
    }

    public static int responseRead(final Object response,
                                   final byte[] buffer,
                                   final int offset,
                                   final int length) {
        final var handle = (ResponseHandle) response;
        try {
            return handle.body.read(buffer, offset, length);
        } catch (IOException exception) {
            handle.lastReadFailure = exception;
            return READ_FAILED;
        }
    }

    public static IOException responseReadFailure(final Object response) {
        final var failure = ((ResponseHandle) response).lastReadFailure;
        return failure == null ? new IOException("The HTTP response body read failed.") : failure;
    }

    public static IOException responseDispose(final Object response) {
        try {
            ((ResponseHandle) response).body.close();
            return null;
        } catch (IOException exception) {
            return exception;
        }
    }

    public static boolean clientDispose(final Object client) {
        ((ClientHandle) client).client.close();
        return true;
    }

    private static String failureKind(final IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) return "Tls";
            if (cause instanceof UnknownHostException) return "Dns";
            if (cause instanceof HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return "Timeout";
            if (cause instanceof ConnectException) return "Connection";
            if (cause instanceof java.net.ProtocolException) return "Protocol";
        }
        return "Transport";
    }

    private static final class ClientHandle {
        private final HttpClient client;

        private ClientHandle(final HttpClient client) {
            this.client = client;
        }
    }

    private static final class SendOutcome {
        private final ResponseHandle response;
        private final Failure failure;

        private SendOutcome(final ResponseHandle response, final Failure failure) {
            this.response = response;
            this.failure = failure;
        }

        private static SendOutcome failed(final String kind, final Throwable cause) {
            return new SendOutcome(null, new Failure(kind, cause));
        }
    }

    private static final class Failure {
        private final String kind;
        private final Throwable cause;

        private Failure(final String kind, final Throwable cause) {
            this.kind = kind;
            this.cause = cause;
        }
    }

    private static final class ResponseHandle {
        private final HttpResponse<InputStream> response;
        private final TimedBodyInputStream body;
        private volatile IOException lastReadFailure;

        private ResponseHandle(final HttpResponse<InputStream> response,
                               final TimedBodyInputStream body) {
            this.response = response;
            this.body = body;
        }
    }

    private static final class TimedBodyInputStream extends InputStream {
        private static final ReadEvent END = new ReadEvent(null, true, null);
        private final InputStream source;
        private final long idleTimeoutNanos;
        private final BlockingQueue<ReadEvent> chunks = new ArrayBlockingQueue<>(2);
        private final AtomicBoolean closed = new AtomicBoolean();
        private Thread pump;
        private byte[] currentChunk;
        private int currentOffset;
        private boolean ended;

        private TimedBodyInputStream(final InputStream source, final long idleTimeoutNanos) {
            this.source = source;
            this.idleTimeoutNanos = idleTimeoutNanos;
            pump = Thread.ofVirtual().name("zeron-http-body").start(this::pump);
        }

        @Override
        public int read() throws IOException {
            final var one = new byte[1];
            final var count = read(one, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (closed.get()) throw new IOException("HTTP response body is closed.");
            if (ended) return -1;
            if (currentChunk == null || currentOffset == currentChunk.length) loadChunk();
            if (ended) return -1;
            final var count = Math.min(length, currentChunk.length - currentOffset);
            System.arraycopy(currentChunk, currentOffset, buffer, offset, count);
            currentOffset += count;
            return count;
        }

        private void loadChunk() throws IOException {
            final ReadEvent event;
            try {
                event = chunks.poll(idleTimeoutNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading HTTP response body.", exception);
            }
            if (event == null) {
                final var timeout = new java.net.SocketTimeoutException(
                        "Timed out waiting for HTTP response body data.");
                try {
                    close();
                } catch (IOException closeFailure) {
                    timeout.addSuppressed(closeFailure);
                }
                throw timeout;
            }
            if (event.failure != null) throw event.failure;
            if (event.end) {
                ended = true;
                return;
            }
            currentChunk = event.bytes;
            currentOffset = 0;
        }

        private void pump() {
            try {
                while (!closed.get()) {
                    final var buffer = new byte[8192];
                    final var count = source.read(buffer);
                    if (count < 0) {
                        chunks.put(END);
                        return;
                    }
                    if (count == 0) continue;
                    chunks.put(new ReadEvent(Arrays.copyOf(buffer, count), false, null));
                }
            } catch (IOException exception) {
                if (!closed.get()) putFailure(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!closed.get()) putFailure(new IOException(
                        "Interrupted while receiving HTTP response body.", exception));
            }
        }

        private void putFailure(final IOException exception) {
            try {
                chunks.put(new ReadEvent(null, false, exception));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            final var worker = pump;
            if (worker != null) worker.interrupt();
            chunks.clear();
            source.close();
        }
    }

    private static final class ReadEvent {
        private final byte[] bytes;
        private final boolean end;
        private final IOException failure;

        private ReadEvent(final byte[] bytes, final boolean end, final IOException failure) {
            this.bytes = bytes;
            this.end = end;
            this.failure = failure;
        }
    }
}
