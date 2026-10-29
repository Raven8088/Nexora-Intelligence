package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.PeriodUnit;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.CatalogFixtures;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.PAID;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour every {@link QuestionInterpreter} must honour, whatever its implementation: it returns exactly the valid
 * interpretation it obtained, never a fabricated one; an interpreted report request carries a draft that only
 * references the given catalog; it refuses a missing question or catalog and reports a failure as
 * {@link InterpretationUnavailableException}.
 */
public abstract class QuestionInterpreterContract {

    protected static final SemanticCatalog CATALOG = CatalogFixtures.CATALOG;

    public static final RequestDraft MONTHLY_SALES = new RequestDraft(List.of(TOTAL_SALES.id()),
            List.of(REGION.id(), ORDER_DATE.id()),
            List.of(FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("Lima"),
                    FilterValue.text("Cusco"))),
            List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)),
            Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Relative(RelativePeriod.LAST_YEAR))));
    public static final RequestDraft BIG_PAID_ORDERS = new RequestDraft(List.of(ORDER_COUNT.id(), TOTAL_SALES.id()),
            null,
            List.of(FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GTE, FilterValue.number(new BigDecimal("1000.5"))),
                    FilterCondition.of(PAID.id(), FilterOperator.EQ, FilterValue.bool(true)),
                    FilterCondition.of(REGION.id(), FilterOperator.IS_NOT_NULL)),
            null,
            Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Range(LocalDate.of(2025, 1, 15),
                    LocalDate.of(2025, 3, 31)))));
    public static final RequestDraft RECENT_SALES = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null,
            Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Last(3, PeriodUnit.MONTHS))));

    /** An interpreter whose underlying engine will answer {@code answer}. */
    protected abstract QuestionInterpreter interpreterAnswering(Interpretation answer);

    /** An interpreter whose underlying engine fails. */
    protected abstract QuestionInterpreter failingInterpreter();

    @Test
    void returnsTheInterpretationOfEveryAllowedCombination() {
        for (Interpretation expected : List.of(
                Interpretation.interpreted(MONTHLY_SALES),
                Interpretation.interpreted(BIG_PAID_ORDERS),
                Interpretation.interpreted(RECENT_SALES),
                new Interpretation(IntentType.REPORT_REQUEST, InterpretationStatus.NEEDS_CLARIFICATION,
                        Optional.of("¿Para qué periodo?")),
                new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED, Optional.empty()),
                new Interpretation(IntentType.UNKNOWN, InterpretationStatus.NEEDS_CLARIFICATION,
                        Optional.of("What do you want to know?")),
                new Interpretation(IntentType.UNKNOWN, InterpretationStatus.UNSUPPORTED, Optional.empty()))) {
            assertThat(interpreterAnswering(expected).interpret(question(), CATALOG)).isEqualTo(expected);
        }
    }

    @Test
    void interpretedDraftsOnlyReferenceTheCatalog() {
        Interpretation interpretation = interpreterAnswering(Interpretation.interpreted(BIG_PAID_ORDERS))
                .interpret(question(), CATALOG);

        RequestDraft draft = interpretation.request().orElseThrow();
        List<DefinitionId> referenced = new ArrayList<>(draft.metrics());
        referenced.addAll(draft.dimensions());
        draft.filters().forEach(f -> referenced.add(f.target()));
        draft.period().ifPresent(p -> referenced.add(p.dimension()));
        assertThat(referenced).allMatch(id -> CATALOG.metric(id).isPresent() || CATALOG.dimension(id).isPresent());
    }

    @Test
    void refusesAMissingQuestionOrCatalog() {
        QuestionInterpreter interpreter = interpreterAnswering(
                new Interpretation(IntentType.OUT_OF_SCOPE, InterpretationStatus.UNSUPPORTED, Optional.empty()));

        assertThatThrownBy(() -> interpreter.interpret(null, CATALOG)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> interpreter.interpret(question(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportsFailuresAsUnavailableInsteadOfInventingAResult() {
        assertThatThrownBy(() -> failingInterpreter().interpret(question(), CATALOG))
                .isInstanceOf(InterpretationUnavailableException.class);
    }

    protected static NaturalLanguageQuestion question() {
        return new NaturalLanguageQuestion("Ventas totales por mes en Lima y Cusco", Optional.of(Language.ES),
                CorrelationId.generate());
    }
}
