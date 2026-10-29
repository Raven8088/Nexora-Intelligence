package com.nexora.intelligence.domain.shared;

/**
 * A question addressed to the user to clarify a request. Single source of the rules shared by interpretations and
 * outcomes: stripped, non-blank, at most {@value #MAX_LENGTH} characters, and free of control characters other than
 * line breaks ({@code \n}, {@code \r}). Error messages never echo the text.
 */
public record ClarificationText(String value) {

    public static final int MAX_LENGTH = 1000;

    public ClarificationText {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("clarification question must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("clarification question must be at most " + MAX_LENGTH + " characters");
        }
        if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw new IllegalArgumentException("clarification question must not contain control characters");
        }
    }

    /** The validated, stripped text. */
    public static String of(String value) {
        return new ClarificationText(value).value();
    }

    @Override
    public String toString() {
        return value;
    }
}
