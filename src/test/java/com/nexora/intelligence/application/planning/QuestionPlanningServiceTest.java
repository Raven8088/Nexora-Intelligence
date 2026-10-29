package com.nexora.intelligence.application.planning;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.DefinitionKey;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.semantic.ValueType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planning flow: catalog → interpretation and deterministic rules → plan creation by Semantic. */
class QuestionPlanningServiceTest {

    /** 2026-10-31 21:00 in Mexico City, 2026-11-01 03:00 in UTC and 04:00 in Madrid. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);
    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    private static final CatalogDimension CREATED_AT = new CatalogDimension(
            new com.nexora.intelligence.domain.semantic.DefinitionId(UUID.randomUUID()), new DefinitionKey("alta"),
            "Alta", Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.TIMESTAMP_TZ),
            EnumSet.of(TimeGranularity.DAY), Optional.of(ZoneId.of("Europe/Madrid")));
    private static final SemanticCatalog WITH_TIMESTAMPS = new SemanticCatalog(SCOPE, CATALOG.metrics(),
            List.of(REGION, ORDER_DATE, CREATED_AT), CATALOG.relationships());

    private final List<SemanticRequest> sent = new ArrayList<>();
    private final AtomicInteger interpretations = new AtomicInteger();
    private final AtomicInteger catalogReads = new AtomicInteger();
    private final QuestionOutcome.PlanCreated created = new QuestionOutcome.PlanCreated(SCOPE,
            new PlanId(UUID.randomUUID()), "b".repeat(64), "nexora-logical-plan/1");

    private QuestionPlanningService service(SemanticCatalog catalog, Interpretation interpretation,
                                            SourceZones zones) {
        SemanticCatalogClient catalogs = (scope, correlationId) -> {
            catalogReads.incrementAndGet();
            return catalog;
        };
        QuestionInterpreter interpreter = (question, c) -> {
            interpretations.incrementAndGet();
            return interpretation;
        };
        LogicalPlanClient plans = (request, correlationId) -> {
            sent.add(request);
            return created;
        };
        return new QuestionPlanningService(catalogs, new QuestionTranslationService(interpreter, CLOCK), plans,
                zones);
    }

    private static NaturalLanguageQuestion question(String text) {
        return new NaturalLanguageQuestion(text, Optional.of(Language.ES), CorrelationId.generate());
    }

    private static Interpretation today(CatalogDimension dimension) {
        return Interpretation.interpreted(new RequestDraft(List.of(TOTAL_SALES.id()), List.of(), List.of(), List.of(),
                Optional.of(new PeriodSelection(dimension.id(), new PeriodSpec.Relative(RelativePeriod.TODAY)))));
    }

    @Test
    void aReadyRequestIsSentToSemanticAndItsPlanReturned() {
        QuestionOutcome outcome = service(CATALOG, Interpretation.interpreted(new RequestDraft(
                List.of(TOTAL_SALES.id()), List.of(REGION.id()), List.of(), List.of(), Optional.empty())),
                SourceZones.utc()).plan(question("Ventas por región"), SCOPE);

        assertThat(outcome).isEqualTo(created);
        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.scope()).isEqualTo(SCOPE);
            assertThat(request.metrics()).containsExactly(TOTAL_SALES.id());
            assertThat(request.dimensions()).containsExactly(REGION.id());
        });
        assertThat(catalogReads).hasValue(1);
    }

    @Test
    void aCatalogWithoutMetricsIsRejectedWithoutCallingTheModelOrCreatingAPlan() {
        SemanticCatalog empty = new SemanticCatalog(SCOPE, List.of(), List.of(REGION), List.of());

        QuestionOutcome outcome = service(empty, today(ORDER_DATE), SourceZones.utc()).plan(question("Ventas"), SCOPE);

        assertThat(outcome).isEqualTo(new Rejected(Rejected.Origin.INTELLIGENCE,
                List.of(Rejected.Cause.of(QuestionPlanningService.CATALOG_EMPTY))));
        assertThat(interpretations).hasValue(0);
        assertThat(sent).isEmpty();
    }

    @Test
    void answersOfTheInterpretationNeverReachSemantic() {
        assertThat(service(CATALOG, new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED,
                Optional.empty()), SourceZones.utc()).plan(question("Escribe un poema"), SCOPE))
                .isInstanceOf(QuestionOutcome.Unsupported.class);
        assertThat(service(CATALOG, new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.NEEDS_CLARIFICATION, Optional.of("¿Qué periodo?")), SourceZones.utc())
                .plan(question("Ventas"), SCOPE)).isEqualTo(new QuestionOutcome.NeedsClarification("¿Qué periodo?"));
        assertThat(sent).isEmpty();
    }

    @Test
    void aRejectionByIntelligenceNeverReachesSemantic() {
        Interpretation wrongGranularity = Interpretation.interpreted(new RequestDraft(List.of(TOTAL_SALES.id()),
                List.of(ORDER_DATE.id()), List.of(), List.of(new com.nexora.intelligence.domain.request
                .GranularityChoice(ORDER_DATE.id(), TimeGranularity.DAY)), Optional.empty()));

        assertThat(service(CATALOG, wrongGranularity, SourceZones.utc()).plan(question("Ventas por día"), SCOPE))
                .isInstanceOfSatisfying(Rejected.class,
                        r -> assertThat(r.origin()).isEqualTo(Rejected.Origin.INTELLIGENCE));
        assertThat(sent).isEmpty();
    }

    @Test
    void aRejectionBySemanticIsReturnedAsIs() {
        Rejected bySemantic = new Rejected(Rejected.Origin.SEMANTIC, List.of(Rejected.Cause.of("NO_PATH")));
        QuestionPlanningService service = new QuestionPlanningService((s, c) -> CATALOG,
                new QuestionTranslationService((q, c) -> Interpretation.interpreted(new RequestDraft(
                        List.of(TOTAL_SALES.id()), List.of(REGION.id()), List.of(), List.of(), Optional.empty())),
                        CLOCK), (r, c) -> bySemantic, SourceZones.utc());

        assertThat(service.plan(question("Ventas por región"), SCOPE)).isEqualTo(bySemantic);
    }

    @Test
    void failuresAreThrownNeverPresentedAsOutcomes() {
        QuestionPlanningService semanticDown = new QuestionPlanningService((s, c) -> {
            throw new SemanticUnavailableException(SemanticUnavailableException.Reason.UNREACHABLE, "down");
        }, new QuestionTranslationService((q, c) -> today(ORDER_DATE), CLOCK), (r, c) -> created, SourceZones.utc());
        assertThatThrownBy(() -> semanticDown.plan(question("Ventas de hoy"), SCOPE))
                .isInstanceOf(SemanticUnavailableException.class);

        QuestionPlanningService modelDown = new QuestionPlanningService((s, c) -> CATALOG,
                new QuestionTranslationService((q, c) -> {
                    throw new InterpretationUnavailableException(InterpretationUnavailableException.Reason.TIMEOUT,
                            "slow");
                }, CLOCK), (r, c) -> created, SourceZones.utc());
        assertThatThrownBy(() -> modelDown.plan(question("Ventas de hoy"), SCOPE))
                .isInstanceOf(InterpretationUnavailableException.class);
    }

    @Test
    void aCatalogOfAnotherScopeIsRefused() {
        SemanticCatalog other = new SemanticCatalog(
                com.nexora.intelligence.domain.semantic.CatalogFixtures.OTHER_SCOPE, CATALOG.metrics(), List.of(),
                List.of());
        assertThatThrownBy(() -> service(other, today(ORDER_DATE), SourceZones.utc()).plan(question("Ventas"), SCOPE))
                .isInstanceOfSatisfying(SemanticUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(SemanticUnavailableException.Reason.CONTRACT_VIOLATION));
        assertThat(sent).isEmpty();
    }

    @Test
    void periodsOfDatesAreMeasuredInTheZoneConfiguredForTheSource() {
        service(CATALOG, today(ORDER_DATE), new SourceZones(ZoneOffset.UTC, Map.of(SCOPE.dataSourceId(), MEXICO)))
                .plan(question("Ventas de hoy"), SCOPE);
        service(CATALOG, today(ORDER_DATE), SourceZones.utc()).plan(question("Ventas de hoy"), SCOPE);

        assertThat(sent.get(0).filters()).containsExactly(
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2026-10-31")),
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.LT, FilterValue.text("2026-11-01")));
        assertThat(sent.get(1).filters()).containsExactly(
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2026-11-01")),
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.LT, FilterValue.text("2026-11-02")));
    }

    @Test
    void theZoneOfADimensionTakesPrecedenceOverTheSourceZone() {
        service(WITH_TIMESTAMPS, today(CREATED_AT),
                new SourceZones(ZoneOffset.UTC, Map.of(SCOPE.dataSourceId(), MEXICO)))
                .plan(question("Altas de hoy"), SCOPE);

        assertThat(sent.get(0).filters()).containsExactly(
                FilterCondition.of(CREATED_AT.id(), FilterOperator.GTE, FilterValue.text("2026-11-01T00:00:00+01:00")),
                FilterCondition.of(CREATED_AT.id(), FilterOperator.LT, FilterValue.text("2026-11-02T00:00:00+01:00")));
    }

    @Test
    void sourcesWithoutAConfiguredZoneUseTheTechnicalDefault() {
        SourceZones zones = new SourceZones(MEXICO, Map.of());
        assertThat(zones.zoneOf(SCOPE.dataSourceId())).isEqualTo(MEXICO);
        assertThat(SourceZones.utc().zoneOf(SCOPE.dataSourceId())).isEqualTo(ZoneOffset.UTC);
    }
}
