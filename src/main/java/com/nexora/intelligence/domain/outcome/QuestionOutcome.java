package com.nexora.intelligence.domain.outcome;

import com.nexora.intelligence.domain.request.RequestViolation;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.ClarificationText;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Final result of turning a question into a logical plan. Exactly one of four cases; none of them carries the
 * question, filter values or any physical name.
 */
public sealed interface QuestionOutcome {

    /**
     * Semantic validated and persisted a logical plan. "Created" means validated and stored by Semantic; plans have
     * no human approval state.
     *
     * @param fingerprint   Semantic's fingerprint of the plan (lowercase hex SHA-256)
     * @param formatVersion plan format, e.g. {@code nexora-logical-plan/1}
     */
    record PlanCreated(SemanticScope scope, PlanId planId, String fingerprint, String formatVersion)
            implements QuestionOutcome {

        private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
        private static final Pattern FORMAT_VERSION = Pattern.compile("[a-z][a-z0-9-]{0,62}/[0-9]{1,4}");

        public PlanCreated {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(planId, "planId");
            if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("fingerprint must be a lowercase hex SHA-256");
            }
            if (formatVersion == null || !FORMAT_VERSION.matcher(formatVersion).matches()) {
                throw new IllegalArgumentException("invalid plan format version");
            }
        }
    }

    /** The question cannot be planned until the user answers {@code question} ({@link ClarificationText} rules). */
    record NeedsClarification(String question) implements QuestionOutcome {

        public static final int MAX_LENGTH = ClarificationText.MAX_LENGTH;

        public NeedsClarification {
            question = ClarificationText.of(question);
        }
    }

    /** The question is outside what Nexora answers (not a report request over the catalog). */
    record Unsupported() implements QuestionOutcome {
    }

    /**
     * The request was refused, by Intelligence before reaching Semantic, by Semantic itself, or by Reports when
     * executing the plan.
     *
     * @param causes        1 to {@value #MAX_CAUSES} controlled codes; never free text from a model or another service
     * @param omittedCauses how many further causes existed beyond the listed ones (a count only, never their content)
     */
    record Rejected(Origin origin, List<Cause> causes, int omittedCauses) implements QuestionOutcome {

        public static final int MAX_CAUSES = 50;

        /** Who refused: Intelligence before Semantic, Semantic when planning, or Reports when executing. */
        public enum Origin { INTELLIGENCE, SEMANTIC, REPORTS }

        /**
         * @param code  an upper-case code such as {@code METRIC_NOT_FOUND} or Semantic's {@code FANOUT_RISK}
         * @param field optional request path such as {@code filters[1].operator}
         */
        public record Cause(String code, Optional<String> field) {

            private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
            private static final Pattern FIELD =
                    Pattern.compile("[a-zA-Z]{1,40}(\\[\\d{1,3}])?(\\.[a-zA-Z]{1,40}(\\[\\d{1,3}])?){0,4}");

            public Cause {
                if (code == null || !CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("rejection code must match " + CODE.pattern());
                }
                field = field == null ? Optional.empty() : field;
                if (field.isPresent() && !FIELD.matcher(field.get()).matches()) {
                    throw new IllegalArgumentException("invalid rejection field");
                }
            }

            public static Cause of(String code) {
                return new Cause(code, Optional.empty());
            }

            public static Cause of(String code, String field) {
                return new Cause(code, Optional.of(field));
            }
        }

        public Rejected {
            Objects.requireNonNull(origin, "origin");
            if (causes == null || causes.isEmpty() || causes.size() > MAX_CAUSES) {
                throw new IllegalArgumentException("a rejection needs between 1 and " + MAX_CAUSES + " causes");
            }
            causes = List.copyOf(causes);
            if (omittedCauses < 0) {
                throw new IllegalArgumentException("omittedCauses must not be negative");
            }
        }

        /** A rejection whose causes are all listed. */
        public Rejected(Origin origin, List<Cause> causes) {
            this(origin, causes, 0);
        }

        /** Whether more causes existed than the {@value #MAX_CAUSES} listed. */
        public boolean hasOmittedCauses() {
            return omittedCauses > 0;
        }

        /** A rejection listing the first {@value #MAX_CAUSES} causes and counting the rest. */
        public static Rejected capped(Origin origin, List<Cause> causes) {
            Objects.requireNonNull(causes, "causes");
            return new Rejected(origin, causes.stream().limit(MAX_CAUSES).toList(),
                    Math.max(0, causes.size() - MAX_CAUSES));
        }

        /**
         * Rejection by Intelligence from the violations of a request against its catalog. The first
         * {@value #MAX_CAUSES} are listed; the rest are only counted in {@link #omittedCauses()}.
         */
        public static Rejected byIntelligence(List<RequestViolation> violations) {
            Objects.requireNonNull(violations, "violations");
            return new Rejected(Origin.INTELLIGENCE, violations.stream()
                    .limit(MAX_CAUSES)
                    .map(v -> Cause.of(v.code().name(), v.field()))
                    .toList(), Math.max(0, violations.size() - MAX_CAUSES));
        }
    }
}
