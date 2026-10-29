package com.nexora.intelligence.domain.semantic;

import java.util.Objects;
import java.util.UUID;

/** Persistent id of a semantic definition (metric or dimension) in Nexora Semantic. */
public record DefinitionId(UUID value) {

    public DefinitionId {
        Objects.requireNonNull(value, "definitionId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
