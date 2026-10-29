package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.SemanticScope;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The authorized semantic context of one data source: its APPROVED and usable metrics, dimensions and
 * relationships, with logical information only. It is what the interpretation may choose from; nothing outside it
 * can be referenced.
 * <ul>
 *   <li>At most {@value #MAX_METRICS} metrics, {@value #MAX_DIMENSIONS} dimensions and {@value #MAX_RELATIONSHIPS}
 *       relationships. A larger catalog is rejected with {@link CatalogLimitExceededException}, never truncated.</li>
 *   <li>Definition ids are unique across metrics and dimensions; keys are unique within each kind (as in Semantic);
 *       relationship ids are unique.</li>
 *   <li>Immutable; the order of each list is kept.</li>
 * </ul>
 */
public record SemanticCatalog(SemanticScope scope, List<CatalogMetric> metrics, List<CatalogDimension> dimensions,
                              List<CatalogRelationship> relationships) {

    public static final int MAX_METRICS = 200;
    public static final int MAX_DIMENSIONS = 200;
    public static final int MAX_RELATIONSHIPS = 200;
    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    public SemanticCatalog {
        Objects.requireNonNull(scope, "scope");
        metrics = copy(metrics, "metrics", MAX_METRICS);
        dimensions = copy(dimensions, "dimensions", MAX_DIMENSIONS);
        relationships = copy(relationships, "relationships", MAX_RELATIONSHIPS);
        Set<DefinitionId> ids = new HashSet<>();
        metrics.forEach(m -> unique(ids, m.id(), "definition id"));
        dimensions.forEach(d -> unique(ids, d.id(), "definition id"));
        Set<DefinitionKey> metricKeys = new HashSet<>();
        metrics.forEach(m -> unique(metricKeys, m.key(), "metric key"));
        Set<DefinitionKey> dimensionKeys = new HashSet<>();
        dimensions.forEach(d -> unique(dimensionKeys, d.key(), "dimension key"));
        Set<RelationshipId> relationshipIds = new HashSet<>();
        relationships.forEach(r -> unique(relationshipIds, r.id(), "relationship id"));
    }

    public Optional<CatalogMetric> metric(DefinitionId id) {
        return metrics.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    public Optional<CatalogDimension> dimension(DefinitionId id) {
        return dimensions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public Optional<CatalogRelationship> relationship(RelationshipId id) {
        return relationships.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** Metrics by id, in catalog order. */
    public Map<DefinitionId, CatalogMetric> metricsById() {
        return index(metrics, CatalogMetric::id);
    }

    /** Dimensions by id, in catalog order. */
    public Map<DefinitionId, CatalogDimension> dimensionsById() {
        return index(dimensions, CatalogDimension::id);
    }

    private static <T> List<T> copy(List<T> items, String part, int limit) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > limit) {
            throw new CatalogLimitExceededException(part, limit);
        }
        return List.copyOf(items);
    }

    private static <T> void unique(Set<T> seen, T value, String what) {
        if (!seen.add(value)) {
            throw new IllegalArgumentException("duplicate " + what + " in the catalog");
        }
    }

    private static <K, T> Map<K, T> index(List<T> items, Function<T, K> key) {
        Map<K, T> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(key.apply(item), item));
        return java.util.Collections.unmodifiableMap(map);
    }
}
