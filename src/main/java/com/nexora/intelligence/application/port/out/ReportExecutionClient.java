package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.result.ReportExecution;

import java.util.Objects;

/**
 * Driven port: asks Nexora Reports to execute a plan that Semantic created. Reports fetches the plan from Semantic
 * itself, re-validates it, checks its validity before and after, and runs it read-only against the configured source;
 * Intelligence never sends SQL, plan documents, tenants or connection details.
 */
public interface ReportExecutionClient {

    /** Result of asking for an execution. */
    sealed interface Execution {
    }

    /** Reports executed the plan; the result is structurally consistent and of {@code plan}. */
    record Executed(ReportExecution execution) implements Execution {
        public Executed {
            Objects.requireNonNull(execution, "execution");
        }
    }

    /**
     * Reports refused to execute the plan for a reason about the plan itself (stale, changed during the execution,
     * physical schema mismatch, not executable safely): a rejection with origin REPORTS and controlled codes.
     */
    record Refused(Rejected rejection) implements Execution {
        public Refused {
            Objects.requireNonNull(rejection, "rejection");
            if (rejection.origin() != Rejected.Origin.REPORTS) {
                throw new IllegalArgumentException("a refusal by Reports has origin REPORTS");
            }
        }
    }

    /**
     * @param correlationId Intelligence's identifier of the request, for traceability only
     * @throws ReportsUnavailableException when Reports cannot be used, answers outside its contract, or refuses for
     *                                     a reason that is not about the plan (identity, configuration, capacity)
     */
    Execution execute(QuestionOutcome.PlanCreated plan, CorrelationId correlationId);
}
