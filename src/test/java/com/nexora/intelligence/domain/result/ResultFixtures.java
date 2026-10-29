package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.semantic.DefinitionKey;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.SCOPE;

/** Executions and plans for result tests, over {@code CatalogFixtures}. */
public final class ResultFixtures {

    public static final QuestionOutcome.PlanCreated PLAN = new QuestionOutcome.PlanCreated(SCOPE,
            new PlanId(UUID.randomUUID()), "f".repeat(64), "nexora-logical-plan/1");

    private ResultFixtures() {
    }

    public static ResultColumn column(String key, ColumnRole role, ValueKind kind) {
        return new ResultColumn(new DefinitionKey(key), role, kind);
    }

    public static List<ResultValue> row(ResultValue... values) {
        return Arrays.asList(values);
    }

    public static ReportExecution execution(List<ResultColumn> columns, List<List<ResultValue>> rows) {
        return new ReportExecution(SCOPE, PLAN.planId(), UUID.randomUUID(), PLAN.fingerprint(),
                ExecutionValidity.CURRENT, columns, rows, false, 1000, List.of());
    }
}
