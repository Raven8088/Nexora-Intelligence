package com.nexora.intelligence.application.port.out;

import java.util.Objects;

/**
 * Nexora Reports could not execute a plan for a reason that is not about the plan: it is never turned into a result
 * or a rejection, and callers must not present it as one. Messages never contain tokens, secrets, values or bodies.
 */
public class ReportsUnavailableException extends RuntimeException {

    public enum Reason {
        /** Reports or the token endpoint could not be reached, or the answer was cut. */
        UNREACHABLE,
        /** No answer in time, or the execution exceeded Reports' time limits (504). */
        TIMEOUT,
        /** Reports has reached its concurrent executions limit (429); it may be tried again later. */
        BUSY,
        /** The execution identity was refused: token not obtained, not verifiable, or rejected (401/403). */
        IDENTITY_REJECTED,
        /** The data source or the plan does not exist for the tenant of the identity (404). */
        NOT_FOUND,
        /** An operator setting prevents the execution (source not configured or not executable, role not read-only,
         *  destination not allowed, Reports' own service identity invalid). */
        MISCONFIGURED,
        /** A dependency of Reports is not available (Semantic, the source database, its audit). */
        DEPENDENCY_UNAVAILABLE,
        /** The execution failed in Reports for an unexpected reason (500). */
        EXECUTION_FAILED,
        /** Reports answered something outside its contract, or a result that does not match the plan. */
        CONTRACT_VIOLATION
    }

    private final Reason reason;

    public ReportsUnavailableException(Reason reason, String internalMessage) {
        super(internalMessage);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ReportsUnavailableException(Reason reason, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
