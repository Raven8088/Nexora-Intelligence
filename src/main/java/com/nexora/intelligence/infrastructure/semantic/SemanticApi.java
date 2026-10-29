package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated calls to Semantic with the retry rules of INT-3.4:
 * <ul>
 *   <li>reads (GET) are retried at most {@code maxRetries} times on transport failures and 502/503/504;</li>
 *   <li>a plan creation (POST) is retried only when it was never sent (connection refused or not established);
 *       once sent, a missing answer is {@code OUTCOME_UNKNOWN} and is never retried;</li>
 *   <li>a 401 discards the cached token and repeats the call once with a new verified token (Semantic did not
 *       process the request);</li>
 *   <li>other statuses are mapped to controlled failures; callers handle their own success and 422 answers.</li>
 * </ul>
 * Logs carry only Intelligence's correlation id, Semantic's request id, the operation, status and duration.
 */
class SemanticApi {

    private static final Logger log = LoggerFactory.getLogger(SemanticApi.class);
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);
    private static final Duration RETRY_DELAY = Duration.ofMillis(250);

    private final SemanticHttp http;
    private final InterpretationTokenProvider tokens;
    private final URI baseUrl;
    private final int maxRetries;
    private final JsonMapper json;

    SemanticApi(SemanticHttp http, InterpretationTokenProvider tokens, URI baseUrl, int maxRetries, JsonMapper json) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.maxRetries = maxRetries;
        this.json = Objects.requireNonNull(json, "json");
    }

    URI uri(String pathAndQuery) {
        return URI.create(baseUrl + pathAndQuery);
    }

    /** A read; returns the response whatever its status after the retry rules (401 and transients handled). */
    SemanticHttp.Response get(TenantId tenant, URI uri, String operation, CorrelationId correlationId) {
        boolean tokenRefreshed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            long started = System.nanoTime();
            SemanticHttp.Response response;
            try {
                response = http.get(uri, tokens.tokenFor(tenant));
            } catch (SemanticHttp.TransportException e) {
                if (e.failure() == SemanticHttp.Failure.OVERSIZED) {
                    throw contract(operation + ": response exceeds the size limit");
                }
                logFailure(operation, correlationId, attempt, e.failure().name(), started);
                if (attempt <= maxRetries) {
                    pause();
                    continue;
                }
                throw new SemanticUnavailableException(e.failure() == SemanticHttp.Failure.NO_ANSWER
                        ? Reason.TIMEOUT : Reason.UNREACHABLE, operation + ": " + e.getMessage(), e);
            }
            logResponse(operation, correlationId, attempt, response, started);
            if (response.status() == 401 && !tokenRefreshed) {
                tokens.evict();
                tokenRefreshed = true;
                attempt--;
                continue;
            }
            if (TRANSIENT_STATUSES.contains(response.status()) && attempt <= maxRetries) {
                pause();
                continue;
            }
            return response;
        }
    }

    /**
     * A plan creation; returns the response whatever its status (401 handled). A request that may have reached
     * Semantic is never repeated, except after a 401, which Semantic answers before processing anything.
     */
    SemanticHttp.Response post(TenantId tenant, URI uri, String body, String operation,
                               CorrelationId correlationId) {
        boolean tokenRefreshed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            long started = System.nanoTime();
            SemanticHttp.Response response;
            try {
                response = http.postJson(uri, tokens.tokenFor(tenant), body);
            } catch (SemanticHttp.TransportException e) {
                logFailure(operation, correlationId, attempt, e.failure().name(), started);
                if (e.failure() == SemanticHttp.Failure.NOT_SENT) {
                    if (attempt <= maxRetries) {
                        pause();
                        continue;
                    }
                    throw new SemanticUnavailableException(Reason.UNREACHABLE, operation + ": not sent", e);
                }
                throw new SemanticUnavailableException(Reason.OUTCOME_UNKNOWN,
                        operation + ": sent without a usable answer; it is not retried", e);
            }
            logResponse(operation, correlationId, attempt, response, started);
            if (response.status() == 401 && !tokenRefreshed) {
                tokens.evict();
                tokenRefreshed = true;
                attempt--;
                continue;
            }
            return response;
        }
    }

    /** The controlled failure for a status the caller does not handle itself. */
    SemanticUnavailableException failure(SemanticHttp.Response response, String operation) {
        int status = response.status();
        String code = problemCode(response.body());
        return switch (status) {
            case 401, 403 -> new SemanticUnavailableException(Reason.IDENTITY_REJECTED,
                    operation + ": identity rejected (HTTP " + status + ")");
            case 404 -> new SemanticUnavailableException(Reason.SOURCE_NOT_FOUND,
                    operation + ": data source not found for the tenant");
            case 409 -> "data_source_archived".equals(code)
                    ? new SemanticUnavailableException(Reason.SOURCE_ARCHIVED, operation + ": data source archived")
                    : contract(operation + ": unexpected conflict");
            case 429 -> new SemanticUnavailableException(Reason.UNREACHABLE, operation + ": throttled (HTTP 429)");
            default -> status >= 500
                    ? new SemanticUnavailableException(Reason.UNREACHABLE, operation + ": HTTP " + status)
                    : contract(operation + ": unexpected HTTP " + status);
        };
    }

    /** The {@code code} of an RFC 7807 body when it is a short lowercase token; null otherwise. */
    String problemCode(String body) {
        try {
            JsonNode node = json.readTree(body);
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isString() && code.asString().matches("[a-z][a-z_]{1,63}")
                    ? code.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    static SemanticUnavailableException contract(String message) {
        return new SemanticUnavailableException(Reason.CONTRACT_VIOLATION, message);
    }

    private static void logResponse(String operation, CorrelationId correlationId, int attempt,
                                    SemanticHttp.Response response, long started) {
        log.info("semantic {} correlationId={} semanticRequestId={} attempt={} status={} durationMs={}",
                operation, correlationId, response.requestId().orElse("-"), attempt, response.status(),
                (System.nanoTime() - started) / 1_000_000);
    }

    private static void logFailure(String operation, CorrelationId correlationId, int attempt, String failure,
                                   long started) {
        log.warn("semantic {} correlationId={} attempt={} failure={} durationMs={}", operation, correlationId,
                attempt, failure, (System.nanoTime() - started) / 1_000_000);
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SemanticUnavailableException(Reason.UNREACHABLE, "interrupted while retrying", e);
        }
    }
}
