package com.nexora.intelligence.application.translation;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.request.SemanticRequest;

import java.util.Objects;

/** Result of translating a question: either a request ready for Semantic, or an answer for the user right away. */
public sealed interface Translation {

    /** A deterministic, catalog-valid request. Semantic still validates it and decides whether it can be planned. */
    record Ready(SemanticRequest request) implements Translation {
        public Ready {
            Objects.requireNonNull(request, "request");
        }
    }

    /** No request: a clarification, an unsupported question or a rejection by Intelligence. Never a plan. */
    record Answered(QuestionOutcome outcome) implements Translation {
        public Answered {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome instanceof QuestionOutcome.PlanCreated) {
                throw new IllegalArgumentException("only Semantic creates plans");
            }
        }
    }
}
