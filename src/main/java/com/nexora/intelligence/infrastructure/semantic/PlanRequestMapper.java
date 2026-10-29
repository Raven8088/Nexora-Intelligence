package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.SemanticRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/**
 * Writes a {@link SemanticRequest} as Semantic's {@code PlanRequestBody}, with exactly its properties (Semantic rejects
 * unknown ones): {@code metrics}, {@code dimensions}, {@code filters[{definitionId, operator, value | values}]},
 * {@code preferredRelationships} and {@code timeGranularities[{dimensionId, granularity}]}.
 * <p>
 * A filter carries no value for IS_NULL / IS_NOT_NULL, {@code value} for comparisons and {@code values} for IN. Text is
 * a JSON string (never an expression; dates as ISO-8601 text), numbers are plain JSON numbers and booleans JSON
 * booleans. Nothing else is added: no SQL, no table or column, no route.
 */
final class PlanRequestMapper {

    private final JsonMapper json;

    PlanRequestMapper(JsonMapper json) {
        this.json = json;
    }

    String body(SemanticRequest request) {
        ObjectNode root = json.createObjectNode();
        ArrayNode metrics = root.putArray("metrics");
        request.metrics().forEach(id -> metrics.add(id.toString()));
        ArrayNode dimensions = root.putArray("dimensions");
        request.dimensions().forEach(id -> dimensions.add(id.toString()));
        ArrayNode filters = root.putArray("filters");
        for (FilterCondition filter : request.filters()) {
            ObjectNode node = filters.addObject();
            node.put("definitionId", filter.target().toString());
            node.put("operator", filter.operator().name());
            switch (filter.operator().arity()) {
                case NONE -> {
                }
                case ONE -> node.set("value", value(filter.values().get(0)));
                case MANY -> {
                    ArrayNode values = node.putArray("values");
                    filter.values().forEach(v -> values.add(value(v)));
                }
            }
        }
        ArrayNode preferred = root.putArray("preferredRelationships");
        request.preferredRelationships().forEach(id -> preferred.add(id.toString()));
        ArrayNode granularities = root.putArray("timeGranularities");
        for (GranularityChoice choice : request.timeGranularities()) {
            granularities.addObject().put("dimensionId", choice.dimension().toString())
                    .put("granularity", choice.granularity().name());
        }
        return json.writeValueAsString(root);
    }

    private JsonNode value(FilterValue value) {
        return switch (value) {
            case FilterValue.Text t -> json.getNodeFactory().stringNode(t.value());
            case FilterValue.Number n -> json.getNodeFactory().numberNode(plain(n.value()));
            case FilterValue.Bool b -> json.getNodeFactory().booleanNode(b.value());
        };
    }

    /** Same number without exponent notation (1E+3 is sent as 1000). */
    static BigDecimal plain(BigDecimal value) {
        return new BigDecimal(value.toPlainString());
    }
}
