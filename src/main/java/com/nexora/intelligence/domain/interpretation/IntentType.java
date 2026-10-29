package com.nexora.intelligence.domain.interpretation;

import java.util.EnumSet;
import java.util.Set;

/** What a question asks for, and the interpretation statuses that make sense for it. */
public enum IntentType {
    /** The question asks for a report over the organization's data: clear, or missing essential information. */
    REPORT_REQUEST(EnumSet.of(InterpretationStatus.INTERPRETED, InterpretationStatus.NEEDS_CLARIFICATION)),
    /** The question is understood but outside what Nexora answers. */
    OUT_OF_SCOPE(EnumSet.of(InterpretationStatus.UNSUPPORTED)),
    /** The intent could not be identified: ask the user, or give up. */
    UNKNOWN(EnumSet.of(InterpretationStatus.NEEDS_CLARIFICATION, InterpretationStatus.UNSUPPORTED));

    private final Set<InterpretationStatus> allowedStatuses;

    IntentType(Set<InterpretationStatus> allowedStatuses) {
        this.allowedStatuses = allowedStatuses;
    }

    /** Whether an interpretation of this intent may have {@code status}. */
    public boolean allows(InterpretationStatus status) {
        return allowedStatuses.contains(status);
    }
}
