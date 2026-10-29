package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;

/**
 * Checks, before anything is sent, that the prompt fits the engine's context window together with the answer it
 * reserves. Servers such as Ollama silently drop the beginning of a prompt that does not fit, which would remove the
 * rules or the catalog, so an oversize request is refused instead.
 * <p>
 * No tokenizer of the model is available here, so the count is a conservative <em>estimate</em>, not an exact
 * count: one token per {@value #ASCII_CHARS_PER_TOKEN} ASCII characters (real tokenizers average about 4), one token
 * per non-ASCII character, a fixed overhead per message, and {@value #SAFETY_PERCENT}% of the window kept free as a
 * safety margin. Everything counts: system message (rules, examples, catalog), question, schema and reserved answer.
 */
final class PromptBudget {

    static final int ASCII_CHARS_PER_TOKEN = 3;
    static final int MESSAGE_OVERHEAD_TOKENS = 16;
    static final int SAFETY_PERCENT = 10;

    private PromptBudget() {
    }

    /** Estimated prompt tokens of the given parts. */
    static int estimate(String... parts) {
        long tokens = 0;
        for (String part : parts) {
            long ascii = part.chars().filter(c -> c < 128).count();
            long other = part.length() - ascii;
            tokens += (ascii + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN + other + MESSAGE_OVERHEAD_TOKENS;
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    /** Usable part of the window once the safety margin is kept free. */
    static int usable(int contextTokens) {
        return contextTokens - contextTokens * SAFETY_PERCENT / 100;
    }

    /**
     * @throws InterpretationUnavailableException {@code CONTEXT_LIMIT_EXCEEDED} when the estimated prompt plus the
     *                                            reserved answer exceed the usable window
     */
    static int check(int contextTokens, int reservedAnswerTokens, String... parts) {
        int prompt = estimate(parts);
        if ((long) prompt + reservedAnswerTokens > usable(contextTokens)) {
            throw new InterpretationUnavailableException(Reason.CONTEXT_LIMIT_EXCEEDED,
                    "estimated prompt of " + prompt + " tokens plus " + reservedAnswerTokens
                            + " reserved for the answer exceed the usable context of " + usable(contextTokens));
        }
        return prompt;
    }
}
