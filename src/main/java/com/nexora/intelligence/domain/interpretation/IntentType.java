package com.nexora.intelligence.domain.interpretation;

/** What a question asks for. */
public enum IntentType {
    /** The question asks for a report over the organization's data. */
    REPORT_REQUEST,
    /** The question is understood but outside what Nexora answers. */
    OUT_OF_SCOPE,
    /** The intent could not be identified. */
    UNKNOWN
}
