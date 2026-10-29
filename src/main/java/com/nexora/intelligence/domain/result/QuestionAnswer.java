package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;

import java.util.Objects;

/** The final answer to a question: a report, or the reason there is none. Exactly one of three cases. */
public sealed interface QuestionAnswer {

    /** Semantic created the plan and Reports executed it. */
    record Reported(QuestionOutcome.PlanCreated plan, ReportResult result) implements QuestionAnswer {
        public Reported {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(result, "result");
            if (!result.execution().planId().equals(plan.planId())) {
                throw new IllegalArgumentException("the result is of another plan");
            }
        }
    }

    /**
     * No plan: a clarification, an unsupported question, or a rejection by Intelligence or Semantic. Never a plan.
     */
    record NotPlanned(QuestionOutcome outcome) implements QuestionAnswer {
        public NotPlanned {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome instanceof QuestionOutcome.PlanCreated) {
                throw new IllegalArgumentException("a created plan is answered by executing it");
            }
        }
    }

    /** Semantic created the plan but Reports refused to execute it (stale plan, schema mismatch, not executable). */
    record NotExecuted(QuestionOutcome.PlanCreated plan, Rejected rejection) implements QuestionAnswer {
        public NotExecuted {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(rejection, "rejection");
            if (rejection.origin() != Rejected.Origin.REPORTS) {
                throw new IllegalArgumentException("only Reports refuses executions");
            }
        }
    }
}
