package com.nexora.intelligence.application.translation;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.request.GranularityChoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CUSTOMER_AGE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionTranslationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-05-15T03:30:00Z"), ZoneOffset.UTC);

    @Test
    void unsupportedAndClarificationAnswersComeStraightFromTheInterpretation() {
        assertThat(translate(new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED,
                Optional.empty()), question("Escribe un poema", Language.ES)))
                .isEqualTo(new Translation.Answered(new QuestionOutcome.Unsupported()));
        assertThat(translate(new Interpretation(IntentType.UNKNOWN, InterpretationStatus.UNSUPPORTED,
                Optional.empty()), question("???", Language.ES)))
                .isEqualTo(new Translation.Answered(new QuestionOutcome.Unsupported()));
        assertThat(translate(new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                Optional.of("¿Para qué periodo?")), question("Ventas", Language.ES)))
                .isEqualTo(new Translation.Answered(new QuestionOutcome.NeedsClarification("¿Para qué periodo?")));
    }

    @Test
    void aResolvedDraftIsReadyForSemanticWithDeterministicBounds() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), List.of(ORDER_DATE.id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("lima"))),
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)),
                Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Relative(RelativePeriod.THIS_YEAR))));

        Translation translation = translate(Interpretation.interpreted(draft),
                question("Ventas por mes de este año en Lima", Language.ES));

        assertThat(translation).isInstanceOfSatisfying(Translation.Ready.class, ready -> {
            SemanticRequest request = ready.request();
            assertThat(request.scope()).isEqualTo(CATALOG.scope());
            assertThat(request.filters()).containsExactly(
                    FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Lima")),
                    FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2025-01-01")),
                    FilterCondition.of(ORDER_DATE.id(), FilterOperator.LT, FilterValue.text("2025-05-16")));
        });
    }

    @Test
    void theSourceZoneDecidesToday() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Relative(RelativePeriod.TODAY))));
        QuestionTranslationService lima = new QuestionTranslationService((q, c) -> Interpretation.interpreted(draft),
                CLOCK, ZoneId.of("America/Lima"));

        Translation translation = lima.translate(question("Ventas de hoy", Language.ES), CATALOG);

        assertThat(((Translation.Ready) translation).request().filters().get(0))
                .isEqualTo(FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2025-05-14")));
    }

    @Test
    void modelDefectsAreInvalidModelOutputNeverAttributedToTheUser() {
        RequestDraft invented = new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Arequipa"))), null, null);
        AtomicInteger calls = new AtomicInteger();
        QuestionTranslationService service = new QuestionTranslationService((q, c) -> {
            calls.incrementAndGet();
            return Interpretation.interpreted(invented);
        }, CLOCK);

        assertThatThrownBy(() -> service.translate(question("Ventas en Lima", Language.ES), CATALOG))
                .isInstanceOfSatisfying(InterpretationUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.INVALID_MODEL_OUTPUT);
                    assertThat(e.getMessage()).contains("TEXT_NOT_IN_QUESTION").doesNotContain("Arequipa")
                            .doesNotContain("Lima");
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void ambiguitiesAskTheUserInTheDeclaredOrDetectedLanguageAndSpanishByDefault() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, List.of(FilterCondition.of(
                TOTAL_SALES.id(), FilterOperator.GT, FilterValue.number(new BigDecimal("1000")))), null, null);

        assertThat(clarification(draft, new NaturalLanguageQuestion("Sales above 1,000", Optional.empty(),
                CorrelationId.generate()))).startsWith("A number in your question");
        assertThat(clarification(draft, new NaturalLanguageQuestion("Ventas mayores a 1.000", Optional.empty(),
                CorrelationId.generate()))).startsWith("Un número de tu pregunta");
        assertThat(clarification(draft, new NaturalLanguageQuestion("1.000", Optional.empty(),
                CorrelationId.generate()))).startsWith("Un número de tu pregunta");
    }

    @Test
    void validationFailuresAreRejectionsByIntelligenceWithCountedOverflow() {
        String values = IntStream.range(0, 60).mapToObj(i -> "v" + i).collect(Collectors.joining(" "));
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, List.of(new FilterCondition(
                CUSTOMER_AGE.id(), FilterOperator.IN, IntStream.range(0, 60).mapToObj(i -> FilterValue.text("v" + i))
                .map(FilterValue.class::cast).toList())), null, null);

        Translation translation = translate(Interpretation.interpreted(draft),
                question("Ventas por edad " + values, Language.ES));

        assertThat(translation).isInstanceOfSatisfying(Translation.Answered.class, answered ->
                assertThat(answered.outcome()).isInstanceOfSatisfying(Rejected.class, rejected -> {
                    assertThat(rejected.origin()).isEqualTo(Rejected.Origin.INTELLIGENCE);
                    assertThat(rejected.causes()).hasSize(50).allMatch(c -> c.code().equals("VALUE_INCOMPATIBLE"));
                    assertThat(rejected.omittedCauses()).isEqualTo(10);
                }));
    }

    @Test
    void draftIssuesOfValidationAlsoBecomeRejections() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, null);
        Translation catalogMismatch = translate(Interpretation.interpreted(new RequestDraft(
                List.of(TOTAL_SALES.id()), List.of(ORDER_DATE.id()), null, null, null)), question("Ventas", Language.ES));

        assertThat(translate(Interpretation.interpreted(draft), question("Ventas", Language.ES)))
                .isInstanceOf(Translation.Ready.class);
        assertThat(catalogMismatch).isEqualTo(new Translation.Answered(new Rejected(Rejected.Origin.INTELLIGENCE,
                List.of(Rejected.Cause.of("GRANULARITY_REQUIRED", "dimensions[0]")))));
    }

    @Test
    void interpreterFailuresPropagateAndArgumentsAreRequired() {
        QuestionTranslationService failing = new QuestionTranslationService((q, c) -> {
            throw new InterpretationUnavailableException(Reason.TIMEOUT, "engine timed out");
        }, CLOCK);
        QuestionInterpreter never = (q, c) -> {
            throw new AssertionError("not called");
        };

        assertThatThrownBy(() -> failing.translate(question("Ventas", Language.ES), CATALOG))
                .isInstanceOfSatisfying(InterpretationUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
        assertThatThrownBy(() -> new QuestionTranslationService(never, CLOCK).translate(null, CATALOG))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QuestionTranslationService(never, CLOCK)
                .translate(question("Ventas", Language.ES), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QuestionTranslationService(null, CLOCK)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Translation.Answered(new QuestionOutcome.PlanCreated(CATALOG.scope(),
                new com.nexora.intelligence.domain.outcome.PlanId(java.util.UUID.randomUUID()), "a".repeat(64),
                "nexora-logical-plan/1"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Translation translate(Interpretation interpretation, NaturalLanguageQuestion question) {
        return new QuestionTranslationService((q, c) -> interpretation, CLOCK).translate(question, CATALOG);
    }

    private static String clarification(RequestDraft draft, NaturalLanguageQuestion question) {
        Translation translation = new QuestionTranslationService((q, c) -> Interpretation.interpreted(draft), CLOCK)
                .translate(question, CATALOG);
        return ((QuestionOutcome.NeedsClarification) ((Translation.Answered) translation).outcome()).question();
    }

    private static NaturalLanguageQuestion question(String text, Language language) {
        return new NaturalLanguageQuestion(text, Optional.of(language), CorrelationId.generate());
    }
}
