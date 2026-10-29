package com.nexora.intelligence.infrastructure.semantic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Simulated Nexora Semantic, token endpoint and JWKS endpoint on the loopback interface. Answers can be queued per
 * path prefix (consumed in order) or given by a function; with nothing configured a path answers HTTP 500. Every
 * request is recorded.
 */
public final class FakeSemanticServer implements AutoCloseable {

    public record Answer(int status, String body, Duration delay, Map<String, String> headers) {
        public static Answer of(int status, String body) {
            return new Answer(status, body, Duration.ZERO, Map.of());
        }
    }

    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }

        public String pathAndQuery() {
            return query == null ? path : path + "?" + query;
        }
    }

    public static final String TOKEN_PATH = "/realms/nexora/token";
    public static final String JWKS_PATH = "/realms/nexora/certs";

    private final HttpServer server;
    private final Map<String, Queue<Answer>> queues = new ConcurrentHashMap<>();
    private final Map<String, Function<Received, Answer>> handlers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public FakeSemanticServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public URI tokenUri() {
        return URI.create(baseUrl() + TOKEN_PATH);
    }

    public URI jwksUri() {
        return URI.create(baseUrl() + JWKS_PATH);
    }

    /** Queues an answer for requests whose path starts with {@code pathPrefix}. */
    public FakeSemanticServer answer(String pathPrefix, int status, String body) {
        return answer(pathPrefix, Answer.of(status, body));
    }

    public FakeSemanticServer answer(String pathPrefix, Answer answer) {
        queues.computeIfAbsent(pathPrefix, p -> new ConcurrentLinkedQueue<>()).add(answer);
        return this;
    }

    /** Answers every request whose path starts with {@code pathPrefix} with {@code handler} (after queued answers). */
    public FakeSemanticServer handle(String pathPrefix, Function<Received, Answer> handler) {
        handlers.put(pathPrefix, handler);
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(String pathPrefix) {
        return received.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
        received.add(request);
        Answer answer = next(request);
        try {
            if (!answer.delay().isZero()) {
                Thread.sleep(answer.delay().toMillis());
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", answer.status() >= 400
                    ? "application/problem+json" : "application/json");
            exchange.getResponseHeaders().add("X-Request-Id", "srv-" + received.size());
            answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client gave up (timeout tests)
        } finally {
            exchange.close();
        }
    }

    private Answer next(Received request) {
        String best = null;
        for (String prefix : queues.keySet()) {
            Queue<Answer> queue = queues.get(prefix);
            if (request.path().startsWith(prefix) && !queue.isEmpty()
                    && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        if (best != null) {
            Answer answer = queues.get(best).poll();
            if (answer != null) {
                return answer;
            }
        }
        String handler = null;
        for (String prefix : handlers.keySet()) {
            if (request.path().startsWith(prefix) && (handler == null || prefix.length() > handler.length())) {
                handler = prefix;
            }
        }
        return handler == null ? Answer.of(500, "{\"code\":\"internal_error\"}") : handlers.get(handler).apply(request);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
