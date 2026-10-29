package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP errors, timeouts and transport failures of reads and plan creations, with their retry rules. */
class SemanticErrorHandlingTest {

    private SemanticFixture semantic;

    @AfterEach
    void tearDown() {
        if (semantic != null) {
            semantic.close();
        }
    }

    private SemanticFixture semantic(int maxRetries, Duration readTimeout) throws IOException {
        semantic = new SemanticFixture(maxRetries, readTimeout);
        return semantic;
    }

    private SemanticRequest request() {
        return new SemanticRequest(semantic.scope, List.of(new DefinitionId(UUID.randomUUID())), List.of(),
                List.of(), List.of(), List.of());
    }

    private static Reason reasonOf(Runnable call) {
        try {
            call.run();
        } catch (SemanticUnavailableException e) {
            return e.reason();
        }
        throw new AssertionError("no SemanticUnavailableException");
    }

    private Reason load() {
        return reasonOf(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId));
    }

    private Reason create() {
        return reasonOf(() -> semantic.planClient().create(request(), semantic.correlationId));
    }

    @ParameterizedTest
    @CsvSource({
            "403, '{\"code\":\"forbidden\"}', IDENTITY_REJECTED",
            "404, '{\"code\":\"not_found\"}', SOURCE_NOT_FOUND",
            "400, '{\"code\":\"validation_failed\"}', CONTRACT_VIOLATION",
            "429, '{}', UNREACHABLE",
            "500, '{\"code\":\"internal_error\"}', UNREACHABLE",
            "502, '', UNREACHABLE",
            "503, '', UNREACHABLE",
            "504, '', UNREACHABLE",
            "302, '', CONTRACT_VIOLATION"})
    void readFailuresAreControlled(int status, String body, Reason reason) throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"),
                new FakeSemanticServer.Answer(status, body, Duration.ZERO,
                        status == 302 ? Map.of("Location", "http://example.com/") : Map.of()));
        assertThat(load()).isEqualTo(reason);
        assertThat(semantic.server.received("/api/")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "403, '{\"code\":\"tenant_access_denied\"}', IDENTITY_REJECTED",
            "404, '{\"code\":\"not_found\"}', SOURCE_NOT_FOUND",
            "409, '{\"code\":\"data_source_archived\"}', SOURCE_ARCHIVED",
            "409, '{\"code\":\"duplicate_key\"}', CONTRACT_VIOLATION",
            "400, '{\"code\":\"malformed_request\"}', CONTRACT_VIOLATION",
            "429, '{}', UNREACHABLE",
            "500, '{\"code\":\"internal_error\"}', UNREACHABLE",
            "503, '', UNREACHABLE"})
    void planCreationFailuresAreControlledAndNeverRetried(int status, String body, Reason reason)
            throws IOException {
        semantic(1, Duration.ofSeconds(5)).server.answer(semantic.path("logical-plans"), status, body);
        semantic.server.answer(semantic.path("logical-plans"), 201, "{}");
        assertThat(create()).isEqualTo(reason);
        assertThat(semantic.server.received(semantic.path("logical-plans"))).hasSize(1);
    }

    @Test
    void transientReadFailuresAreRetriedOnceWhenConfigured() throws IOException {
        semantic(1, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"), 503, "");
        semantic.catalog(List.of(), List.of(), List.of());

        assertThat(semantic.catalogClient().load(semantic.scope, semantic.correlationId).metrics()).isEmpty();
        assertThat(semantic.server.received(semantic.path("definitions"))).hasSize(3);
    }

    @Test
    void withoutRetriesATransientReadFailureIsReported() throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"), 503, "");
        assertThat(load()).isEqualTo(Reason.UNREACHABLE);
    }

    @Test
    void aReadWithoutAnswerInTimeIsATimeout() throws IOException {
        semantic(0, Duration.ofMillis(300)).server.answer(semantic.path("definitions"),
                new FakeSemanticServer.Answer(200, "{}", Duration.ofSeconds(2), Map.of()));
        assertThat(load()).isEqualTo(Reason.TIMEOUT);
    }

    @Test
    void aPlanCreationWithoutAnswerInTimeHasAnUnknownOutcomeAndIsNotRetried() throws IOException {
        semantic(1, Duration.ofMillis(300)).server.answer(semantic.path("logical-plans"),
                new FakeSemanticServer.Answer(201, "{}", Duration.ofSeconds(2), Map.of()));
        assertThat(create()).isEqualTo(Reason.OUTCOME_UNKNOWN);
        assertThat(semantic.server.received(semantic.path("logical-plans"))).hasSize(1);
    }

    @Test
    void anUnreachableSemanticIsReportedWithoutSendingAnything() throws IOException {
        semantic(1, Duration.ofSeconds(5));
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SemanticApi unreachable = new SemanticApi(new SemanticHttp(Duration.ofSeconds(1), Duration.ofSeconds(1),
                1 << 20), semantic.tokenProvider, java.net.URI.create("http://127.0.0.1:" + closedPort), 1,
                SemanticFixture.JSON);
        assertThat(reasonOf(() -> new HttpSemanticCatalogClient(unreachable,
                new CatalogResponseMapper(SemanticFixture.JSON)).load(semantic.scope, semantic.correlationId)))
                .isEqualTo(Reason.UNREACHABLE);
        assertThat(reasonOf(() -> new HttpLogicalPlanClient(unreachable, new PlanRequestMapper(SemanticFixture.JSON),
                SemanticFixture.JSON).create(request(), semantic.correlationId))).isEqualTo(Reason.UNREACHABLE);
    }

    @Test
    void oversizedAnswersAreRejected() throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"), 200,
                "{\"items\":[],\"pad\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}");
        assertThat(load()).isEqualTo(Reason.CONTRACT_VIOLATION);
    }

    @Test
    void a401RenewsTheTokenAndRepeatsTheReadOnce() throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"), 401,
                "{\"code\":\"unauthorized\"}");
        semantic.catalog(List.of(), List.of(), List.of());

        semantic.catalogClient().load(semantic.scope, semantic.correlationId);

        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
        assertThat(semantic.server.received(semantic.path("definitions"))).hasSize(3);
    }

    @Test
    void a401RenewsTheTokenAndRepeatsThePlanCreationOnce() throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("logical-plans"), 401,
                "{\"code\":\"unauthorized\"}");
        semantic.server.answer(semantic.path("logical-plans"), 422,
                "{\"code\":\"logical_plan_rejected\",\"errors\":[{\"code\":\"NO_PATH\",\"field\":\"metrics\"}]}");

        assertThat(semantic.planClient().create(request(), semantic.correlationId))
                .isInstanceOf(com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected.class);
        assertThat(semantic.server.received(semantic.path("logical-plans"))).hasSize(2);
        assertThat(semantic.server.received(FakeSemanticServer.TOKEN_PATH)).hasSize(2);
    }

    @Test
    void aSecond401IsAnIdentityRejection() throws IOException {
        semantic(1, Duration.ofSeconds(5));
        semantic.server.handle(semantic.path(""), r -> FakeSemanticServer.Answer.of(401, "{\"code\":\"unauthorized\"}"));
        assertThat(load()).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(semantic.server.received("/api/")).hasSize(2);
        assertThat(create()).isEqualTo(Reason.IDENTITY_REJECTED);
        assertThat(semantic.server.received(semantic.path("logical-plans"))).hasSize(2);
    }

    @Test
    void failureMessagesNeverCarryTokensSecretsOrBodies() throws IOException {
        semantic(0, Duration.ofSeconds(5)).server.answer(semantic.path("definitions"), 500,
                "{\"code\":\"internal_error\",\"detail\":\"password=hunter2\"}");
        assertThatThrownBy(() -> semantic.catalogClient().load(semantic.scope, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class, e -> assertThat(e.getMessage())
                        .doesNotContain("hunter2").doesNotContain(SemanticFixture.SECRET).doesNotContain("eyJ"));
    }
}
