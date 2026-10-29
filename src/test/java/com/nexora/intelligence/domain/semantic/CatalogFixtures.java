package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Small, realistic catalog for tests: sales of one data source. */
public final class CatalogFixtures {

    public static final SemanticScope SCOPE = new SemanticScope(new TenantId(UUID.randomUUID()),
            new DataSourceId(UUID.randomUUID()));
    public static final SemanticScope OTHER_SCOPE = new SemanticScope(SCOPE.tenantId(),
            new DataSourceId(UUID.randomUUID()));

    public static final CatalogMetric TOTAL_SALES = metric("total-sales", "Ventas totales", Aggregation.SUM);
    public static final CatalogMetric ORDER_COUNT = metric("order-count", "Número de pedidos", Aggregation.COUNT);
    public static final CatalogDimension REGION = dimension("region", "Región", DimensionType.CATEGORICAL,
            ValueType.TEXT, Set.of());
    public static final CatalogDimension ORDER_DATE = dimension("order-date", "Fecha de pedido",
            DimensionType.TEMPORAL, ValueType.DATE, EnumSet.of(TimeGranularity.MONTH, TimeGranularity.YEAR));
    public static final CatalogDimension PAID = dimension("paid", "Pagado", DimensionType.CATEGORICAL,
            ValueType.BOOLEAN, Set.of());
    public static final CatalogDimension CUSTOMER_AGE = dimension("customer-age", "Edad del cliente",
            DimensionType.CATEGORICAL, ValueType.NUMERIC, Set.of());
    public static final CatalogDimension CHANNEL = new CatalogDimension(id(), new DefinitionKey("channel"), "Canal",
            Optional.empty(), DimensionType.CATEGORICAL, Optional.empty(), Set.of());
    public static final CatalogRelationship ORDER_CUSTOMER = new CatalogRelationship(
            new RelationshipId(UUID.randomUUID()), "Pedido del cliente");

    public static final SemanticCatalog CATALOG = new SemanticCatalog(SCOPE, List.of(TOTAL_SALES, ORDER_COUNT),
            List.of(REGION, ORDER_DATE, PAID, CUSTOMER_AGE, CHANNEL), List.of(ORDER_CUSTOMER));

    private CatalogFixtures() {
    }

    public static DefinitionId id() {
        return new DefinitionId(UUID.randomUUID());
    }

    public static CatalogMetric metric(String key, String name, Aggregation aggregation) {
        return new CatalogMetric(id(), new DefinitionKey(key), name, Optional.of("Descripción de " + name),
                aggregation);
    }

    public static CatalogDimension dimension(String key, String name, DimensionType type, ValueType valueType,
                                             Set<TimeGranularity> granularities) {
        return new CatalogDimension(id(), new DefinitionKey(key), name, Optional.empty(), type,
                Optional.of(valueType), granularities);
    }
}
