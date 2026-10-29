package com.nexora.intelligence.domain.shared;

import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.NeedsClarification;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One set of clarification rules, shared by interpretations and outcomes. */
class ClarificationTextTest {

    private static final String[] VALID = {"¿Para qué periodo?", "  Which year?  ", "¿Año?\n¿Región?", "a\r\nb",
            "x".repeat(1000)};
    private static final String[] INVALID = {null, "", "   ", "x".repeat(1001), "¿A\u0000ño?", "¿A\tño?",
            "a\u0007b", "a\u001bb"};

    @Test
    void validTextsAreStripped() {
        assertThat(new ClarificationText("  ¿Para qué periodo?  ").value()).isEqualTo("¿Para qué periodo?");
        assertThat(ClarificationText.of("a\r\nb")).isEqualTo("a\r\nb");
        assertThat(ClarificationText.MAX_LENGTH).isEqualTo(1000);
    }

    @Test
    void invalidTextsAreRejectedWithoutEchoingThem() {
        for (String invalid : INVALID) {
            assertThatThrownBy(() -> new ClarificationText(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .message().doesNotContain("ño").doesNotContain("xxxx");
        }
    }

    @Test
    void interpretationsAndOutcomesApplyExactlyTheSameRules() {
        for (String valid : VALID) {
            String expected = ClarificationText.of(valid);
            assertThat(new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                    Optional.of(valid)).clarificationQuestion()).contains(expected);
            assertThat(new NeedsClarification(valid).question()).isEqualTo(expected);
        }
        for (String invalid : INVALID) {
            if (invalid != null) {
                assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST,
                        InterpretationStatus.NEEDS_CLARIFICATION, Optional.of(invalid)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> new NeedsClarification(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Interpretation.MAX_CLARIFICATION_LENGTH).isEqualTo(ClarificationText.MAX_LENGTH);
        assertThat(NeedsClarification.MAX_LENGTH).isEqualTo(ClarificationText.MAX_LENGTH);
    }
}
