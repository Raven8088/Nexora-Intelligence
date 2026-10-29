package com.nexora.intelligence.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** Nexora tenant. It will come from authenticated context only, never from a question or a model answer. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenantId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
