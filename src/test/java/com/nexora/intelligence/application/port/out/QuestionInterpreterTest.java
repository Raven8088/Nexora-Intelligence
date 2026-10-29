package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The port is a plain contract: any adapter (here a test double) receives a validated question. */
class QuestionInterpreterTest {

    @Test
    void anAdapterReceivesTheValidatedQuestionAndReturnsAnInterpretation() {
        List<NaturalLanguageQuestion> received = new ArrayList<>();
        QuestionInterpreter testDouble = (question, catalog) -> {
            received.add(question);
            return new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                    Optional.of("¿Para qué periodo?"));
        };
        NaturalLanguageQuestion question = new NaturalLanguageQuestion("  Ventas totales  ",
                Optional.of(Language.ES), CorrelationId.generate());

        Interpretation interpretation = testDouble.interpret(question, QuestionInterpreterContract.CATALOG);

        assertThat(received).containsExactly(question);
        assertThat(received.get(0).text()).isEqualTo("Ventas totales");
        assertThat(interpretation.status()).isEqualTo(InterpretationStatus.NEEDS_CLARIFICATION);
    }

    /** The shared contract, run against a plain test double. */
    @Nested
    class TestDoubleHonoursTheContract extends QuestionInterpreterContract {

        @Override
        protected QuestionInterpreter interpreterAnswering(Interpretation answer) {
            return (question, catalog) -> {
                Objects.requireNonNull(question, "question");
                Objects.requireNonNull(catalog, "catalog");
                return answer;
            };
        }

        @Override
        protected QuestionInterpreter failingInterpreter() {
            return (question, catalog) -> {
                throw new InterpretationUnavailableException(InterpretationUnavailableException.Reason.TIMEOUT,
                        "engine timed out");
            };
        }
    }
}
