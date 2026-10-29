package com.nexora.intelligence.domain.outcome;

import com.nexora.intelligence.domain.outcome.QuestionOutcome.NeedsClarification;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.PlanCreated;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected.Cause;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected.Origin;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Unsupported;
import com.nexora.intelligence.domain.request.RequestViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionOutcomeTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final PlanId PLAN = new PlanId(UUID.randomUUID());

    @Test
    void planCreatedCarriesOnlyScopeIdFingerprintAndFormat() {
        PlanCreated created = new PlanCreated(SCOPE, PLAN, FINGERPRINT, "nexora-logical-plan/1");

        assertThat(created.scope()).isEqualTo(SCOPE);
        assertThat(created.planId()).isEqualTo(PLAN);
        assertThatThrownBy(() -> new PlanCreated(null, PLAN, FINGERPRINT, "nexora-logical-plan/1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanCreated(SCOPE, null, FINGERPRINT, "nexora-logical-plan/1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanId(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void fingerprintsAreLowercaseSha256(String fingerprint) {
        assertThatThrownBy(() -> new PlanCreated(SCOPE, PLAN, fingerprint, "nexora-logical-plan/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nexora-logical-plan", "nexora-logical-plan/", "Nexora/1", "plan/1; x", "/1"})
    void formatVersionsAreControlled(String format) {
        assertThatThrownBy(() -> new PlanCreated(SCOPE, PLAN, FINGERPRINT, format))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clarificationsFollowTheSameRulesAsInterpretations() {
        assertThat(new NeedsClarification("  ¿Para qué periodo?  ").question()).isEqualTo("¿Para qué periodo?");
        assertThat(new NeedsClarification("¿Año?\n¿Región?").question()).isEqualTo("¿Año?\n¿Región?");
        for (String invalid : new String[] {null, "", "  ", "x".repeat(1001), "¿A\u0000ño?", "¿A\tño?"}) {
            assertThatThrownBy(() -> new NeedsClarification(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unsupportedHasNoContent() {
        assertThat(new Unsupported()).isEqualTo(new Unsupported());
    }

    @Test
    void rejectionsCarryAnOriginAndControlledCauses() {
        Rejected rejected = new Rejected(Origin.SEMANTIC, List.of(Cause.of("FANOUT_RISK"),
                Cause.of("OPERATOR_INCOMPATIBLE", "filters[1].operator")));

        assertThat(rejected.origin()).isEqualTo(Origin.SEMANTIC);
        assertThat(rejected.causes()).extracting(Cause::code).containsExactly("FANOUT_RISK", "OPERATOR_INCOMPATIBLE");
        assertThat(rejected.causes().get(0).field()).isEmpty();
        assertThat(new Cause("NO_PATH", null).field()).isEmpty();
        assertThatThrownBy(() -> new Rejected(null, List.of(Cause.of("NO_PATH"))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Rejected(Origin.SEMANTIC, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rejected(Origin.SEMANTIC, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rejected(Origin.SEMANTIC, IntStream.range(0, 51)
                .mapToObj(i -> Cause.of("NO_PATH")).toList())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "no_path", "NO PATH", "SELECT * FROM payroll", "Juan Pérez",
            "A23456789012345678901234567890123456789012345678901234567890123456"})
    void causeCodesCannotCarryFreeText(String code) {
        assertThatThrownBy(() -> Cause.of(code)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "filters[0] = 'Lima'", "filters[1234]", "1metrics", "a.b.c.d.e.f.g",
            "filters[0].values[0]\n"})
    void causeFieldsAreRequestPathsOnly(String field) {
        assertThatThrownBy(() -> Cause.of("FILTER_INVALID", field)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intelligenceRejectionsComeFromRequestViolations() {
        Rejected rejected = Rejected.byIntelligence(List.of(
                new RequestViolation(RequestViolation.Code.METRIC_NOT_FOUND, "metrics[0]"),
                new RequestViolation(RequestViolation.Code.VALUE_INCOMPATIBLE, "filters[2].values[1]")));

        assertThat(rejected.origin()).isEqualTo(Origin.INTELLIGENCE);
        assertThat(rejected.causes()).containsExactly(Cause.of("METRIC_NOT_FOUND", "metrics[0]"),
                Cause.of("VALUE_INCOMPATIBLE", "filters[2].values[1]"));
        assertThatThrownBy(() -> Rejected.byIntelligence(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionsAreImmutable() {
        List<Cause> causes = new ArrayList<>(List.of(Cause.of("NO_PATH")));
        Rejected rejected = new Rejected(Origin.SEMANTIC, causes);
        causes.add(Cause.of("FANOUT_RISK"));

        assertThat(rejected.causes()).containsExactly(Cause.of("NO_PATH"));
        assertThatThrownBy(() -> rejected.causes().add(Cause.of("FANOUT_RISK")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyOutcomeIsOneOfTheFourCases() {
        List<QuestionOutcome> outcomes = List.of(new PlanCreated(SCOPE, PLAN, FINGERPRINT, "nexora-logical-plan/1"),
                new NeedsClarification("¿Qué año?"), new Unsupported(),
                new Rejected(Origin.INTELLIGENCE, List.of(Cause.of("METRIC_NOT_FOUND", "metrics[0]"))));

        assertThat(outcomes).extracting(QuestionOutcomeTest::describe)
                .containsExactly("plan", "clarification", "unsupported", "rejected");
        assertThat(QuestionOutcome.class.getPermittedSubclasses()).hasSize(4);
        assertThat(Optional.of(outcomes.get(0)).filter(PlanCreated.class::isInstance)).isPresent();
    }

    /** Exhaustive switch: adding a case without handling it would not compile. */
    private static String describe(QuestionOutcome outcome) {
        return switch (outcome) {
            case PlanCreated p -> "plan";
            case NeedsClarification c -> "clarification";
            case Unsupported u -> "unsupported";
            case Rejected r -> "rejected";
        };
    }

    @Test
    void rejectionsCountCausesBeyondTheListedOnesWithoutTheirContent() {
        List<RequestViolation> violations = IntStream.range(0, 75)
                .mapToObj(i -> new RequestViolation(RequestViolation.Code.VALUE_INCOMPATIBLE,
                        "filters[" + (i / 10) + "].values[" + (i % 10) + "]"))
                .toList();

        Rejected rejected = Rejected.byIntelligence(violations);

        assertThat(rejected.causes()).hasSize(Rejected.MAX_CAUSES);
        assertThat(rejected.omittedCauses()).isEqualTo(25);
        assertThat(rejected.hasOmittedCauses()).isTrue();
        assertThat(Rejected.byIntelligence(violations.subList(0, 50)).hasOmittedCauses()).isFalse();
        assertThat(new Rejected(Origin.SEMANTIC, List.of(Cause.of("NO_PATH"))).omittedCauses()).isZero();
        assertThat(new Rejected(Origin.SEMANTIC, List.of(Cause.of("NO_PATH")), 3).hasOmittedCauses()).isTrue();
        assertThatThrownBy(() -> new Rejected(Origin.SEMANTIC, List.of(Cause.of("NO_PATH")), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
