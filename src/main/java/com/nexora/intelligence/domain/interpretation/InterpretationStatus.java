package com.nexora.intelligence.domain.interpretation;

/** Outcome of an interpretation. */
public enum InterpretationStatus {
    /** The question was interpreted. */
    INTERPRETED,
    /** The question is ambiguous: a clarification question must be asked to the user. */
    NEEDS_CLARIFICATION,
    /** The question cannot be served. */
    UNSUPPORTED
}
