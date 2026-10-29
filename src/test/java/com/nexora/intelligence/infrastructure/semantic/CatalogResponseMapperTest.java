package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.semantic.ValueType;
import com.nexora.intelligence.domain.shared.DataSourceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mapping of Semantic's catalog items: what is kept, excluded, or rejected as a contract violation. */
class CatalogResponseMapperTest {

    private static SemanticFixture semantic;
    private final CatalogResponseMapper mapper = new CatalogResponseMapper(SemanticFixture.JSON);

    @BeforeAll
    static void setUp() throws Exception {
        semantic = new SemanticFixture();
    }

    @AfterAll
    static void tearDown() {
        semantic.close();
    }

    private DataSourceId source() {
        return new DataSourceId(semantic.dataSource);
    }

    private Optional<CatalogDimension> dimension(String type, String valueType, List<String> granularities,
                                                 String zone) {
        return mapper.dimension(semantic.dimension(UUID.randomUUID(), "dim", type, valueType, granularities, zone,
                true), source());
    }

    private static void contractViolation(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SemanticUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NUMERIC", "TEXT", "BOOLEAN", "UUID", "TIME", "OTHER"})
    void categoricalValueTypesAreMappedOneToOne(String valueType) {
        assertThat(dimension("CATEGORICAL", valueType, List.of(), null)).get()
                .satisfies(d -> assertThat(d.valueType()).contains(ValueType.valueOf(valueType)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATE", "TIMESTAMP", "TIMESTAMP_TZ"})
    void temporalValueTypesAreMappedOneToOne(String valueType) {
        assertThat(dimension("TEMPORAL", valueType, List.of("DAY"), null)).get()
                .satisfies(d -> assertThat(d.valueType()).contains(ValueType.valueOf(valueType)));
    }

    @Test
    void anAbsentOrUnknownValueTypeIsKeptAsUnknown() {
        assertThat(dimension("CATEGORICAL", null, List.of(), null)).get()
                .satisfies(d -> assertThat(d.valueType()).isEmpty());
        assertThat(dimension("CATEGORICAL", "GEOGRAPHY", List.of(), null)).get()
                .satisfies(d -> assertThat(d.valueType()).isEmpty());
    }

    @Test
    void unknownGranularitiesAreDroppedAndATemporalDimensionWithoutKnownOnesIsExcluded() {
        assertThat(dimension("TEMPORAL", "DATE", List.of("DECADE", "MONTH"), null)).get()
                .satisfies(d -> assertThat(d.granularities()).containsExactly(TimeGranularity.MONTH));
        assertThat(dimension("TEMPORAL", "DATE", List.of("DECADE"), null)).isEmpty();
        assertThat(dimension("TEMPORAL", "DATE", List.of(), null)).isEmpty();
    }

    @Test
    void definitionsIntelligenceCannotRepresentSafelyAreExcluded() {
        assertThat(dimension("SPATIAL", "TEXT", List.of(), null)).isEmpty();
        assertThat(dimension("TEMPORAL", "TEXT", List.of("DAY"), null)).isEmpty();
        assertThat(dimension("TEMPORAL", "DATE", List.of("DAY"), "Mars/Olympus")).isEmpty();
        assertThat(dimension("TEMPORAL", "DATE", List.of("DAY"), "UTC")).isEmpty();
        assertThat(mapper.metric(semantic.metric(UUID.randomUUID(), "ventas", "MEDIAN", true), source())).isEmpty();
        assertThat(mapper.metric(semantic.metric(UUID.randomUUID(), "Ventas Totales", "SUM", true), source()))
                .isEmpty();
        assertThat(mapper.metric(SemanticFixture.with(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true),
                n -> n.put("name", "línea\nrota")), source())).isEmpty();
        assertThat(mapper.relationship(SemanticFixture.with(semantic.relationship(UUID.randomUUID(), "x", true),
                n -> n.remove("name")), source())).isEmpty();
    }

    @Test
    void timeZonesOfTimestampsWithTimeZoneAreKept() {
        assertThat(dimension("TEMPORAL", "TIMESTAMP_TZ", List.of("DAY"), "UTC")).get()
                .satisfies(d -> assertThat(d.timeZone()).contains(java.time.ZoneId.of("UTC")));
    }

    @Test
    void notUsableItemsAreExcluded() {
        assertThat(mapper.metric(semantic.metric(UUID.randomUUID(), "ventas", "SUM", false), source())).isEmpty();
        assertThat(mapper.dimension(semantic.dimension(UUID.randomUUID(), "region", "CATEGORICAL", "TEXT", List.of(),
                null, false), source())).isEmpty();
        assertThat(mapper.relationship(semantic.relationship(UUID.randomUUID(), "r", false), source())).isEmpty();
    }

    @Test
    void itemsOutsideTheContractFailClosed() {
        List<Consumer<ObjectNode>> breaks = List.of(
                n -> n.remove("id"),
                n -> n.put("id", "not-a-uuid"),
                n -> n.put("id", "1-1-1-1-1"),
                n -> n.put("dataSourceId", UUID.randomUUID().toString()),
                n -> n.remove("dataSourceId"),
                n -> n.put("kind", "DIMENSION"),
                n -> n.put("status", "DRAFT"),
                n -> n.put("status", "INACTIVE"),
                n -> n.remove("usable"),
                n -> n.put("usable", "true"),
                n -> n.remove("metric"));
        for (Consumer<ObjectNode> change : breaks) {
            ObjectNode item = SemanticFixture.with(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true), change);
            contractViolation(() -> mapper.metric(item, source()));
        }
        contractViolation(() -> mapper.dimension(SemanticFixture.with(semantic.dimension(UUID.randomUUID(), "d",
                "CATEGORICAL", "TEXT", List.of(), null, true), n -> n.remove("dimension")), source()));
        contractViolation(() -> mapper.dimension(SemanticFixture.with(semantic.dimension(UUID.randomUUID(), "d",
                "CATEGORICAL", "TEXT", List.of(), null, true),
                n -> ((ObjectNode) n.get("dimension")).put("granularities", "DAY")), source()));
        contractViolation(() -> mapper.relationship(SemanticFixture.with(semantic.relationship(UUID.randomUUID(),
                "r", true), n -> n.put("status", "DRAFT")), source()));
        contractViolation(() -> mapper.id(SemanticFixture.JSON.getNodeFactory().stringNode("x")));
    }

    @Test
    void pagesOutsideTheContractFailClosed() {
        List<String> bodies = List.of("", "not json", "[]", "{\"items\":[]}",
                "{\"items\":{},\"page\":0,\"size\":200,\"totalItems\":0}",
                "{\"items\":[],\"page\":1,\"size\":200,\"totalItems\":0}",
                "{\"items\":[],\"page\":0,\"size\":50,\"totalItems\":0}",
                "{\"items\":[],\"page\":0,\"size\":200,\"totalItems\":-1}",
                "{\"items\":[],\"page\":0,\"size\":200,\"totalItems\":0} {}",
                "{\"items\":[],\"items\":[],\"page\":0,\"size\":200,\"totalItems\":0}");
        for (String body : bodies) {
            contractViolation(() -> mapper.page(body, 0, 200));
        }
        assertThat(mapper.page("{\"items\":[],\"page\":0,\"size\":200,\"totalItems\":0,\"extra\":1}", 0, 200)
                .totalItems()).isZero();
    }

    @Test
    void physicalReferencesNeverReachTheDomain() {
        CatalogDimension dimension = dimension("CATEGORICAL", "TEXT", List.of(), null).orElseThrow();
        assertThat(dimension.toString()).doesNotContain("sales").doesNotContain("orders").doesNotContain("column");
        assertThat(mapper.metric(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true), source())
                .orElseThrow().toString()).doesNotContain("sales").doesNotContain("total\"");
        assertThat(mapper.relationship(semantic.relationship(UUID.randomUUID(), "Pedido del cliente", true),
                source()).orElseThrow().toString()).doesNotContain("customers");
    }
}
