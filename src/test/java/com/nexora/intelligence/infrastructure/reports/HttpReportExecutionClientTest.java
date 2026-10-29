package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException.Reason;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.result.ColumnRole;
import com.nexora.intelligence.domain.result.ExecutionValidity;
import com.nexora.intelligence.domain.result.ExecutionWarning;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ResultValue;
import com.nexora.intelligence.domain.result.ValueKind;
import com.nexora.intelligence.infrastructure.semantic.FakeSemanticServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executions against a simulated Reports: request, typed result, refusals, failures, identity and retries. */
class HttpReportExecutionClientTest {

    private static final List<String[]> COLUMNS = List.of(
            new String[] {"fecha", "DIMENSION", "DATE"},
            new String[] {"nota", "DIMENSION", "TEXT"},
            new String[] {"suma_valor", "METRIC", "NUMERIC"},
            new String[] {"num_lecturas", "METRIC", "INTEGER"});

    private ReportsFixture reports;

    @AfterEach
    void tearDown() {
        if (reports != null) {
            reports.close();
        }
    }

    private ReportsFixture reports(int maxRetries, Duration readTimeout) throws IOException {
        reports = new ReportsFixture(maxRetries, readTimeout, 1000);
        return reports;
    }

    static List<Object> row(Object... values) {
        return java.util.Arrays.asList(values);
    }

    private ReportExecutionClient.Execution execute() {
        return reports.client().execute(reports.plan, reports.correlationId);
    }

    private Reason failure() {
        try {
            execute();
        } catch (ReportsUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("no ReportsUnavailableException");
    }

    @Test
    void sendsOnlyMaxRowsAsJsonToThePlanOfTheScopeWithAVerifiedToken() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 200,
                reports.body(reports.success(COLUMNS, List.of(), n -> {
                })));

        execute();

        FakeSemanticServer.Received sent = reports.server.received("/api/").get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo(reports.path());
        assertThat(sent.query()).isNull();
        assertThat(sent.body()).isEqualTo("{\"maxRows\":1000}");
        assertThat(sent.header("Content-Type")).startsWith("application/json");
        assertThat(sent.header("Authorization")).startsWith("Bearer ey");
        assertThat(sent.body()).doesNotContainIgnoringCase("select").doesNotContain(reports.tenant.toString());
        assertThat(reports.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(1);
    }

    @Test
    void aResultKeepsItsTypesAndExactValues() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 200, reports.body(reports.success(COLUMNS,
                List.of(row("2026-01-01", "revisar", "200.500", 1L),
                        row("2026-02-01", null, "-0.000001", new BigInteger("9223372036854775808"))),
                n -> n.putArray("warnings").addObject().put("code", "PLAN_CURRENT_WITH_CHANGES")
                        .put("message", "fixed"))));

        ReportExecution execution = ((ReportExecutionClient.Executed) execute()).execution();

        assertThat(execution.scope()).isEqualTo(reports.scope);
        assertThat(execution.planId()).isEqualTo(reports.plan.planId());
        assertThat(execution.planFingerprint()).isEqualTo(ReportsFixture.FINGERPRINT);
        assertThat(execution.validity()).isEqualTo(ExecutionValidity.CURRENT);
        assertThat(execution.columns()).extracting(c -> c.key().value(), c -> c.role(), c -> c.kind())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("fecha", ColumnRole.DIMENSION, ValueKind.DATE),
                        org.assertj.core.groups.Tuple.tuple("nota", ColumnRole.DIMENSION, ValueKind.TEXT),
                        org.assertj.core.groups.Tuple.tuple("suma_valor", ColumnRole.METRIC, ValueKind.NUMERIC),
                        org.assertj.core.groups.Tuple.tuple("num_lecturas", ColumnRole.METRIC, ValueKind.INTEGER));
        assertThat(execution.rows().get(0)).containsExactly(new ResultValue.Date(LocalDate.of(2026, 1, 1)),
                new ResultValue.Text("revisar"), new ResultValue.Decimal(new BigDecimal("200.500")),
                new ResultValue.Integer(BigInteger.ONE));
        ResultValue.Decimal decimal = (ResultValue.Decimal) execution.rows().get(0).get(2);
        assertThat(decimal.value().scale()).isEqualTo(3);
        assertThat(decimal.value().toPlainString()).isEqualTo("200.500");
        assertThat(execution.rows().get(1).get(1)).isEqualTo(ResultValue.Null.INSTANCE);
        assertThat(execution.rows().get(1).get(2)).isEqualTo(new ResultValue.Decimal(new BigDecimal("-0.000001")));
        assertThat(execution.rows().get(1).get(3))
                .isEqualTo(new ResultValue.Integer(new BigInteger("9223372036854775808")));
        assertThat(execution.warnings()).containsExactly(
                new ExecutionWarning(ExecutionWarning.PLAN_CURRENT_WITH_CHANGES));
        assertThat(execution.truncated()).isFalse();
    }

    @Test
    void aTruncatedResultIsKeptAsTruncated() throws IOException {
        reports = new ReportsFixture(0, Duration.ofSeconds(5), 2);
        reports.server.answer(reports.path(), 200, reports.body(reports.success(COLUMNS,
                List.of(row("2026-01-01", "a", "1.0", 1L), row("2026-01-01", "b", "2.0", 2L)), n -> {
                    n.put("truncated", true);
                    n.putArray("warnings").addObject().put("code", "RESULT_TRUNCATED").put("message", "fixed");
                })));

        ReportExecution execution = ((ReportExecutionClient.Executed) execute()).execution();

        assertThat(execution.truncated()).isTrue();
        assertThat(execution.rowCount()).isEqualTo(2);
        assertThat(execution.maxRows()).isEqualTo(2);
        assertThat(execution.warnings()).containsExactly(new ExecutionWarning(ExecutionWarning.RESULT_TRUNCATED));
        assertThat(reports.server.received("/api/").get(0).body()).isEqualTo("{\"maxRows\":2}");
    }

    @ParameterizedTest
    @CsvSource({"plan_stale, PLAN_STALE", "plan_changed_during_execution, PLAN_CHANGED_DURING_EXECUTION",
            "physical_schema_mismatch, PHYSICAL_SCHEMA_MISMATCH"})
    void refusalsAboutThePlanAreRejectionsByReports(String code, String cause) throws IOException {
        reports(1, Duration.ofSeconds(5)).server.answer(reports.path(), 409, ReportsFixture.problem(409, code, null));

        assertThat(execute()).isEqualTo(new ReportExecutionClient.Refused(new Rejected(Rejected.Origin.REPORTS,
                List.of(Rejected.Cause.of(cause)))));
        assertThat(reports.server.received("/api/")).hasSize(1);
    }

    @Test
    void aPlanThatIsNotExecutableListsTheViolationCodesWithSafePathsOnly() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 422, ReportsFixture.problem(422,
                "plan_not_executable", List.<String[]>of(new String[] {"UNSAFE_FILTER", "$.plan.operations[1].kind"},
                        new String[] {"UNSUPPORTED_TYPE", "$.plan['weird'] OR 1=1"},
                        new String[] {"not a code", "$.plan"})));

        ReportExecutionClient.Refused refused = (ReportExecutionClient.Refused) execute();

        assertThat(refused.rejection()).isEqualTo(new Rejected(Rejected.Origin.REPORTS, List.of(
                Rejected.Cause.of("UNSAFE_FILTER", "plan.operations[1].kind"),
                new Rejected.Cause("UNSUPPORTED_TYPE", Optional.empty()))));
        assertThat(refused.rejection().toString()).doesNotContain("secret-value").doesNotContain("orders");
    }

    @Test
    void aPlanThatIsNotExecutableWithoutViolationsKeepsItsGeneralCode() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 422,
                ReportsFixture.problem(422, "plan_not_executable", List.of()));
        assertThat(((ReportExecutionClient.Refused) execute()).rejection().causes())
                .containsExactly(Rejected.Cause.of("PLAN_NOT_EXECUTABLE"));
    }

    @ParameterizedTest
    @CsvSource({
            "400, malformed_request, CONTRACT_VIOLATION",
            "400, validation_failed, CONTRACT_VIOLATION",
            "403, forbidden, IDENTITY_REJECTED",
            "403, requester_not_allowed, IDENTITY_REJECTED",
            "404, not_found, NOT_FOUND",
            "409, data_source_not_configured, MISCONFIGURED",
            "409, data_source_not_executable, MISCONFIGURED",
            "409, something_else, CONTRACT_VIOLATION",
            "422, validation_failed, CONTRACT_VIOLATION",
            "429, too_many_executions, BUSY",
            "500, data_source_role_not_read_only, MISCONFIGURED",
            "500, data_source_target_not_allowed, MISCONFIGURED",
            "500, execution_failed, EXECUTION_FAILED",
            "502, service_identity_invalid, MISCONFIGURED",
            "502, semantic_contract_violation, DEPENDENCY_UNAVAILABLE",
            "503, semantic_unavailable, DEPENDENCY_UNAVAILABLE",
            "503, plan_validity_unconfirmed, DEPENDENCY_UNAVAILABLE",
            "503, data_source_unavailable, DEPENDENCY_UNAVAILABLE",
            "503, audit_unavailable, DEPENDENCY_UNAVAILABLE",
            "504, execution_timeout, TIMEOUT",
            "302, redirect, CONTRACT_VIOLATION"})
    void otherAnswersAreControlledFailuresAndNeverRetried(int status, String code, Reason reason) throws IOException {
        reports(1, Duration.ofSeconds(5)).server.answer(reports.path(), new FakeSemanticServer.Answer(status,
                ReportsFixture.problem(status, code, null), Duration.ZERO,
                status == 302 ? Map.of("Location", "http://example.com/") : Map.of()));
        reports.server.answer(reports.path(), 200, reports.body(reports.success(COLUMNS, List.of(), n -> {
        })));

        assertThat(failure()).isEqualTo(reason);
        assertThat(reports.server.received("/api/")).hasSize(1);
    }

    @Test
    void a401RenewsTheTokenAndRepeatsTheExecutionOnce() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 401,
                ReportsFixture.problem(401, "unauthorized", null));
        reports.server.answer(reports.path(), 200, reports.body(reports.success(COLUMNS, List.of(), n -> {
        })));

        assertThat(execute()).isInstanceOf(ReportExecutionClient.Executed.class);
        assertThat(reports.server.received("/api/")).hasSize(2);
        assertThat(reports.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
    }

    @Test
    void aSecond401IsAnIdentityRejection() throws IOException {
        reports(1, Duration.ofSeconds(5)).server.handle(reports.path(),
                r -> FakeSemanticServer.Answer.of(401, ReportsFixture.problem(401, "unauthorized", null)));
        assertThat(failure()).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(reports.server.received("/api/")).hasSize(2);
    }

    @Test
    void anExecutionWithoutAnswerInTimeIsATimeoutAndIsNotRepeated() throws IOException {
        reports(1, Duration.ofMillis(300)).server.answer(reports.path(), new FakeSemanticServer.Answer(200, "{}",
                Duration.ofSeconds(2), Map.of()));
        assertThat(failure()).isEqualTo(Reason.TIMEOUT);
        assertThat(reports.server.received("/api/")).hasSize(1);
    }

    @Test
    void anUnreachableReportsIsReported() throws IOException {
        reports(1, Duration.ofSeconds(5));
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ReportsProperties unreachable = new ReportsProperties(URI.create("http://127.0.0.1:" + closedPort),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1 << 20, 1000, 1, reports.identity());
        assertThatThrownBy(() -> ReportsConfiguration.client(unreachable, java.time.Clock.systemUTC())
                .execute(reports.plan, reports.correlationId))
                .isInstanceOfSatisfying(ReportsUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE));
    }

    @Test
    void oversizedAnswersAreRejected() throws IOException {
        reports = new ReportsFixture(0, Duration.ofSeconds(5), 1000);
        ReportsProperties small = new ReportsProperties(reports.server.baseUrl(), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 64 * 1024, 1000, 0, reports.identity());
        reports.server.answer(reports.path(), 200, "{\"pad\":\"" + "x".repeat(70 * 1024) + "\"}");
        assertThatThrownBy(() -> ReportsConfiguration.client(small, java.time.Clock.systemUTC())
                .execute(reports.plan, reports.correlationId))
                .isInstanceOfSatisfying(ReportsUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    @Test
    void tokensForAnotherAudienceRoleOrPrincipalAreNeverSent() throws IOException {
        reports(0, Duration.ofSeconds(5));
        List<String> wrong = List.of(
                reports.executionToken(c -> c.put("aud", List.of("nexora-semantic"))),
                reports.executionToken(c -> c.put("roles", List.of("REPORTS_CLIENT"))),
                reports.executionToken(c -> c.put("principal_type", "AI")),
                reports.executionToken(c -> c.put("principal_type", "HUMAN")),
                reports.executionToken(c -> c.put("tenant", java.util.UUID.randomUUID().toString())),
                reports.tokens.forgedToken(reports.tenant));
        for (String token : wrong) {
            reports.server.answer(FakeSemanticServer.TOKEN_PATH, 200, reports.tokens.tokenResponse(token));
            assertThat(failure()).isEqualTo(Reason.IDENTITY_REJECTED);
        }
        assertThat(reports.server.received("/api/")).isEmpty();
    }

    @Test
    void aTokenWithBothAudiencesAndRolesServesTheExecution() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(FakeSemanticServer.TOKEN_PATH, 200,
                reports.tokens.tokenResponse(reports.executionToken(c -> {
                    c.put("aud", List.of("nexora-semantic", "nexora-reports"));
                    c.put("roles", List.of("REPORTS_CLIENT", "REPORT_RUNNER"));
                })));
        reports.server.answer(reports.path(), 200, reports.body(reports.success(COLUMNS, List.of(), n -> {
        })));
        assertThat(execute()).isInstanceOf(ReportExecutionClient.Executed.class);
    }

    @Test
    void failureMessagesNeverCarryTokensSecretsOrValues() throws IOException {
        reports(0, Duration.ofSeconds(5)).server.answer(reports.path(), 500,
                ReportsFixture.problem(500, "execution_failed", null));
        assertThatThrownBy(this::execute).isInstanceOfSatisfying(ReportsUnavailableException.class, e ->
                assertThat(e.getMessage()).doesNotContain("secret-value").doesNotContain(ReportsFixture.SECRET)
                        .doesNotContain("eyJ"));
        assertThat(reports.properties.toString()).doesNotContain(ReportsFixture.SECRET).contains("clientSecret=***");
    }
}
