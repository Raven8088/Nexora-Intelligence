package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.semantic.DefinitionKey;

import java.util.Objects;

/**
 * A column of a report result as Nexora Reports describes it: the key of the semantic definition it comes from, its
 * role and the kind of its values. Keys are unique within each role, as in Semantic.
 */
public record ResultColumn(DefinitionKey key, ColumnRole role, ValueKind kind) {

    public ResultColumn {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(kind, "kind");
    }
}
