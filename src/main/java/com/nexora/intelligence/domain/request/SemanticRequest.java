package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.request.RequestViolation.Code;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.RelationshipId;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.SemanticScope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A structured request with the shape of Nexora Semantic's logical plan request: persistent ids of metrics,
 * dimensions and relationships, typed filters and time granularities. It holds no table, column or SQL text.
 * <p>
 * Construction enforces the structural rules Semantic also enforces (limits, no repeated references, filters or
 * granularities, granularities only for requested dimensions). {@link #violationsAgainst(SemanticCatalog)} checks the
 * references against an authorized catalog. Semantic remains the final authority: it re-validates everything and
 * alone decides routes, joins, fan-out and plan safety.
 *
 * @param metrics                1 to {@value #MAX_METRICS} metrics, in the order the result keeps
 * @param dimensions             up to {@value #MAX_DIMENSIONS} dimensions
 * @param filters                up to {@value #MAX_FILTERS} filters
 * @param timeGranularities      one choice per requested TEMPORAL dimension
 * @param preferredRelationships up to {@value #MAX_PREFERRED_RELATIONSHIPS} relationships Semantic should prefer
 */
public record SemanticRequest(SemanticScope scope, List<DefinitionId> metrics, List<DefinitionId> dimensions,
                              List<FilterCondition> filters, List<GranularityChoice> timeGranularities,
                              List<RelationshipId> preferredRelationships) {

    public static final int MAX_METRICS = 20;
    public static final int MAX_DIMENSIONS = 20;
    public static final int MAX_FILTERS = 50;
    public static final int MAX_PREFERRED_RELATIONSHIPS = 20;

    public SemanticRequest {
        Objects.requireNonNull(scope, "scope");
        metrics = copy(metrics, "metrics", MAX_METRICS);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        dimensions = copy(dimensions, "dimensions", MAX_DIMENSIONS);
        filters = copy(filters, "filters", MAX_FILTERS);
        timeGranularities = copy(timeGranularities, "timeGranularities", MAX_DIMENSIONS);
        preferredRelationships = copy(preferredRelationships, "preferredRelationships",
                MAX_PREFERRED_RELATIONSHIPS);

        Set<DefinitionId> selected = new HashSet<>();
        metrics.forEach(id -> unique(selected, id, "a definition is referenced more than once"));
        dimensions.forEach(id -> unique(selected, id, "a definition is referenced more than once"));
        Set<String> filterSignatures = new HashSet<>();
        filters.forEach(f -> unique(filterSignatures, f.signature(), "a filter is repeated"));
        Set<DefinitionId> withGranularity = new HashSet<>();
        for (GranularityChoice choice : timeGranularities) {
            if (!dimensions.contains(choice.dimension())) {
                throw new IllegalArgumentException("a time granularity must refer to a requested dimension");
            }
            unique(withGranularity, choice.dimension(), "a dimension has more than one time granularity");
        }
        Set<RelationshipId> relationships = new HashSet<>();
        preferredRelationships.forEach(id -> unique(relationships, id, "a relationship is referenced more than once"));
    }

    /**
     * Every reason this request does not fit {@code catalog}; empty when it does. All violations are reported, not
     * only the first.
     */
    public List<RequestViolation> violationsAgainst(SemanticCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        List<RequestViolation> violations = new ArrayList<>();
        if (!catalog.scope().equals(scope)) {
            violations.add(new RequestViolation(Code.SCOPE_MISMATCH, "scope"));
            return List.copyOf(violations);
        }
        Map<DefinitionId, CatalogMetric> catalogMetrics = catalog.metricsById();
        Map<DefinitionId, CatalogDimension> catalogDimensions = catalog.dimensionsById();

        for (int i = 0; i < metrics.size(); i++) {
            if (!catalogMetrics.containsKey(metrics.get(i))) {
                violations.add(new RequestViolation(Code.METRIC_NOT_FOUND, "metrics[" + i + "]"));
            }
        }
        for (int i = 0; i < dimensions.size(); i++) {
            CatalogDimension dimension = catalogDimensions.get(dimensions.get(i));
            if (dimension == null) {
                violations.add(new RequestViolation(Code.DIMENSION_NOT_FOUND, "dimensions[" + i + "]"));
            } else if (dimension.type() == DimensionType.TEMPORAL && timeGranularities.stream()
                    .noneMatch(g -> g.dimension().equals(dimension.id()))) {
                violations.add(new RequestViolation(Code.GRANULARITY_REQUIRED, "dimensions[" + i + "]"));
            }
        }
        for (int i = 0; i < filters.size(); i++) {
            checkFilter(filters.get(i), "filters[" + i + "]", catalogMetrics, catalogDimensions, violations);
        }
        for (int i = 0; i < timeGranularities.size(); i++) {
            GranularityChoice choice = timeGranularities.get(i);
            CatalogDimension dimension = catalogDimensions.get(choice.dimension());
            String field = "timeGranularities[" + i + "]";
            if (dimension == null) {
                continue; // already reported as DIMENSION_NOT_FOUND
            }
            if (dimension.type() != DimensionType.TEMPORAL) {
                violations.add(new RequestViolation(Code.GRANULARITY_NOT_TEMPORAL, field + ".dimension"));
            } else if (!dimension.allows(choice.granularity())) {
                violations.add(new RequestViolation(Code.GRANULARITY_NOT_ALLOWED, field + ".granularity"));
            }
        }
        for (int i = 0; i < preferredRelationships.size(); i++) {
            if (catalog.relationship(preferredRelationships.get(i)).isEmpty()) {
                violations.add(new RequestViolation(Code.RELATIONSHIP_NOT_FOUND, "preferredRelationships[" + i + "]"));
            }
        }
        return List.copyOf(violations);
    }

    private void checkFilter(FilterCondition filter, String field, Map<DefinitionId, CatalogMetric> catalogMetrics,
                             Map<DefinitionId, CatalogDimension> catalogDimensions,
                             List<RequestViolation> violations) {
        CatalogDimension dimension = catalogDimensions.get(filter.target());
        if (dimension != null) {
            if (dimension.valueType().isEmpty()) {
                return; // the value category is unknown here: Semantic checks operator and values
            }
            if (!filter.operator().appliesTo(dimension.valueType().get())) {
                violations.add(new RequestViolation(Code.OPERATOR_INCOMPATIBLE, field + ".operator"));
                return;
            }
            checkValues(filter, field, v -> v.fits(dimension.valueType().get()), violations);
            return;
        }
        if (catalogMetrics.containsKey(filter.target())) {
            if (!metrics.contains(filter.target())) {
                violations.add(new RequestViolation(Code.METRIC_FILTER_NOT_REQUESTED, field + ".target"));
            } else if (!filter.operator().appliesToMetrics()) {
                violations.add(new RequestViolation(Code.OPERATOR_INCOMPATIBLE, field + ".operator"));
            } else {
                checkValues(filter, field, v -> v instanceof FilterValue.Number, violations);
            }
            return;
        }
        violations.add(new RequestViolation(Code.FILTER_TARGET_NOT_FOUND, field + ".target"));
    }

    private static void checkValues(FilterCondition filter, String field,
                                    java.util.function.Predicate<FilterValue> fits,
                                    List<RequestViolation> violations) {
        for (int j = 0; j < filter.values().size(); j++) {
            if (!fits.test(filter.values().get(j))) {
                violations.add(new RequestViolation(Code.VALUE_INCOMPATIBLE, field + ".values[" + j + "]"));
            }
        }
    }

    private static <T> List<T> copy(List<T> items, String field, int max) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > max) {
            throw new IllegalArgumentException(field + " must have at most " + max + " elements");
        }
        return List.copyOf(items);
    }

    private static <T> void unique(Set<T> seen, T value, String message) {
        if (!seen.add(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
