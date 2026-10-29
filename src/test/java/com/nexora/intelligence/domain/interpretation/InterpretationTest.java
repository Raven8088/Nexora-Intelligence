package com.nexora.intelligence.domain.interpretation;

import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterpretationTest {

    private static final RequestDraft DRAFT = new RequestDraft(List.of(new DefinitionId(UUID.randomUUID())), null,
            null, null, null);

    @Test
    void interpretedQuestionsCarryNoClarification() {
        Interpretation interpretation = new Interpretation(IntentType.REPORT_REQUEST,
                InterpretationStatus.INTERPRETED, Optional.empty(), Optional.of(DRAFT));

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
    @CsvSource({"REPORT_REQUEST, INTERPRETED", "OUT_OF_SCOPE, UNSUPPORTED", "UNKNOWN, UNSUPPORTED"})
    void otherStatusesMustNotCarryAClarificationQuestion(IntentType intent, InterpretationStatus status) {
        Optional<RequestDraft> draft = draftFor(intent, status);
        assertThatThrownBy(() -> new Interpretation(intent, status, Optional.of("¿Qué periodo?"), draft))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Interpretation(intent, status, null, draft).clarificationQuestion()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "REPORT_REQUEST, INTERPRETED, true",
            "REPORT_REQUEST, NEEDS_CLARIFICATION, true",
            "REPORT_REQUEST, UNSUPPORTED, false",
            "OUT_OF_SCOPE, INTERPRETED, false",
            "OUT_OF_SCOPE, NEEDS_CLARIFICATION, false",
            "OUT_OF_SCOPE, UNSUPPORTED, true",
            "UNKNOWN, INTERPRETED, false",
            "UNKNOWN, NEEDS_CLARIFICATION, true",
            "UNKNOWN, UNSUPPORTED, true"})
    void onlyTheAllowedIntentAndStatusCombinationsExist(IntentType intent, InterpretationStatus status,
                                                         boolean allowed) {
        Optional<String> clarification = status == InterpretationStatus.NEEDS_CLARIFICATION
                ? Optional.of("¿Qué periodo?") : Optional.empty();

        assertThat(intent.allows(status)).isEqualTo(allowed);
        if (allowed) {
            assertThat(new Interpretation(intent, status, clarification, draftFor(intent, status)).status())
                    .isEqualTo(status);
        } else {
            assertThatThrownBy(() -> new Interpretation(intent, status, clarification, draftFor(intent, status)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
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

    @Test
    void aRequestDraftIsPresentExactlyForInterpretedReportRequests() {
        assertThat(Interpretation.interpreted(DRAFT).request()).contains(DRAFT);
        assertThat(Interpretation.interpreted(DRAFT).intent()).isEqualTo(IntentType.REPORT_REQUEST);
        assertThatThrownBy(() -> Interpretation.interpreted(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.INTERPRETED,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.INTERPRETED,
                Optional.empty(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                Optional.of("¿Qué año?"), Optional.of(DRAFT))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED,
                Optional.empty(), Optional.of(DRAFT))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Interpretation(IntentType.UNKNOWN, InterpretationStatus.UNSUPPORTED, Optional.empty())
                .request()).isEmpty();
    }

    private static Optional<RequestDraft> draftFor(IntentType intent, InterpretationStatus status) {
        return intent == IntentType.REPORT_REQUEST && status == InterpretationStatus.INTERPRETED
                ? Optional.of(DRAFT) : Optional.empty();
    }
}
