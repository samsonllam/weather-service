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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server standing in for a weather provider, so tests exercise the actual client,
 * timeouts and JSON decoding. It answers every path with the configured status and body.
 */
public final class FakeProviderServer implements AutoCloseable {

    private final HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration delay = Duration.ZERO;
    private volatile URI lastRequestUri;
    private final AtomicInteger requestCount = new AtomicInteger();

    public FakeProviderServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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

    /** Delays every response, to simulate a provider that accepts connections but does not answer. */
    public FakeProviderServer respondAfter(Duration delay) {
        this.delay = delay;
        return this;
    }

    public void reset() {
        respond(200, "{}");
        delay = Duration.ZERO;
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
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
