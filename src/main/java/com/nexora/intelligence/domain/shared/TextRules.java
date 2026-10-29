package com.nexora.intelligence.domain.shared;

import java.util.Optional;

/** Text checks shared by the domain. Error messages name the field, never the rejected value. */
public final class TextRules {

    private TextRules() {
    }

    /** Stripped, non-blank, at most {@code max} characters, single line (no control characters). */
    public static String requireLine(String value, String field, int max) {
        String stripped = requireText(value, field, max);
        if (stripped.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return stripped;
    }

    /** Stripped, non-blank, at most {@code max} characters; line breaks and tabs are the only control characters. */
    public static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        if (stripped.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return stripped;
    }

    /** Like {@link #requireText} for optional text; null or blank becomes empty. */
    public static Optional<String> optionalText(Optional<String> value, String field, int max) {
        if (value == null || value.isEmpty() || value.get().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(requireText(value.get(), field, max));
    }
}
