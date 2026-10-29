package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.PeriodUnit;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.period.TemporalContext;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.DraftIssue.Category;
import com.nexora.intelligence.domain.request.DraftIssue.Code;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DefinitionKey;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.semantic.ValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CHANNEL;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_COUNT;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_CUSTOMER;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.PAID;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestDraftResolverTest {

    /** 2025-05-15T03:30Z: still May 14th in Lima, already May 15th in UTC and Madrid. */
    private static final Instant NOW = Instant.parse("2025-05-15T03:30:00Z");
    private static final TemporalContext UTC = TemporalContext.utc(NOW);

    private static final CatalogDimension SHIPPED_AT = new CatalogDimension(id(), new DefinitionKey("shipped-at"),
            "Fecha de envío", Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.TIMESTAMP),
            EnumSet.allOf(TimeGranularity.class));
    private static final CatalogDimension PAID_AT = new CatalogDimension(id(), new DefinitionKey("paid-at"),
            "Fecha de pago", Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.TIMESTAMP_TZ),
            EnumSet.allOf(TimeGranularity.class), Optional.of(ZoneId.of("Europe/Madrid")));
    private static final CatalogDimension CREATED_AT = new CatalogDimension(id(), new DefinitionKey("created-at"),
            "Fecha de alta", Optional.empty(), DimensionType.TEMPORAL, Optional.of(ValueType.TIMESTAMP_TZ),
            EnumSet.allOf(TimeGranularity.class));
    private static final CatalogDimension UNTYPED_DATE = new CatalogDimension(id(), new DefinitionKey("closed-at"),
            "Fecha de cierre", Optional.empty(), DimensionType.TEMPORAL, Optional.empty(),
            EnumSet.allOf(TimeGranularity.class));
    private static final SemanticCatalog FULL = new SemanticCatalog(SCOPE, CATALOG.metrics(),
            concat(CATALOG.dimensions(), List.of(SHIPPED_AT, PAID_AT, CREATED_AT, UNTYPED_DATE)),
            CATALOG.relationships());

    // ---- happy path and order --------------------------------------------------------------------------------------

    @Test
    void aCompleteDraftBecomesARequestWithValuesTakenFromTheQuestion() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), List.of(REGION.id(), ORDER_DATE.id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("lima"),
                                FilterValue.text("cusco")),
                        FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT,
                                FilterValue.number(new BigDecimal("1000")))),
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)),
                Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Relative(RelativePeriod.LAST_YEAR))));

        SemanticRequest request = resolved(draft, question("Ventas por mes del año pasado en Lima y Cusco, "
                + "solo regiones con más de 1.000"), UTC);

        assertThat(request.scope()).isEqualTo(SCOPE);
        assertThat(request.metrics()).containsExactly(TOTAL_SALES.id());
        assertThat(request.dimensions()).containsExactly(REGION.id(), ORDER_DATE.id());
        assertThat(request.preferredRelationships()).isEmpty();
        assertThat(request.filters()).containsExactly(
                FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("Lima"), FilterValue.text("Cusco")),
                FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT, FilterValue.number(new BigDecimal("1000"))),
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2024-01-01")),
                FilterCondition.of(ORDER_DATE.id(), FilterOperator.LT, FilterValue.text("2025-01-01")));
        assertThat(request.violationsAgainst(FULL)).isEmpty();
    }

    @Test
    void preferredRelationshipsAreNeverPartOfAResolvedRequest() {
        SemanticRequest request = resolved(new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, null),
                question("Ventas totales"), UTC);

        assertThat(request.preferredRelationships()).isEmpty();
        assertThat(CATALOG.relationship(ORDER_CUSTOMER.id())).isPresent();
    }

    @Test
    void catalogViolationsComeLastAsValidationFailures() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), List.of(ORDER_DATE.id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.GT, FilterValue.text("Lima"))),
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.DAY)), null);

        DraftResolution resolution = RequestDraftResolver.resolve(draft, CATALOG, question("Ventas en Lima"), UTC);

        assertThat(resolution).isInstanceOfSatisfying(DraftResolution.ValidationFailed.class, failed -> {
            assertThat(failed.issues()).isEmpty();
            assertThat(failed.violations()).containsExactly(
                    new RequestViolation(RequestViolation.Code.OPERATOR_INCOMPATIBLE, "filters[0].operator"),
                    new RequestViolation(RequestViolation.Code.GRANULARITY_NOT_ALLOWED,
                            "timeGranularities[0].granularity"));
        });
    }

    // ---- model defects -------------------------------------------------------------------------------------------

    @Test
    void inventedValuesAndReferencesAreModelDefectsNotUserErrors() {
        DefinitionId unknown = id();
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id(), unknown), List.of(id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Arequipa")),
                        FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT, FilterValue.number(new BigDecimal(500))),
                        FilterCondition.of(REGION.id(), FilterOperator.NE, FilterValue.bool(true)),
                        FilterCondition.of(id(), FilterOperator.IS_NULL),
                        FilterCondition.of(ORDER_DATE.id(), FilterOperator.GTE, FilterValue.text("2025-01-01"))),
                null, Optional.of(new PeriodSelection(REGION.id(), new PeriodSpec.Year(2025))));

        DraftResolution resolution = RequestDraftResolver.resolve(draft, CATALOG,
                question("Ventas en Lima en 2025"), UTC);

        assertThat(resolution).isInstanceOfSatisfying(DraftResolution.ModelDefect.class, defect ->
                assertThat(defect.issues()).containsExactly(
                        new DraftIssue(Code.UNKNOWN_REFERENCE, "metrics[1]"),
                        new DraftIssue(Code.UNKNOWN_REFERENCE, "dimensions[0]"),
                        new DraftIssue(Code.TEXT_NOT_IN_QUESTION, "filters[0].values[0]"),
                        new DraftIssue(Code.NUMBER_NOT_IN_QUESTION, "filters[1].values[0]"),
                        new DraftIssue(Code.BOOLEAN_NOT_APPLICABLE, "filters[2].values[0]"),
                        new DraftIssue(Code.UNKNOWN_REFERENCE, "filters[3].target"),
                        new DraftIssue(Code.TEMPORAL_FILTER_NOT_ALLOWED, "filters[4].target"),
                        new DraftIssue(Code.PERIOD_DIMENSION_NOT_TEMPORAL, "period.dimension")));
    }

    @Test
    void booleansOnlyOnKnownBooleanDimensions() {
        RequestDraft paid = new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(PAID.id(), FilterOperator.EQ, FilterValue.bool(true))), null, null);
        RequestDraft untyped = new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(CHANNEL.id(), FilterOperator.EQ, FilterValue.bool(true))), null, null);

        assertThat(RequestDraftResolver.resolve(paid, CATALOG, question("Ventas pagadas"), UTC))
                .isInstanceOf(DraftResolution.Resolved.class);
        assertThat(RequestDraftResolver.resolve(untyped, CATALOG, question("Ventas por canal"), UTC))
                .isInstanceOf(DraftResolution.ModelDefect.class);
    }

    @Test
    void valuesThatCollapseOnceTakenFromTheQuestionAreModelDefects() {
        RequestDraft repeatedValues = new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("lima"),
                        FilterValue.text("LIMA"))), null, null);
        RequestDraft repeatedFilters = new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("lima")),
                        FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Lima"))), null, null);

        assertThat(RequestDraftResolver.resolve(repeatedValues, CATALOG, question("Ventas en Lima"), UTC))
                .isEqualTo(new DraftResolution.ModelDefect(List.of(
                        new DraftIssue(Code.DUPLICATE_AFTER_NORMALIZATION, "filters[0].values"))));
        assertThat(RequestDraftResolver.resolve(repeatedFilters, CATALOG, question("Ventas en Lima"), UTC))
                .isEqualTo(new DraftResolution.ModelDefect(List.of(
                        new DraftIssue(Code.DUPLICATE_AFTER_NORMALIZATION, "filters"))));
    }

    // ---- ambiguity and validation ----------------------------------------------------------------------------------

    @Test
    void ambiguousNumeralsAskTheUserOnlyWhenThereIsNoModelDefect() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, List.of(FilterCondition.of(
                TOTAL_SALES.id(), FilterOperator.GT, FilterValue.number(new BigDecimal("1000")))), null, null);
        NaturalLanguageQuestion noLanguage = new NaturalLanguageQuestion("Ventas mayores a 1.000", Optional.empty(),
                CorrelationId.generate());

        assertThat(RequestDraftResolver.resolve(draft, CATALOG, noLanguage, UTC))
                .isEqualTo(new DraftResolution.Ambiguous(List.of(
                        new DraftIssue(Code.NUMBER_AMBIGUOUS, "filters[0].values[0]"))));
        assertThat(RequestDraftResolver.resolve(draft, CATALOG, question("Ventas mayores a 1.000"), UTC))
                .isInstanceOf(DraftResolution.Resolved.class);

        RequestDraft alsoDefective = new RequestDraft(List.of(TOTAL_SALES.id()), null, List.of(
                FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT, FilterValue.number(new BigDecimal("1000"))),
                FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("Tacna"))), null, null);
        assertThat(RequestDraftResolver.resolve(alsoDefective, CATALOG, noLanguage, UTC))
                .isInstanceOf(DraftResolution.ModelDefect.class);
    }

    @Test
    void aPeriodOnADimensionWithoutValueTypeIsAValidationFailure() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null,
                Optional.of(new PeriodSelection(UNTYPED_DATE.id(), new PeriodSpec.Year(2025))));

        assertThat(RequestDraftResolver.resolve(draft, FULL, question("Ventas de 2025"), UTC))
                .isEqualTo(new DraftResolution.ValidationFailed(List.of(
                        new DraftIssue(Code.PERIOD_TYPE_UNKNOWN, "period.dimension")), List.of()));
    }

    @Test
    void thePeriodBoundsCountTowardsSemanticsFilterLimit() {
        String question = "Ventas de 2025 en " + String.join(", ", IntStream.range(0, 49).mapToObj(i -> "r" + i)
                .toList());
        Optional<PeriodSelection> year = Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Year(2025)));

        SemanticRequest fifty = resolved(new RequestDraft(List.of(TOTAL_SALES.id()), null, regionFilters(48), null,
                year), question(question), UTC);
        assertThat(fifty.filters()).hasSize(SemanticRequest.MAX_FILTERS);

        assertThat(RequestDraftResolver.resolve(new RequestDraft(List.of(TOTAL_SALES.id()), null, regionFilters(49),
                null, year), CATALOG, question(question), UTC))
                .isEqualTo(new DraftResolution.ValidationFailed(List.of(
                        new DraftIssue(Code.TOO_MANY_FILTERS, "filters")), List.of()));
        assertThat(resolved(new RequestDraft(List.of(TOTAL_SALES.id()), null, regionFilters(49), null, null),
                question(question), UTC).filters()).hasSize(49);
    }

    // ---- period bounds ---------------------------------------------------------------------------------------------

    @Test
    void dateBoundsUseTheSourceZoneForToday() {
        Optional<PeriodSelection> today = Optional.of(new PeriodSelection(ORDER_DATE.id(),
                new PeriodSpec.Relative(RelativePeriod.TODAY)));
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, today);

        assertThat(resolved(draft, question("Ventas de hoy"), UTC).filters()).containsExactly(
                bound(ORDER_DATE, FilterOperator.GTE, "2025-05-15"), bound(ORDER_DATE, FilterOperator.LT, "2025-05-16"));
        assertThat(resolved(draft, question("Ventas de hoy"), new TemporalContext(NOW, ZoneId.of("America/Lima")))
                .filters()).containsExactly(bound(ORDER_DATE, FilterOperator.GTE, "2025-05-14"),
                bound(ORDER_DATE, FilterOperator.LT, "2025-05-15"));
    }

    @Test
    void timestampBoundsAreLocalMidnights() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                new PeriodSelection(SHIPPED_AT.id(), new PeriodSpec.Last(7, PeriodUnit.DAYS))));

        assertThat(resolved(draft, question("Ventas de los últimos 7 días"), UTC).filters()).containsExactly(
                bound(SHIPPED_AT, FilterOperator.GTE, "2025-05-09T00:00:00"),
                bound(SHIPPED_AT, FilterOperator.LT, "2025-05-16T00:00:00"));
    }

    @Test
    void timestampWithTimeZoneBoundsUseTheDimensionZoneOverTheSourceZone() {
        TemporalContext lima = new TemporalContext(NOW, ZoneId.of("America/Lima"));
        RequestDraft madrid = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                new PeriodSelection(PAID_AT.id(), new PeriodSpec.Relative(RelativePeriod.TODAY))));
        RequestDraft source = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                new PeriodSelection(CREATED_AT.id(), new PeriodSpec.Relative(RelativePeriod.TODAY))));

        assertThat(resolved(madrid, question("Ventas de hoy"), lima).filters()).containsExactly(
                bound(PAID_AT, FilterOperator.GTE, "2025-05-15T00:00:00+02:00"),
                bound(PAID_AT, FilterOperator.LT, "2025-05-16T00:00:00+02:00"));
        assertThat(resolved(source, question("Ventas de hoy"), lima).filters()).containsExactly(
                bound(CREATED_AT, FilterOperator.GTE, "2025-05-14T00:00:00-05:00"),
                bound(CREATED_AT, FilterOperator.LT, "2025-05-15T00:00:00-05:00"));
        assertThat(resolved(source, question("Ventas de hoy"), UTC).filters()).containsExactly(
                bound(CREATED_AT, FilterOperator.GTE, "2025-05-15T00:00:00Z"),
                bound(CREATED_AT, FilterOperator.LT, "2025-05-16T00:00:00Z"));
    }

    @Test
    void boundsCrossingADaylightSavingChangeKeepTheirOwnOffsets() {
        RequestDraft march = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                new PeriodSelection(PAID_AT.id(), new PeriodSpec.Month(2025, 3))));

        assertThat(resolved(march, question("Ventas de marzo de 2025"), UTC).filters()).containsExactly(
                bound(PAID_AT, FilterOperator.GTE, "2025-03-01T00:00:00+01:00"),
                bound(PAID_AT, FilterOperator.LT, "2025-04-01T00:00:00+02:00"));
    }

    @Test
    void generatedBoundsRespectSemanticsContract() {
        for (CatalogDimension dimension : List.of(ORDER_DATE, SHIPPED_AT, PAID_AT, CREATED_AT)) {
            RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, Optional.of(
                    new PeriodSelection(dimension.id(), new PeriodSpec.Range(LocalDate.of(1900, 1, 1),
                            LocalDate.of(2100, 12, 31)))));
            SemanticRequest request = resolved(draft, question("Ventas históricas"),
                    new TemporalContext(NOW, ZoneId.of("Asia/Kolkata")));
            ValueType type = dimension.valueType().orElseThrow();

            assertThat(request.violationsAgainst(FULL)).isEmpty();
            assertThat(request.filters()).hasSize(2).allSatisfy(filter -> {
                assertThat(filter.operator().appliesTo(type)).isTrue();
                assertThat(filter.values()).singleElement().isInstanceOfSatisfying(FilterValue.Text.class, text -> {
                    assertThat(text.value().length()).isLessThanOrEqualTo(FilterValue.MAX_TEXT_LENGTH);
                    // the same parsers Semantic applies per value type
                    switch (type) {
                        case DATE -> LocalDate.parse(text.value());
                        case TIMESTAMP -> LocalDateTime.parse(text.value());
                        case TIMESTAMP_TZ -> OffsetDateTime.parse(text.value());
                        default -> throw new AssertionError(type);
                    }
                });
            });
            assertThat(request.filters()).extracting(FilterCondition::operator)
                    .containsExactly(FilterOperator.GTE, FilterOperator.LT);
        }
    }

    @Test
    void argumentsAreRequired() {
        RequestDraft draft = new RequestDraft(List.of(TOTAL_SALES.id()), null, null, null, null);
        assertThatThrownBy(() -> RequestDraftResolver.resolve(null, CATALOG, question("x"), UTC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestDraftResolver.resolve(draft, null, question("x"), UTC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestDraftResolver.resolve(draft, CATALOG, null, UTC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestDraftResolver.resolve(draft, CATALOG, question("x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- draft and resolution invariants ---------------------------------------------------------------------------

    @Test
    void draftsFollowTheStructuralRulesOfRequests() {
        assertThatThrownBy(() -> new RequestDraft(List.of(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestDraft(IntStream.range(0, 21).mapToObj(i -> id()).toList(), null, null,
                null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestDraft(List.of(TOTAL_SALES.id()), null, regionFilters(51), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestDraft(List.of(TOTAL_SALES.id(), TOTAL_SALES.id()), null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestDraft(List.of(TOTAL_SALES.id()), null, null,
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSelection(null, new PeriodSpec.Year(2025)))
                .isInstanceOf(NullPointerException.class);

        List<DefinitionId> metrics = new ArrayList<>(List.of(TOTAL_SALES.id()));
        RequestDraft draft = new RequestDraft(metrics, null, null, null, null);
        metrics.add(ORDER_COUNT.id());
        assertThat(draft.metrics()).containsExactly(TOTAL_SALES.id());
        assertThat(draft.period()).isEmpty();
        assertThatThrownBy(() -> draft.filters().add(FilterCondition.of(REGION.id(), FilterOperator.IS_NULL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolutionsKeepIssuesOfTheirOwnCategory() {
        DraftIssue defect = new DraftIssue(Code.TEXT_NOT_IN_QUESTION, "filters[0].values[0]");
        DraftIssue ambiguity = new DraftIssue(Code.NUMBER_AMBIGUOUS, "filters[0].values[0]");
        DraftIssue validation = new DraftIssue(Code.TOO_MANY_FILTERS, "filters");

        assertThat(defect.category()).isEqualTo(Category.MODEL_DEFECT);
        assertThat(ambiguity.category()).isEqualTo(Category.AMBIGUOUS);
        assertThat(validation.category()).isEqualTo(Category.VALIDATION);
        assertThatThrownBy(() -> new DraftResolution.ModelDefect(List.of(ambiguity)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftResolution.Ambiguous(List.of(defect)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftResolution.ValidationFailed(List.of(defect), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftResolution.ValidationFailed(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftResolution.ModelDefect(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DraftResolution.Resolved(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DraftIssue(Code.TOO_MANY_FILTERS, "filters = 'x'"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DraftResolution.class.getPermittedSubclasses()).hasSize(4);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static SemanticRequest resolved(RequestDraft draft, NaturalLanguageQuestion question, TemporalContext time) {
        DraftResolution resolution = RequestDraftResolver.resolve(draft, FULL, question, time);
        assertThat(resolution).isInstanceOf(DraftResolution.Resolved.class);
        return ((DraftResolution.Resolved) resolution).request();
    }

    private static NaturalLanguageQuestion question(String text) {
        return new NaturalLanguageQuestion(text, Optional.of(Language.ES), CorrelationId.generate());
    }

    private static FilterCondition bound(CatalogDimension dimension, FilterOperator operator, String value) {
        return FilterCondition.of(dimension.id(), operator, FilterValue.text(value));
    }

    private static List<FilterCondition> regionFilters(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> FilterCondition.of(REGION.id(), FilterOperator.EQ, FilterValue.text("r" + i)))
                .toList();
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
