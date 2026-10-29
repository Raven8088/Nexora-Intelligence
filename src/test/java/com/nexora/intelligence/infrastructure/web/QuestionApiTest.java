package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.planning.SourceZones;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.result.ColumnRole;
import com.nexora.intelligence.domain.result.ExecutionValidity;
import com.nexora.intelligence.domain.result.ExecutionWarning;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ResultColumn;
import com.nexora.intelligence.domain.result.ResultValue;
import com.nexora.intelligence.domain.result.ValueKind;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.SemanticScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The local question API over HTTP (MockMvc), with the planning flow wired to controllable test doubles. */
@SpringBootTest(properties = {
        "nexora.intelligence.api.enabled=true",
        "nexora.intelligence.api.max-concurrent-questions=1",
        "nexora.intelligence.api.data-sources.wellnode-demo.tenant-id=" + QuestionApiTest.TENANT,
        "nexora.intelligence.api.data-sources.wellnode-demo.data-source-id=" + QuestionApiTest.DATA_SOURCE})
@AutoConfigureMockMvc
class QuestionApiTest {

    static final String TENANT = "11111111-1111-4111-8111-111111111111";
    static final String DATA_SOURCE = "22222222-2222-4222-8222-222222222222";
    static final String SECRET = "super-secret-token-value";

    /** What the test doubles do; reset before each test. */
    static final class Doubles {
        static final AtomicReference<Supplier<SemanticCatalog>> catalog = new AtomicReference<>();
        static final AtomicReference<Supplier<Interpretation>> interpretation = new AtomicReference<>();
        static final AtomicReference<Supplier<QuestionOutcome>> plan = new AtomicReference<>();
        static final AtomicReference<java.util.function.Function<QuestionOutcome.PlanCreated,
                ReportExecutionClient.Execution>> execution = new AtomicReference<>();
        static final List<SemanticScope> scopes = new ArrayList<>();
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        QuestionPlanningService questionPlanningService() {
            Clock clock = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);
            return new QuestionPlanningService((scope, correlationId) -> {
                Doubles.scopes.add(scope);
                SemanticCatalog base = Doubles.catalog.get().get();
                return new SemanticCatalog(scope, base.metrics(), base.dimensions(), base.relationships());
            }, new QuestionTranslationService((q, c) -> Doubles.interpretation.get().get(), clock),
                    (request, correlationId) -> Doubles.plan.get().get(), SourceZones.utc(),
                    Optional.of((plan, correlationId) -> Doubles.execution.get().apply(plan)));
        }
    }

    @Autowired
    private MockMvc mvc;

    private static SemanticScope configured() {
        return new SemanticScope(new com.nexora.intelligence.domain.shared.TenantId(UUID.fromString(TENANT)),
                new com.nexora.intelligence.domain.shared.DataSourceId(UUID.fromString(DATA_SOURCE)));
    }

    private static QuestionOutcome.PlanCreated created() {
        return new QuestionOutcome.PlanCreated(configured(), new PlanId(UUID.fromString(
                "33333333-3333-4333-8333-333333333333")), "a".repeat(64), "nexora-logical-plan/1");
    }

    @BeforeEach
    void reset() {
        Doubles.scopes.clear();
        Doubles.catalog.set(() -> CATALOG);
        Doubles.interpretation.set(() -> Interpretation.interpreted(new RequestDraft(
                List.of(TOTAL_SALES.id(), ORDER_COUNT.id()), List.of(REGION.id()), List.of(), List.of(),
                Optional.empty())));
        Doubles.plan.set(QuestionApiTest::created);
        Doubles.execution.set(plan -> new ReportExecutionClient.Executed(new ReportExecution(configured(),
                plan.planId(), UUID.fromString("44444444-4444-4444-8444-444444444444"), plan.fingerprint(),
                ExecutionValidity.CURRENT_WITH_CHANGES,
                List.of(new ResultColumn(REGION.key(), ColumnRole.DIMENSION, ValueKind.TEXT),
                        new ResultColumn(TOTAL_SALES.key(), ColumnRole.METRIC, ValueKind.NUMERIC),
                        new ResultColumn(ORDER_COUNT.key(), ColumnRole.METRIC, ValueKind.INTEGER)),
                List.of(List.of(new ResultValue.Text("Lima"), new ResultValue.Decimal(new BigDecimal("200.500")),
                                new ResultValue.Integer(new BigInteger("9223372036854775808"))),
                        Arrays.asList(ResultValue.Null.INSTANCE, ResultValue.Null.INSTANCE,
                                new ResultValue.Integer(BigInteger.ZERO))),
                false, 1000, List.of(new ExecutionWarning(ExecutionWarning.PLAN_CURRENT_WITH_CHANGES)))));
    }

    private static MockHttpServletRequestBuilder ask(String body) {
        return post("/api/v1/questions").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String question(String text) {
        return "{\"question\":\"" + text + "\",\"language\":\"es\",\"context\":{\"dataSource\":\"wellnode-demo\"}}";
    }

    // ---- answers ----------------------------------------------------------------------------------------------------

    @Test
    void aReportedAnswerKeepsExactValuesAndTheirDefinitions() throws Exception {
        MvcResult result = mvc.perform(ask(question("Ventas y pedidos por región")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.dataSource").value("wellnode-demo"))
                .andExpect(jsonPath("$.plan.planId").value("33333333-3333-4333-8333-333333333333"))
                .andExpect(jsonPath("$.plan.formatVersion").value("nexora-logical-plan/1"))
                .andExpect(jsonPath("$.result.executionId").value("44444444-4444-4444-8444-444444444444"))
                .andExpect(jsonPath("$.result.validity").value("CURRENT_WITH_CHANGES"))
                .andExpect(jsonPath("$.result.columns[0].key").value("region"))
                .andExpect(jsonPath("$.result.columns[0].definitionId").value(REGION.id().toString()))
                .andExpect(jsonPath("$.result.columns[0].role").value("DIMENSION"))
                .andExpect(jsonPath("$.result.columns[1].type").value("NUMERIC"))
                .andExpect(jsonPath("$.result.columns[2].definitionId").value(ORDER_COUNT.id().toString()))
                .andExpect(jsonPath("$.result.rowCount").value(2))
                .andExpect(jsonPath("$.result.truncated").value(false))
                .andExpect(jsonPath("$.result.maxRows").value(1000))
                .andExpect(jsonPath("$.result.warnings[0]").value("PLAN_CURRENT_WITH_CHANGES"))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("[\"Lima\",\"200.500\",9223372036854775808]").contains("[null,null,0]");
        assertThat(body).doesNotContain(TENANT).doesNotContain(DATA_SOURCE).doesNotContainIgnoringCase("select");
        assertThat(result.getResponse().getHeader("X-Request-Id"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.read(body, "$.correlationId"));
        assertThat(Doubles.scopes).containsExactly(configured());
    }

    @Test
    void clarificationsUnsupportedQuestionsAndRejectionsAreAnswersToo() throws Exception {
        Doubles.interpretation.set(() -> new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.NEEDS_CLARIFICATION, Optional.of("¿Para qué periodo?")));
        mvc.perform(ask(question("Ventas"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.clarification").value("¿Para qué periodo?"))
                .andExpect(jsonPath("$.plan").doesNotExist());

        Doubles.interpretation.set(() -> new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED,
                Optional.empty()));
        mvc.perform(ask(question("Escribe un poema"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNSUPPORTED"));

        Doubles.catalog.set(() -> new SemanticCatalog(SCOPE, List.of(), List.of(REGION), List.of()));
        mvc.perform(ask(question("Ventas"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.origin").value("INTELLIGENCE"))
                .andExpect(jsonPath("$.rejection.causes[0].code").value("CATALOG_EMPTY"))
                .andExpect(jsonPath("$.rejection.omittedCauses").value(0));
    }

    @Test
    void aRejectionBySemanticAndARefusalByReportsAreReportedWithTheirOrigin() throws Exception {
        Doubles.plan.set(() -> new Rejected(Rejected.Origin.SEMANTIC,
                List.of(Rejected.Cause.of("AMBIGUOUS_PATH", "dimensions[0]"))));
        mvc.perform(ask(question("Ventas por región"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.origin").value("SEMANTIC"))
                .andExpect(jsonPath("$.rejection.causes[0].field").value("dimensions[0]"));

        Doubles.plan.set(QuestionApiTest::created);
        Doubles.execution.set(plan -> new ReportExecutionClient.Refused(new Rejected(Rejected.Origin.REPORTS,
                List.of(Rejected.Cause.of("PLAN_STALE")))));
        mvc.perform(ask(question("Ventas por región"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_EXECUTED"))
                .andExpect(jsonPath("$.plan.planId").exists())
                .andExpect(jsonPath("$.rejection.origin").value("REPORTS"))
                .andExpect(jsonPath("$.rejection.causes[0].code").value("PLAN_STALE"));
    }

    // ---- request validation -----------------------------------------------------------------------------------------

    @Test
    void malformedRequestsAreRefusedWithoutReachingTheModel() throws Exception {
        Doubles.interpretation.set(() -> {
            throw new AssertionError("must not interpret");
        });
        String[] malformed = {"", "not json", "[]", "\"text\"",
                question("x") + " {}",
                "{\"question\":\"a\",\"question\":\"b\",\"context\":{\"dataSource\":\"wellnode-demo\"}}",
                "{\"question\":\"a\",\"context\":{\"dataSource\":\"wellnode-demo\"},\"sql\":\"SELECT 1\"}",
                "{\"question\":\"a\",\"context\":{\"dataSource\":\"wellnode-demo\"},\"tenantId\":\"" + TENANT + "\"}",
                "{\"question\":\"a\",\"context\":{\"dataSource\":\"wellnode-demo\",\"tenantId\":\"" + TENANT + "\"}}"};
        for (String body : malformed) {
            mvc.perform(ask(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.code").value("malformed_request"))
                    .andExpect(jsonPath("$.correlationId").exists());
        }
        mvc.perform(post("/api/v1/questions").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[] {'{', '"', 'q', (byte) 0xC3, '"', ':', '1', '}'}))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("malformed_request"));
        assertThat(Doubles.scopes).isEmpty();
    }

    @Test
    void invalidFieldsAreListedWithFixedMessages() throws Exception {
        mvc.perform(ask("{\"question\":5,\"language\":\"fr\",\"context\":{\"dataSource\":\"Bad Alias!\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("question"))
                .andExpect(jsonPath("$.errors[1].field").value("language"))
                .andExpect(jsonPath("$.errors[2].field").value("context.dataSource"));
        mvc.perform(ask("{\"question\":\"Ventas\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("context"));
        MvcResult unknown = mvc.perform(ask("{\"question\":\"Ventas\",\"context\":{\"dataSource\":\"otra-fuente\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("context.dataSource")).andReturn();
        assertThat(unknown.getResponse().getContentAsString()).doesNotContain("wellnode-demo");
        for (String text : new String[] {"   ", "a".repeat(2001), "Ventas\\u0007"}) {
            MvcResult result = mvc.perform(ask(question(text))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("question")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("aaaaaaaaaa");
        }
        assertThat(Doubles.scopes).isEmpty();
    }

    @Test
    void onlyJsonBodiesWithinTheLimitAreRead() throws Exception {
        mvc.perform(post("/api/v1/questions").contentType(MediaType.TEXT_PLAIN).content(question("Ventas")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_media_type"));
        mvc.perform(post("/api/v1/questions").content(question("Ventas")))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/questions").contentType("application/json;charset=ISO-8859-1")
                .content(question("Ventas"))).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/questions").contentType("application/json;charset=UTF-8")
                .content(question("Ventas"))).andExpect(status().isOk());
        mvc.perform(ask("{\"question\":\"" + "a".repeat(9000) + "\",\"context\":{\"dataSource\":\"wellnode-demo\"}}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("payload_too_large"));
    }

    @Test
    void theCorrelationIdIsAssignedByTheServer() throws Exception {
        MvcResult result = mvc.perform(ask(question("Ventas por región")).header("X-Request-Id", "forged-id"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotEqualTo("forged-id")
                .matches("[0-9a-f-]{36}");
    }

    // ---- local only -------------------------------------------------------------------------------------------------

    @Test
    void requestsFromOtherMachinesAreRefusedEvenWithForwardingHeaders() throws Exception {
        for (String address : new String[] {"10.0.0.5", "192.168.1.20", "203.0.113.7", "fe80::1"}) {
            mvc.perform(ask(question("Ventas por región")).with(r -> {
                        r.setRemoteAddr(address);
                        return r;
                    }).header("X-Forwarded-For", "127.0.0.1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("local_only"));
        }
        assertThat(Doubles.scopes).isEmpty();
        mvc.perform(ask(question("Ventas por región")).with(r -> {
            r.setRemoteAddr("::1");
            return r;
        })).andExpect(status().isOk());
    }

    @Test
    void loopbackDetectionNeverResolvesNames() {
        assertThat(LoopbackOnlyFilter.isLoopback("127.0.0.1")).isTrue();
        assertThat(LoopbackOnlyFilter.isLoopback("127.10.0.1")).isTrue();
        assertThat(LoopbackOnlyFilter.isLoopback("::1")).isTrue();
        assertThat(LoopbackOnlyFilter.isLoopback("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(LoopbackOnlyFilter.isLoopback("localhost")).isFalse();
        assertThat(LoopbackOnlyFilter.isLoopback("10.0.0.1")).isFalse();
        assertThat(LoopbackOnlyFilter.isLoopback("")).isFalse();
        assertThat(LoopbackOnlyFilter.isLoopback(null)).isFalse();
    }

    // ---- failures ---------------------------------------------------------------------------------------------------

    @Test
    void failuresBecomeControlledProblemsWithoutInternalDetails() throws Exception {
        record Case(RuntimeException failure, int status, String code) {
        }
        List<Case> cases = List.of(
                new Case(new InterpretationUnavailableException(InterpretationUnavailableException.Reason.TIMEOUT,
                        SECRET), 504, "interpretation_timeout"),
                new Case(new InterpretationUnavailableException(InterpretationUnavailableException.Reason.HTTP_ERROR,
                        SECRET), 503, "interpretation_unavailable"),
                new Case(new InterpretationUnavailableException(
                        InterpretationUnavailableException.Reason.INVALID_MODEL_OUTPUT, SECRET), 502,
                        "interpretation_invalid"),
                new Case(new InterpretationUnavailableException(
                        InterpretationUnavailableException.Reason.CONTEXT_LIMIT_EXCEEDED, SECRET), 503,
                        "interpretation_capacity_exceeded"),
                new Case(new SemanticUnavailableException(SemanticUnavailableException.Reason.UNREACHABLE, SECRET),
                        503, "semantic_unavailable"),
                new Case(new SemanticUnavailableException(SemanticUnavailableException.Reason.OUTCOME_UNKNOWN, SECRET),
                        504, "plan_outcome_unknown"),
                new Case(new SemanticUnavailableException(SemanticUnavailableException.Reason.IDENTITY_REJECTED,
                        SECRET), 502, "semantic_identity_rejected"),
                new Case(new SemanticUnavailableException(SemanticUnavailableException.Reason.SOURCE_ARCHIVED, SECRET),
                        409, "data_source_archived"),
                new Case(new SemanticUnavailableException(SemanticUnavailableException.Reason.CATALOG_INCONSISTENT,
                        SECRET), 503, "catalog_changing"),
                new Case(new ReportsUnavailableException(ReportsUnavailableException.Reason.BUSY, SECRET), 429,
                        "reports_busy"),
                new Case(new ReportsUnavailableException(ReportsUnavailableException.Reason.TIMEOUT, SECRET), 504,
                        "reports_timeout"),
                new Case(new ReportsUnavailableException(ReportsUnavailableException.Reason.MISCONFIGURED, SECRET),
                        502, "reports_misconfigured"),
                new Case(new ReportsUnavailableException(ReportsUnavailableException.Reason.CONTRACT_VIOLATION,
                        SECRET), 502, "reports_contract_violation"),
                new Case(new IllegalStateException(SECRET), 500, "internal_error"));
        for (Case c : cases) {
            Doubles.execution.set(plan -> {
                throw c.failure();
            });
            MvcResult result = mvc.perform(ask(question("Ventas por región")))
                    .andExpect(status().is(c.status()))
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.code").value(c.code()))
                    .andExpect(jsonPath("$.correlationId").exists()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET)
                    .doesNotContain("Exception").doesNotContain(TENANT);
        }
    }

    @Test
    void everyFailureReasonHasAProblem() {
        for (var reason : InterpretationUnavailableException.Reason.values()) {
            assertThat(ApiProblems.of(new InterpretationUnavailableException(reason, "x"))).isNotNull();
        }
        for (var reason : SemanticUnavailableException.Reason.values()) {
            assertThat(ApiProblems.of(new SemanticUnavailableException(reason, "x"))).isNotNull();
        }
        for (var reason : ReportsUnavailableException.Reason.values()) {
            assertThat(ApiProblems.of(new ReportsUnavailableException(reason, "x"))).isNotNull();
        }
    }

    @Test
    void questionsBeyondTheConcurrencyLimitAreRefused() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Doubles.execution.set(plan -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ReportExecutionClient.Refused(new Rejected(Rejected.Origin.REPORTS,
                    List.of(Rejected.Cause.of("PLAN_STALE"))));
        });
        Thread first = new Thread(() -> {
            try {
                mvc.perform(ask(question("Ventas por región")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        first.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        mvc.perform(ask(question("Ventas por región"))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_questions"));
        release.countDown();
        first.join(10_000);
        mvc.perform(ask(question("Ventas por región"))).andExpect(status().isOk());
    }

    @Test
    void otherMethodsAndPathsExposeNothing() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/questions")).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(404, 405);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("trace");
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
