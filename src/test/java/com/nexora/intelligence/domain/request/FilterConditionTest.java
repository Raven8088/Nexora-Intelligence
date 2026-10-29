package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterConditionTest {

    private static final DefinitionId TARGET = new DefinitionId(UUID.randomUUID());

    @ParameterizedTest
    @EnumSource(value = FilterOperator.class, names = {"IS_NULL", "IS_NOT_NULL"})
    void nullChecksTakeNoValue(FilterOperator operator) {
        assertThat(FilterCondition.of(TARGET, operator).values()).isEmpty();
        assertThat(new FilterCondition(TARGET, operator, null).values()).isEmpty();
        assertThatThrownBy(() -> FilterCondition.of(TARGET, operator, FilterValue.text("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = FilterOperator.class, names = {"EQ", "NE", "GT", "GTE", "LT", "LTE"})
    void comparisonsTakeExactlyOneValue(FilterOperator operator) {
        assertThat(FilterCondition.of(TARGET, operator, FilterValue.number(BigDecimal.TEN)).values()).hasSize(1);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, operator)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, operator, FilterValue.number(BigDecimal.ONE),
                FilterValue.number(BigDecimal.TEN))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inTakesOneToOneHundredDistinctValues() {
        assertThat(new FilterCondition(TARGET, FilterOperator.IN, texts(100)).values()).hasSize(100);
        assertThatThrownBy(() -> new FilterCondition(TARGET, FilterOperator.IN, texts(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, FilterOperator.IN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, FilterOperator.IN, FilterValue.text("Lima"),
                FilterValue.text("Lima"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, FilterOperator.IN,
                FilterValue.number(new BigDecimal("1.0")), FilterValue.number(new BigDecimal("1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FilterCondition.of(TARGET, FilterOperator.IN, FilterValue.text("1"),
                FilterValue.number(BigDecimal.ONE)).values()).hasSize(2);
    }

    @Test
    void targetAndOperatorAreRequired() {
        assertThatThrownBy(() -> FilterCondition.of(null, FilterOperator.IS_NULL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FilterCondition.of(TARGET, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void textValuesAreDataWithinLimits() {
        assertThat(FilterValue.text(" Lima ")).isEqualTo(new FilterValue.Text(" Lima "));
        assertThat(FilterValue.text("x' OR '1'='1")).isInstanceOf(FilterValue.Text.class);
        assertThat(FilterValue.text("x".repeat(1000))).isInstanceOf(FilterValue.Text.class);
        for (String invalid : new String[] {null, "", "x".repeat(1001), "Li\u0000ma", "Li\nma"}) {
            assertThatThrownBy(() -> FilterValue.text(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> FilterValue.number(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void conditionsAreImmutable() {
        List<FilterValue> values = new ArrayList<>(List.of(FilterValue.text("Lima")));
        FilterCondition condition = new FilterCondition(TARGET, FilterOperator.IN, values);
        values.add(FilterValue.text("Cusco"));

        assertThat(condition.values()).containsExactly(FilterValue.text("Lima"));
        assertThatThrownBy(() -> condition.values().add(FilterValue.text("Cusco")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Same table as Semantic's SemanticResolver.operatorsFor. */
    @ParameterizedTest
    @CsvSource({
            "NUMERIC,      true,  true,  true",
            "TEXT,         true,  true,  false",
            "BOOLEAN,      true,  false, false",
            "DATE,         true,  true,  true",
            "TIMESTAMP,    true,  true,  true",
            "TIMESTAMP_TZ, true,  true,  true",
            "TIME,         true,  true,  true",
            "UUID,         true,  true,  false",
            "OTHER,        false, false, false"})
    void operatorsByValueTypeMirrorSemantic(ValueType type, boolean equality, boolean in, boolean ordering) {
        assertThat(FilterOperator.IS_NULL.appliesTo(type)).isTrue();
        assertThat(FilterOperator.IS_NOT_NULL.appliesTo(type)).isTrue();
        assertThat(FilterOperator.EQ.appliesTo(type)).isEqualTo(equality);
        assertThat(FilterOperator.NE.appliesTo(type)).isEqualTo(equality);
        assertThat(FilterOperator.IN.appliesTo(type)).isEqualTo(in);
        for (FilterOperator operator : List.of(FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT,
                FilterOperator.LTE)) {
            assertThat(operator.appliesTo(type)).isEqualTo(ordering);
        }
    }

    @Test
    void metricFiltersOnlyCompare() {
        assertThat(List.of(FilterOperator.values())).filteredOn(FilterOperator::appliesToMetrics)
                .containsExactly(FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GTE,
                        FilterOperator.LT, FilterOperator.LTE);
    }

    @Test
    void valuesFitTheirValueTypes() {
        FilterValue text = FilterValue.text("2025-01-01");
        FilterValue number = FilterValue.number(BigDecimal.ONE);
        FilterValue bool = FilterValue.bool(true);

        assertThat(List.of(ValueType.values())).filteredOn(text::fits).containsExactly(ValueType.TEXT,
                ValueType.DATE, ValueType.TIMESTAMP, ValueType.TIMESTAMP_TZ, ValueType.TIME, ValueType.UUID);
        assertThat(List.of(ValueType.values())).filteredOn(number::fits).containsExactly(ValueType.NUMERIC);
        assertThat(List.of(ValueType.values())).filteredOn(bool::fits).containsExactly(ValueType.BOOLEAN);
    }

    private static List<FilterValue> texts(int count) {
        return IntStream.range(0, count).mapToObj(i -> FilterValue.text("v" + i)).toList();
    }
}
