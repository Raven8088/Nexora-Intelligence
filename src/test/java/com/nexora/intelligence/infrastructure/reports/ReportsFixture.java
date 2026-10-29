package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import com.nexora.intelligence.infrastructure.semantic.FakeSemanticServer;
import com.nexora.intelligence.infrastructure.semantic.TestTokens;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** A simulated Reports with its token and JWKS endpoints, and the real adapter configured against it. */
final class ReportsFixture implements AutoCloseable {

    static final String SECRET = "r3p0rts-secret-never-logged";
    static final String FINGERPRINT = "d".repeat(64);
    static final JsonMapper JSON = ReportsConfiguration.json();

    final UUID tenant = UUID.randomUUID();
    final UUID dataSource = UUID.randomUUID();
    final SemanticScope scope = new SemanticScope(new TenantId(tenant), new DataSourceId(dataSource));
    final QuestionOutcome.PlanCreated plan = new QuestionOutcome.PlanCreated(scope, new PlanId(UUID.randomUUID()),
            FINGERPRINT, "nexora-logical-plan/1");
    final CorrelationId correlationId = CorrelationId.generate();
    final FakeSemanticServer server = new FakeSemanticServer();
    final TestTokens tokens = new TestTokens();
    final ReportsProperties properties;

    ReportsFixture() throws IOException {
        this(0, Duration.ofSeconds(5), 1000);
    }

    ReportsFixture(int maxRetries, Duration readTimeout, int maxRows) throws IOException {
        server.handle(FakeSemanticServer.JWKS_PATH, r -> FakeSemanticServer.Answer.of(200, tokens.jwks()));
        server.handle(FakeSemanticServer.TOKEN_PATH,
                r -> FakeSemanticServer.Answer.of(200, tokens.tokenResponse(executionToken(c -> {
                }))));
        properties = new ReportsProperties(server.baseUrl(), Duration.ofSeconds(2), readTimeout, 4 * 1024 * 1024,
                maxRows, maxRetries, identity());
    }

    ReportsProperties.Identity identity() {
        return new ReportsProperties.Identity(tenant, server.tokenUri(), server.jwksUri(), TestTokens.ISSUER,
                "nexora-reports", "nexora-intelligence", SECRET, List.of("RS256"), "tenant", "principal_type",
                "roles", "REPORT_RUNNER", Duration.ofSeconds(30));
    }

    /** A token as the internal Keycloak would issue it for an execution identity, with {@code changes} applied. */
    String executionToken(Consumer<Map<String, Object>> changes) {
        return tokens.token(tenant, claims -> {
            claims.put("aud", List.of("nexora-reports"));
            claims.put("roles", List.of("REPORT_RUNNER"));
            changes.accept(claims);
        });
    }

    HttpReportExecutionClient client() {
        return ReportsConfiguration.client(properties, Clock.systemUTC());
    }

    String path() {
        return "/api/v1/data-sources/" + dataSource + "/plans/" + plan.planId() + "/executions";
    }

    /** A successful answer of Reports for the fixture's plan; {@code change} can alter it. */
    ObjectNode success(List<String[]> columns, List<List<Object>> rows, Consumer<ObjectNode> change) {
        ObjectNode root = JSON.createObjectNode();
        root.put("executionId", UUID.randomUUID().toString());
        root.put("planId", plan.planId().toString());
        root.put("dataSourceId", dataSource.toString());
        root.put("planFingerprint", FINGERPRINT);
        root.put("validity", "CURRENT");
        ArrayNode cols = root.putArray("columns");
        for (String[] c : columns) {
            cols.addObject().put("name", c[0]).put("role", c[1]).put("type", c[2]);
        }
        ArrayNode list = root.putArray("rows");
        for (List<Object> row : rows) {
            ArrayNode r = list.addArray();
            for (Object v : row) {
                switch (v) {
                    case null -> r.addNull();
                    case String s -> r.add(s);
                    case Boolean b -> r.add(b);
                    case Long l -> r.add(l);
                    case Integer i -> r.add(i);
                    case java.math.BigInteger b -> r.add(b);
                    case java.math.BigDecimal d -> r.add(d);
                    case Double d -> r.add(d);
                    default -> throw new IllegalArgumentException(v.getClass().toString());
                }
            }
        }
        root.put("rowCount", rows.size());
        root.put("truncated", false);
        root.put("maxRows", properties.maxRows());
        root.put("durationMs", 41);
        root.putArray("warnings");
        change.accept(root);
        return root;
    }

    String body(ObjectNode node) {
        return JSON.writeValueAsString(node);
    }

    static String problem(int status, String code, List<String[]> violations) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "about:blank");
        node.put("title", "x");
        node.put("status", status);
        node.put("code", code);
        node.put("detail", "fixed detail with \"sales\".\"orders\" and secret-value");
        node.put("instance", "/api/v1/...");
        node.put("correlationId", UUID.randomUUID().toString());
        if (violations != null) {
            ArrayNode list = node.putArray("violations");
            violations.forEach(v -> list.addObject().put("code", v[0]).put("path", v[1]));
        }
        return JSON.writeValueAsString(node);
    }

    @Override
    public void close() {
        server.close();
    }
}
