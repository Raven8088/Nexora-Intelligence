package com.nexora.intelligence.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** Data source registered in Nexora Semantic. The model never chooses it. */
public record DataSourceId(UUID value) {

    public DataSourceId {
        Objects.requireNonNull(value, "dataSourceId");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
