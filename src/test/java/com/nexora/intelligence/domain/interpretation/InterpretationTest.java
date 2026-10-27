package com.nexora.intelligence.domain.interpretation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterpretationTest {

    @Test
    void interpretedQuestionsCarryNoClarification() {
        Interpretation interpretation = new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.INTERPRETED, Optional.empty());

        assertThat(interpretation.intent()).isEqualTo(IntentType.REPORT_REQUEST);
        assertThat(interpretation.status()).isEqualTo(InterpretationStatus.INTERPRETED);
        assertThat(interpretation.clarificationQuestion()).isEmpty();
    }

    @Test
    void needsClarificationRequiresAStrippedClarificationQuestion() {
        Interpretation interpretation = new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.NEEDS_CLARIFICATION, Optional.of("  ¿De qué año?  "));

        assertThat(interpretation.clarificationQuestion()).contains("¿De qué año?");
        assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.NEEDS_CLARIFICATION, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.NEEDS_CLARIFICATION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = InterpretationStatus.class, names = "NEEDS_CLARIFICATION", mode = EnumSource.Mode.EXCLUDE)
    void otherStatusesMustNotCarryAClarificationQuestion(InterpretationStatus status) {
        assertThatThrownBy(() -> new Interpretation(IntentType.UNKNOWN, status, Optional.of("¿Qué periodo?")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Interpretation(IntentType.UNKNOWN, status, null).clarificationQuestion()).isEmpty();
    }

    @Test
    void clarificationQuestionsAreValidated() {
        for (String invalid : new String[] {"", "   ", "¿Qué\u0000año?", "x".repeat(1001)}) {
            assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST,
                    InterpretationStatus.NEEDS_CLARIFICATION, Optional.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                Optional.of("¿Año?\n¿Región?")).clarificationQuestion()).contains("¿Año?\n¿Región?");
    }

    @Test
    void intentAndStatusAreRequired() {
        assertThatThrownBy(() -> new Interpretation(null, InterpretationStatus.UNSUPPORTED, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.OUT_OF_SCOPE, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
