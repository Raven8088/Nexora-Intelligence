package com.nexora.intelligence.domain.result;

import java.util.regex.Pattern;

/**
 * A warning attached to a successful execution, by code only (Reports' messages are not kept). Known codes:
 * {@value #PLAN_CURRENT_WITH_CHANGES} and {@value #RESULT_TRUNCATED}; others are kept as given.
 */
public record ExecutionWarning(String code) {

    public static final String PLAN_CURRENT_WITH_CHANGES = "PLAN_CURRENT_WITH_CHANGES";
    public static final String RESULT_TRUNCATED = "RESULT_TRUNCATED";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public ExecutionWarning {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("warning code must match " + CODE.pattern());
        }
    }
}
