package com.nexora.intelligence.domain.semantic;

/**
 * Logical value category of a dimension, as Nexora Semantic derives it from the column type. Only the category is
 * kept, never the physical type name.
 */
public enum ValueType {
    NUMERIC,
    TEXT,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    TIMESTAMP_TZ,
    TIME,
    UUID,
    OTHER;

    /** DATE, TIMESTAMP or TIMESTAMP_TZ: the only categories a TEMPORAL dimension can have. */
    public boolean isTemporal() {
        return this == DATE || this == TIMESTAMP || this == TIMESTAMP_TZ;
    }
}
