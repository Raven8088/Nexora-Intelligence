package com.nexora.intelligence.domain.question;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaturalLanguageQuestionTest {

    private static final CorrelationId CORRELATION = CorrelationId.generate();

    @Test
    void textIsStrippedAndLanguageIsKept() {
        NaturalLanguageQuestion question = new NaturalLanguageQuestion("  ¿Cuántas ventas hubo en marzo?  ",
                Optional.of(Language.ES), CORRELATION);

        assertThat(question.text()).isEqualTo("¿Cuántas ventas hubo en marzo?");
        assertThat(question.language()).contains(Language.ES);
        assertThat(question.correlationId()).isEqualTo(CORRELATION);
    }

    @Test
    void absentLanguageStaysUndetermined() {
        assertThat(new NaturalLanguageQuestion("Total sales", Optional.empty(), CORRELATION).language()).isEmpty();
        assertThat(new NaturalLanguageQuestion("Total sales", null, CORRELATION).language()).isEmpty();
    }

    @Test
    void lineBreaksAreAllowed() {
        assertThat(new NaturalLanguageQuestion("Ventas por mes\r\ny por región\n", Optional.empty(), CORRELATION)
                .text()).isEqualTo("Ventas por mes\r\ny por región");
    }

    @Test
    void textMustNotBeBlank() {
        for (String blank : new String[] {null, "", "   ", "\n\n"}) {
            assertThatThrownBy(() -> new NaturalLanguageQuestion(blank, Optional.empty(), CORRELATION))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void textIsLimitedToTwoThousandCharactersAfterStripping() {
        String max = "a".repeat(NaturalLanguageQuestion.MAX_TEXT_LENGTH);
        assertThat(new NaturalLanguageQuestion("  " + max + "  ", Optional.empty(), CORRELATION).text()).hasSize(2000);
        assertThatThrownBy(() -> new NaturalLanguageQuestion(max + "b", Optional.empty(), CORRELATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ventas\u0000", "ventas\tmarzo", "ventas\u001b[31m", "ventas\u007fmarzo", "x\u0085y"})
    void controlCharactersOtherThanLineBreaksAreRejectedWithoutEchoingTheText(String text) {
        assertThatThrownBy(() -> new NaturalLanguageQuestion(text, Optional.empty(), CORRELATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("ventas");
    }

    @Test
    void aCorrelationIdIsRequired() {
        assertThatThrownBy(() -> new NaturalLanguageQuestion("Ventas", Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CorrelationId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void correlationIdsAreGeneratedByNexora() {
        CorrelationId first = CorrelationId.generate();
        CorrelationId second = CorrelationId.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(UUID.fromString(first.toString())).isEqualTo(first.value());
    }
}
