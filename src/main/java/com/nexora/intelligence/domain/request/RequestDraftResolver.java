package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.period.DateRange;
import com.nexora.intelligence.domain.period.PeriodResolver;
import com.nexora.intelligence.domain.period.TemporalContext;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.DraftIssue.Category;
import com.nexora.intelligence.domain.request.DraftIssue.Code;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.semantic.ValueType;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic path from a {@link RequestDraft} to a {@link SemanticRequest}, in this order:
 * <ol>
 *   <li>draft validation against the catalog and the question (references, literal policy, period dimension,
 *       Semantic's filter limit including the two period bounds);</li>
 *   <li>deterministic resolution of the period into half-open bounds ({@code GTE} start, {@code LT} end), written
 *       in the dimension's value type and time zone;</li>
 *   <li>construction of the {@link SemanticRequest};</li>
 *   <li>{@link SemanticRequest#violationsAgainst(SemanticCatalog)}.</li>
 * </ol>
 * Issues are reported by category, all of the most attributable one: model defects first, then ambiguities, then
 * validation failures. Nothing here calls a model or another service.
 */
public final class RequestDraftResolver {

    /** Two filters bound a period: {@code GTE} start and {@code LT} end. */
    public static final int PERIOD_FILTERS = 2;

    private static final DateTimeFormatter LOCAL_TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter OFFSET_TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXXXX");

    private RequestDraftResolver() {
    }

    public static DraftResolution resolve(RequestDraft draft, SemanticCatalog catalog, NaturalLanguageQuestion question,
                                          TemporalContext time) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(time, "time");
        Map<DefinitionId, CatalogMetric> metrics = catalog.metricsById();
        Map<DefinitionId, CatalogDimension> dimensions = catalog.dimensionsById();
        QuestionLiterals literals = new QuestionLiterals(question.text(), question.language());
        List<DraftIssue> issues = new ArrayList<>();

        // 1. Draft validation.
        for (int i = 0; i < draft.metrics().size(); i++) {
            if (!metrics.containsKey(draft.metrics().get(i))) {
                issues.add(new DraftIssue(Code.UNKNOWN_REFERENCE, "metrics[" + i + "]"));
            }
        }
        for (int i = 0; i < draft.dimensions().size(); i++) {
            if (!dimensions.containsKey(draft.dimensions().get(i))) {
                issues.add(new DraftIssue(Code.UNKNOWN_REFERENCE, "dimensions[" + i + "]"));
            }
        }
        List<FilterCondition> filters = new ArrayList<>();
        for (int i = 0; i < draft.filters().size(); i++) {
            filter(draft.filters().get(i), "filters[" + i + "]", metrics, dimensions, literals, issues)
                    .ifPresent(filters::add);
        }
        CatalogDimension periodDimension = null;
        if (draft.period().isPresent()) {
            periodDimension = dimensions.get(draft.period().get().dimension());
            if (periodDimension == null) {
                issues.add(new DraftIssue(Code.UNKNOWN_REFERENCE, "period.dimension"));
            } else if (periodDimension.type() != DimensionType.TEMPORAL) {
                issues.add(new DraftIssue(Code.PERIOD_DIMENSION_NOT_TEMPORAL, "period.dimension"));
            } else if (periodDimension.valueType().isEmpty()) {
                issues.add(new DraftIssue(Code.PERIOD_TYPE_UNKNOWN, "period.dimension"));
            }
            if (draft.filters().size() + PERIOD_FILTERS > SemanticRequest.MAX_FILTERS) {
                issues.add(new DraftIssue(Code.TOO_MANY_FILTERS, "filters"));
            }
        }
        Optional<DraftResolution> rejected = byCategory(issues);
        if (rejected.isPresent()) {
            return rejected.get();
        }

        // 2. Deterministic period resolution.
        if (periodDimension != null) {
            filters.addAll(bounds(periodDimension, draft.period().get(), time));
        }

        // 3. SemanticRequest construction.
        SemanticRequest request;
        try {
            request = new SemanticRequest(catalog.scope(), draft.metrics(), draft.dimensions(), filters,
                    draft.timeGranularities(), List.of());
        } catch (IllegalArgumentException e) {
            return new DraftResolution.ModelDefect(List.of(new DraftIssue(Code.DUPLICATE_AFTER_NORMALIZATION,
                    "filters")));
        }

        // 4. Catalog violations.
        List<RequestViolation> violations = request.violationsAgainst(catalog);
        return violations.isEmpty() ? new DraftResolution.Resolved(request)
                : new DraftResolution.ValidationFailed(List.of(), violations);
    }

    private static Optional<FilterCondition> filter(FilterCondition filter, String field,
                                                    Map<DefinitionId, CatalogMetric> metrics,
                                                    Map<DefinitionId, CatalogDimension> dimensions,
                                                    QuestionLiterals literals, List<DraftIssue> issues) {
        CatalogDimension dimension = dimensions.get(filter.target());
        if (dimension == null && !metrics.containsKey(filter.target())) {
            issues.add(new DraftIssue(Code.UNKNOWN_REFERENCE, field + ".target"));
            return Optional.empty();
        }
        if (dimension != null && dimension.type() == DimensionType.TEMPORAL) {
            issues.add(new DraftIssue(Code.TEMPORAL_FILTER_NOT_ALLOWED, field + ".target"));
            return Optional.empty();
        }
        int before = issues.size();
        List<FilterValue> values = new ArrayList<>();
        for (int j = 0; j < filter.values().size(); j++) {
            String valueField = field + ".values[" + j + "]";
            switch (filter.values().get(j)) {
                case FilterValue.Text text -> literals.findText(text.value()).ifPresentOrElse(
                        span -> values.add(FilterValue.text(span)),
                        () -> issues.add(new DraftIssue(Code.TEXT_NOT_IN_QUESTION, valueField)));
                case FilterValue.Number number -> {
                    switch (literals.findNumber(number.value())) {
                        case FOUND -> values.add(number);
                        case AMBIGUOUS -> issues.add(new DraftIssue(Code.NUMBER_AMBIGUOUS, valueField));
                        case NOT_FOUND -> issues.add(new DraftIssue(Code.NUMBER_NOT_IN_QUESTION, valueField));
                    }
                }
                case FilterValue.Bool bool -> {
                    if (dimension != null && dimension.valueType().equals(Optional.of(ValueType.BOOLEAN))) {
                        values.add(bool);
                    } else {
                        issues.add(new DraftIssue(Code.BOOLEAN_NOT_APPLICABLE, valueField));
                    }
                }
            }
        }
        if (issues.size() > before) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FilterCondition(filter.target(), filter.operator(), values));
        } catch (IllegalArgumentException e) {
            issues.add(new DraftIssue(Code.DUPLICATE_AFTER_NORMALIZATION, field + ".values"));
            return Optional.empty();
        }
    }

    /** Half-open bounds of the period in the dimension's value type, measured in its effective time zone. */
    private static List<FilterCondition> bounds(CatalogDimension dimension, PeriodSelection period,
                                                TemporalContext time) {
        ZoneId zone = time.zoneFor(dimension.timeZone());
        DateRange range = PeriodResolver.resolve(period.spec(), time.today(zone));
        ValueType type = dimension.valueType().orElseThrow();
        return List.of(
                FilterCondition.of(dimension.id(), FilterOperator.GTE, FilterValue.text(format(range.from(), type, zone))),
                FilterCondition.of(dimension.id(), FilterOperator.LT,
                        FilterValue.text(format(range.toExclusive(), type, zone))));
    }

    /** ISO-8601 text Semantic parses for each temporal value type. */
    static String format(LocalDate date, ValueType type, ZoneId zone) {
        return switch (type) {
            case DATE -> date.toString();
            case TIMESTAMP -> LOCAL_TIMESTAMP.format(date.atStartOfDay());
            case TIMESTAMP_TZ -> OFFSET_TIMESTAMP.format(date.atStartOfDay(zone).toOffsetDateTime());
            default -> throw new IllegalStateException("not a temporal value type: " + type);
        };
    }

    private static Optional<DraftResolution> byCategory(List<DraftIssue> issues) {
        for (Category category : Category.values()) {
            List<DraftIssue> matching = issues.stream().filter(i -> i.category() == category).toList();
            if (!matching.isEmpty()) {
                return Optional.of(switch (category) {
                    case MODEL_DEFECT -> new DraftResolution.ModelDefect(matching);
                    case AMBIGUOUS -> new DraftResolution.Ambiguous(matching);
                    case VALIDATION -> new DraftResolution.ValidationFailed(matching, List.of());
                });
            }
        }
        return Optional.empty();
    }
}
