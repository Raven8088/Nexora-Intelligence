package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.PeriodUnit;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Strict conversion of the model's answer into an {@link Interpretation}. The answer must be exactly one JSON object
 * with exactly {@code intent}, {@code status}, {@code clarificationQuestion} and {@code request}; free text, code
 * fences, extra or missing fields, unknown values, aliases outside the catalog, a request where none is allowed and
 * combinations the domain forbids are all rejected. Error messages never echo the model's output.
 * <p>
 * This is the structural gate only: the deterministic rules (literals, periods, catalog violations) are applied
 * afterwards to the {@link RequestDraft}, independently of the inference engine.
 */
final class ModelOutputParser {

    private static final Set<String> TOP_LEVEL = Set.of("intent", "status", "clarificationQuestion", "request");
    private static final Set<String> REQUEST = Set.of("metrics", "dimensions", "filters", "granularities", "period");
    private static final Set<String> FILTER = Set.of("target", "operator", "values");
    private static final Set<String> GRANULARITY = Set.of("dimension", "granularity");
    private static final Set<String> PERIOD = Set.copyOf(InterpretationPrompt.PERIOD_FIELDS);
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final JsonMapper json;

    ModelOutputParser(JsonMapper json) {
        this.json = json;
    }

    Interpretation parse(String content, CatalogAliases aliases) {
        if (content == null || content.isBlank()) {
            throw invalid("empty model output");
        }
        JsonNode node;
        try {
            node = json.readTree(content.strip());
        } catch (JacksonException e) {
            throw invalid("model output is not a single JSON document");
        }
        requireObject(node, TOP_LEVEL, "model output");
        IntentType intent = enumValue(IntentType.class, node.get("intent"), "intent");
        InterpretationStatus status = enumValue(InterpretationStatus.class, node.get("status"), "status");
        JsonNode clarification = node.get("clarificationQuestion");
        if (!clarification.isNull() && !clarification.isString()) {
            throw invalid("clarificationQuestion must be a string or null");
        }
        JsonNode request = node.get("request");
        Optional<RequestDraft> draft = request.isNull() ? Optional.empty() : Optional.of(draft(request, aliases));
        try {
            return new Interpretation(intent, status,
                    clarification.isNull() ? Optional.empty() : Optional.of(clarification.stringValue()), draft);
        } catch (IllegalArgumentException e) {
            throw invalid("model output violates the interpretation rules: " + e.getMessage());
        }
    }

    private RequestDraft draft(JsonNode node, CatalogAliases aliases) {
        requireObject(node, REQUEST, "request");
        List<DefinitionId> metrics = aliasList(node.get("metrics"), "metrics", aliases::metric);
        List<DefinitionId> dimensions = aliasList(node.get("dimensions"), "dimensions", aliases::dimension);
        List<FilterCondition> filters = new ArrayList<>();
        for (JsonNode filter : array(node.get("filters"), "filters")) {
            filters.add(filter(filter, aliases));
        }
        List<GranularityChoice> granularities = new ArrayList<>();
        for (JsonNode choice : array(node.get("granularities"), "granularities")) {
            requireObject(choice, GRANULARITY, "granularity");
            granularities.add(new GranularityChoice(alias(choice.get("dimension"), "granularity dimension",
                    aliases::dimension), enumValue(TimeGranularity.class, choice.get("granularity"), "granularity")));
        }
        JsonNode period = node.get("period");
        Optional<PeriodSelection> selection = period.isNull() ? Optional.empty()
                : Optional.of(period(period, aliases));
        try {
            return new RequestDraft(metrics, dimensions, filters, granularities, selection);
        } catch (IllegalArgumentException e) {
            throw invalid("request violates the structural rules: " + e.getMessage());
        }
    }

    private FilterCondition filter(JsonNode node, CatalogAliases aliases) {
        requireObject(node, FILTER, "filter");
        DefinitionId target = alias(node.get("target"), "filter target", aliases::definition);
        FilterOperator operator = enumValue(FilterOperator.class, node.get("operator"), "operator");
        List<FilterValue> values = new ArrayList<>();
        for (JsonNode value : array(node.get("values"), "filter values")) {
            values.add(value(value));
        }
        try {
            return new FilterCondition(target, operator, values);
        } catch (IllegalArgumentException e) {
            throw invalid("filter violates the structural rules: " + e.getMessage());
        }
    }

    private static FilterValue value(JsonNode node) {
        try {
            if (node.isString()) {
                return FilterValue.text(node.stringValue());
            }
            if (node.isNumber()) {
                return FilterValue.number(node.decimalValue());
            }
            if (node.isBoolean()) {
                return FilterValue.bool(node.booleanValue());
            }
        } catch (IllegalArgumentException e) {
            throw invalid("filter value is not acceptable");
        }
        throw invalid("filter values must be strings, numbers or booleans");
    }

    private PeriodSelection period(JsonNode node, CatalogAliases aliases) {
        requireObject(node, PERIOD, "period");
        DefinitionId dimension = alias(node.get("dimension"), "period dimension", aliases::dimension);
        String kind = text(node.get("kind"), "period kind");
        Set<String> used = InterpretationPrompt.PERIOD_KIND_FIELDS.get(kind);
        if (used == null) {
            throw invalid("period kind has an unknown value");
        }
        List<String> valued = InterpretationPrompt.PERIOD_FIELDS.stream()
                .filter(field -> !field.equals("dimension") && !field.equals("kind"))
                .filter(field -> !node.get(field).isNull())
                .toList();
        if (!Set.copyOf(valued).equals(used)) {
            // only the (already validated) kind and field names: never a value of the model's output
            throw new RejectedModelOutputException("period fields do not match its kind",
                    "period kind=" + kind + " valuedFields=" + valued);
        }
        try {
            PeriodSpec spec = switch (kind) {
                case "YEAR" -> new PeriodSpec.Year(integer(node.get("year")));
                case "QUARTER" -> new PeriodSpec.Quarter(integer(node.get("year")), integer(node.get("quarter")));
                case "MONTH" -> new PeriodSpec.Month(integer(node.get("year")), integer(node.get("month")));
                case "DAY" -> new PeriodSpec.Day(date(node.get("date")));
                case "RANGE" -> new PeriodSpec.Range(date(node.get("from")), date(node.get("to")));
                case "RELATIVE" -> new PeriodSpec.Relative(enumValue(RelativePeriod.class, node.get("relative"),
                        "relative period"));
                default -> new PeriodSpec.Last(integer(node.get("count")),
                        enumValue(PeriodUnit.class, node.get("unit"), "period unit"));
            };
            return new PeriodSelection(dimension, spec);
        } catch (IllegalArgumentException e) {
            throw invalid("period is not acceptable");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void requireObject(JsonNode node, Set<String> fields, String what) {
        if (node == null || !node.isObject()) {
            throw invalid(what + " is not a JSON object");
        }
        if (node.size() != fields.size() || !fields.stream().allMatch(node::has)) {
            throw invalid(what + " must have exactly the fields " + fields.stream().sorted().toList());
        }
    }

    private static List<JsonNode> array(JsonNode node, String what) {
        if (node == null || !node.isArray()) {
            throw invalid(what + " must be an array");
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    private static List<DefinitionId> aliasList(JsonNode node, String what,
                                                Function<String, Optional<DefinitionId>> resolver) {
        List<DefinitionId> ids = new ArrayList<>();
        for (JsonNode alias : array(node, what)) {
            ids.add(alias(alias, what, resolver));
        }
        return ids;
    }

    private static DefinitionId alias(JsonNode node, String what, Function<String, Optional<DefinitionId>> resolver) {
        return resolver.apply(text(node, what)).orElseThrow(() -> invalid(what + " is not an alias of the catalog"));
    }

    private static String text(JsonNode node, String what) {
        if (node == null || !node.isString()) {
            throw invalid(what + " must be a string");
        }
        return node.stringValue();
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid("period numbers must be integers");
        }
        return node.intValue();
    }

    private static LocalDate date(JsonNode node) {
        String value = text(node, "period date");
        if (!ISO_DATE.matcher(value).matches()) {
            throw invalid("period dates must be YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid("period date is not a calendar date");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode value, String field) {
        String name = text(value, field);
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw invalid(field + " has an unknown value");
    }

    /** Every rejection of the content; the message is fixed text, never the model's output. */
    private static RejectedModelOutputException invalid(String message) {
        return new RejectedModelOutputException(message);
    }
}
