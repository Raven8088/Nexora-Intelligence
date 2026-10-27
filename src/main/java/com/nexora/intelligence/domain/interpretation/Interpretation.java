package com.nexora.intelligence.domain.interpretation;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured interpretation of a question. Immutable and always consistent: a clarification question is present if
 * and only if the status is {@link InterpretationStatus#NEEDS_CLARIFICATION}; it is stripped, non-blank, at most
 * {@value #MAX_CLARIFICATION_LENGTH} characters and free of control characters other than line breaks.
 */
public record Interpretation(IntentType intent, InterpretationStatus status, Optional<String> clarificationQuestion) {

    public static final int MAX_CLARIFICATION_LENGTH = 1000;

    public Interpretation {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(status, "status");
        clarificationQuestion = clarificationQuestion == null ? Optional.empty()
                : clarificationQuestion.map(Interpretation::clarification);
        if (status == InterpretationStatus.NEEDS_CLARIFICATION && clarificationQuestion.isEmpty()) {
            throw new IllegalArgumentException("NEEDS_CLARIFICATION requires a clarification question");
        }
        if (status != InterpretationStatus.NEEDS_CLARIFICATION && clarificationQuestion.isPresent()) {
            throw new IllegalArgumentException("only NEEDS_CLARIFICATION carries a clarification question");
        }
    }

    private static String clarification(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("clarification question must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_CLARIFICATION_LENGTH) {
            throw new IllegalArgumentException(
                    "clarification question must be at most " + MAX_CLARIFICATION_LENGTH + " characters");
        }
        if (stripped.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw new IllegalArgumentException("clarification question must not contain control characters");
        }
        return stripped;
    }
}
