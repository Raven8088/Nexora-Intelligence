package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** A simulated Semantic with its token and JWKS endpoints, and the real adapters configured against it. */
final class SemanticFixture implements AutoCloseable {

    static final String SECRET = "s3cr3t-value-never-logged";
    static final JsonMapper JSON = SemanticConfiguration.json();

    final UUID tenant = UUID.randomUUID();
    final UUID dataSource = UUID.randomUUID();
    final SemanticScope scope = new SemanticScope(new TenantId(tenant), new DataSourceId(dataSource));
    final CorrelationId correlationId = CorrelationId.generate();
    final FakeSemanticServer server;
    final TestTokens tokens = new TestTokens();
    final SemanticProperties properties;
    final InterpretationTokenProvider tokenProvider;
    final SemanticApi api;

    SemanticFixture() throws IOException {
        this(0, Duration.ofSeconds(5));
    }

    SemanticFixture(int maxRetries, Duration readTimeout) throws IOException {
        server = new FakeSemanticServer();
        server.handle(FakeSemanticServer.JWKS_PATH, r -> FakeSemanticServer.Answer.of(200, tokens.jwks()));
        server.handle(FakeSemanticServer.TOKEN_PATH,
                r -> FakeSemanticServer.Answer.of(200, tokens.tokenResponse(tokens.token(tenant))));
        properties = new SemanticProperties(server.baseUrl(), Duration.ofSeconds(2), readTimeout, 4 * 1024 * 1024,
                maxRetries, identity(tenant));
        SemanticHttp http = new SemanticHttp(properties.connectTimeout(), properties.readTimeout(),
                properties.maxResponseBytes());
        tokenProvider = new InterpretationTokenProvider(properties.identity(), http,
                SemanticConfiguration.jwtDecoder(properties), Clock.systemUTC());
        api = new SemanticApi(http, tokenProvider, properties.baseUrl(), maxRetries, JSON);
    }

    SemanticProperties.Identity identity(UUID identityTenant) {
        return new SemanticProperties.Identity(identityTenant, server.tokenUri(), server.jwksUri(), TestTokens.ISSUER,
                TestTokens.AUDIENCE, "nexora-intelligence", SECRET, List.of("RS256"), "tenant", "principal_type",
                "roles", "REPORTS_CLIENT", Duration.ofSeconds(30));
    }

    HttpSemanticCatalogClient catalogClient() {
        return new HttpSemanticCatalogClient(api, new CatalogResponseMapper(JSON));
    }

    HttpLogicalPlanClient planClient() {
        return new HttpLogicalPlanClient(api, new PlanRequestMapper(JSON), JSON);
    }

    String path(String suffix) {
        return "/api/v1/data-sources/" + dataSource + "/" + suffix;
    }

    // ---- JSON of Semantic responses -------------------------------------------------------------------------------

    ObjectNode metric(UUID id, String key, String aggregation, boolean usable) {
        ObjectNode node = definition(id, "METRIC", key, usable);
        ObjectNode block = node.putObject("metric");
        block.put("conceptId", UUID.randomUUID().toString());
        block.put("aggregation", aggregation);
        block.put("columnId", UUID.randomUUID().toString());
        block.put("columnNaturalKey", "\"sales\".\"orders\".\"total\"");
        return node;
    }

    ObjectNode dimension(UUID id, String key, String type, String valueType, List<String> granularities,
                         String timeZone, boolean usable) {
        ObjectNode node = definition(id, "DIMENSION", key, usable);
        ObjectNode block = node.putObject("dimension");
        block.put("conceptId", UUID.randomUUID().toString());
        block.put("columnId", UUID.randomUUID().toString());
        block.put("columnNaturalKey", "\"sales\".\"orders\".\"" + key + "\"");
        block.put("type", type);
        if (valueType != null) {
            block.put("valueType", valueType);
        }
        ArrayNode list = block.putArray("granularities");
        granularities.forEach(list::add);
        if (timeZone != null) {
            block.put("timeZone", timeZone);
        }
        return node;
    }

    ObjectNode relationship(UUID id, String name, boolean usable) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id.toString());
        node.put("dataSourceId", dataSource.toString());
        node.put("name", name);
        node.put("origin", "DECLARED_FK");
        node.put("status", "APPROVED");
        node.put("physicallyValid", usable);
        node.put("usable", usable);
        node.putObject("source").put("objectId", UUID.randomUUID().toString()).put("naturalKey", "\"sales\".\"orders\"");
        node.putObject("target").put("objectId", UUID.randomUUID().toString())
                .put("naturalKey", "\"sales\".\"customers\"");
        node.put("version", 1);
        return node;
    }

    private ObjectNode definition(UUID id, String kind, String key, boolean usable) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id.toString());
        node.put("dataSourceId", dataSource.toString());
        node.put("kind", kind);
        node.put("key", key);
        node.put("name", "Nombre " + key);
        node.put("description", "Descripción de " + key);
        node.put("status", "APPROVED");
        node.put("origin", "MANUAL");
        node.put("usable", usable);
        node.putArray("issues");
        node.put("createdBy", "editor");
        node.put("createdAt", "2026-10-20T08:00:00Z");
        node.put("version", 1);
        return node;
    }

    static String page(List<ObjectNode> items, int page, int size, long total) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode array = root.putArray("items");
        items.forEach(array::add);
        root.put("page", page);
        root.put("size", size);
        root.put("totalItems", total);
        return JSON.writeValueAsString(root);
    }

    static String page(List<ObjectNode> items) {
        return page(items, 0, HttpSemanticCatalogClient.PAGE_SIZE, items.size());
    }

    /** Answers the three listings of a catalog, each in a single page. */
    SemanticFixture catalog(List<ObjectNode> metrics, List<ObjectNode> dimensions, List<ObjectNode> relationships) {
        server.answer(path("definitions"), 200, page(metrics));
        server.answer(path("definitions"), 200, page(dimensions));
        server.answer(path("relationships"), 200, page(relationships));
        return this;
    }

    static ObjectNode with(ObjectNode node, Consumer<ObjectNode> change) {
        change.accept(node);
        return node;
    }

    @Override
    public void close() {
        server.close();
    }
}
