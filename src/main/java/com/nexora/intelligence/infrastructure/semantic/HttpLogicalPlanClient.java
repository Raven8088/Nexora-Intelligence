package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.request.SemanticRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asks Semantic to create a logical plan ({@code POST /api/v1/data-sources/{id}/logical-plans}).
 * <ul>
 *   <li>201: the answer must be for the same data source, with a lowercase hex SHA-256 fingerprint and the format
 *       {@value #FORMAT_VERSION}; only the plan id, fingerprint and format are kept. The plan document is required
 *       to be an object but is never read, changed or stored.</li>
 *   <li>422 ({@code semantic_resolution_failed} or {@code logical_plan_rejected}, including definitions that changed
 *       since the catalog was read): {@link Rejected} with origin SEMANTIC and Semantic's codes and request paths.
 *       Messages, names, relationship ids and alternatives are never copied.</li>
 *   <li>Anything else: a controlled {@code SemanticUnavailableException} (see {@link SemanticApi}).</li>
 * </ul>
 */
class HttpLogicalPlanClient implements LogicalPlanClient {

    /** The only plan format Nexora Reports executes. */
    static final String FORMAT_VERSION = "nexora-logical-plan/1";
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern FIELD =
            Pattern.compile("[a-zA-Z]{1,40}(\\[\\d{1,3}])?(\\.[a-zA-Z]{1,40}(\\[\\d{1,3}])?){0,4}");
    private static final Set<String> REJECTIONS = Set.of("semantic_resolution_failed", "logical_plan_rejected");
    private static final Logger log = LoggerFactory.getLogger(HttpLogicalPlanClient.class);

    private final SemanticApi api;
    private final PlanRequestMapper requests;
    private final JsonMapper json;

    HttpLogicalPlanClient(SemanticApi api, PlanRequestMapper requests, JsonMapper json) {
        this.api = Objects.requireNonNull(api, "api");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public QuestionOutcome create(SemanticRequest request, CorrelationId correlationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlationId, "correlationId");
        String body = requests.body(request);
        SemanticHttp.Response response = api.post(request.scope().tenantId(),
                api.uri("/api/v1/data-sources/" + request.scope().dataSourceId() + "/logical-plans"), body,
                "create-plan", correlationId);
        return switch (response.status()) {
            case 201 -> created(request, response, correlationId);
            case 422 -> rejected(response, correlationId);
            default -> throw api.failure(response, "create-plan");
        };
    }

    private QuestionOutcome.PlanCreated created(SemanticRequest request, SemanticHttp.Response response,
                                                CorrelationId correlationId) {
        JsonNode root = read(response.body(), "plan response");
        if (!root.isObject()) {
            throw SemanticApi.contract("plan response is not a JSON object");
        }
        UUID id = uuid(root.get("id"), "id");
        UUID dataSource = uuid(root.get("dataSourceId"), "dataSourceId");
        if (!request.scope().dataSourceId().value().equals(dataSource)) {
            throw SemanticApi.contract("plan response of another data source");
        }
        String fingerprint = text(root.get("fingerprint"));
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw SemanticApi.contract("plan response without a valid fingerprint");
        }
        if (!FORMAT_VERSION.equals(text(root.get("formatVersion")))) {
            throw SemanticApi.contract("plan response with an unsupported format version");
        }
        JsonNode plan = root.get("plan");
        if (plan == null || !plan.isObject()) {
            throw SemanticApi.contract("plan response without its plan document");
        }
        log.info("semantic plan created correlationId={} semanticRequestId={} planId={}", correlationId,
                response.requestId().orElse("-"), id);
        return new QuestionOutcome.PlanCreated(request.scope(), new PlanId(id), fingerprint, FORMAT_VERSION);
    }

    private Rejected rejected(SemanticHttp.Response response, CorrelationId correlationId) {
        JsonNode root = read(response.body(), "rejection");
        String code = root.isObject() ? text(root.get("code")) : null;
        if (code == null || !REJECTIONS.contains(code)) {
            throw SemanticApi.contract("422 answer that is not a semantic rejection");
        }
        List<Rejected.Cause> causes = new ArrayList<>();
        JsonNode errors = root.get("errors");
        if (errors != null && errors.isArray()) {
            for (JsonNode error : errors) {
                String causeCode = error.isObject() ? text(error.get("code")) : null;
                if (causeCode == null || !CODE.matcher(causeCode).matches()) {
                    continue;
                }
                String field = text(error.get("field"));
                causes.add(new Rejected.Cause(causeCode, Optional.ofNullable(field)
                        .filter(f -> FIELD.matcher(f).matches())));
            }
        }
        if (causes.isEmpty()) {
            causes.add(Rejected.Cause.of(code.toUpperCase(Locale.ROOT)));
        }
        log.info("semantic plan rejected correlationId={} semanticRequestId={} code={} causes={}", correlationId,
                response.requestId().orElse("-"), code, causes.size());
        return Rejected.capped(Rejected.Origin.SEMANTIC, causes);
    }

    private JsonNode read(String body, String what) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw SemanticApi.contract(what + " is empty");
            }
            return node;
        } catch (JacksonException e) {
            throw SemanticApi.contract(what + " is not valid JSON");
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node);
        if (value == null) {
            throw SemanticApi.contract("plan response without " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw SemanticApi.contract("plan response " + field + " is not a UUID");
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}
