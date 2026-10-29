package com.nexora.intelligence.domain.request;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link RequestDraftResolver}. Errors are kept apart by who they are attributable to, so a generation
 * error of the model is never presented as a problem of the user's question.
 */
public sealed interface DraftResolution {

    /** The draft became a request that fits the catalog; Semantic still has the final word. */
    record Resolved(SemanticRequest request) implements DraftResolution {
        public Resolved {
            Objects.requireNonNull(request, "request");
        }
    }

    /** The proposal itself is wrong (issues of category {@link DraftIssue.Category#MODEL_DEFECT}). */
    record ModelDefect(List<DraftIssue> issues) implements DraftResolution {
        public ModelDefect {
            issues = requireCategory(issues, DraftIssue.Category.MODEL_DEFECT);
        }
    }

    /** The question admits several readings (issues of category {@link DraftIssue.Category#AMBIGUOUS}). */
    record Ambiguous(List<DraftIssue> issues) implements DraftResolution {
        public Ambiguous {
            issues = requireCategory(issues, DraftIssue.Category.AMBIGUOUS);
        }
    }

    /**
     * A well-formed proposal Nexora rejects: draft issues of category {@link DraftIssue.Category#VALIDATION}, or
     * violations of the built request against the catalog. At least one of both lists is non-empty.
     */
    record ValidationFailed(List<DraftIssue> issues, List<RequestViolation> violations) implements DraftResolution {
        public ValidationFailed {
            issues = issues == null ? List.of() : List.copyOf(issues);
            violations = violations == null ? List.of() : List.copyOf(violations);
            if (issues.isEmpty() && violations.isEmpty()) {
                throw new IllegalArgumentException("a validation failure needs at least one issue or violation");
            }
            if (issues.stream().anyMatch(i -> i.category() != DraftIssue.Category.VALIDATION)) {
                throw new IllegalArgumentException("only VALIDATION issues belong to a validation failure");
            }
        }
    }

    private static List<DraftIssue> requireCategory(List<DraftIssue> issues, DraftIssue.Category category) {
        if (issues == null || issues.isEmpty()) {
            throw new IllegalArgumentException("at least one issue is required");
        }
        if (issues.stream().anyMatch(i -> i.category() != category)) {
            throw new IllegalArgumentException("all issues must be of category " + category);
        }
        return List.copyOf(issues);
    }
}
