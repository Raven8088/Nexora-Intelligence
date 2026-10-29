package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.domain.question.CorrelationId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Error bodies of the question API (RFC 7807): {@code type}, {@code title}, {@code status}, {@code code},
 * {@code detail} and {@code correlationId}, plus {@code errors[{field, message}]} for validation. Codes and details
 * are fixed per problem: never SQL, tokens, secrets, the question, values or messages of other services.
 */
final class ApiProblems {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Problem {
        MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed_request",
                "The request body is not a valid JSON question (unknown properties are rejected)."),
        VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation_failed", "The request is not valid."),
        LOCAL_ONLY(HttpStatus.FORBIDDEN, "local_only", "This API only accepts requests from the local machine."),
        PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The request body is too large."),
        UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Use application/json."),
        TOO_MANY_QUESTIONS(HttpStatus.TOO_MANY_REQUESTS, "too_many_questions",
                "Too many questions are being answered; retry later."),
        INTERPRETATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "interpretation_timeout",
                "The inference engine did not answer in time."),
        INTERPRETATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "interpretation_unavailable",
                "The inference engine is not available."),
        INTERPRETATION_INVALID(HttpStatus.BAD_GATEWAY, "interpretation_invalid",
                "The inference engine did not produce a valid interpretation."),
        INTERPRETATION_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "interpretation_capacity_exceeded",
                "The question and the catalog do not fit the inference engine's context."),
        SEMANTIC_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "semantic_unavailable",
                "Nexora Semantic is not available."),
        SEMANTIC_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "semantic_timeout", "Nexora Semantic did not answer in time."),
        PLAN_OUTCOME_UNKNOWN(HttpStatus.GATEWAY_TIMEOUT, "plan_outcome_unknown",
                "The plan request got no answer; it was not repeated."),
        SEMANTIC_IDENTITY_REJECTED(HttpStatus.BAD_GATEWAY, "semantic_identity_rejected",
                "The service identity for Nexora Semantic was rejected."),
        SEMANTIC_DATA_SOURCE_NOT_FOUND(HttpStatus.BAD_GATEWAY, "semantic_data_source_not_found",
                "The configured data source does not exist in Nexora Semantic."),
        DATA_SOURCE_ARCHIVED(HttpStatus.CONFLICT, "data_source_archived", "The data source is archived."),
        SEMANTIC_CONTRACT_VIOLATION(HttpStatus.BAD_GATEWAY, "semantic_contract_violation",
                "Nexora Semantic returned an unexpected response."),
        CATALOG_TOO_LARGE(HttpStatus.SERVICE_UNAVAILABLE, "catalog_too_large",
                "The semantic catalog exceeds the supported size."),
        CATALOG_CHANGING(HttpStatus.SERVICE_UNAVAILABLE, "catalog_changing",
                "The semantic catalog changed while it was read; retry later."),
        REPORTS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "reports_unavailable", "Nexora Reports is not available."),
        REPORTS_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "reports_timeout", "The execution did not finish in time."),
        REPORTS_BUSY(HttpStatus.TOO_MANY_REQUESTS, "reports_busy", "Nexora Reports is busy; retry later."),
        REPORTS_IDENTITY_REJECTED(HttpStatus.BAD_GATEWAY, "reports_identity_rejected",
                "The service identity for Nexora Reports was rejected."),
        REPORTS_NOT_FOUND(HttpStatus.BAD_GATEWAY, "reports_not_found",
                "Nexora Reports did not find the data source or the plan."),
        REPORTS_MISCONFIGURED(HttpStatus.BAD_GATEWAY, "reports_misconfigured",
                "Nexora Reports is not configured to execute on this data source."),
        REPORTS_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "reports_dependency_unavailable",
                "A dependency of Nexora Reports is not available."),
        REPORTS_EXECUTION_FAILED(HttpStatus.BAD_GATEWAY, "reports_execution_failed", "The execution failed."),
        REPORTS_CONTRACT_VIOLATION(HttpStatus.BAD_GATEWAY, "reports_contract_violation",
                "Nexora Reports returned an unexpected or inconsistent result."),
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected error.");

        private final HttpStatus status;
        private final String code;
        private final String detail;

        Problem(HttpStatus status, String code, String detail) {
            this.status = status;
            this.code = code;
            this.detail = detail;
        }

        HttpStatus status() {
            return status;
        }

        String code() {
            return code;
        }
    }

    private ApiProblems() {
    }

    static Problem of(InterpretationUnavailableException e) {
        return switch (e.reason()) {
            case TIMEOUT -> Problem.INTERPRETATION_TIMEOUT;
            case HTTP_ERROR -> Problem.INTERPRETATION_UNAVAILABLE;
            case INVALID_MODEL_OUTPUT -> Problem.INTERPRETATION_INVALID;
            case CONTEXT_LIMIT_EXCEEDED -> Problem.INTERPRETATION_CAPACITY_EXCEEDED;
        };
    }

    static Problem of(SemanticUnavailableException e) {
        return switch (e.reason()) {
            case UNREACHABLE -> Problem.SEMANTIC_UNAVAILABLE;
            case TIMEOUT -> Problem.SEMANTIC_TIMEOUT;
            case OUTCOME_UNKNOWN -> Problem.PLAN_OUTCOME_UNKNOWN;
            case IDENTITY_REJECTED -> Problem.SEMANTIC_IDENTITY_REJECTED;
            case SOURCE_NOT_FOUND -> Problem.SEMANTIC_DATA_SOURCE_NOT_FOUND;
            case SOURCE_ARCHIVED -> Problem.DATA_SOURCE_ARCHIVED;
            case CONTRACT_VIOLATION -> Problem.SEMANTIC_CONTRACT_VIOLATION;
            case CATALOG_TOO_LARGE -> Problem.CATALOG_TOO_LARGE;
            case CATALOG_INCONSISTENT -> Problem.CATALOG_CHANGING;
        };
    }

    static Problem of(ReportsUnavailableException e) {
        return switch (e.reason()) {
            case UNREACHABLE -> Problem.REPORTS_UNAVAILABLE;
            case TIMEOUT -> Problem.REPORTS_TIMEOUT;
            case BUSY -> Problem.REPORTS_BUSY;
            case IDENTITY_REJECTED -> Problem.REPORTS_IDENTITY_REJECTED;
            case NOT_FOUND -> Problem.REPORTS_NOT_FOUND;
            case MISCONFIGURED -> Problem.REPORTS_MISCONFIGURED;
            case DEPENDENCY_UNAVAILABLE -> Problem.REPORTS_DEPENDENCY_UNAVAILABLE;
            case EXECUTION_FAILED -> Problem.REPORTS_EXECUTION_FAILED;
            case CONTRACT_VIOLATION -> Problem.REPORTS_CONTRACT_VIOLATION;
        };
    }

    /** The problem body; {@code errors} is a list of {field, message} with fixed rule texts, or null. */
    static String body(Problem problem, CorrelationId correlationId, List<Map<String, String>> errors) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "about:blank");
        node.put("title", problem.status.getReasonPhrase());
        node.put("status", problem.status.value());
        node.put("code", problem.code);
        node.put("detail", problem.detail);
        if (correlationId != null) {
            node.put("correlationId", correlationId.toString());
        }
        if (errors != null) {
            ArrayNode list = node.putArray("errors");
            errors.forEach(e -> list.addObject().put("field", e.get("field")).put("message", e.get("message")));
        }
        return JSON.writeValueAsString(node);
    }

    static void write(HttpServletResponse response, Problem problem, CorrelationId correlationId) throws IOException {
        response.setStatus(problem.status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body(problem, correlationId, null).getBytes(StandardCharsets.UTF_8));
    }
}
