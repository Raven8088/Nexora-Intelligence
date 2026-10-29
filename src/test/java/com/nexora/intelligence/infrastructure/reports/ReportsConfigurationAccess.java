package com.nexora.intelligence.infrastructure.reports;

/** Gives tests outside the package the (package-private) Reports configuration class. */
public final class ReportsConfigurationAccess {

    private ReportsConfigurationAccess() {
    }

    public static Class<?> type() {
        return ReportsConfiguration.class;
    }
}
