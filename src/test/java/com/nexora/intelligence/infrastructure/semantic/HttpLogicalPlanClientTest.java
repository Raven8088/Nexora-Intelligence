package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan creation against a simulated Semantic: 201, 422 and the checks on Semantic's answer. */
class HttpLogicalPlanClientTest {

    private static final String FINGERPRINT = "a".repeat(64);

    private SemanticFixture semantic;
    private SemanticRequest request;

    @BeforeEach
    void setUp() throws Exception {
        semantic = new SemanticFixture();
        request = new SemanticRequest(semantic.scope, List.of(new DefinitionId(UUID.randomUUID())), List.of(),
                List.of(), List.of(), List.of());
    }

    @AfterEach
    void tearDown() {
        semantic.close();
    }

    private String plans() {
        return semantic.path("logical-plans");
    }

    private String created(UUID planId, Consumer<ObjectNode> change) {
        ObjectNode node = SemanticFixture.JSON.createObjectNode();
        node.put("id", planId.toString());
        node.put("dataSourceId", semantic.dataSource.toString());
        node.put("formatVersion", "nexora-logical-plan/1");
        node.put("fingerprint", FINGERPRINT);
        node.put("createdBy", "service-account-intelligence");
        node.put("createdAt", "2026-10-28T10:00:00Z");
        node.putObject("plan").put("formatVersion", "nexora-logical-plan/1").putArray("joins");
        change.accept(node);
        return SemanticFixture.JSON.writeValueAsString(node);
    }

    private static String problem(String code, List<String[]> errors) {
        ObjectNode node = SemanticFixture.JSON.createObjectNode();
        node.put("type", "about:blank");
        node.put("status", 422);
        node.put("detail", "Request cannot be planned safely");
        node.put("code", code);
        node.put("correlationId", UUID.randomUUID().toString());
        ArrayNode list = node.putArray("errors");
        for (String[] error : errors) {
            ObjectNode e = list.addObject();
            e.put("code", error[0]);
            e.put("field", error[1]);
            e.put("message", "secret detail about \"sales\".\"orders\"");
            e.putArray("relationshipIds").add(UUID.randomUUID().toString());
        }
        return SemanticFixture.JSON.writeValueAsString(node);
    }

    @Test
    void aCreatedPlanKeepsOnlyItsIdFingerprintAndFormat() {
        UUID planId = UUID.randomUUID();
        semantic.server.answer(plans(), 201, created(planId, n -> {
        }));

        QuestionOutcome outcome = semantic.planClient().create(request, semantic.correlationId);

        assertThat(outcome).isEqualTo(new QuestionOutcome.PlanCreated(semantic.scope,
                new com.nexora.intelligence.domain.outcome.PlanId(planId), FINGERPRINT, "nexora-logical-plan/1"));
        FakeSemanticServer.Received sent = semantic.server.received(plans()).get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.header("Content-Type")).startsWith("application/json");
        assertThat(sent.header("Authorization")).startsWith("Bearer ");
        assertThat(sent.body()).isEqualTo(new PlanRequestMapper(SemanticFixture.JSON).body(request));
    }

    @Test
    void answersOutsideTheContractAreRejected() {
        List<Consumer<ObjectNode>> breaks = List.of(
                n -> n.put("dataSourceId", UUID.randomUUID().toString()),
                n -> n.remove("dataSourceId"),
                n -> n.put("fingerprint", "A".repeat(64)),
                n -> n.put("fingerprint", "abc"),
                n -> n.remove("fingerprint"),
                n -> n.put("formatVersion", "nexora-logical-plan/2"),
                n -> n.remove("formatVersion"),
                n -> n.put("id", "x"),
                n -> n.remove("plan"),
                n -> n.put("plan", "SELECT 1"));
        for (Consumer<ObjectNode> change : breaks) {
            semantic.server.answer(plans(), 201, created(UUID.randomUUID(), change));
            assertThatThrownBy(() -> semantic.planClient().create(request, semantic.correlationId))
                    .isInstanceOfSatisfying(SemanticUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
        }
        semantic.server.answer(plans(), 201, "not json");
        assertThatThrownBy(() -> semantic.planClient().create(request, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
        semantic.server.answer(plans(), 200, created(UUID.randomUUID(), n -> {
        }));
        assertThatThrownBy(() -> semantic.planClient().create(request, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    @Test
    void aPlanningRejectionBecomesARejectionBySemanticWithItsCodesOnly() {
        semantic.server.answer(plans(), 422, problem("logical_plan_rejected", List.of(
                new String[] {"AMBIGUOUS_PATH", "dimensions[0]"},
                new String[] {"FANOUT_RISK", "metrics[1]"})));

        QuestionOutcome outcome = semantic.planClient().create(request, semantic.correlationId);

        assertThat(outcome).isEqualTo(new Rejected(Rejected.Origin.SEMANTIC, List.of(
                Rejected.Cause.of("AMBIGUOUS_PATH", "dimensions[0]"), Rejected.Cause.of("FANOUT_RISK", "metrics[1]"))));
        assertThat(outcome.toString()).doesNotContain("secret").doesNotContain("orders");
    }

    @Test
    void aResolutionFailureFromACatalogThatChangedIsARejectionBySemantic() {
        semantic.server.answer(plans(), 422, problem("semantic_resolution_failed", List.of(
                new String[] {"DEFINITION_NOT_APPROVED", "metrics[0]"},
                new String[] {"DEFINITION_INVALIDATED", "dimensions[0]"})));

        assertThat(semantic.planClient().create(request, semantic.correlationId))
                .isInstanceOfSatisfying(Rejected.class, rejected -> {
                    assertThat(rejected.origin()).isEqualTo(Rejected.Origin.SEMANTIC);
                    assertThat(rejected.causes()).extracting(Rejected.Cause::code)
                            .containsExactly("DEFINITION_NOT_APPROVED", "DEFINITION_INVALIDATED");
                });
        assertThat(semantic.server.received(plans())).hasSize(1);
    }

    @Test
    void invalidCodesAndFieldsAreNotCopied() {
        semantic.server.answer(plans(), 422, problem("logical_plan_rejected", List.of(
                new String[] {"not a code", "metrics[0]"},
                new String[] {"NO_PATH", "dimensions[0]; DROP"})));

        assertThat(semantic.planClient().create(request, semantic.correlationId))
                .isEqualTo(new Rejected(Rejected.Origin.SEMANTIC,
                        List.of(new Rejected.Cause("NO_PATH", Optional.empty()))));
    }

    @Test
    void aRejectionWithoutDetailsKeepsItsGeneralCode() {
        semantic.server.answer(plans(), 422, problem("logical_plan_rejected", List.of()));

        assertThat(semantic.planClient().create(request, semantic.correlationId))
                .isEqualTo(new Rejected(Rejected.Origin.SEMANTIC, List.of(
                        Rejected.Cause.of("LOGICAL_PLAN_REJECTED"))));
    }

    @Test
    void manyCausesAreCappedAndCounted() {
        List<String[]> errors = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            errors.add(new String[] {"FILTER_INVALID", "filters[" + i + "]"});
        }
        semantic.server.answer(plans(), 422, problem("semantic_resolution_failed", errors));

        assertThat(semantic.planClient().create(request, semantic.correlationId))
                .isInstanceOfSatisfying(Rejected.class, rejected -> {
                    assertThat(rejected.causes()).hasSize(Rejected.MAX_CAUSES);
                    assertThat(rejected.omittedCauses()).isEqualTo(10);
                });
    }

    @Test
    void a422ThatIsNotASemanticRejectionIsAContractViolation() {
        semantic.server.answer(plans(), 422, "{\"code\":\"definition_invalid\",\"errors\":[]}");

        assertThatThrownBy(() -> semantic.planClient().create(request, semantic.correlationId))
                .isInstanceOfSatisfying(SemanticUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }
}
