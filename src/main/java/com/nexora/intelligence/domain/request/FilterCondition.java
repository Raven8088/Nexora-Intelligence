package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.DefinitionId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A filter on a dimension (applied before aggregation) or on one of the requested metrics (after aggregation),
 * identified only by its definition id. The number of values matches the operator: none for IS_NULL / IS_NOT_NULL,
 * exactly one for comparisons, 1 to {@value #MAX_IN_VALUES} distinct values for IN.
 */
public record FilterCondition(DefinitionId target, FilterOperator operator, List<FilterValue> values) {

    public static final int MAX_IN_VALUES = 100;

    public FilterCondition {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operator, "operator");
        values = values == null ? List.of() : List.copyOf(values);
        switch (operator.arity()) {
            case NONE -> {
                if (!values.isEmpty()) {
                    throw new IllegalArgumentException(operator + " takes no value");
                }
            }
            case ONE -> {
                if (values.size() != 1) {
                    throw new IllegalArgumentException(operator + " takes exactly one value");
                }
            }
            case MANY -> {
                if (values.isEmpty() || values.size() > MAX_IN_VALUES) {
                    throw new IllegalArgumentException("IN takes between 1 and " + MAX_IN_VALUES + " values");
                }
            }
        }
        Set<String> distinct = new HashSet<>();
        for (FilterValue value : values) {
            if (!distinct.add(value.canonical())) {
                throw new IllegalArgumentException("filter values must not be repeated");
            }
        }
    }

    public static FilterCondition of(DefinitionId target, FilterOperator operator, FilterValue... values) {
        return new FilterCondition(target, operator, List.of(values));
    }

    /** Canonical identity used to detect repeated filters. */
    String signature() {
        StringBuilder signature = new StringBuilder(target.toString()).append('|').append(operator);
        values.forEach(v -> signature.append('|').append(v.canonical()));
        return signature.toString();
    }
}
