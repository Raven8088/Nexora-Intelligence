package com.nexora.intelligence.infrastructure.serviceclient;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal HTTP client for fixed, operator-configured endpoints (Nexora services and the token endpoint). Redirects
 * are never followed and bodies are bounded, so a misbehaving upstream cannot send Intelligence elsewhere nor exhaust
 * its memory. Transport failures are classified by whether the request may have reached the server.
 */
public class BoundedHttp {

    /** Status, bounded body and the server's correlation id ({@code X-Request-Id}) of a response. */
    public record Response(int status, String body, Optional<String> requestId) {
    }

    /** How a request failed before a complete response was received. */
    public enum Failure {
        /** The connection could not be established: the request was never sent. */
        NOT_SENT,
        /** No complete answer in time: the request may have been processed. */
        NO_ANSWER,
        /** The answer exceeded the size limit. */
        OVERSIZED,
        /** Any other transport failure after the connection: the request may have been processed. */
        BROKEN
    }

    public static class TransportException extends RuntimeException {
        private final Failure failure;

        public TransportException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    private static final String REQUEST_ID = "X-Request-Id";

    private final HttpClient client;
    private final Duration readTimeout;
    private final int maxBytes;

    public BoundedHttp(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.maxBytes = maxBytes;
    }

    public Response get(URI uri, String bearerToken) {
        return send(HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET().build());
    }

    public Response postJson(URI uri, String bearerToken, String json) {
        return send(HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build());
    }

    public Response postForm(URI uri, String authorization, String form) {
        return send(HttpRequest.newBuilder(uri).timeout(readTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(maxBytes + 1);
                if (body.length > maxBytes) {
                    throw new TransportException(Failure.OVERSIZED, "response body exceeds the limit", null);
                }
                return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8),
                        response.headers().firstValue(REQUEST_ID).filter(BoundedHttp::isSafeId));
            }
        } catch (HttpConnectTimeoutException e) {
            throw new TransportException(Failure.NOT_SENT, "connection timed out", e);
        } catch (ConnectException e) {
            throw new TransportException(Failure.NOT_SENT, "connection refused", e);
        } catch (HttpTimeoutException e) {
            throw new TransportException(Failure.NO_ANSWER, "no answer in time", e);
        } catch (IOException e) {
            throw new TransportException(notSent(e) ? Failure.NOT_SENT : Failure.BROKEN, "transport failure", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(Failure.BROKEN, "interrupted", e);
        }
    }

    private static boolean notSent(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Only well-formed identifiers are logged: a UUID-like token, never arbitrary text from the server. */
    private static boolean isSafeId(String value) {
        return value.matches("[A-Za-z0-9-]{1,64}");
    }
}
