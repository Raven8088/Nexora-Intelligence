package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.semantic.Aggregation;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.semantic.ValueType;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the catalog from a simulated Semantic: requests, pagination, filtering, limits and consistency. */
class HttpSemanticCatalogClientTest {

    private SemanticFixture semantic;

    @BeforeEach
    void setUp() throws Exception {
        semantic = new SemanticFixture();
    }

    @AfterEach
    void tearDown() {
        semantic.close();
    }

    @Test
    void readsOnlyUsableApprovedDefinitionsWithTheirValueTypes() {
        UUID sales = UUID.randomUUID();
        UUID date = UUID.randomUUID();
        UUID region = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        UUID relation = UUID.randomUUID();
        semantic.catalog(
                List.of(semantic.metric(sales, "ventas", "SUM", true),
                        semantic.metric(UUID.randomUUID(), "rota", "COUNT", false)),
                List.of(semantic.dimension(date, "fecha", "TEMPORAL", "DATE", List.of("DAY", "MONTH", "YEAR"), null,
                                true),
                        semantic.dimension(region, "region", "CATEGORICAL", "TEXT", List.of(), null, true),
                        semantic.dimension(created, "alta", "TEMPORAL", "TIMESTAMP_TZ", List.of("DAY"),
                                "America/Mexico_City", true),
                        semantic.dimension(UUID.randomUUID(), "vieja", "CATEGORICAL", "TEXT", List.of(), null, false)),
                List.of(semantic.relationship(relation, "Pedido del cliente", true),
                        semantic.relationship(UUID.randomUUID(), "Rota", false)));

        SemanticCatalog catalog = semantic.catalogClient().load(semantic.scope, semantic.correlationId);

        assertThat(catalog.scope()).isEqualTo(semantic.scope);
        assertThat(catalog.metrics()).singleElement().satisfies(m -> {
            assertThat(m.id().value()).isEqualTo(sales);
            assertThat(m.key().value()).isEqualTo("ventas");
            assertThat(m.name()).isEqualTo("Nombre ventas");
            assertThat(m.description()).contains("Descripción de ventas");
            assertThat(m.aggregation()).isEqualTo(Aggregation.SUM);
        });
        assertThat(catalog.dimensions()).extracting(d -> d.id().value()).containsExactly(date, region, created);
        CatalogDimension fecha = catalog.dimensions().get(0);
        assertThat(fecha.type()).isEqualTo(DimensionType.TEMPORAL);
        assertThat(fecha.valueType()).contains(ValueType.DATE);
        assertThat(fecha.granularities()).containsExactlyInAnyOrder(TimeGranularity.DAY, TimeGranularity.MONTH,
                TimeGranularity.YEAR);
        assertThat(fecha.timeZone()).isEmpty();
        assertThat(catalog.dimensions().get(1).valueType()).contains(ValueType.TEXT);
        assertThat(catalog.dimensions().get(2).valueType()).contains(ValueType.TIMESTAMP_TZ);
        assertThat(catalog.dimensions().get(2).timeZone()).contains(ZoneId.of("America/Mexico_City"));
        assertThat(catalog.relationships()).singleElement().satisfies(r -> {
            assertThat(r.id().value()).isEqualTo(relation);
            assertThat(r.name()).isEqualTo("Pedido del cliente");
        });
    }

    @Test
    void sendsExactlyThreeAuthenticatedRequestsWithTheApprovedFilterAndMaximumPageSize() {
        semantic.catalog(List.of(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true)), List.of(), List.of());

        semantic.catalogClient().load(semantic.scope, semantic.correlationId);

        List<FakeSemanticServer.Received> api = semantic.server.received("/api/");
        assertThat(api).extracting(FakeSemanticServer.Received::pathAndQuery).containsExactly(
                semantic.path("definitions?kind=METRIC&status=APPROVED&page=0&size=200"),
                semantic.path("definitions?kind=DIMENSION&status=APPROVED&page=0&size=200"),
                semantic.path("relationships?status=APPROVED&page=0&size=200"));
        assertThat(api).allSatisfy(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.header("Authorization")).startsWith("Bearer ey");
        });
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(1);
        assertThat(semantic.server.received("/api/v1/data-sources/" + semantic.dataSource + "/objects")).isEmpty();
    }

    @Test
    void everyLoadReadsTheCatalogAgain() {
        semantic.catalog(List.of(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true)), List.of(), List.of());
        semantic.catalog(List.of(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true),
                semantic.metric(UUID.randomUUID(), "pedidos", "COUNT", true)), List.of(), List.of());
        HttpSemanticCatalogClient client = semantic.catalogClient();

        assertThat(client.load(semantic.scope, semantic.correlationId).metrics()).hasSize(1);
        assertThat(client.load(semantic.scope, semantic.correlationId).metrics()).hasSize(2);
        assertThat(semantic.server.received("/api/")).hasSize(6);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(1);
    }

    @Test
    void readsEveryPageAndKeepsOnlyUsableItems() {
        List<ObjectNode> all = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            all.add(semantic.metric(UUID.randomUUID(), String.format("m%03d", i), "COUNT", i % 3 == 0));
        }
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(0, 200), 0, 200, 450));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(200, 400), 1, 200, 450));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(400, 450), 2, 200, 450));
        semantic.server.answer(semantic.path("definitions"), 200, SemanticFixture.page(List.of()));
        semantic.server.answer(semantic.path("relationships"), 200, SemanticFixture.page(List.of()));

        SemanticCatalog catalog = semantic.catalogClient().load(semantic.scope, semantic.correlationId);

        assertThat(catalog.metrics()).hasSize(150);
        assertThat(semantic.server.received(semantic.path("definitions")))
                .extracting(FakeSemanticServer.Received::query)
                .startsWith("kind=METRIC&status=APPROVED&page=0&size=200",
                        "kind=METRIC&status=APPROVED&page=1&size=200", "kind=METRIC&status=APPROVED&page=2&size=200");
    }

    @Test
    void moreUsableItemsThanTheLimitAreRejectedNotTruncated() {
        List<ObjectNode> all = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            all.add(semantic.metric(UUID.randomUUID(), String.format("m%03d", i), "COUNT", true));
        }
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(0, 200), 0, 200, 201));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(200, 201), 1, 200, 201));
        semantic.server.answer(semantic.path("definitions"), 200, SemanticFixture.page(List.of()));
        semantic.server.answer(semantic.path("relationships"), 200, SemanticFixture.page(List.of()));

        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CATALOG_TOO_LARGE));
    }

    @Test
    void listingsBeyondTheMaximumNumberOfPagesAreRejectedBeforeReadingThem() {
        semantic.server.answer(semantic.path("definitions"), 200, SemanticFixture.page(List.of(), 0, 200, 1001));

        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CATALOG_TOO_LARGE));
        assertThat(semantic.server.received("/api/")).hasSize(1);
    }

    @Test
    void aListingThatChangesWhileReadIsReadAgainOnce() {
        List<ObjectNode> all = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            all.add(semantic.metric(UUID.randomUUID(), String.format("m%03d", i), "COUNT", i == 0));
        }
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(0, 200), 0, 200, 201));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(199, 201), 1, 200, 202));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(0, 200), 0, 200, 201));
        semantic.server.answer(semantic.path("definitions"), 200,
                SemanticFixture.page(all.subList(200, 201), 1, 200, 201));
        semantic.server.answer(semantic.path("definitions"), 200, SemanticFixture.page(List.of()));
        semantic.server.answer(semantic.path("relationships"), 200, SemanticFixture.page(List.of()));

        assertThat(semantic.catalogClient().load(semantic.scope, semantic.correlationId).metrics()).hasSize(1);
        assertThat(semantic.server.received(semantic.path("definitions"))).hasSize(5);
    }

    @Test
    void aListingThatKeepsChangingFailsAsInconsistent() {
        List<ObjectNode> all = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            all.add(semantic.metric(UUID.randomUUID(), String.format("m%03d", i), "COUNT", true));
        }
        for (int reading = 0; reading < 2; reading++) {
            semantic.server.answer(semantic.path("definitions"), 200,
                    SemanticFixture.page(all.subList(0, 200), 0, 200, 201));
            // the item moved to page 1 is repeated: an insertion shifted the listing
            semantic.server.answer(semantic.path("definitions"), 200,
                    SemanticFixture.page(all.subList(199, 200), 1, 200, 201));
        }

        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CATALOG_INCONSISTENT));
    }

    @Test
    void anEmptyCatalogIsReturnedAsSuch() {
        semantic.catalog(List.of(), List.of(), List.of());

        SemanticCatalog catalog = semantic.catalogClient().load(semantic.scope, semantic.correlationId);

        assertThat(catalog.metrics()).isEmpty();
        assertThat(catalog.dimensions()).isEmpty();
        assertThat(catalog.relationships()).isEmpty();
    }

    @Test
    void itemsOfAnotherDataSourceFailClosed() {
        ObjectNode foreign = semantic.metric(UUID.randomUUID(), "ventas", "SUM", true);
        foreign.put("dataSourceId", UUID.randomUUID().toString());
        semantic.catalog(List.of(foreign), List.of(), List.of());

        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    @Test
    void duplicateKeysAmongUsableDefinitionsFailClosed() {
        semantic.catalog(List.of(semantic.metric(UUID.randomUUID(), "ventas", "SUM", true),
                semantic.metric(UUID.randomUUID(), "ventas", "COUNT", true)), List.of(), List.of());

        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    @Test
    void aScopeOfAnotherTenantNeverReachesSemantic() {
        SemanticScope other = new SemanticScope(new TenantId(UUID.randomUUID()),
                new DataSourceId(semantic.dataSource));

        assertThatThrownBy(() -> semantic.catalogClient().load(other, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.IDENTITY_REJECTED));
        assertThat(semantic.server.received()).isEmpty();
    }
}
