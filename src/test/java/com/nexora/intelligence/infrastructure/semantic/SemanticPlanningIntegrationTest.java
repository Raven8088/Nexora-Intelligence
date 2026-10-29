package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.planning.SourceZones;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole INT-3.4 flow over HTTP against a simulated Semantic: catalog with value types → interpretation (a fixed
 * one; the inference adapter has its own tests) → deterministic period resolution in the source zone → plan.
 */
class SemanticPlanningIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);

    private SemanticFixture semantic;

    @BeforeEach
    void setUp() throws Exception {
        semantic = new SemanticFixture();
    }

    @AfterEach
    void tearDown() {
        semantic.close();
    }

    @Test
    void aQuestionAboutTodayBecomesAPlanWithTheValueTypeFromTheCatalog() {
        UUID sales = UUID.randomUUID();
        UUID date = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        semantic.catalog(List.of(semantic.metric(sales, "ventas", "SUM", true)),
                List.of(semantic.dimension(date, "fecha", "TEMPORAL", "DATE", List.of("DAY", "MONTH"), null, true)),
                List.of());
        semantic.server.answer(semantic.path("logical-plans"), 201, "{\"id\":\"" + planId + "\",\"dataSourceId\":\""
                + semantic.dataSource + "\",\"formatVersion\":\"nexora-logical-plan/1\",\"fingerprint\":\""
                + "c".repeat(64) + "\",\"createdBy\":\"svc\",\"createdAt\":\"2026-11-01T03:00:00Z\",\"plan\":{}}");
        AtomicReference<SemanticCatalog> seen = new AtomicReference<>();
        QuestionTranslationService translation = new QuestionTranslationService((question, catalog) -> {
            seen.set(catalog);
            return Interpretation.interpreted(new RequestDraft(List.of(catalog.metrics().get(0).id()), List.of(),
                    List.of(), List.of(), Optional.of(new PeriodSelection(catalog.dimensions().get(0).id(),
                    new PeriodSpec.Relative(RelativePeriod.TODAY)))));
        }, CLOCK);
        QuestionPlanningService service = new QuestionPlanningService(semantic.catalogClient(), translation,
                semantic.planClient(), new SourceZones(ZoneOffset.UTC,
                Map.of(semantic.scope.dataSourceId(), ZoneId.of("America/Mexico_City"))));

        QuestionOutcome outcome = service.plan(new NaturalLanguageQuestion("Ventas de hoy", Optional.of(Language.ES),
                CorrelationId.generate()), semantic.scope);

        assertThat(outcome).isInstanceOfSatisfying(QuestionOutcome.PlanCreated.class,
                created -> assertThat(created.planId().value()).isEqualTo(planId));
        assertThat(seen.get().dimensions().get(0).valueType())
                .contains(com.nexora.intelligence.domain.semantic.ValueType.DATE);
        JsonNode body = SemanticFixture.JSON.readTree(semantic.server.received(semantic.path("logical-plans"))
                .get(0).body());
        assertThat(body.get("metrics").get(0).asString()).isEqualTo(sales.toString());
        assertThat(body.get("filters").toString()).isEqualTo("[{\"definitionId\":\"" + date
                + "\",\"operator\":\"GTE\",\"value\":\"2026-10-31\"},{\"definitionId\":\"" + date
                + "\",\"operator\":\"LT\",\"value\":\"2026-11-01\"}]");
        assertThat(semantic.server.received("/api/")).hasSize(4);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(1);
    }

    @Test
    void withoutValueTypeAPeriodCannotBeResolvedAndNothingIsSent() {
        semantic.catalog(List.of(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true)),
                List.of(semantic.dimension(UUID.randomUUID(), "fecha", "TEMPORAL", null, List.of("DAY"), null, true)),
                List.of());
        QuestionPlanningService service = new QuestionPlanningService(semantic.catalogClient(),
                new QuestionTranslationService((question, catalog) -> Interpretation.interpreted(new RequestDraft(
                        List.of(catalog.metrics().get(0).id()), List.of(), List.of(), List.of(),
                        Optional.of(new PeriodSelection(catalog.dimensions().get(0).id(),
                                new PeriodSpec.Relative(RelativePeriod.TODAY))))), CLOCK),
                semantic.planClient(), SourceZones.utc());

        QuestionOutcome outcome = service.plan(new NaturalLanguageQuestion("Ventas de hoy", Optional.of(Language.ES),
                CorrelationId.generate()), semantic.scope);

        assertThat(outcome).isNotInstanceOf(QuestionOutcome.PlanCreated.class);
        assertThat(semantic.server.received(semantic.path("logical-plans"))).isEmpty();
    }
}
