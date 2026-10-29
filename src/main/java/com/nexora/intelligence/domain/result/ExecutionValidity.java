package com.nexora.intelligence.domain.result;

/** Validity of the plan confirmed by Semantic after the execution; a STALE plan never produces a result. */
public enum ExecutionValidity {
    CURRENT,
    /** Only non-blocking catalog changes since the plan was created. */
    CURRENT_WITH_CHANGES
}
