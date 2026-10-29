package com.nexora.intelligence.infrastructure.semantic;

/** Gives tests outside the package the (package-private) Semantic configuration class. */
public final class SemanticConfigurationAccess {

    private SemanticConfigurationAccess() {
    }

    public static Class<?> type() {
        return SemanticConfiguration.class;
    }
}
