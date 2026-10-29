package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.semantic.ValueType;

import java.util.Optional;

/**
 * Representation of the values of one result column, as Nexora Reports types them. Nothing is converted between
 * kinds: a NUMERIC value stays an exact decimal, an INTEGER an exact integer.
 */
public enum ValueKind {
    TEXT,
    /** Exact decimal (SUM, AVG, numeric dimensions); never a binary floating-point number. */
    NUMERIC,
    /** Exact integer (COUNT). */
    INTEGER,
    BOOLEAN,
    /** A date; TEMPORAL dimensions give the first day of their bucket. */
    DATE,
    /** Wall-clock date and time, without zone. */
    TIMESTAMP,
    /** An instant (UTC). */
    TIMESTAMP_TZ,
    TIME,
    UUID;

    /** The kind Reports uses for a categorical dimension of {@code type}; empty for OTHER (never executable). */
    public static Optional<ValueKind> ofDimension(ValueType type) {
        return switch (type) {
            case NUMERIC -> Optional.of(NUMERIC);
            case TEXT -> Optional.of(TEXT);
            case BOOLEAN -> Optional.of(BOOLEAN);
            case DATE -> Optional.of(DATE);
            case TIMESTAMP -> Optional.of(TIMESTAMP);
            case TIMESTAMP_TZ -> Optional.of(TIMESTAMP_TZ);
            case TIME -> Optional.of(TIME);
            case UUID -> Optional.of(UUID);
            case OTHER -> Optional.empty();
        };
    }
}
