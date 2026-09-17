package io.github.samsonllam.weather.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server standing in for a weather provider, so tests exercise the actual client,
 * timeouts and JSON decoding. It answers every path with the configured status and body.
 */
public final class FakeProviderServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration headerDelay = Duration.ZERO;
    private volatile Duration bodyDelay = Duration.ZERO;
    private volatile URI lastRequestUri;
    private final AtomicInteger requestCount = new AtomicInteger();

    public FakeProviderServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeProviderServer respond(int status, String jsonBody) {
        this.status = status;
        this.body = jsonBody;
        return this;
    }

    /** Delays the whole response, to simulate a provider that accepts connections but does not answer. */
    public FakeProviderServer respondAfter(Duration delay) {
        this.headerDelay = delay;
        return this;
    }

    /** Sends the headers promptly but stalls before the body, to simulate a provider that hangs mid-response. */
    public FakeProviderServer stallBodyFor(Duration delay) {
        this.bodyDelay = delay;
        return this;
    }

    public void reset() {
        respond(200, "{}");
        headerDelay = Duration.ZERO;
        bodyDelay = Duration.ZERO;
        lastRequestUri = null;
        requestCount.set(0);
    }

    public int requestCount() {
        return requestCount.get();
    }

    public URI lastRequestUri() {
        return lastRequestUri;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        lastRequestUri = exchange.getRequestURI();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            Thread.sleep(headerDelay);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            Thread.sleep(bodyDelay);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            // The server was closed while this response was being delayed on purpose.
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
