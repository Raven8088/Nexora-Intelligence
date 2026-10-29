package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.request.RequestViolation.Code;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.RelationshipId;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CHANNEL;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CUSTOMER_AGE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_CUSTOMER;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.OTHER_SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.PAID;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticRequestTest {

    // ---- structure ---------------------------------------------------------------------------------------------

    @Test
    void aCompleteRequestFitsTheCatalog() {
        SemanticRequest request = new SemanticRequest(SCOPE,
                List.of(TOTAL_SALES.id(), ORDER_COUNT.id()),
                List.of(REGION.id(), ORDER_DATE.id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("Lima"),
                                FilterValue.text("Cusco")),
                        FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2025-01-01")),
                        FilterCondition.of(PAID.id(), FilterOperator.EQ, FilterValue.bool(true)),
                        FilterCondition.of(CHANNEL.id(), FilterOperator.IS_NOT_NULL),
                        FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT,
                                FilterValue.number(new BigDecimal("1000")))),
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)),
                List.of(ORDER_CUSTOMER.id()));

        assertThat(request.violationsAgainst(CATALOG)).isEmpty();
    }

    @Test
    void onlyMetricsAreRequired() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()), null, null, null, null);

        assertThat(request.dimensions()).isEmpty();
        assertThat(request.filters()).isEmpty();
        assertThat(request.timeGranularities()).isEmpty();
        assertThat(request.preferredRelationships()).isEmpty();
        assertThat(request.violationsAgainst(CATALOG)).isEmpty();
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(null, List.of(TOTAL_SALES.id()), null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void limitsMatchSemantic() {
        assertThat(new SemanticRequest(SCOPE, ids(20), ids(20), filters(50), null, relationships(20))
                .metrics()).hasSize(20);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, ids(21), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, ids(1), ids(21), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, ids(1), null, filters(51), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, ids(1), null, null, null, relationships(21)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesFiltersAndGranularitiesAreNotRepeated() {
        DefinitionId sales = TOTAL_SALES.id();
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales, sales), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales), List.of(REGION.id(), REGION.id()),
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales), List.of(sales), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        FilterCondition lima = FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Lima"));
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales), null, List.of(lima,
                FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Lima"))), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales), List.of(ORDER_DATE.id()), null,
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH),
                        new GranularityChoice(ORDER_DATE.id(), TimeGranularity.YEAR)), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(sales), null, null, null,
                List.of(ORDER_CUSTOMER.id(), ORDER_CUSTOMER.id()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void granularitiesOnlyForRequestedDimensions() {
        assertThatThrownBy(() -> new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()), null, null,
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GranularityChoice(ORDER_DATE.id(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GranularityChoice(null, TimeGranularity.DAY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requestsAreImmutable() {
        List<DefinitionId> metrics = new ArrayList<>(List.of(TOTAL_SALES.id()));
        SemanticRequest request = new SemanticRequest(SCOPE, metrics, null, null, null, null);
        metrics.add(ORDER_COUNT.id());

        assertThat(request.metrics()).containsExactly(TOTAL_SALES.id());
        assertThatThrownBy(() -> request.metrics().add(ORDER_COUNT.id()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.filters().add(FilterCondition.of(REGION.id(), FilterOperator.IS_NULL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- references against the catalog ------------------------------------------------------------------------

    @Test
    void aRequestForAnotherScopeIsNotCheckedFurther() {
        SemanticRequest request = new SemanticRequest(OTHER_SCOPE, List.of(id()), null, null, null, null);

        assertThat(request.violationsAgainst(CATALOG))
                .containsExactly(new RequestViolation(Code.SCOPE_MISMATCH, "scope"));
    }

    @Test
    void unknownReferencesAreAllReported() {
        SemanticRequest request = new SemanticRequest(SCOPE,
                List.of(TOTAL_SALES.id(), id(), REGION.id()),
                List.of(id(), ORDER_COUNT.id()),
                List.of(FilterCondition.of(id(), FilterOperator.IS_NULL)),
                null,
                List.of(new RelationshipId(UUID.randomUUID())));

        assertThat(request.violationsAgainst(CATALOG)).containsExactly(
                new RequestViolation(Code.METRIC_NOT_FOUND, "metrics[1]"),
                new RequestViolation(Code.METRIC_NOT_FOUND, "metrics[2]"),
                new RequestViolation(Code.DIMENSION_NOT_FOUND, "dimensions[0]"),
                new RequestViolation(Code.DIMENSION_NOT_FOUND, "dimensions[1]"),
                new RequestViolation(Code.FILTER_TARGET_NOT_FOUND, "filters[0].target"),
                new RequestViolation(Code.RELATIONSHIP_NOT_FOUND, "preferredRelationships[0]"));
    }

    @Test
    void metricsCanOnlyBeFilteredWhenRequestedWithComparisonsAndNumbers() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()), null, List.of(
                FilterCondition.of(ORDER_COUNT.id(), FilterOperator.GT, FilterValue.number(BigDecimal.TEN)),
                FilterCondition.of(TOTAL_SALES.id(), FilterOperator.IN, FilterValue.number(BigDecimal.ONE)),
                FilterCondition.of(TOTAL_SALES.id(), FilterOperator.IS_NULL),
                FilterCondition.of(TOTAL_SALES.id(), FilterOperator.LT, FilterValue.text("100"))),
                null, null);

        assertThat(request.violationsAgainst(CATALOG)).containsExactly(
                new RequestViolation(Code.METRIC_FILTER_NOT_REQUESTED, "filters[0].target"),
                new RequestViolation(Code.OPERATOR_INCOMPATIBLE, "filters[1].operator"),
                new RequestViolation(Code.OPERATOR_INCOMPATIBLE, "filters[2].operator"),
                new RequestViolation(Code.VALUE_INCOMPATIBLE, "filters[3].values[0]"));
    }

    @Test
    void operatorsAndValuesMustFitTheDimensionValueType() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()), null, List.of(
                FilterCondition.of(REGION.id(), FilterOperator.GT, FilterValue.text("Lima")),
                FilterCondition.of(PAID.id(), FilterOperator.IN, FilterValue.bool(true)),
                FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.number(BigDecimal.ONE)),
                FilterCondition.of(CUSTOMER_AGE.id(), FilterOperator.IN, FilterValue.number(BigDecimal.TEN),
                        FilterValue.text("veinte")),
                FilterCondition.of(PAID.id(), FilterOperator.EQ, FilterValue.text("true")),
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.LT, FilterValue.number(new BigDecimal(2025)))),
                null, null);

        assertThat(request.violationsAgainst(CATALOG)).containsExactly(
                new RequestViolation(Code.OPERATOR_INCOMPATIBLE, "filters[0].operator"),
                new RequestViolation(Code.OPERATOR_INCOMPATIBLE, "filters[1].operator"),
                new RequestViolation(Code.VALUE_INCOMPATIBLE, "filters[2].values[0]"),
                new RequestViolation(Code.VALUE_INCOMPATIBLE, "filters[3].values[1]"),
                new RequestViolation(Code.VALUE_INCOMPATIBLE, "filters[4].values[0]"),
                new RequestViolation(Code.VALUE_INCOMPATIBLE, "filters[5].values[0]"));
    }

    @Test
    void withoutAKnownValueTypeOperatorChecksAreLeftToSemantic() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()), null, List.of(
                FilterCondition.of(CHANNEL.id(), FilterOperator.GT, FilterValue.bool(true))), null, null);

        assertThat(request.violationsAgainst(CATALOG)).isEmpty();
    }

    @Test
    void granularitiesFollowTheCatalog() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()),
                List.of(ORDER_DATE.id(), REGION.id()), null,
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.DAY),
                        new GranularityChoice(REGION.id(), TimeGranularity.YEAR)), null);

        assertThat(request.violationsAgainst(CATALOG)).containsExactly(
                new RequestViolation(Code.GRANULARITY_NOT_ALLOWED, "timeGranularities[0].granularity"),
                new RequestViolation(Code.GRANULARITY_NOT_TEMPORAL, "timeGranularities[1].dimension"));
    }

    @Test
    void temporalDimensionsNeedAGranularity() {
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(TOTAL_SALES.id()),
                List.of(REGION.id(), ORDER_DATE.id()), null, null, null);

        assertThat(request.violationsAgainst(CATALOG))
                .containsExactly(new RequestViolation(Code.GRANULARITY_REQUIRED, "dimensions[1]"));
    }

    @Test
    void violationsNeverCarryRequestValues() {
        assertThatThrownBy(() -> new RequestViolation(Code.FILTER_TARGET_NOT_FOUND, "filters[0]; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestViolation(null, "metrics[0]")).isInstanceOf(NullPointerException.class);
    }

    private static List<DefinitionId> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> id()).toList();
    }

    private static List<FilterCondition> filters(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("r" + i)))
                .toList();
    }

    private static List<RelationshipId> relationships(int count) {
        return IntStream.range(0, count).mapToObj(i -> new RelationshipId(UUID.randomUUID())).toList();
    }
}
