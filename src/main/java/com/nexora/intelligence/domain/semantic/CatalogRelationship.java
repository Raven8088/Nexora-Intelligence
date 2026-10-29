package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.TextRules;

import java.util.Objects;

/** An APPROVED and usable relationship, known only by its id and logical name (no tables, columns or joins). */
public record CatalogRelationship(RelationshipId id, String name) {

    public CatalogRelationship {
        Objects.requireNonNull(id, "id");
        name = TextRules.requireLine(name, "relationship name", SemanticCatalog.MAX_NAME_LENGTH);
    }
}
