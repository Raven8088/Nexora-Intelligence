package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.RelationshipId;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON sent to Semantic has exactly the properties of its PlanRequestBody. */
class PlanRequestMapperTest {

    private static final SemanticScope SCOPE = new SemanticScope(new TenantId(UUID.randomUUID()),
            new DataSourceId(UUID.randomUUID()));
    private final PlanRequestMapper mapper = new PlanRequestMapper(SemanticFixture.JSON);

    private static DefinitionId id() {
        return new DefinitionId(UUID.randomUUID());
    }

    private JsonNode json(SemanticRequest request) {
        return SemanticFixture.JSON.readTree(mapper.body(request));
    }

    @Test
    void mapsEveryPartOfTheRequest() {
        DefinitionId sales = id();
        DefinitionId month = id();
        DefinitionId region = id();
        DefinitionId paid = id();
        DefinitionId age = id();
        RelationshipId relationship = new RelationshipId(UUID.randomUUID());
        SemanticRequest request = new SemanticRequest(SCOPE, List.of(sales), List.of(month, region), List.of(
                FilterCondition.of(region, FilterOperator.IN, FilterValue.text("Lima"), FilterValue.text("Cusco")),
                FilterCondition.of(paid, FilterOperator.EQ, FilterValue.bool(true)),
                FilterCondition.of(age, FilterOperator.GTE, FilterValue.number(new BigDecimal("1E+3"))),
                FilterCondition.of(month, FilterOperator.GTE, FilterValue.text("2026-10-01")),
                FilterCondition.of(region, FilterOperator.IS_NOT_NULL),
                FilterCondition.of(sales, FilterOperator.GT, FilterValue.number(new BigDecimal("1500.50")))),
                List.of(new GranularityChoice(month, TimeGranularity.MONTH)), List.of(relationship));

        JsonNode body = json(request);

        assertThat(body.propertyNames()).containsExactly("metrics", "dimensions", "filters",
                "preferredRelationships", "timeGranularities");
        assertThat(body.get("metrics").toString()).isEqualTo("[\"" + sales + "\"]");
        assertThat(body.get("dimensions").toString()).isEqualTo("[\"" + month + "\",\"" + region + "\"]");
        JsonNode filters = body.get("filters");
        assertThat(filters.get(0).toString()).isEqualTo("{\"definitionId\":\"" + region
                + "\",\"operator\":\"IN\",\"values\":[\"Lima\",\"Cusco\"]}");
        assertThat(filters.get(1).toString()).isEqualTo("{\"definitionId\":\"" + paid
                + "\",\"operator\":\"EQ\",\"value\":true}");
        assertThat(filters.get(2).toString()).isEqualTo("{\"definitionId\":\"" + age
                + "\",\"operator\":\"GTE\",\"value\":1000}");
        assertThat(filters.get(3).get("value").asString()).isEqualTo("2026-10-01");
        assertThat(filters.get(4).toString()).isEqualTo("{\"definitionId\":\"" + region
                + "\",\"operator\":\"IS_NOT_NULL\"}");
        assertThat(filters.get(5).get("value").isNumber()).isTrue();
        assertThat(filters.get(5).get("value").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(body.get("preferredRelationships").toString()).isEqualTo("[\"" + relationship + "\"]");
        assertThat(body.get("timeGranularities").toString()).isEqualTo("[{\"dimensionId\":\"" + month
                + "\",\"granularity\":\"MONTH\"}]");
    }

    @Test
    void emptyPartsAreSentAsEmptyLists() {
        JsonNode body = json(new SemanticRequest(SCOPE, List.of(id()), List.of(), List.of(), List.of(), List.of()));
        assertThat(body.get("dimensions").isEmpty()).isTrue();
        assertThat(body.get("filters").isEmpty()).isTrue();
        assertThat(body.get("preferredRelationships").isEmpty()).isTrue();
        assertThat(body.get("timeGranularities").isEmpty()).isTrue();
    }

    @Test
    void textIsAlwaysAJsonStringNeverAnExpression() {
        DefinitionId region = id();
        String hostile = "Lima' OR 1=1; DROP TABLE orders; --";
        JsonNode body = json(new SemanticRequest(SCOPE, List.of(id()), List.of(region), List.of(
                FilterCondition.of(region, FilterOperator.EQ, FilterValue.text(hostile))), List.of(), List.of()));
        assertThat(body.get("filters").get(0).get("value").isString()).isTrue();
        assertThat(body.get("filters").get(0).get("value").asString()).isEqualTo(hostile);
        assertThat(mapper.body(new SemanticRequest(SCOPE, List.of(id()), List.of(), List.of(), List.of(), List.of())))
                .doesNotContainIgnoringCase("select").doesNotContainIgnoringCase("table");
    }

    @Test
    void largeRequestsKeepTheirOrder() {
        List<DefinitionId> metrics = new ArrayList<>();
        for (int i = 0; i < SemanticRequest.MAX_METRICS; i++) {
            metrics.add(id());
        }
        JsonNode body = json(new SemanticRequest(SCOPE, metrics, List.of(), List.of(), List.of(), List.of()));
        for (int i = 0; i < metrics.size(); i++) {
            assertThat(body.get("metrics").get(i).asString()).isEqualTo(metrics.get(i).toString());
        }
    }
}
