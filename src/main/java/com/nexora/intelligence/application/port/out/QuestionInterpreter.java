package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;

/**
 * Driven port: the capability of interpreting a natural language question. Implementations are adapters for a
 * concrete inference engine and live in the infrastructure layer; INT-1 ships none. An implementation never grants
 * permissions and never produces SQL.
 */
public interface QuestionInterpreter {

    Interpretation interpret(NaturalLanguageQuestion question);
}
