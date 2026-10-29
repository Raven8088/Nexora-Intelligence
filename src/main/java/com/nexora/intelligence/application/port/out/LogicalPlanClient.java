package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.request.SemanticRequest;

/**
 * Driven port: asks Nexora Semantic to validate a structured request and persist a logical plan. Semantic is the only
 * authority on plans: it re-resolves the request against its current catalog, chooses routes and joins, and decides
 * whether the plan is safe. Implementations never build SQL nor look inside the plan document.
 */
public interface LogicalPlanClient {

    /**
     * @param correlationId Intelligence's identifier of the request, for traceability only
     * @return {@link QuestionOutcome.PlanCreated} when Semantic created the plan, or {@link QuestionOutcome.Rejected}
     *         with origin SEMANTIC and Semantic's controlled codes when it refused the request
     * @throws SemanticUnavailableException when Semantic cannot be used, answers outside its contract, or the outcome
     *                                      of a sent request is unknown ({@code OUTCOME_UNKNOWN}, never retried)
     */
    QuestionOutcome create(SemanticRequest request, CorrelationId correlationId);
}
