package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes interpretations the way a well-behaved model would answer them, with the catalog's aliases. */
final class ModelAnswers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ModelAnswers() {
    }

    static String answer(Interpretation interpretation, CatalogAliases aliases) {
        ObjectNode root = JSON.createObjectNode();
        root.put("intent", interpretation.intent().name());
        root.put("status", interpretation.status().name());
        if (interpretation.clarificationQuestion().isPresent()) {
            root.put("clarificationQuestion", interpretation.clarificationQuestion().get());
        } else {
            root.putNull("clarificationQuestion");
        }
        if (interpretation.request().isPresent()) {
            root.set("request", request(interpretation.request().get(), aliases));
        } else {
            root.putNull("request");
        }
        return JSON.writeValueAsString(root);
    }

    static ObjectNode request(RequestDraft draft, CatalogAliases aliases) {
        ObjectNode request = JSON.createObjectNode();
        ArrayNode metrics = request.putArray("metrics");
        draft.metrics().forEach(id -> metrics.add(alias(id, aliases)));
        ArrayNode dimensions = request.putArray("dimensions");
        draft.dimensions().forEach(id -> dimensions.add(alias(id, aliases)));
        ArrayNode filters = request.putArray("filters");
        for (FilterCondition filter : draft.filters()) {
            ObjectNode node = filters.addObject();
            node.put("target", alias(filter.target(), aliases));
            node.put("operator", filter.operator().name());
            ArrayNode values = node.putArray("values");
            for (FilterValue value : filter.values()) {
                switch (value) {
                    case FilterValue.Text t -> values.add(t.value());
                    case FilterValue.Number n -> values.add(n.value());
                    case FilterValue.Bool b -> values.add(b.value());
                }
            }
        }
        ArrayNode granularities = request.putArray("granularities");
        for (GranularityChoice choice : draft.timeGranularities()) {
            granularities.addObject().put("dimension", alias(choice.dimension(), aliases))
                    .put("granularity", choice.granularity().name());
        }
        if (draft.period().isPresent()) {
            request.set("period", period(draft.period().get(), aliases));
        } else {
            request.putNull("period");
        }
        return request;
    }

    static ObjectNode period(PeriodSelection selection, CatalogAliases aliases) {
        ObjectNode node = JSON.createObjectNode();
        node.put("dimension", alias(selection.dimension(), aliases));
        InterpretationPrompt.PERIOD_FIELDS.stream().skip(1).forEach(node::putNull);
        switch (selection.spec()) {
            case PeriodSpec.Year y -> node.put("kind", "YEAR").put("year", y.year());
            case PeriodSpec.Quarter q -> node.put("kind", "QUARTER").put("year", q.year()).put("quarter", q.quarter());
            case PeriodSpec.Month m -> node.put("kind", "MONTH").put("year", m.year()).put("month", m.month());
            case PeriodSpec.Day d -> node.put("kind", "DAY").put("date", d.date().toString());
            case PeriodSpec.Range r -> node.put("kind", "RANGE").put("from", r.first().toString())
                    .put("to", r.last().toString());
            case PeriodSpec.Relative r -> node.put("kind", "RELATIVE").put("relative", r.period().name());
            case PeriodSpec.Last l -> node.put("kind", "LAST").put("count", l.count()).put("unit", l.unit().name());
        }
        return node;
    }

    private static String alias(DefinitionId id, CatalogAliases aliases) {
        return aliases.aliasOf(id).orElseThrow();
    }
}
