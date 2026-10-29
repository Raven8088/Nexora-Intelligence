package com.nexora.intelligence.domain.semantic;

import java.util.Objects;
import java.util.UUID;

/** Persistent id of an approved semantic relationship in Nexora Semantic. */
public record RelationshipId(UUID value) {

    public RelationshipId {
        Objects.requireNonNull(value, "relationshipId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
