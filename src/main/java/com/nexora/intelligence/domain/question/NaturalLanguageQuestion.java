package com.nexora.intelligence.domain.question;

import java.util.Objects;
import java.util.Optional;

/**
 * A question in natural language, as received by Nexora. Immutable and always valid: the text is stripped, non-blank,
 * at most {@value #MAX_TEXT_LENGTH} characters and free of control characters other than line breaks. Error messages
 * never echo the text.
 *
 * @param language      declared language; empty when undetermined (it is never assumed)
 * @param correlationId identifier assigned by Nexora, never taken from an external client
 */
public record NaturalLanguageQuestion(String text, Optional<Language> language, CorrelationId correlationId) {

    public static final int MAX_TEXT_LENGTH = 2000;

    public NaturalLanguageQuestion {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("question text must not be blank");
        }
        text = text.strip();
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("question text must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw new IllegalArgumentException("question text must not contain control characters");
        }
        language = language == null ? Optional.empty() : language;
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
