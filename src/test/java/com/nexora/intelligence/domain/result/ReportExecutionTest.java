package com.nexora.intelligence.domain.result;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nexora.intelligence.domain.result.ResultFixtures.PLAN;
import static com.nexora.intelligence.domain.result.ResultFixtures.column;
import static com.nexora.intelligence.domain.result.ResultFixtures.execution;
import static com.nexora.intelligence.domain.result.ResultFixtures.row;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExecutionTest {

    private static final List<ResultColumn> COLUMNS = List.of(column("region", ColumnRole.DIMENSION, ValueKind.TEXT),
            column("total-sales", ColumnRole.METRIC, ValueKind.NUMERIC),
            column("order-count", ColumnRole.METRIC, ValueKind.INTEGER));

    @Test
    void aConsistentExecutionKeepsItsValuesAndIsImmutable() {
        List<List<ResultValue>> rows = new ArrayList<>();
        rows.add(row(new ResultValue.Text("Lima"), new ResultValue.Decimal(new BigDecimal("10.50")),
                new ResultValue.Integer(BigInteger.TWO)));
        ReportExecution execution = execution(COLUMNS, rows);
        rows.clear();
        assertThat(execution.rowCount()).isEqualTo(1);
        assertThat(execution.rows().get(0).get(1)).isEqualTo(new ResultValue.Decimal(new BigDecimal("10.50")));
        assertThat(execution.indexOf(ColumnRole.METRIC, new com.nexora.intelligence.domain.semantic.DefinitionKey(
                "order-count"))).isEqualTo(2);
        assertThat(execution.indexOf(ColumnRole.DIMENSION, new com.nexora.intelligence.domain.semantic.DefinitionKey(
                "order-count"))).isEqualTo(-1);
    }

    @Test
    void rowsMustMatchTheColumns() {
        assertThatThrownBy(() -> execution(COLUMNS, List.of(row(new ResultValue.Text("Lima")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution(COLUMNS, List.of(row(new ResultValue.Text("Lima"),
                new ResultValue.Text("10.5"), new ResultValue.Integer(BigInteger.ONE)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(execution(COLUMNS, List.of(row(ResultValue.Null.INSTANCE, ResultValue.Null.INSTANCE,
                ResultValue.Null.INSTANCE))).rowCount()).isEqualTo(1);
    }

    @Test
    void keysAreUniqueWithinARoleAndThereIsAtLeastOneColumn() {
        assertThatThrownBy(() -> execution(List.of(column("region", ColumnRole.DIMENSION, ValueKind.TEXT),
                column("region", ColumnRole.DIMENSION, ValueKind.TEXT)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncationMustBeConsistentWithMaxRows() {
        List<List<ResultValue>> two = List.of(row(new ResultValue.Integer(BigInteger.ONE)),
                row(new ResultValue.Integer(BigInteger.TWO)));
        List<ResultColumn> count = List.of(column("order-count", ColumnRole.METRIC, ValueKind.INTEGER));
        assertThat(new ReportExecution(SCOPE, PLAN.planId(), UUID.randomUUID(), PLAN.fingerprint(),
                ExecutionValidity.CURRENT, count, two, true, 2, List.of()).truncated()).isTrue();
        assertThatThrownBy(() -> new ReportExecution(SCOPE, PLAN.planId(), UUID.randomUUID(), PLAN.fingerprint(),
                ExecutionValidity.CURRENT, count, two, true, 3, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportExecution(SCOPE, PLAN.planId(), UUID.randomUUID(), PLAN.fingerprint(),
                ExecutionValidity.CURRENT, count, two, false, 1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportExecution(SCOPE, PLAN.planId(), UUID.randomUUID(), PLAN.fingerprint(),
                ExecutionValidity.CURRENT, count, List.of(), false, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesFitOnlyTheirKind() {
        for (ValueKind kind : ValueKind.values()) {
            assertThat(ResultValue.Null.INSTANCE.fits(kind)).isTrue();
        }
        assertThat(new ResultValue.Decimal(BigDecimal.ONE).fits(ValueKind.INTEGER)).isFalse();
        assertThat(new ResultValue.Integer(BigInteger.ONE).fits(ValueKind.NUMERIC)).isFalse();
        assertThat(new ResultValue.Text("2026-01-01").fits(ValueKind.DATE)).isFalse();
    }

    @Test
    void warningCodesAreControlled() {
        assertThat(new ExecutionWarning("RESULT_TRUNCATED").code()).isEqualTo(ExecutionWarning.RESULT_TRUNCATED);
        assertThatThrownBy(() -> new ExecutionWarning("free text")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionWarning(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
