package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.semantic.DefinitionKey;
import com.nexora.intelligence.domain.result.ColumnRole;
import com.nexora.intelligence.domain.result.ExecutionValidity;
import com.nexora.intelligence.domain.result.ExecutionWarning;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ResultColumn;
import com.nexora.intelligence.domain.result.ResultValue;
import com.nexora.intelligence.domain.result.ValueKind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads Reports' {@code ExecutionResponse} strictly and without converting values between types:
 * <ul>
 *   <li>INTEGER: a JSON integer, kept as an exact {@code BigInteger};</li>
 *   <li>NUMERIC: a plain decimal string ({@code "200.500"}), kept as a {@code BigDecimal} with its scale; a JSON
 *       number is refused, because it may already have lost precision;</li>
 *   <li>DATE, TIMESTAMP, TIMESTAMP_TZ (UTC), TIME: ISO-8601 strings; BOOLEAN: JSON booleans; TEXT and UUID: strings;
 *       NULL: JSON null.</li>
 * </ul>
 * The answer must be of the requested plan and data source, with a CURRENT or CURRENT_WITH_CHANGES validity, the same
 * {@code maxRows}, a {@code rowCount} equal to the rows, and rows consistent with the columns. Anything else is a
 * {@code CONTRACT_VIOLATION}: an ambiguous result is never presented. Values are never logged nor put in messages.
 */
final class ExecutionResponseMapper {

    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final JsonMapper json;

    ExecutionResponseMapper(JsonMapper json) {
        this.json = json;
    }

    ReportExecution read(String body, QuestionOutcome.PlanCreated plan, int requestedMaxRows) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw contract("execution response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw contract("execution response is not a JSON object");
        }
        UUID executionId = uuid(root.get("executionId"), "executionId");
        if (!plan.planId().value().equals(uuid(root.get("planId"), "planId"))) {
            throw contract("execution response of another plan");
        }
        if (!plan.scope().dataSourceId().value().equals(uuid(root.get("dataSourceId"), "dataSourceId"))) {
            throw contract("execution response of another data source");
        }
        String fingerprint = text(root.get("planFingerprint"));
        if (fingerprint == null || !fingerprint.equals(plan.fingerprint())) {
            throw contract("execution response of a plan with another fingerprint");
        }
        ExecutionValidity validity = known(ExecutionValidity.class, root.get("validity"))
                .orElseThrow(() -> contract("execution response without an executable validity"));
        List<ResultColumn> columns = columns(root.get("columns"));
        JsonNode rows = root.get("rows");
        JsonNode rowCount = root.get("rowCount");
        JsonNode truncated = root.get("truncated");
        JsonNode maxRows = root.get("maxRows");
        if (rows == null || !rows.isArray() || !isInt(rowCount) || rowCount.intValue() != rows.size()
                || truncated == null || !truncated.isBoolean() || !isInt(maxRows)
                || maxRows.intValue() != requestedMaxRows) {
            throw contract("execution response rows, rowCount, truncated or maxRows are not consistent");
        }
        List<List<ResultValue>> values = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != columns.size()) {
                throw contract("an execution row does not have one value per column");
            }
            List<ResultValue> cells = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                cells.add(value(row.get(i), columns.get(i).kind()));
            }
            values.add(cells);
        }
        List<ExecutionWarning> warnings = warnings(root.get("warnings"));
        try {
            return new ReportExecution(plan.scope(), new PlanId(plan.planId().value()), executionId, fingerprint,
                    validity, columns, values, truncated.booleanValue(), maxRows.intValue(), warnings);
        } catch (IllegalArgumentException e) {
            throw contract("execution response is not consistent: " + e.getMessage());
        }
    }

    private static List<ResultColumn> columns(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw contract("execution response without columns");
        }
        List<ResultColumn> columns = new ArrayList<>();
        for (JsonNode column : node) {
            String name = column.isObject() ? text(column.get("name")) : null;
            Optional<ColumnRole> role = column.isObject() ? known(ColumnRole.class, column.get("role"))
                    : Optional.empty();
            Optional<ValueKind> kind = column.isObject() ? known(ValueKind.class, column.get("type"))
                    : Optional.empty();
            if (name == null || role.isEmpty() || kind.isEmpty()) {
                throw contract("execution column without a known name, role or type");
            }
            try {
                columns.add(new ResultColumn(new DefinitionKey(name), role.get(), kind.get()));
            } catch (IllegalArgumentException e) {
                throw contract("execution column name is not a definition key");
            }
        }
        return columns;
    }

    private static ResultValue value(JsonNode node, ValueKind kind) {
        if (node == null || node.isNull()) {
            return ResultValue.Null.INSTANCE;
        }
        try {
            return switch (kind) {
                case INTEGER -> {
                    if (!node.isIntegralNumber()) {
                        throw contract("an INTEGER value is not a JSON integer");
                    }
                    yield new ResultValue.Integer(node.bigIntegerValue());
                }
                case NUMERIC -> {
                    String text = text(node);
                    if (text == null || !DECIMAL.matcher(text).matches()) {
                        throw contract("a NUMERIC value is not an exact decimal string");
                    }
                    yield new ResultValue.Decimal(new BigDecimal(text));
                }
                case BOOLEAN -> {
                    if (!node.isBoolean()) {
                        throw contract("a BOOLEAN value is not a JSON boolean");
                    }
                    yield new ResultValue.Bool(node.booleanValue());
                }
                case TEXT -> new ResultValue.Text(required(node, kind));
                case DATE -> new ResultValue.Date(LocalDate.parse(required(node, kind)));
                case TIMESTAMP -> new ResultValue.Timestamp(LocalDateTime.parse(required(node, kind)));
                case TIMESTAMP_TZ -> new ResultValue.TimestampTz(instant(required(node, kind)));
                case TIME -> new ResultValue.Time(LocalTime.parse(required(node, kind)));
                case UUID -> new ResultValue.Uuid(UUID.fromString(required(node, kind)));
            };
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw contract("a " + kind + " value cannot be read");
        }
    }

    private static Instant instant(String text) {
        OffsetDateTime value = OffsetDateTime.parse(text);
        if (value.getOffset().getTotalSeconds() != 0) {
            throw contract("a TIMESTAMP_TZ value is not in UTC");
        }
        return value.toInstant();
    }

    private static String required(JsonNode node, ValueKind kind) {
        String text = text(node);
        if (text == null) {
            throw contract("a " + kind + " value is not a string");
        }
        return text;
    }

    private static List<ExecutionWarning> warnings(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw contract("execution warnings is not an array");
        }
        List<ExecutionWarning> warnings = new ArrayList<>();
        for (JsonNode warning : node) {
            String code = warning.isObject() ? text(warning.get("code")) : null;
            try {
                warnings.add(new ExecutionWarning(code));
            } catch (IllegalArgumentException e) {
                throw contract("execution warning without a valid code");
            }
        }
        return warnings;
    }

    private static UUID uuid(JsonNode node, String field) {
        String text = text(node);
        if (text == null) {
            throw contract("execution response without " + field);
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw contract("execution response " + field + " is not a UUID");
        }
    }

    private static <E extends Enum<E>> Optional<E> known(Class<E> type, JsonNode node) {
        String name = text(node);
        if (name != null) {
            for (E value : type.getEnumConstants()) {
                if (value.name().equals(name)) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static boolean isInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }

    static ReportsUnavailableException contract(String message) {
        return new ReportsUnavailableException(ReportsUnavailableException.Reason.CONTRACT_VIOLATION, message);
    }
}
