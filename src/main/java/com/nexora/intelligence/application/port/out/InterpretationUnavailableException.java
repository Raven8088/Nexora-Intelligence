package com.nexora.intelligence.application.port.out;

import java.util.Objects;

/**
 * The interpretation engine could not produce a valid interpretation. A failure is never turned into an
 * {@link com.nexora.intelligence.domain.interpretation.Interpretation}: callers must not present it as a result.
 * Messages never contain the question nor the engine's output.
 */
public class InterpretationUnavailableException extends RuntimeException {

    public enum Reason {
        /** The engine did not answer in time (or could not be reached in time). */
        TIMEOUT,
        /** The engine could not be reached or answered with an HTTP error. */
        HTTP_ERROR,
        /** The engine answered, but not with a valid interpretation. */
        INVALID_MODEL_OUTPUT,
        /** The prompt, its schema and the reserved answer do not fit the engine's context window. */
        CONTEXT_LIMIT_EXCEEDED
    }

    private final Reason reason;

    public InterpretationUnavailableException(Reason reason, String internalMessage) {
        super(internalMessage);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public InterpretationUnavailableException(Reason reason, String internalMessage, Throwable cause) {
        super(internalMessage, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
