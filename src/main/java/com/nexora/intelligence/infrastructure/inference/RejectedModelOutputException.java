package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;

import java.util.Objects;
import java.util.Optional;

/**
 * The model answered normally (complete response, {@code finish_reason=stop}) but {@link ModelOutputParser} rejected
 * the content. It is an {@code INVALID_MODEL_OUTPUT} like any other, so callers see no difference; inside the adapter
 * it is the <em>only</em> failure that may be asked once more ({@code invalid-output-retries}). Transport errors,
 * HTTP statuses, timeouts, truncated or filtered answers, oversized responses and context limits never are.
 * <p>
 * {@link #getMessage()} is the broken rule: fixed text written by the parser, never the model's output, so it can be
 * logged and repeated to the model. {@link #shape()} is an optional diagnostic made only of enumerated values and
 * field names (for a period: its kind and which fields carried a value), never of the values themselves.
 */
final class RejectedModelOutputException extends InterpretationUnavailableException {

    /** Broken rules are short fixed texts; capped so the repetition note has a known maximum size. */
    static final int MAX_RULE_LENGTH = 200;

    private final transient String shape;

    RejectedModelOutputException(String brokenRule) {
        this(brokenRule, null);
    }

    RejectedModelOutputException(String brokenRule, String shape) {
        super(Reason.INVALID_MODEL_OUTPUT, cap(Objects.requireNonNull(brokenRule, "brokenRule")));
        this.shape = shape;
    }

    private static String cap(String rule) {
        return rule.length() <= MAX_RULE_LENGTH ? rule : rule.substring(0, MAX_RULE_LENGTH);
    }

    Optional<String> shape() {
        return Optional.ofNullable(shape);
    }
}
