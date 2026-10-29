package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException.Reason;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.infrastructure.serviceclient.BoundedHttp;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceIdentityException;
import com.nexora.intelligence.infrastructure.serviceclient.ServiceTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Executes a plan with {@code POST /api/v1/data-sources/{dataSourceId}/plans/{planId}/executions} of Nexora Reports,
 * sending only {@code {"maxRows": n}} as JSON (never SQL, plan documents, tenants or connection details).
 * <ul>
 *   <li>200: read strictly by {@link ExecutionResponseMapper}.</li>
 *   <li>409 {@code plan_stale}, {@code plan_changed_during_execution}, {@code physical_schema_mismatch} and 422
 *       {@code plan_not_executable}: a refusal about the plan, {@link Rejected} with origin REPORTS and controlled
 *       codes (Reports' details are never copied).</li>
 *   <li>Everything else: {@link ReportsUnavailableException} with a controlled reason.</li>
 *   <li>A 401 discards the cached token and repeats the call once (Reports processed nothing). Otherwise a request
 *       is repeated only when it was never sent, and only with {@code max-retries=1}; an execution that may have run
 *       is never repeated automatically.</li>
 * </ul>
 * Logs carry only Intelligence's correlation id, Reports' request and execution ids, status, row count and duration.
 */
class HttpReportExecutionClient implements ReportExecutionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpReportExecutionClient.class);
    private static final Duration RETRY_DELAY = Duration.ofMillis(250);
    private static final Set<String> PLAN_REFUSALS = Set.of("plan_stale", "plan_changed_during_execution",
            "physical_schema_mismatch");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern FIELD =
            Pattern.compile("[a-zA-Z]{1,40}(\\[\\d{1,3}])?(\\.[a-zA-Z]{1,40}(\\[\\d{1,3}])?){0,4}");

    private final BoundedHttp http;
    private final ServiceTokenProvider tokens;
    private final URI baseUrl;
    private final int maxRows;
    private final int maxRetries;
    private final JsonMapper json;
    private final ExecutionResponseMapper mapper;

    HttpReportExecutionClient(BoundedHttp http, ServiceTokenProvider tokens, URI baseUrl, int maxRows,
                              int maxRetries, JsonMapper json) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.maxRows = maxRows;
        this.maxRetries = maxRetries;
        this.json = Objects.requireNonNull(json, "json");
        this.mapper = new ExecutionResponseMapper(json);
    }

    @Override
    public Execution execute(QuestionOutcome.PlanCreated plan, CorrelationId correlationId) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(correlationId, "correlationId");
        URI uri = URI.create(baseUrl + "/api/v1/data-sources/" + plan.scope().dataSourceId() + "/plans/"
                + plan.planId() + "/executions");
        String body = json.writeValueAsString(json.createObjectNode().put("maxRows", maxRows));
        BoundedHttp.Response response = send(plan, uri, body, correlationId);
        return switch (response.status()) {
            case 200 -> executed(response, plan, correlationId);
            case 409, 422 -> refusedOrFailure(response);
            default -> throw failure(response.status(), problemCode(response.body()));
        };
    }

    private BoundedHttp.Response send(QuestionOutcome.PlanCreated plan, URI uri, String body,
                                      CorrelationId correlationId) {
        boolean tokenRefreshed = false;
        int attempt = 0;
        while (true) {
            attempt++;
            long started = System.nanoTime();
            BoundedHttp.Response response;
            try {
                response = http.postJson(uri, token(plan), body);
            } catch (BoundedHttp.TransportException e) {
                log.warn("reports execute correlationId={} planId={} attempt={} failure={} durationMs={}",
                        correlationId, plan.planId(), attempt, e.failure(), millis(started));
                switch (e.failure()) {
                    case NOT_SENT -> {
                        if (attempt <= maxRetries) {
                            pause();
                            continue;
                        }
                        throw new ReportsUnavailableException(Reason.UNREACHABLE, "execution not sent", e);
                    }
                    case NO_ANSWER -> throw new ReportsUnavailableException(Reason.TIMEOUT,
                            "no answer in time; the execution is not repeated", e);
                    case OVERSIZED -> throw ExecutionResponseMapper.contract("execution response exceeds the limit");
                    default -> throw new ReportsUnavailableException(Reason.UNREACHABLE,
                            "execution answer cut; it is not repeated", e);
                }
            }
            log.info("reports execute correlationId={} reportsRequestId={} planId={} attempt={} status={} "
                            + "durationMs={}", correlationId, response.requestId().orElse("-"), plan.planId(),
                    attempt, response.status(), millis(started));
            if (response.status() == 401 && !tokenRefreshed) {
                tokens.evict();
                tokenRefreshed = true;
                attempt--;
                continue;
            }
            return response;
        }
    }

    private String token(QuestionOutcome.PlanCreated plan) {
        try {
            return tokens.tokenFor(plan.scope().tenantId().value());
        } catch (ServiceIdentityException e) {
            throw new ReportsUnavailableException(switch (e.failure()) {
                case REJECTED -> Reason.IDENTITY_REJECTED;
                case UNREACHABLE -> Reason.UNREACHABLE;
                case TIMEOUT -> Reason.TIMEOUT;
            }, e.getMessage(), e);
        }
    }

    private Executed executed(BoundedHttp.Response response, QuestionOutcome.PlanCreated plan,
                              CorrelationId correlationId) {
        ReportExecution execution = mapper.read(response.body(), plan, maxRows);
        log.info("reports executed correlationId={} planId={} executionId={} rows={} truncated={} validity={}",
                correlationId, plan.planId(), execution.executionId(), execution.rowCount(), execution.truncated(),
                execution.validity());
        return new Executed(execution);
    }

    private Refused refusedOrFailure(BoundedHttp.Response response) {
        JsonNode problem = problem(response.body());
        String code = problem == null ? null : text(problem.get("code"));
        if (response.status() == 409 && PLAN_REFUSALS.contains(code)) {
            return new Refused(new Rejected(Rejected.Origin.REPORTS,
                    List.of(Rejected.Cause.of(code.toUpperCase(Locale.ROOT)))));
        }
        if (response.status() == 422 && "plan_not_executable".equals(code)) {
            List<Rejected.Cause> causes = new ArrayList<>();
            JsonNode violations = problem.get("violations");
            if (violations != null && violations.isArray()) {
                for (JsonNode violation : violations) {
                    String violationCode = violation.isObject() ? text(violation.get("code")) : null;
                    if (violationCode != null && CODE.matcher(violationCode).matches()) {
                        causes.add(new Rejected.Cause(violationCode, field(text(violation.get("path")))));
                    }
                }
            }
            if (causes.isEmpty()) {
                causes.add(Rejected.Cause.of("PLAN_NOT_EXECUTABLE"));
            }
            return new Refused(Rejected.capped(Rejected.Origin.REPORTS, causes));
        }
        throw failure(response.status(), code);
    }

    /** The controlled failure of a status that is not a result nor a refusal about the plan. */
    static ReportsUnavailableException failure(int status, String code) {
        Reason reason = switch (status) {
            case 401, 403 -> Reason.IDENTITY_REJECTED;
            case 404 -> Reason.NOT_FOUND;
            case 409 -> "data_source_not_configured".equals(code) || "data_source_not_executable".equals(code)
                    ? Reason.MISCONFIGURED : Reason.CONTRACT_VIOLATION;
            case 429 -> Reason.BUSY;
            case 500 -> "data_source_role_not_read_only".equals(code) || "data_source_target_not_allowed".equals(code)
                    ? Reason.MISCONFIGURED : Reason.EXECUTION_FAILED;
            case 502 -> "service_identity_invalid".equals(code) ? Reason.MISCONFIGURED
                    : Reason.DEPENDENCY_UNAVAILABLE;
            case 503 -> Reason.DEPENDENCY_UNAVAILABLE;
            case 504 -> Reason.TIMEOUT;
            default -> status >= 500 ? Reason.EXECUTION_FAILED : Reason.CONTRACT_VIOLATION;
        };
        return new ReportsUnavailableException(reason, "Reports answered HTTP " + status
                + (code == null ? "" : " " + code));
    }

    /** A violation path such as {@code $.plan.operations[1].kind}, kept only when it is a safe request path. */
    private static Optional<String> field(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String field = path.startsWith("$.") ? path.substring(2) : path;
        return FIELD.matcher(field).matches() ? Optional.of(field) : Optional.empty();
    }

    private JsonNode problem(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private String problemCode(String body) {
        JsonNode problem = problem(body);
        String code = problem == null ? null : text(problem.get("code"));
        return code != null && code.matches("[a-z][a-z_]{1,63}") ? code : null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReportsUnavailableException(Reason.UNREACHABLE, "interrupted while retrying", e);
        }
    }
}
