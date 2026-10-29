package com.nexora.intelligence.infrastructure.reports;

import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException.Reason;
import com.nexora.intelligence.domain.result.ReportExecution;
import com.nexora.intelligence.domain.result.ResultValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.nexora.intelligence.infrastructure.reports.HttpReportExecutionClientTest.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Strict reading of Reports' execution response: every type exactly, and nothing ambiguous accepted. */
class ExecutionResponseMapperTest {

    private static final List<String[]> ALL_TYPES = List.of(
            new String[] {"texto", "DIMENSION", "TEXT"},
            new String[] {"numero", "DIMENSION", "NUMERIC"},
            new String[] {"activo", "DIMENSION", "BOOLEAN"},
            new String[] {"dia", "DIMENSION", "DATE"},
            new String[] {"momento", "DIMENSION", "TIMESTAMP"},
            new String[] {"instante", "DIMENSION", "TIMESTAMP_TZ"},
            new String[] {"hora", "DIMENSION", "TIME"},
            new String[] {"ident", "DIMENSION", "UUID"},
            new String[] {"total", "METRIC", "NUMERIC"},
            new String[] {"cuenta", "METRIC", "INTEGER"});

    private static ReportsFixture reports;
    private final ExecutionResponseMapper mapper = new ExecutionResponseMapper(ReportsFixture.JSON);

    @BeforeAll
    static void setUp() throws Exception {
        reports = new ReportsFixture();
    }

    @AfterAll
    static void tearDown() {
        reports.close();
    }

    private ReportExecution read(ObjectNode node) {
        return mapper.read(reports.body(node), reports.plan, 1000);
    }

    private static void contractViolation(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ReportsUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_VIOLATION));
    }

    private void broken(List<String[]> columns, List<List<Object>> rows, Consumer<ObjectNode> change) {
        contractViolation(() -> read(reports.success(columns, rows, change)));
    }

    @Test
    void everyTypeIsReadExactly() {
        UUID id = UUID.randomUUID();
        ReportExecution execution = read(reports.success(ALL_TYPES, List.of(
                row("Lima", "12.30", true, "2026-01-01", "2026-01-12T02:00", "2026-01-12T02:00Z", "08:30:15",
                        id.toString(), "123456789012345678901234567890.123456789", 42L),
                row(null, null, null, null, null, null, null, null, null, null)), n -> {
        }));

        assertThat(execution.rows().get(0)).containsExactly(
                new ResultValue.Text("Lima"),
                new ResultValue.Decimal(new BigDecimal("12.30")),
                new ResultValue.Bool(true),
                new ResultValue.Date(LocalDate.of(2026, 1, 1)),
                new ResultValue.Timestamp(LocalDateTime.of(2026, 1, 12, 2, 0)),
                new ResultValue.TimestampTz(Instant.parse("2026-01-12T02:00:00Z")),
                new ResultValue.Time(LocalTime.of(8, 30, 15)),
                new ResultValue.Uuid(id),
                new ResultValue.Decimal(new BigDecimal("123456789012345678901234567890.123456789")),
                new ResultValue.Integer(BigInteger.valueOf(42)));
        assertThat(((ResultValue.Decimal) execution.rows().get(0).get(1)).value().toPlainString()).isEqualTo("12.30");
        assertThat(execution.rows().get(1)).allMatch(v -> v instanceof ResultValue.Null);
    }

    @Test
    void valuesThatMayHaveLostPrecisionOrTypeAreRefused() {
        List<String[]> numeric = List.<String[]>of(new String[] {"total", "METRIC", "NUMERIC"});
        List<String[]> integer = List.<String[]>of(new String[] {"cuenta", "METRIC", "INTEGER"});
        broken(numeric, List.of(row(new BigDecimal("12.5"))), n -> {
        });
        broken(numeric, List.of(row(12.5d)), n -> {
        });
        broken(numeric, List.of(row("1.2E+3")), n -> {
        });
        broken(numeric, List.of(row("12,5")), n -> {
        });
        broken(numeric, List.of(row("")), n -> {
        });
        broken(integer, List.of(row("42")), n -> {
        });
        broken(integer, List.of(row(4.2d)), n -> {
        });
        broken(List.<String[]>of(new String[] {"activo", "DIMENSION", "BOOLEAN"}), List.of(row("true")), n -> {
        });
        broken(List.<String[]>of(new String[] {"dia", "DIMENSION", "DATE"}), List.of(row("01/01/2026")), n -> {
        });
        broken(List.<String[]>of(new String[] {"instante", "DIMENSION", "TIMESTAMP_TZ"}),
                List.of(row("2026-01-12T02:00-06:00")), n -> {
                });
        broken(List.<String[]>of(new String[] {"ident", "DIMENSION", "UUID"}), List.of(row("x")), n -> {
        });
        broken(List.<String[]>of(new String[] {"texto", "DIMENSION", "TEXT"}), List.of(row(5L)), n -> {
        });
    }

    @Test
    void anExecutionOfAnotherPlanSourceOrFingerprintIsRefused() {
        List<String[]> c = List.<String[]>of(new String[] {"cuenta", "METRIC", "INTEGER"});
        broken(c, List.of(), n -> n.put("planId", UUID.randomUUID().toString()));
        broken(c, List.of(), n -> n.put("dataSourceId", UUID.randomUUID().toString()));
        broken(c, List.of(), n -> n.put("planFingerprint", "e".repeat(64)));
        broken(c, List.of(), n -> n.remove("planFingerprint"));
        broken(c, List.of(), n -> n.remove("executionId"));
        broken(c, List.of(), n -> n.put("executionId", "x"));
    }

    @Test
    void onlyExecutableValiditiesAreAccepted() {
        List<String[]> c = List.<String[]>of(new String[] {"cuenta", "METRIC", "INTEGER"});
        broken(c, List.of(), n -> n.put("validity", "STALE"));
        broken(c, List.of(), n -> n.remove("validity"));
        assertThat(read(reports.success(c, List.of(), n -> n.put("validity", "CURRENT_WITH_CHANGES"))).validity())
                .isEqualTo(com.nexora.intelligence.domain.result.ExecutionValidity.CURRENT_WITH_CHANGES);
    }

    @Test
    void inconsistentShapesAreRefused() {
        List<String[]> c = List.<String[]>of(new String[] {"cuenta", "METRIC", "INTEGER"});
        broken(c, List.of(row(1L)), n -> n.put("rowCount", 2));
        broken(c, List.of(row(1L)), n -> n.put("maxRows", 500));
        broken(c, List.of(row(1L)), n -> n.put("truncated", true));
        broken(c, List.of(row(1L)), n -> n.put("truncated", "false"));
        broken(c, List.of(row(1L, 2L)), n -> {
        });
        broken(c, List.of(row()), n -> {
        });
        broken(List.of(), List.of(), n -> {
        });
        broken(List.<String[]>of(new String[] {"xx", "METRIC", "FLOAT"}), List.of(), n -> {
        });
        broken(List.<String[]>of(new String[] {"xx", "MEASURE", "INTEGER"}), List.of(), n -> {
        });
        broken(List.<String[]>of(new String[] {"Not A Key", "METRIC", "INTEGER"}), List.of(), n -> {
        });
        broken(List.<String[]>of(new String[] {"xx", "METRIC", "INTEGER"}, new String[] {"xx", "METRIC", "NUMERIC"}),
                List.of(), n -> {
                });
        broken(c, List.of(), n -> n.putArray("warnings").addObject().put("code", "not valid"));
        broken(c, List.of(), n -> n.put("rows", "[]"));
        contractViolation(() -> mapper.read("not json", reports.plan, 1000));
        contractViolation(() -> mapper.read("[]", reports.plan, 1000));
        contractViolation(() -> mapper.read(reports.body(reports.success(c, List.of(), n -> {
        })) + " {}", reports.plan, 1000));
        contractViolation(() -> mapper.read("{\"planId\":\"a\",\"planId\":\"b\"}", reports.plan, 1000));
    }

    @Test
    void theSameKeyMayAppearOnceAsDimensionAndOnceAsMetric() {
        ReportExecution execution = read(reports.success(List.<String[]>of(new String[] {"xx", "DIMENSION", "TEXT"},
                new String[] {"xx", "METRIC", "INTEGER"}), List.of(row("a", 1L)), n -> {
        }));
        assertThat(execution.columns()).hasSize(2);
    }

    @Test
    void unknownExtraFieldsAreIgnoredForForwardCompatibility() {
        List<String[]> c = List.<String[]>of(new String[] {"cuenta", "METRIC", "INTEGER"});
        assertThat(read(reports.success(c, List.of(row(3L)), n -> n.put("newField", 1))).rowCount()).isEqualTo(1);
    }
}
