package com.nexora.intelligence.domain.question;

import java.util.Objects;
import java.util.UUID;

/**
 * Correlation identifier of one interpretation request, for traceability across Nexora services. It is always assigned
 * by Nexora ({@link #generate()}); an identifier sent by an external client is never trusted nor reused as one.
 */
public record CorrelationId(UUID value) {

    public CorrelationId {
        Objects.requireNonNull(value, "correlationId");
    }

    /** A new identifier assigned by Nexora. */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
