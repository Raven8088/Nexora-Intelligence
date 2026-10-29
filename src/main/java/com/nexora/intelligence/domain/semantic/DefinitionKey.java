package com.nexora.intelligence.domain.semantic;

import java.util.regex.Pattern;

/** Logical key of a definition, with Semantic's format: lowercase letters, digits, '_' and '-', 2 to 63 chars. */
public record DefinitionKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9_-]{1,62}");

    public DefinitionKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("definition key must match " + FORMAT.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
