package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.nexora.intelligence.domain.result.ResultFixtures.PLAN;
import static com.nexora.intelligence.domain.result.ResultFixtures.column;
import static com.nexora.intelligence.domain.result.ResultFixtures.execution;
import static com.nexora.intelligence.domain.result.ResultFixtures.row;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CHANNEL;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CUSTOMER_AGE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.OTHER_SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Binding an execution to the definitions of the request that produced its plan. */
class ReportResultTest {

    private static final SemanticRequest REQUEST = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id(),
            ORDER_COUNT.id()), List.of(ORDER_DATE.id(), REGION.id()), List.of(),
            List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)), List.of());

    private static final List<ResultColumn> COLUMNS = List.of(
            column("order-date", ColumnRole.DIMENSION, ValueKind.DATE),
            column("region", ColumnRole.DIMENSION, ValueKind.TEXT),
            column("total-sales", ColumnRole.METRIC, ValueKind.NUMERIC),
            column("order-count", ColumnRole.METRIC, ValueKind.INTEGER));

    private static ReportResult bind(ReportExecution execution) {
        return ReportResult.bind(execution, PLAN, REQUEST, CATALOG);
    }

    private static void mismatch(ReportExecution execution) {
        assertThatThrownBy(() -> bind(execution)).isInstanceOf(ReportResult.MismatchException.class);
    }

    @Test
    void everyColumnIsBoundToItsDefinitionAndValuesAreUntouched() {
        ReportExecution execution = execution(COLUMNS, List.of(row(new ResultValue.Date(LocalDate.of(2026, 1, 1)),
                new ResultValue.Text("Lima"), new ResultValue.Decimal(new BigDecimal("200.500")),
                new ResultValue.Integer(BigInteger.ONE))));

        ReportResult result = bind(execution);

        assertThat(result.definitions()).containsExactly(ORDER_DATE.id(), REGION.id(), TOTAL_SALES.id(),
                ORDER_COUNT.id());
        assertThat(result.execution()).isSameAs(execution);
    }

    @Test
    void columnOrderMayDifferFromTheRequest() {
        List<ResultColumn> reordered = List.of(COLUMNS.get(1), COLUMNS.get(0), COLUMNS.get(3), COLUMNS.get(2));
        assertThat(bind(execution(reordered, List.of())).definitions()).containsExactly(REGION.id(), ORDER_DATE.id(),
                ORDER_COUNT.id(), TOTAL_SALES.id());
    }

    @Test
    void anExecutionOfAnotherScopePlanOrFingerprintIsRejected() {
        ReportExecution base = execution(COLUMNS, List.of());
        mismatch(new ReportExecution(OTHER_SCOPE, base.planId(), base.executionId(), base.planFingerprint(),
                base.validity(), COLUMNS, List.of(), false, 1000, List.of()));
        mismatch(new ReportExecution(SCOPE, new PlanId(UUID.randomUUID()), base.executionId(),
                base.planFingerprint(), base.validity(), COLUMNS, List.of(), false, 1000, List.of()));
        mismatch(new ReportExecution(SCOPE, base.planId(), base.executionId(), "0".repeat(64), base.validity(),
                COLUMNS, List.of(), false, 1000, List.of()));
    }

    @Test
    void columnsMustBeExactlyTheRequestedDefinitions() {
        mismatch(execution(COLUMNS.subList(0, 3), List.of()));
        mismatch(execution(List.of(COLUMNS.get(0), COLUMNS.get(1), COLUMNS.get(2), COLUMNS.get(3),
                column("customer-age", ColumnRole.DIMENSION, ValueKind.NUMERIC)), List.of()));
        mismatch(execution(List.of(column("region", ColumnRole.METRIC, ValueKind.NUMERIC), COLUMNS.get(0),
                COLUMNS.get(3)), List.of()));
        mismatch(execution(List.of(column("unknown-key", ColumnRole.DIMENSION, ValueKind.TEXT), COLUMNS.get(1),
                COLUMNS.get(2), COLUMNS.get(3)), List.of()));
    }

    @Test
    void kindsMustFollowTheDefinitions() {
        mismatch(execution(List.of(COLUMNS.get(0), COLUMNS.get(1), column("total-sales", ColumnRole.METRIC,
                ValueKind.INTEGER), COLUMNS.get(3)), List.of()));
        mismatch(execution(List.of(COLUMNS.get(0), COLUMNS.get(1), COLUMNS.get(2), column("order-count",
                ColumnRole.METRIC, ValueKind.NUMERIC)), List.of()));
        mismatch(execution(List.of(column("order-date", ColumnRole.DIMENSION, ValueKind.TIMESTAMP),
                COLUMNS.get(1), COLUMNS.get(2), COLUMNS.get(3)), List.of()));
        mismatch(execution(List.of(COLUMNS.get(0), column("region", ColumnRole.DIMENSION, ValueKind.NUMERIC),
                COLUMNS.get(2), COLUMNS.get(3)), List.of()));
    }

    @Test
    void aDimensionWithUnknownValueTypeAcceptsTheKindReportsGives() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(ORDER_COUNT.id()), List.of(CHANNEL.id(),
                CUSTOMER_AGE.id()), List.of(), List.of(), List.of());
        ReportExecution execution = execution(List.of(column("channel", ColumnRole.DIMENSION, ValueKind.UUID),
                column("customer-age", ColumnRole.DIMENSION, ValueKind.NUMERIC),
                column("order-count", ColumnRole.METRIC, ValueKind.INTEGER)), List.of());
        assertThat(ReportResult.bind(execution, PLAN, request, CATALOG).definitions())
                .containsExactly(CHANNEL.id(), CUSTOMER_AGE.id(), ORDER_COUNT.id());
    }

    @Test
    void answersKeepTheirInvariants() {
        ReportResult result = bind(execution(COLUMNS, List.of()));
        assertThat(new QuestionAnswer.Reported(PLAN, result).result()).isSameAs(result);
        assertThatThrownBy(() -> new QuestionAnswer.Reported(new QuestionOutcome.PlanCreated(SCOPE,
                new PlanId(UUID.randomUUID()), PLAN.fingerprint(), PLAN.formatVersion()), result))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionAnswer.NotPlanned(PLAN)).isInstanceOf(IllegalArgumentException.class);
        QuestionOutcome.Rejected bySemantic = new QuestionOutcome.Rejected(QuestionOutcome.Rejected.Origin.SEMANTIC,
                List.of(QuestionOutcome.Rejected.Cause.of("NO_PATH")));
        assertThat(new QuestionAnswer.NotPlanned(bySemantic).outcome()).isEqualTo(bySemantic);
        assertThatThrownBy(() -> new QuestionAnswer.NotExecuted(PLAN, bySemantic))
                .isInstanceOf(IllegalArgumentException.class);
        QuestionOutcome.Rejected byReports = new QuestionOutcome.Rejected(QuestionOutcome.Rejected.Origin.REPORTS,
                List.of(QuestionOutcome.Rejected.Cause.of("PLAN_STALE")));
        assertThat(new QuestionAnswer.NotExecuted(PLAN, byReports).rejection()).isEqualTo(byReports);
    }
}
