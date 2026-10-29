package com.nexora.intelligence.domain.request;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Why a structured request does not fit a catalog. The field is a path in the request ({@code metrics[0]},
 * {@code filters[2].values[0]}); no value of the request is ever included.
 */
public record RequestViolation(Code code, String field) {

    private static final Pattern FIELD = Pattern.compile("[a-zA-Z]+(\\[\\d{1,3}])?(\\.[a-zA-Z]+(\\[\\d{1,3}])?)*");

    public enum Code {
        SCOPE_MISMATCH,
        METRIC_NOT_FOUND,
        DIMENSION_NOT_FOUND,
        FILTER_TARGET_NOT_FOUND,
        METRIC_FILTER_NOT_REQUESTED,
        OPERATOR_INCOMPATIBLE,
        VALUE_INCOMPATIBLE,
        GRANULARITY_NOT_TEMPORAL,
        GRANULARITY_NOT_ALLOWED,
        GRANULARITY_REQUIRED,
        RELATIONSHIP_NOT_FOUND
    }

    public RequestViolation {
        Objects.requireNonNull(code, "code");
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("invalid violation field");
        }
    }
}
