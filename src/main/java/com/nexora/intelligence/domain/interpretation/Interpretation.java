package com.nexora.intelligence.domain.interpretation;

import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.shared.ClarificationText;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured interpretation of a question. Immutable and always consistent:
 * <ul>
 *   <li>the status is one the intent allows ({@link IntentType#allows}): REPORT_REQUEST is INTERPRETED or
 *       NEEDS_CLARIFICATION, OUT_OF_SCOPE is UNSUPPORTED, UNKNOWN is NEEDS_CLARIFICATION or UNSUPPORTED;</li>
 *   <li>a clarification question is present if and only if the status is
 *       {@link InterpretationStatus#NEEDS_CLARIFICATION}; it is stripped, non-blank, at most
 *       {@value #MAX_CLARIFICATION_LENGTH} characters and free of control characters other than line breaks
 *       ({@link ClarificationText}).</li>
 *   <li>a {@link RequestDraft} is present if and only if the question is an interpreted report request
 *       (REPORT_REQUEST + INTERPRETED); it is still a proposal that deterministic rules must resolve.</li>
 * </ul>
 */
public record Interpretation(IntentType intent, InterpretationStatus status, Optional<String> clarificationQuestion,
                             Optional<RequestDraft> request) {

    public static final int MAX_CLARIFICATION_LENGTH = ClarificationText.MAX_LENGTH;

    public Interpretation {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(status, "status");
        if (!intent.allows(status)) {
            throw new IllegalArgumentException("status " + status + " is not allowed for intent " + intent);
        }
        clarificationQuestion = clarificationQuestion == null ? Optional.empty()
                : clarificationQuestion.map(ClarificationText::of);
        if (status == InterpretationStatus.NEEDS_CLARIFICATION && clarificationQuestion.isEmpty()) {
            throw new IllegalArgumentException("NEEDS_CLARIFICATION requires a clarification question");
        }
        if (status != InterpretationStatus.NEEDS_CLARIFICATION && clarificationQuestion.isPresent()) {
            throw new IllegalArgumentException("only NEEDS_CLARIFICATION carries a clarification question");
        }
        request = request == null ? Optional.empty() : request;
        boolean interpreted = intent == IntentType.REPORT_REQUEST && status == InterpretationStatus.INTERPRETED;
        if (interpreted && request.isEmpty()) {
            throw new IllegalArgumentException("an interpreted report request needs a request draft");
        }
        if (!interpreted && request.isPresent()) {
            throw new IllegalArgumentException("only an interpreted report request carries a request draft");
        }
    }

    /** An interpretation without request draft: anything but an interpreted report request. */
    public Interpretation(IntentType intent, InterpretationStatus status, Optional<String> clarificationQuestion) {
        this(intent, status, clarificationQuestion, Optional.empty());
    }

    /** A report request interpreted into {@code draft}. */
    public static Interpretation interpreted(RequestDraft draft) {
        return new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.INTERPRETED, Optional.empty(),
                Optional.of(java.util.Objects.requireNonNull(draft, "draft")));
    }
}
