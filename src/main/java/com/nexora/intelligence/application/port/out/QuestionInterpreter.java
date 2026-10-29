package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;

/**
 * Driven port: the capability of interpreting a natural language question against an authorized semantic catalog.
 * Implementations are adapters for a concrete inference engine and live in the infrastructure layer. An
 * implementation never grants permissions, never produces SQL and may only reference definitions of
 * {@code catalog}.
 */
public interface QuestionInterpreter {

    /**
     * @return a valid interpretation, never a fabricated one; an interpreted report request carries a
     *         {@link com.nexora.intelligence.domain.request.RequestDraft} that still has to be resolved
     *         deterministically
     * @throws InterpretationUnavailableException when the engine fails, times out, does not fit its context or
     *                                            answers with invalid output
     */
    Interpretation interpret(NaturalLanguageQuestion question, SemanticCatalog catalog);
}
