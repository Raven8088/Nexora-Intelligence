package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.TimeGranularity;

import java.util.Objects;

/** The time bucket chosen for one requested TEMPORAL dimension. */
public record GranularityChoice(DefinitionId dimension, TimeGranularity granularity) {

    public GranularityChoice {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(granularity, "granularity");
    }
}
