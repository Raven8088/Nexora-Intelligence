package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.result.QuestionAnswer;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ReportResult;
import com.nexora.intelligence.domain.result.ResultColumn;
import com.nexora.intelligence.domain.result.ResultValue;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Writes a {@link QuestionAnswer} as the API's JSON answer. Values keep their exact form: NUMERIC as a plain decimal
 * string ({@code "200.500"}), INTEGER as a JSON integer, dates and times as ISO-8601 strings (TIMESTAMP_TZ in UTC),
 * BOOLEAN as a JSON boolean, NULL as null. No SQL, plan document, tenant id, token or message of another service is
 * ever written.
 * <pre>
 * { "correlationId": "…", "status": "REPORTED" | "NEEDS_CLARIFICATION" | "UNSUPPORTED" | "REJECTED" | "NOT_EXECUTED",
 *   "dataSource": "wellnode-demo",
 *   "plan":   { "planId", "fingerprint", "formatVersion" },                       (REPORTED, NOT_EXECUTED)
 *   "result": { "executionId", "validity", "columns": [{ "key", "definitionId", "role", "type" }],
 *               "rows", "rowCount", "truncated", "maxRows", "warnings": ["RESULT_TRUNCATED"] },  (REPORTED)
 *   "clarification": "¿Para qué periodo?",                                         (NEEDS_CLARIFICATION)
 *   "rejection": { "origin", "causes": [{ "code", "field" }], "omittedCauses" } }  (REJECTED, NOT_EXECUTED)
 * </pre>
 */
final class QuestionAnswerWriter {

    private final JsonMapper json = JsonMapper.builder().build();

    String write(QuestionAnswer answer, CorrelationId correlationId, String dataSource) {
        ObjectNode root = json.createObjectNode();
        root.put("correlationId", correlationId.toString());
        switch (answer) {
            case QuestionAnswer.Reported reported -> {
                root.put("status", "REPORTED");
                root.put("dataSource", dataSource);
                plan(root, reported.plan());
                result(root.putObject("result"), reported.result());
            }
            case QuestionAnswer.NotExecuted notExecuted -> {
                root.put("status", "NOT_EXECUTED");
                root.put("dataSource", dataSource);
                plan(root, notExecuted.plan());
                rejection(root, notExecuted.rejection());
            }
            case QuestionAnswer.NotPlanned notPlanned -> {
                root.put("dataSource", dataSource);
                switch (notPlanned.outcome()) {
                    case QuestionOutcome.NeedsClarification clarification -> {
                        root.put("status", "NEEDS_CLARIFICATION");
                        root.put("clarification", clarification.question());
                    }
                    case QuestionOutcome.Unsupported unsupported -> root.put("status", "UNSUPPORTED");
                    case Rejected rejected -> {
                        root.put("status", "REJECTED");
                        rejection(root, rejected);
                    }
                    case QuestionOutcome.PlanCreated created ->
                            throw new IllegalStateException("a created plan is never NOT_PLANNED");
                }
            }
        }
        return json.writeValueAsString(root);
    }

    private static void plan(ObjectNode root, QuestionOutcome.PlanCreated plan) {
        root.putObject("plan").put("planId", plan.planId().toString()).put("fingerprint", plan.fingerprint())
                .put("formatVersion", plan.formatVersion());
    }

    private static void rejection(ObjectNode root, Rejected rejected) {
        ObjectNode node = root.putObject("rejection");
        node.put("origin", rejected.origin().name());
        ArrayNode causes = node.putArray("causes");
        for (Rejected.Cause cause : rejected.causes()) {
            ObjectNode c = causes.addObject().put("code", cause.code());
            cause.field().ifPresent(f -> c.put("field", f));
        }
        node.put("omittedCauses", rejected.omittedCauses());
    }

    private static void result(ObjectNode node, ReportResult result) {
        ReportExecution execution = result.execution();
        node.put("executionId", execution.executionId().toString());
        node.put("validity", execution.validity().name());
        ArrayNode columns = node.putArray("columns");
        List<ResultColumn> list = execution.columns();
        for (int i = 0; i < list.size(); i++) {
            ResultColumn column = list.get(i);
            columns.addObject().put("key", column.key().value())
                    .put("definitionId", result.definitions().get(i).toString())
                    .put("role", column.role().name())
                    .put("type", column.kind().name());
        }
        ArrayNode rows = node.putArray("rows");
        for (List<ResultValue> row : execution.rows()) {
            ArrayNode r = rows.addArray();
            for (ResultValue value : row) {
                switch (value) {
                    case ResultValue.Null n -> r.addNull();
                    case ResultValue.Text t -> r.add(t.value());
                    case ResultValue.Decimal d -> r.add(d.value().toPlainString());
                    case ResultValue.Integer i -> r.add(i.value());
                    case ResultValue.Bool b -> r.add(b.value());
                    case ResultValue.Date d -> r.add(d.value().toString());
                    case ResultValue.Timestamp t -> r.add(t.value().toString());
                    case ResultValue.TimestampTz t -> r.add(t.value().toString());
                    case ResultValue.Time t -> r.add(t.value().toString());
                    case ResultValue.Uuid u -> r.add(u.value().toString());
                }
            }
        }
        node.put("rowCount", execution.rowCount());
        node.put("truncated", execution.truncated());
        node.put("maxRows", execution.maxRows());
        ArrayNode warnings = node.putArray("warnings");
        execution.warnings().forEach(w -> warnings.add(w.code()));
    }
}
