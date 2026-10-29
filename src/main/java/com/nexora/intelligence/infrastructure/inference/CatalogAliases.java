package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closed, per-request aliases for the definitions of one catalog: {@code m1..mN} for metrics and {@code d1..dN} for
 * dimensions, in catalog order. The model only ever sees these aliases; ids, keys of other catalogs and physical
 * names never reach it. Any alias outside this table is invalid output.
 */
final class CatalogAliases {

    private final Map<String, CatalogMetric> metrics = new LinkedHashMap<>();
    private final Map<String, CatalogDimension> dimensions = new LinkedHashMap<>();
    private final Map<DefinitionId, String> byId = new HashMap<>();

    CatalogAliases(SemanticCatalog catalog) {
        List<CatalogMetric> catalogMetrics = catalog.metrics();
        for (int i = 0; i < catalogMetrics.size(); i++) {
            String alias = "m" + (i + 1);
            metrics.put(alias, catalogMetrics.get(i));
            byId.put(catalogMetrics.get(i).id(), alias);
        }
        List<CatalogDimension> catalogDimensions = catalog.dimensions();
        for (int i = 0; i < catalogDimensions.size(); i++) {
            String alias = "d" + (i + 1);
            dimensions.put(alias, catalogDimensions.get(i));
            byId.put(catalogDimensions.get(i).id(), alias);
        }
    }

    Map<String, CatalogMetric> metrics() {
        return Collections.unmodifiableMap(metrics);
    }

    Map<String, CatalogDimension> dimensions() {
        return Collections.unmodifiableMap(dimensions);
    }

    List<String> metricAliases() {
        return List.copyOf(metrics.keySet());
    }

    List<String> dimensionAliases() {
        return List.copyOf(dimensions.keySet());
    }

    List<String> temporalAliases() {
        return dimensions.entrySet().stream().filter(e -> e.getValue().type() == DimensionType.TEMPORAL)
                .map(Map.Entry::getKey).toList();
    }

    /** Aliases a filter may target: non-temporal dimensions and metrics (periods go through {@code period}). */
    List<String> filterTargetAliases() {
        List<String> targets = new java.util.ArrayList<>(dimensions.entrySet().stream()
                .filter(e -> e.getValue().type() != DimensionType.TEMPORAL).map(Map.Entry::getKey).toList());
        targets.addAll(metrics.keySet());
        return List.copyOf(targets);
    }

    Optional<DefinitionId> metric(String alias) {
        return Optional.ofNullable(metrics.get(alias)).map(CatalogMetric::id);
    }

    Optional<DefinitionId> dimension(String alias) {
        return Optional.ofNullable(dimensions.get(alias)).map(CatalogDimension::id);
    }

    /** A metric or dimension alias. */
    Optional<DefinitionId> definition(String alias) {
        return metric(alias).or(() -> dimension(alias));
    }

    /** The alias of a definition of this catalog (for tests and diagnostics). */
    Optional<String> aliasOf(DefinitionId id) {
        return Optional.ofNullable(byId.get(id));
    }
}
