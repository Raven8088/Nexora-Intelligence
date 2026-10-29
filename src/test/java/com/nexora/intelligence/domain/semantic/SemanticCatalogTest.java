package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_CUSTOMER;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.dimension;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.id;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.metric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticCatalogTest {

    // ---- catalog -----------------------------------------------------------------------------------------------

    @Test
    void aValidCatalogKeepsItsDefinitionsInOrderAndFindsThemById() {
        assertThat(CATALOG.scope()).isEqualTo(SCOPE);
        assertThat(CATALOG.metrics()).containsExactly(TOTAL_SALES, ORDER_COUNT);
        assertThat(CATALOG.metric(TOTAL_SALES.id())).contains(TOTAL_SALES);
        assertThat(CATALOG.dimension(ORDER_DATE.id())).contains(ORDER_DATE);
        assertThat(CATALOG.relationship(ORDER_CUSTOMER.id())).contains(ORDER_CUSTOMER);
        assertThat(CATALOG.metric(REGION.id())).isEmpty();
        assertThat(CATALOG.dimension(TOTAL_SALES.id())).isEmpty();
        assertThat(CATALOG.metric(id())).isEmpty();
        assertThat(CATALOG.metricsById().keySet()).containsExactly(TOTAL_SALES.id(), ORDER_COUNT.id());
    }

    @Test
    void anEmptyCatalogIsValid() {
        SemanticCatalog empty = new SemanticCatalog(SCOPE, null, List.of(), null);

        assertThat(empty.metrics()).isEmpty();
        assertThat(empty.dimensions()).isEmpty();
        assertThat(empty.relationships()).isEmpty();
    }

    @Test
    void theScopeIsRequired() {
        assertThatThrownBy(() -> new SemanticCatalog(null, List.of(), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SemanticScope(null, new DataSourceId(UUID.randomUUID())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SemanticScope(new TenantId(UUID.randomUUID()), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void catalogsUpToTheLimitsAreAccepted() {
        SemanticCatalog full = new SemanticCatalog(SCOPE, metrics(200), dimensions(200), relationships(200));

        assertThat(full.metrics()).hasSize(200);
        assertThat(full.dimensions()).hasSize(200);
        assertThat(full.relationships()).hasSize(200);
    }

    @Test
    void largerCatalogsAreRejectedExplicitlyAndNeverTruncated() {
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, metrics(201), List.of(), List.of()))
                .isInstanceOfSatisfying(CatalogLimitExceededException.class, e -> {
                    assertThat(e.part()).isEqualTo("metrics");
                    assertThat(e.limit()).isEqualTo(200);
                });
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(), dimensions(201), List.of()))
                .isInstanceOfSatisfying(CatalogLimitExceededException.class,
                        e -> assertThat(e.part()).isEqualTo("dimensions"));
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(), List.of(), relationships(201)))
                .isInstanceOfSatisfying(CatalogLimitExceededException.class,
                        e -> assertThat(e.part()).isEqualTo("relationships"));
    }

    @Test
    void idsMustBeUniqueAcrossMetricsAndDimensions() {
        CatalogMetric metric = metric("sales", "Ventas", Aggregation.SUM);
        CatalogDimension sameId = new CatalogDimension(metric.id(), new DefinitionKey("region"), "Región",
                Optional.empty(), DimensionType.CATEGORICAL, Optional.empty(), Set.of());

        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(metric, metric), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(metric), List.of(sameId), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(), List.of(),
                List.of(ORDER_CUSTOMER, new CatalogRelationship(ORDER_CUSTOMER.id(), "Otra"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreUniqueWithinEachKindAsInSemantic() {
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(metric("sales", "Ventas", Aggregation.SUM),
                metric("sales", "Ventas 2", Aggregation.AVG)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, List.of(), List.of(
                dimension("region", "Región", DimensionType.CATEGORICAL, ValueType.TEXT, Set.of()),
                dimension("region", "Zona", DimensionType.CATEGORICAL, ValueType.TEXT, Set.of())), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        SemanticCatalog sameKeyAcrossKinds = new SemanticCatalog(SCOPE,
                List.of(metric("region", "Regiones atendidas", Aggregation.COUNT)),
                List.of(dimension("region", "Región", DimensionType.CATEGORICAL, ValueType.TEXT, Set.of())),
                List.of());
        assertThat(sameKeyAcrossKinds.metrics()).hasSize(1);
    }

    @Test
    void catalogsAreImmutable() {
        List<CatalogMetric> source = new ArrayList<>(List.of(TOTAL_SALES));
        SemanticCatalog catalog = new SemanticCatalog(SCOPE, source, List.of(REGION), List.of());
        source.add(ORDER_COUNT);

        assertThat(catalog.metrics()).containsExactly(TOTAL_SALES);
        assertThatThrownBy(() -> catalog.metrics().add(ORDER_COUNT)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.dimensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.metricsById().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ORDER_DATE.granularities().add(TimeGranularity.DAY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullEntriesAreRejected() {
        List<CatalogMetric> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> new SemanticCatalog(SCOPE, withNull, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- definitions -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ab", "total-sales", "order_count", "a1", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void validKeys(String key) {
        assertThat(new DefinitionKey(key).value()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "Total", "total sales", "-sales", "_sales", "ventas.total", "sales;drop",
            "ñandú"})
    void invalidKeys(String key) {
        assertThatThrownBy(() -> new DefinitionKey(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysOfMaximumLengthOnly() {
        assertThatThrownBy(() -> new DefinitionKey("x".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreRequired() {
        assertThatThrownBy(() -> new DefinitionId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelationshipId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CatalogMetric(null, new DefinitionKey("sales"), "Ventas", Optional.empty(),
                Aggregation.SUM)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CatalogMetric(id(), new DefinitionKey("sales"), "Ventas", Optional.empty(),
                null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void namesAndDescriptionsAreValidated() {
        CatalogMetric metric = new CatalogMetric(id(), new DefinitionKey("sales"), "  Ventas  ",
                Optional.of("  Importe neto\nsin impuestos  "), Aggregation.SUM);
        assertThat(metric.name()).isEqualTo("Ventas");
        assertThat(metric.description()).contains("Importe neto\nsin impuestos");
        assertThat(new CatalogMetric(id(), new DefinitionKey("sales"), "Ventas", Optional.of("  "), Aggregation.SUM)
                .description()).isEmpty();
        assertThat(new CatalogMetric(id(), new DefinitionKey("sales"), "Ventas", null, Aggregation.SUM)
                .description()).isEmpty();

        for (String invalid : new String[] {null, "", "  ", "x".repeat(201), "Ven\ntas", "Ven\u0000tas"}) {
            assertThatThrownBy(() -> new CatalogMetric(id(), new DefinitionKey("sales"), invalid, Optional.empty(),
                    Aggregation.SUM)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CatalogRelationship(new RelationshipId(UUID.randomUUID()), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new CatalogMetric(id(), new DefinitionKey("sales"), "Ventas",
                Optional.of("x".repeat(2001)), Aggregation.SUM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogMetric(id(), new DefinitionKey("sales"), "Ventas",
                Optional.of("a\u0007b"), Aggregation.SUM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporalDimensionsNeedGranularitiesAndATemporalValueType() {
        assertThatThrownBy(() -> dimension("order-date", "Fecha", DimensionType.TEMPORAL, ValueType.DATE, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dimension("order-date", "Fecha", DimensionType.TEMPORAL, ValueType.TEXT,
                EnumSet.of(TimeGranularity.DAY))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dimension("order-date", "Fecha", DimensionType.TEMPORAL, ValueType.TIME,
                EnumSet.of(TimeGranularity.DAY))).isInstanceOf(IllegalArgumentException.class);
        for (ValueType temporal : List.of(ValueType.DATE, ValueType.TIMESTAMP, ValueType.TIMESTAMP_TZ)) {
            assertThat(dimension("order-date", "Fecha", DimensionType.TEMPORAL, temporal,
                    EnumSet.of(TimeGranularity.DAY)).allows(TimeGranularity.DAY)).isTrue();
        }
        CatalogDimension unknownType = new CatalogDimension(id(), new DefinitionKey("order-date"), "Fecha",
                Optional.empty(), DimensionType.TEMPORAL, null, EnumSet.of(TimeGranularity.MONTH));
        assertThat(unknownType.valueType()).isEmpty();
        assertThat(unknownType.allows(TimeGranularity.MONTH)).isTrue();
        assertThat(unknownType.allows(TimeGranularity.DAY)).isFalse();
    }

    @Test
    void categoricalDimensionsHaveNoGranularities() {
        assertThatThrownBy(() -> dimension("region", "Región", DimensionType.CATEGORICAL, ValueType.TEXT,
                EnumSet.of(TimeGranularity.YEAR))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CatalogDimension(id(), new DefinitionKey("region"), "Región", Optional.empty(),
                DimensionType.CATEGORICAL, Optional.empty(), null).granularities()).isEmpty();
    }

    private static List<CatalogMetric> metrics(int count) {
        return IntStream.range(0, count).mapToObj(i -> metric("m" + i, "Métrica " + i, Aggregation.SUM)).toList();
    }

    private static List<CatalogDimension> dimensions(int count) {
        return IntStream.range(0, count).mapToObj(i -> dimension("d" + i, "Dimensión " + i,
                DimensionType.CATEGORICAL, ValueType.TEXT, Set.of())).toList();
    }

    private static List<CatalogRelationship> relationships(int count) {
        return IntStream.range(0, count).mapToObj(i -> new CatalogRelationship(new RelationshipId(UUID.randomUUID()),
                "Relación " + i)).toList();
    }

    @Test
    void aTimeZoneOnlyAppliesToTemporalDimensionsOverTimestampsWithTimeZone() {
        java.time.ZoneId lima = java.time.ZoneId.of("America/Lima");
        CatalogDimension paidAt = new CatalogDimension(id(), new DefinitionKey("paid-at"), "Fecha de pago",
                Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.TIMESTAMP_TZ),
                EnumSet.of(TimeGranularity.DAY), Optional.of(lima));
        assertThat(paidAt.timeZone()).contains(lima);
        assertThat(new CatalogDimension(id(), new DefinitionKey("paid-at"), "Fecha de pago", Optional.empty(),
                DimensionType.TEMPORAL, Optional.empty(), EnumSet.of(TimeGranularity.DAY), Optional.of(lima))
                .timeZone()).contains(lima);
        assertThat(new CatalogDimension(id(), new DefinitionKey("paid-at"), "Fecha de pago", Optional.empty(),
                DimensionType.TEMPORAL, Optional.of(ValueType.DATE), EnumSet.of(TimeGranularity.DAY), null)
                .timeZone()).isEmpty();
        assertThat(ORDER_DATE.timeZone()).isEmpty();

        assertThatThrownBy(() -> new CatalogDimension(id(), new DefinitionKey("order-date"), "Fecha",
                Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.DATE),
                EnumSet.of(TimeGranularity.DAY), Optional.of(lima))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogDimension(id(), new DefinitionKey("region"), "Región",
                Optional.empty(), DimensionType.CATEGORICAL, Optional.of(ValueType.TEXT), Set.of(),
                Optional.of(lima))).isInstanceOf(IllegalArgumentException.class);
    }
}
