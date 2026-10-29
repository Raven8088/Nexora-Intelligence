package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptBudgetTest {

    @Test
    void estimatesConservativelyPerPart() {
        assertThat(PromptBudget.estimate("")).isEqualTo(16);
        assertThat(PromptBudget.estimate("abc")).isEqualTo(1 + 16);
        assertThat(PromptBudget.estimate("abcd")).isEqualTo(2 + 16);
        assertThat(PromptBudget.estimate("a".repeat(3000))).isEqualTo(1000 + 16);
        assertThat(PromptBudget.estimate("a", "b")).isEqualTo(2 * (1 + 16));
    }

    @Test
    void nonAsciiCharactersCountAsOneTokenEach() {
        assertThat(PromptBudget.estimate("ñ".repeat(30))).isEqualTo(30 + 16);
        assertThat(PromptBudget.estimate("売上".repeat(10))).isEqualTo(20 + 16);
        assertThat(PromptBudget.estimate("ventas ñ")).isEqualTo(3 + 1 + 16);
    }

    @Test
    void keepsASafetyMarginOfTheWindowFree() {
        assertThat(PromptBudget.usable(8192)).isEqualTo(8192 - 819);
        assertThat(PromptBudget.usable(16384)).isEqualTo(16384 - 1638);
    }

    @Test
    void thePromptAndTheReservedAnswerMustFit() {
        int usable = PromptBudget.usable(8192);
        String fits = "a".repeat((usable - 4096 - 16) * 3);
        String exceeds = fits + "aaaa";

        assertThat(PromptBudget.check(8192, 4096, fits)).isEqualTo(usable - 4096);
        assertThatThrownBy(() -> PromptBudget.check(8192, 4096, exceeds))
                .isInstanceOfSatisfying(InterpretationUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.CONTEXT_LIMIT_EXCEEDED);
                    assertThat(e.getMessage()).doesNotContain("aaaa");
                });
        assertThatThrownBy(() -> PromptBudget.check(8192, 8000, "short"))
                .isInstanceOf(InterpretationUnavailableException.class);
    }
}
