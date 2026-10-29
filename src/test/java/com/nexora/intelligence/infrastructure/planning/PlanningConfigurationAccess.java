package com.nexora.intelligence.infrastructure.planning;

/** Gives tests outside the package the wiring classes of the planning flow (all package-private). */
public final class PlanningConfigurationAccess {

    private PlanningConfigurationAccess() {
    }

    public static Class<?>[] configurations() {
        return new Class<?>[] {PlanningConfiguration.class,
                com.nexora.intelligence.infrastructure.semantic.SemanticConfigurationAccess.type(),
                com.nexora.intelligence.infrastructure.reports.ReportsConfigurationAccess.type()};
    }
}
