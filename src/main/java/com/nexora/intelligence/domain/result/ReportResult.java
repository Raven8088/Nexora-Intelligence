package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.Aggregation;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A report result bound to the semantic definitions of the question: {@code definitions.get(i)} is the metric or
 * dimension column {@code i} comes from. Values are kept exactly as {@link ReportExecution} holds them.
 */
public record ReportResult(ReportExecution execution, List<DefinitionId> definitions) {

    /** Why an execution does not correspond to the plan and the request it should answer. */
    public static final class MismatchException extends IllegalArgumentException {
        public MismatchException(String message) {
            super(message);
        }
    }

    public ReportResult {
        Objects.requireNonNull(execution, "execution");
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        if (definitions.size() != execution.columns().size()) {
            throw new IllegalArgumentException("one definition per column is required");
        }
    }

    /**
     * Binds {@code execution} to the definitions of {@code request}. It must be the execution of {@code plan} (same
     * scope, plan id and fingerprint); its DIMENSION and METRIC columns must be exactly the requested dimensions and
     * metrics (by key, within the catalog); and each column must have the kind its definition implies: INTEGER for
     * COUNT, NUMERIC for SUM and AVG, DATE for TEMPORAL dimensions, the kind of the value type for categorical
     * dimensions (any kind when the value type is unknown).
     *
     * @throws MismatchException when any of these does not hold; nothing is presented then
     */
    public static ReportResult bind(ReportExecution execution, QuestionOutcome.PlanCreated plan,
                                    SemanticRequest request, SemanticCatalog catalog) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        if (!execution.scope().equals(plan.scope()) || !request.scope().equals(plan.scope())
                || !catalog.scope().equals(plan.scope())) {
            throw new MismatchException("the execution belongs to another scope");
        }
        if (!execution.planId().equals(plan.planId())) {
            throw new MismatchException("the execution is of another plan");
        }
        if (!execution.planFingerprint().equals(plan.fingerprint())) {
            throw new MismatchException("the executed plan has another fingerprint");
        }
        Map<DefinitionId, CatalogMetric> metrics = catalog.metricsById();
        Map<DefinitionId, CatalogDimension> dimensions = catalog.dimensionsById();
        List<DefinitionId> bound = new ArrayList<>();
        Set<DefinitionId> seen = new HashSet<>();
        for (ResultColumn column : execution.columns()) {
            DefinitionId id = switch (column.role()) {
                case METRIC -> metricColumn(column, request, metrics);
                case DIMENSION -> dimensionColumn(column, request, dimensions);
            };
            seen.add(id);
            bound.add(id);
        }
        if (!seen.containsAll(request.metrics()) || !seen.containsAll(request.dimensions())) {
            throw new MismatchException("a requested definition has no column in the result");
        }
        return new ReportResult(execution, bound);
    }

    private static DefinitionId metricColumn(ResultColumn column, SemanticRequest request,
                                             Map<DefinitionId, CatalogMetric> metrics) {
        for (DefinitionId id : request.metrics()) {
            CatalogMetric metric = metrics.get(id);
            if (metric != null && metric.key().equals(column.key())) {
                ValueKind expected = metric.aggregation() == Aggregation.COUNT ? ValueKind.INTEGER : ValueKind.NUMERIC;
                if (column.kind() != expected) {
                    throw new MismatchException("a metric column has an unexpected kind");
                }
                return id;
            }
        }
        throw new MismatchException("a metric column was not requested");
    }

    private static DefinitionId dimensionColumn(ResultColumn column, SemanticRequest request,
                                                Map<DefinitionId, CatalogDimension> dimensions) {
        for (DefinitionId id : request.dimensions()) {
            CatalogDimension dimension = dimensions.get(id);
            if (dimension != null && dimension.key().equals(column.key())) {
                Optional<ValueKind> expected = dimension.type() == DimensionType.TEMPORAL
                        ? Optional.of(ValueKind.DATE)
                        : dimension.valueType().map(t -> ValueKind.ofDimension(t)
                                .orElseThrow(() -> new MismatchException("a dimension of type OTHER was executed")));
                if (expected.isPresent() && column.kind() != expected.get()) {
                    throw new MismatchException("a dimension column has an unexpected kind");
                }
                return id;
            }
        }
        throw new MismatchException("a dimension column was not requested");
    }
}
