package com.nexora.intelligence.application.planning;

import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.result.ColumnRole;
import com.nexora.intelligence.domain.result.ExecutionValidity;
import com.nexora.intelligence.domain.result.QuestionAnswer;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ResultColumn;
import com.nexora.intelligence.domain.result.ResultValue;
import com.nexora.intelligence.domain.result.ValueKind;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link QuestionPlanningService#answer}: from the question to the executed result, without duplicating planning. */
class QuestionAnsweringTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);
    private static final Interpretation SALES_BY_REGION = Interpretation.interpreted(new RequestDraft(
            List.of(TOTAL_SALES.id()), List.of(REGION.id()), List.of(), List.of(), Optional.empty()));

    private final QuestionOutcome.PlanCreated created = new QuestionOutcome.PlanCreated(SCOPE,
            new PlanId(UUID.randomUUID()), "a".repeat(64), "nexora-logical-plan/1");
    private final List<SemanticRequest> plansAsked = new ArrayList<>();
    private final List<QuestionOutcome.PlanCreated> executed = new ArrayList<>();
    private final AtomicInteger catalogReads = new AtomicInteger();

    private QuestionPlanningService service(SemanticCatalog catalog, Interpretation interpretation,
                                            QuestionOutcome planOutcome, ReportExecutionClient reports) {
        LogicalPlanClient plans = (request, correlationId) -> {
            plansAsked.add(request);
            return planOutcome;
        };
        return new QuestionPlanningService((scope, c) -> {
            catalogReads.incrementAndGet();
            return catalog;
        }, new QuestionTranslationService((q, c) -> interpretation, CLOCK), plans, SourceZones.utc(),
                Optional.of((plan, correlationId) -> {
                    executed.add(plan);
                    return reports.execute(plan, correlationId);
                }));
    }

    private static NaturalLanguageQuestion question() {
        return new NaturalLanguageQuestion("Ventas por región", Optional.of(Language.ES), CorrelationId.generate());
    }

    private ReportExecution execution(List<ResultColumn> columns, String fingerprint) {
        return new ReportExecution(SCOPE, created.planId(), UUID.randomUUID(), fingerprint, ExecutionValidity.CURRENT,
                columns, List.of(List.of(new ResultValue.Text("Lima"),
                new ResultValue.Decimal(new BigDecimal("1500.25")))), false, 1000, List.of());
    }

    private static List<ResultColumn> columns() {
        return List.of(new ResultColumn(REGION.key(), ColumnRole.DIMENSION, ValueKind.TEXT),
                new ResultColumn(TOTAL_SALES.key(), ColumnRole.METRIC, ValueKind.NUMERIC));
    }

    @Test
    void aCreatedPlanIsExecutedAndItsResultBoundToTheDefinitions() {
        ReportExecution execution = execution(columns(), created.fingerprint());

        QuestionAnswer answer = service(CATALOG, SALES_BY_REGION, created,
                (plan, c) -> new ReportExecutionClient.Executed(execution)).answer(question(), SCOPE);

        assertThat(answer).isInstanceOfSatisfying(QuestionAnswer.Reported.class, reported -> {
            assertThat(reported.plan()).isEqualTo(created);
            assertThat(reported.result().definitions()).containsExactly(REGION.id(), TOTAL_SALES.id());
            assertThat(reported.result().execution().rows().get(0).get(1))
                    .isEqualTo(new ResultValue.Decimal(new BigDecimal("1500.25")));
        });
        assertThat(executed).containsExactly(created);
        assertThat(catalogReads).hasValue(1);
        assertThat(plansAsked).hasSize(1);
    }

    @Test
    void aRefusalByReportsIsReturnedWithThePlan() {
        Rejected stale = new Rejected(Rejected.Origin.REPORTS, List.of(Rejected.Cause.of("PLAN_STALE")));

        assertThat(service(CATALOG, SALES_BY_REGION, created, (plan, c) -> new ReportExecutionClient.Refused(stale))
                .answer(question(), SCOPE)).isEqualTo(new QuestionAnswer.NotExecuted(created, stale));
    }

    @Test
    void withoutAPlanNothingIsExecuted() {
        Rejected bySemantic = new Rejected(Rejected.Origin.SEMANTIC, List.of(Rejected.Cause.of("NO_PATH")));
        ReportExecutionClient never = (plan, c) -> {
            throw new AssertionError("must not execute");
        };
        assertThat(service(CATALOG, SALES_BY_REGION, bySemantic, never).answer(question(), SCOPE))
                .isEqualTo(new QuestionAnswer.NotPlanned(bySemantic));
        assertThat(service(CATALOG, new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED,
                Optional.empty()), created, never).answer(question(), SCOPE))
                .isEqualTo(new QuestionAnswer.NotPlanned(new QuestionOutcome.Unsupported()));
        SemanticCatalog empty = new SemanticCatalog(SCOPE, List.of(), List.of(REGION), List.of());
        assertThat(service(empty, SALES_BY_REGION, created, never).answer(question(), SCOPE))
                .isInstanceOfSatisfying(QuestionAnswer.NotPlanned.class, notPlanned -> assertThat(notPlanned.outcome())
                        .isEqualTo(new Rejected(Rejected.Origin.INTELLIGENCE,
                                List.of(Rejected.Cause.of(QuestionPlanningService.CATALOG_EMPTY)))));
        assertThat(executed).isEmpty();
        assertThat(plansAsked).hasSize(1);
    }

    @Test
    void aResultThatDoesNotMatchThePlanIsNeverPresented() {
        ReportExecution otherFingerprint = execution(columns(), "b".repeat(64));
        assertThatThrownBy(() -> service(CATALOG, SALES_BY_REGION, created,
                (plan, c) -> new ReportExecutionClient.Executed(otherFingerprint)).answer(question(), SCOPE))
                .isInstanceOfSatisfying(ReportsUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(ReportsUnavailableException.Reason.CONTRACT_VIOLATION));

        ReportExecution extraColumn = new ReportExecution(SCOPE, created.planId(), UUID.randomUUID(),
                created.fingerprint(), ExecutionValidity.CURRENT, List.of(columns().get(0), columns().get(1),
                new ResultColumn(com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT.key(),
                        ColumnRole.METRIC, ValueKind.INTEGER)), List.of(), false, 1000, List.of());
        assertThatThrownBy(() -> service(CATALOG, SALES_BY_REGION, created,
                (plan, c) -> new ReportExecutionClient.Executed(extraColumn)).answer(question(), SCOPE))
                .isInstanceOfSatisfying(ReportsUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(ReportsUnavailableException.Reason.CONTRACT_VIOLATION));
    }

    @Test
    void failuresOfReportsAreThrownNeverPresentedAsAnswers() {
        assertThatThrownBy(() -> service(CATALOG, SALES_BY_REGION, created, (plan, c) -> {
            throw new ReportsUnavailableException(ReportsUnavailableException.Reason.BUSY, "busy");
        }).answer(question(), SCOPE)).isInstanceOf(ReportsUnavailableException.class);
    }

    @Test
    void answeringRequiresReportsWhilePlanningDoesNot() {
        QuestionPlanningService planningOnly = new QuestionPlanningService((scope, c) -> CATALOG,
                new QuestionTranslationService((q, c) -> SALES_BY_REGION, CLOCK), (r, c) -> created,
                SourceZones.utc());
        assertThat(planningOnly.canExecute()).isFalse();
        assertThat(planningOnly.plan(question(), SCOPE)).isEqualTo(created);
        assertThatThrownBy(() -> planningOnly.answer(question(), SCOPE)).isInstanceOf(IllegalStateException.class);
        assertThat(service(CATALOG, SALES_BY_REGION, created, (p, c) -> null).canExecute()).isTrue();
    }
}
