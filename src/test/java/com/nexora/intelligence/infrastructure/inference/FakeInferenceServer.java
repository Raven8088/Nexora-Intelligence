package com.nexora.intelligence.infrastructure.inference;

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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Simulated OpenAI-compatible inference server on the loopback interface. Answers are queued in order; when the queue
 * is empty it answers HTTP 500. Every received request is recorded.
 */
final class FakeInferenceServer implements AutoCloseable {

    record Answer(int status, String body, Duration delay) {
    }

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    FakeInferenceServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    FakeInferenceServer answer(int status, String body) {
        answers.add(new Answer(status, body, Duration.ZERO));
        return this;
    }

    FakeInferenceServer answerAfter(Duration delay, String body) {
        answers.add(new Answer(200, body, delay));
        return this;
    }

    /** A Chat Completions answer whose final content is {@code content}. */
    FakeInferenceServer answerContent(String content) {
        return answer(200, completion(content, "stop", null));
    }

    List<Received> received() {
        return received;
    }

    static String completion(String content, String finishReason, String reasoning) {
        String message = "{\"role\":\"assistant\",\"content\":" + quote(content)
                + (reasoning == null ? "" : ",\"reasoning\":" + quote(reasoning)) + "}";
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"test\",\"choices\":[{\"index\":0,"
                + "\"message\":" + message + ",\"finish_reason\":"
                + (finishReason == null ? "null" : quote(finishReason)) + "}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    /** A completion whose reported usage says the server used {@code promptTokens} prompt tokens. */
    static String completionWithPromptTokens(String content, int promptTokens) {
        return completion(content, "stop", null).replace("\"prompt_tokens\":10", "\"prompt_tokens\":" + promptTokens);
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            Answer answer = answers.poll();
            if (answer == null) {
                answer = new Answer(500, "{\"error\":\"no answer queued\"}", Duration.ZERO);
            }
            if (!answer.delay().isZero()) {
                try {
                    Thread.sleep(answer.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            // the client gave up (timeout); nothing to answer
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
