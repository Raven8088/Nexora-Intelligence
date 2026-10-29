package com.nexora.intelligence.domain.outcome;

import java.util.Objects;
import java.util.UUID;

/** Id of a logical plan persisted by Nexora Semantic. */
public record PlanId(UUID value) {

    public PlanId {
        Objects.requireNonNull(value, "planId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
