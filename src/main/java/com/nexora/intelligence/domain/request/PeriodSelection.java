package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.semantic.DefinitionId;

import java.util.Objects;

/** The period proposed for a question and the TEMPORAL dimension it applies to. */
public record PeriodSelection(DefinitionId dimension, PeriodSpec spec) {

    public PeriodSelection {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(spec, "spec");
    }
}
