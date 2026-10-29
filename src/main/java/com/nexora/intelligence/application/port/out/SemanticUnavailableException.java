package com.nexora.intelligence.application.port.out;

import java.util.Objects;

/**
 * Nexora Semantic could not be used to read the catalog or to create a plan. A failure is never turned into a
 * catalog, a plan or a rejection: callers must not present it as a result. Messages never contain tokens, secrets,
 * questions, filter values or bodies received from Semantic.
 */
public class SemanticUnavailableException extends RuntimeException {

    public enum Reason {
        /** Semantic or the token endpoint could not be reached, or answered with a server error. */
        UNREACHABLE,
        /** No answer in time to a request that has no side effects (it may be tried again later). */
        TIMEOUT,
        /**
         * A plan creation was sent but no answer arrived: Semantic may or may not have created the plan. It is never
         * retried automatically.
         */
        OUTCOME_UNKNOWN,
        /** The interpretation identity was refused: token not obtained, not verifiable, or rejected (401/403). */
        IDENTITY_REJECTED,
        /** The data source does not exist for the tenant of the identity (404). */
        SOURCE_NOT_FOUND,
        /** The data source is archived (409). */
        SOURCE_ARCHIVED,
        /** Semantic answered something outside its contract (malformed JSON, missing fields, 400...). */
        CONTRACT_VIOLATION,
        /** The usable catalog exceeds the supported limits; it is never truncated. */
        CATALOG_TOO_LARGE,
        /** The catalog changed while its pages were read and a second reading did not stabilize it. */
        CATALOG_INCONSISTENT
    }

    private final Reason reason;

    public SemanticUnavailableException(Reason reason, String internalMessage) {
        super(internalMessage);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public SemanticUnavailableException(Reason reason, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
