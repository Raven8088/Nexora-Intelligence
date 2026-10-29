package com.nexora.intelligence.infrastructure;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.result.QuestionAnswer;
import com.nexora.intelligence.domain.result.ResultValue;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import com.nexora.intelligence.infrastructure.planning.PlanningConfigurationAccess;
import com.nexora.intelligence.infrastructure.semantic.FakeSemanticServer;
import com.nexora.intelligence.infrastructure.semantic.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole INT-3.4 + INT-3.5 flow, wired by the real Spring configurations against a simulated Semantic and Reports
 * (one Keycloak-like client with both audiences and roles): catalog → interpretation (fixed) → plan → execution →
 * result bound to the definitions, with exact values.
 */
class QuestionToReportIntegrationTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID dataSource = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();
    private final UUID date = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final String fingerprint = "c".repeat(64);
    private FakeSemanticServer server;
    private final TestTokens tokens = new TestTokens();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeSemanticServer();
        server.handle(FakeSemanticServer.JWKS_PATH, r -> FakeSemanticServer.Answer.of(200, tokens.jwks()));
        server.handle(FakeSemanticServer.TOKEN_PATH, r -> FakeSemanticServer.Answer.of(200,
                tokens.tokenResponse(tokens.token(tenant, claims -> {
                    claims.put("aud", List.of("nexora-semantic", "nexora-reports"));
                    claims.put("roles", List.of("REPORTS_CLIENT", "REPORT_RUNNER"));
                }))));
        String base = "/api/v1/data-sources/" + dataSource + "/";
        server.answer(base + "definitions", 200, page("{\"id\":\"" + sales + "\",\"dataSourceId\":\"" + dataSource
                + "\",\"kind\":\"METRIC\",\"key\":\"ventas\",\"name\":\"Ventas\",\"status\":\"APPROVED\","
                + "\"usable\":true,\"metric\":{\"aggregation\":\"SUM\",\"columnNaturalKey\":\"x\"}}"));
        server.answer(base + "definitions", 200, page("{\"id\":\"" + date + "\",\"dataSourceId\":\"" + dataSource
                + "\",\"kind\":\"DIMENSION\",\"key\":\"fecha\",\"name\":\"Fecha\",\"status\":\"APPROVED\","
                + "\"usable\":true,\"dimension\":{\"type\":\"TEMPORAL\",\"valueType\":\"DATE\","
                + "\"granularities\":[\"DAY\",\"MONTH\"]}}"));
        server.answer(base + "relationships", 200, page(""));
        server.answer(base + "logical-plans", 201, "{\"id\":\"" + planId + "\",\"dataSourceId\":\"" + dataSource
                + "\",\"formatVersion\":\"nexora-logical-plan/1\",\"fingerprint\":\"" + fingerprint
                + "\",\"createdBy\":\"svc\",\"createdAt\":\"2026-11-01T03:00:00Z\",\"plan\":{}}");
        server.answer(base + "plans/" + planId + "/executions", 200, "{\"executionId\":\"" + UUID.randomUUID()
                + "\",\"planId\":\"" + planId + "\",\"dataSourceId\":\"" + dataSource + "\",\"planFingerprint\":\""
                + fingerprint + "\",\"validity\":\"CURRENT\",\"columns\":["
                + "{\"name\":\"fecha\",\"role\":\"DIMENSION\",\"type\":\"DATE\"},"
                + "{\"name\":\"ventas\",\"role\":\"METRIC\",\"type\":\"NUMERIC\"}],"
                + "\"rows\":[[\"2026-10-01\",\"98765432109876543210.0050\"],[\"2026-11-01\",null]],\"rowCount\":2,"
                + "\"truncated\":false,\"maxRows\":500,\"durationMs\":12,\"warnings\":[]}");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static String page(String item) {
        return "{\"items\":[" + item + "],\"page\":0,\"size\":200,\"totalItems\":" + (item.isEmpty() ? 0 : 1) + "}";
    }

    private String[] properties() {
        String identity = "identity.";
        return new String[] {
                "nexora.intelligence.inference.base-url=http://127.0.0.1:9/v1",
                "nexora.intelligence.semantic.base-url=" + server.baseUrl(),
                "nexora.intelligence.reports.base-url=" + server.baseUrl(),
                "nexora.intelligence.reports.max-rows=500",
                "nexora.intelligence.planning.source-zones." + dataSource + "=America/Mexico_City",
                "nexora.intelligence.semantic." + identity + "tenant-id=" + tenant,
                "nexora.intelligence.semantic." + identity + "token-uri=" + server.tokenUri(),
                "nexora.intelligence.semantic." + identity + "jwk-set-uri=" + server.jwksUri(),
                "nexora.intelligence.semantic." + identity + "issuer=" + TestTokens.ISSUER,
                "nexora.intelligence.semantic." + identity + "client-id=nexora-intelligence",
                "nexora.intelligence.semantic." + identity + "client-secret=test-only",
                "nexora.intelligence.reports." + identity + "tenant-id=" + tenant,
                "nexora.intelligence.reports." + identity + "token-uri=" + server.tokenUri(),
                "nexora.intelligence.reports." + identity + "jwk-set-uri=" + server.jwksUri(),
                "nexora.intelligence.reports." + identity + "issuer=" + TestTokens.ISSUER,
                "nexora.intelligence.reports." + identity + "client-id=nexora-intelligence",
                "nexora.intelligence.reports." + identity + "client-secret=test-only"};
    }

    @Test
    void aQuestionBecomesAnExecutedReportWithExactValues() {
        QuestionInterpreter interpreter = (question, catalog) -> Interpretation.interpreted(new RequestDraft(
                List.of(catalog.metrics().get(0).id()), List.of(catalog.dimensions().get(0).id()), List.of(),
                List.of(new GranularityChoice(catalog.dimensions().get(0).id(), TimeGranularity.MONTH)),
                Optional.of(new PeriodSelection(catalog.dimensions().get(0).id(),
                        new PeriodSpec.Relative(RelativePeriod.THIS_YEAR)))));
        new ApplicationContextRunner()
                .withUserConfiguration(PlanningConfigurationAccess.configurations())
                .withBean(QuestionInterpreter.class, () -> interpreter)
                .withPropertyValues(properties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QuestionPlanningService service = context.getBean(QuestionPlanningService.class);
                    assertThat(service.canExecute()).isTrue();

                    QuestionAnswer answer = service.answer(new NaturalLanguageQuestion("Ventas por mes de este año",
                            Optional.of(Language.ES), CorrelationId.generate()),
                            new SemanticScope(new TenantId(tenant), new DataSourceId(dataSource)));

                    assertThat(answer).isInstanceOfSatisfying(QuestionAnswer.Reported.class, reported -> {
                        assertThat(reported.plan().planId().value()).isEqualTo(planId);
                        assertThat(reported.result().definitions()).extracting(d -> d.value())
                                .containsExactly(date, sales);
                        assertThat(reported.result().execution().rows()).containsExactly(
                                List.of(new ResultValue.Date(LocalDate.of(2026, 10, 1)),
                                        new ResultValue.Decimal(new BigDecimal("98765432109876543210.0050"))),
                                List.of(new ResultValue.Date(LocalDate.of(2026, 11, 1)), ResultValue.Null.INSTANCE));
                    });
                    assertThat(server.received("/api/v1/data-sources/" + dataSource + "/plans/" + planId
                            + "/executions").get(0).body()).isEqualTo("{\"maxRows\":500}");
                    // one token per target service (Semantic and Reports), each verified for its own audience
                    assertThat(server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
                });
    }
}
