package com.nexora.intelligence.domain.result;

import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.semantic.DefinitionKey;
import com.nexora.intelligence.domain.shared.SemanticScope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A successful execution of a logical plan by Nexora Reports, structurally consistent: every row has one value per
 * column and every value fits its column's kind; keys are unique within each role; at most {@code maxRows} rows, and
 * exactly {@code maxRows} when the result is truncated. It is not yet bound to the semantic definitions of the
 * question ({@link ReportResult}).
 *
 * @param executionId     Reports' id of the execution (also in its audit)
 * @param planFingerprint fingerprint of the executed plan, as Semantic computed it
 * @param truncated       more rows existed than {@code maxRows}; only the first ones (in grouping order) are here
 */
public record ReportExecution(SemanticScope scope, PlanId planId, UUID executionId, String planFingerprint,
                              ExecutionValidity validity, List<ResultColumn> columns, List<List<ResultValue>> rows,
                              boolean truncated, int maxRows, List<ExecutionWarning> warnings) {

    public ReportExecution {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(planFingerprint, "planFingerprint");
        Objects.requireNonNull(validity, "validity");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a result has at least one column");
        }
        Set<String> keys = new HashSet<>();
        for (ResultColumn column : columns) {
            if (!keys.add(column.role() + ":" + column.key())) {
                throw new IllegalArgumentException("a column key is repeated within its role");
            }
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        Objects.requireNonNull(rows, "rows");
        if (rows.size() > maxRows || (truncated && rows.size() != maxRows)) {
            throw new IllegalArgumentException("the number of rows does not match maxRows and truncation");
        }
        List<List<ResultValue>> copy = new ArrayList<>(rows.size());
        for (List<ResultValue> row : rows) {
            if (row == null || row.size() != columns.size()) {
                throw new IllegalArgumentException("a row does not have one value per column");
            }
            for (int i = 0; i < row.size(); i++) {
                ResultValue value = Objects.requireNonNull(row.get(i), "value");
                if (!value.fits(columns.get(i).kind())) {
                    throw new IllegalArgumentException("a value does not fit the kind of its column");
                }
            }
            copy.add(List.copyOf(row));
        }
        rows = List.copyOf(copy);
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public int rowCount() {
        return rows.size();
    }

    /** Index of the column of {@code role} with {@code key}, or -1. */
    public int indexOf(ColumnRole role, DefinitionKey key) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).role() == role && columns.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
