package com.nexora.intelligence.infrastructure.serviceclient;

import java.util.Objects;

/**
 * A service token could not be obtained or did not pass verification. Each adapter maps it to the failure type of
 * its own port. Messages never contain tokens, secrets or bodies.
 */
public class ServiceIdentityException extends RuntimeException {

    public enum Failure {
        /** The token endpoint refused the credentials, or the token failed a check: it is never sent. */
        REJECTED,
        /** The token endpoint or the issuer's keys could not be reached. */
        UNREACHABLE,
        /** The token endpoint did not answer in time. */
        TIMEOUT
    }

    private final Failure failure;

    public ServiceIdentityException(Failure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ServiceIdentityException(Failure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public Failure failure() {
        return failure;
    }
}
