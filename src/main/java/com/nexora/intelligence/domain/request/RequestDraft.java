package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.DefinitionId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the interpretation proposes, with catalog ids but before any deterministic step: filter values exactly as
 * proposed, the period unresolved, no scope and no preferred relationships (the model never chooses them in v1).
 * It is turned into a {@link SemanticRequest} only by {@link RequestDraftResolver}.
 * <p>
 * Construction enforces the same structural limits and uniqueness rules as {@link SemanticRequest}; a draft that
 * breaks them is a defect of whoever produced it.
 */
public record RequestDraft(List<DefinitionId> metrics, List<DefinitionId> dimensions, List<FilterCondition> filters,
                           List<GranularityChoice> timeGranularities, Optional<PeriodSelection> period) {

    public RequestDraft {
        metrics = copy(metrics, "metrics", SemanticRequest.MAX_METRICS);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        dimensions = copy(dimensions, "dimensions", SemanticRequest.MAX_DIMENSIONS);
        filters = copy(filters, "filters", SemanticRequest.MAX_FILTERS);
        timeGranularities = copy(timeGranularities, "timeGranularities", SemanticRequest.MAX_DIMENSIONS);
        period = period == null ? Optional.empty() : period;

        Set<DefinitionId> selected = new HashSet<>();
        metrics.forEach(id -> unique(selected, id, "a definition is referenced more than once"));
        dimensions.forEach(id -> unique(selected, id, "a definition is referenced more than once"));
        Set<String> signatures = new HashSet<>();
        filters.forEach(f -> unique(signatures, f.signature(), "a filter is repeated"));
        Set<DefinitionId> withGranularity = new HashSet<>();
        for (GranularityChoice choice : timeGranularities) {
            if (!dimensions.contains(choice.dimension())) {
                throw new IllegalArgumentException("a time granularity must refer to a requested dimension");
            }
            unique(withGranularity, choice.dimension(), "a dimension has more than one time granularity");
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
        if (!seen.add(Objects.requireNonNull(value))) {
            throw new IllegalArgumentException(message);
        }
    }
}
