package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;
import com.nexora.intelligence.domain.interpretation.IntentType;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.period.PeriodSpec;
import com.nexora.intelligence.domain.period.PeriodUnit;
import com.nexora.intelligence.domain.period.RelativePeriod;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.FilterOperator;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.nexora.intelligence.domain.semantic.CatalogFixtures.CATALOG;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.ORDER_DATE;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.REGION;
import static com.nexora.intelligence.domain.semantic.CatalogFixtures.TOTAL_SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog aliases in these tests: m1 = total sales, m2 = order count; d1 = region (TEXT), d2 = order date
 * (TEMPORAL, MONTH/YEAR), d3 = paid (BOOLEAN), d4 = customer age (NUMERIC), d5 = channel (unknown type).
 */
class ModelOutputParserTest {

    private static final String NO_REQUEST = "\"request\":null";

    private final ModelOutputParser parser = new ModelOutputParser(JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build());
    private final CatalogAliases aliases = new CatalogAliases(CATALOG);

    // ---- valid answers ---------------------------------------------------------------------------------------------

    @Test
    void parsesAnInterpretedReportRequestIntoADraftWithCatalogIds() {
        Interpretation result = parse(interpreted("""
                {"metrics":["m1"],"dimensions":["d1","d2"],
                 "filters":[{"target":"d1","operator":"IN","values":["Lima","Cusco"]},
                            {"target":"m1","operator":"GT","values":[1000.50]}],
                 "granularities":[{"dimension":"d2","granularity":"MONTH"}],
                 "period":{"dimension":"d2","kind":"RELATIVE","year":null,"quarter":null,"month":null,"date":null,
                           "from":null,"to":null,"relative":"LAST_YEAR","count":null,"unit":null}}"""));

        assertThat(result.intent()).isEqualTo(IntentType.REPORT_REQUEST);
        assertThat(result.status()).isEqualTo(InterpretationStatus.INTERPRETED);
        assertThat(result.request()).contains(new RequestDraft(List.of(TOTAL_SALES.id()),
                List.of(REGION.id(), ORDER_DATE.id()),
                List.of(FilterCondition.of(REGION.id(), FilterOperator.IN, FilterValue.text("Lima"),
                                FilterValue.text("Cusco")),
                        FilterCondition.of(TOTAL_SALES.id(), FilterOperator.GT,
                                FilterValue.number(new BigDecimal("1000.50")))),
                List.of(new GranularityChoice(ORDER_DATE.id(), TimeGranularity.MONTH)),
                Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Relative(RelativePeriod.LAST_YEAR)))));
    }

    @Test
    void numbersKeepTheirExactDecimalValue() {
        Interpretation result = parse(interpreted("{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":["
                + "{\"target\":\"m1\",\"operator\":\"LT\",\"values\":[0.1]}],\"granularities\":[],\"period\":null}"));

        FilterValue value = result.request().orElseThrow().filters().get(0).values().get(0);
        assertThat(value).isEqualTo(FilterValue.number(new BigDecimal("0.1")));
    }

    @Test
    void parsesEveryPeriodKind() {
        assertThat(period("\"kind\":\"YEAR\",\"year\":2024")).isEqualTo(new PeriodSpec.Year(2024));
        assertThat(period("\"kind\":\"QUARTER\",\"year\":2024,\"quarter\":4")).isEqualTo(new PeriodSpec.Quarter(2024, 4));
        assertThat(period("\"kind\":\"MONTH\",\"year\":2024,\"month\":2")).isEqualTo(new PeriodSpec.Month(2024, 2));
        assertThat(period("\"kind\":\"DAY\",\"date\":\"2024-02-29\""))
                .isEqualTo(new PeriodSpec.Day(LocalDate.of(2024, 2, 29)));
        assertThat(period("\"kind\":\"RANGE\",\"from\":\"2024-01-15\",\"to\":\"2024-03-15\""))
                .isEqualTo(new PeriodSpec.Range(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 15)));
        assertThat(period("\"kind\":\"RELATIVE\",\"relative\":\"THIS_WEEK\""))
                .isEqualTo(new PeriodSpec.Relative(RelativePeriod.THIS_WEEK));
        assertThat(period("\"kind\":\"LAST\",\"count\":3,\"unit\":\"MONTHS\""))
                .isEqualTo(new PeriodSpec.Last(3, PeriodUnit.MONTHS));
    }

    @Test
    void parsesClarificationsAndUnsupportedQuestionsWithoutRequest() {
        assertThat(parse("{\"intent\":\"REPORT_REQUEST\",\"status\":\"NEEDS_CLARIFICATION\","
                + "\"clarificationQuestion\":\"  ¿Para qué periodo?  \"," + NO_REQUEST + "}").clarificationQuestion())
                .contains("¿Para qué periodo?");
        assertThat(parse("{\"intent\":\"UNKNOWN\",\"status\":\"NEEDS_CLARIFICATION\","
                + "\"clarificationQuestion\":\"Which period?\"," + NO_REQUEST + "}").clarificationQuestion())
                .contains("Which period?");
        Interpretation outOfScope = parse("{\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,"
                + NO_REQUEST + ",\"intent\":\"OUT_OF_SCOPE\"}");
        assertThat(outOfScope.intent()).isEqualTo(IntentType.OUT_OF_SCOPE);
        assertThat(outOfScope.request()).isEmpty();
    }

    @Test
    void aWellBehavedAnswerRoundTrips() {
        Interpretation expected = Interpretation.interpreted(new RequestDraft(List.of(TOTAL_SALES.id()), null,
                List.of(FilterCondition.of(REGION.id(), FilterOperator.IS_NULL)), null,
                Optional.of(new PeriodSelection(ORDER_DATE.id(), new PeriodSpec.Last(30, PeriodUnit.DAYS)))));

        assertThat(parse(ModelAnswers.answer(expected, aliases))).isEqualTo(expected);
    }

    // ---- invalid answers -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "Sure! Here is the JSON you asked for.",
            "```json\n{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}\n```",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null} x",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}{}",
            "[{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}]",
            "null",
            // INT-2 shape without request, extra or duplicated fields
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null,\"sql\":\"x\"}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"intent\":\"REPORT_REQUEST\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}",
            // wrong types, unknown values, forbidden combinations
            "{\"intent\":1,\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}",
            "{\"intent\":\"out_of_scope\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":null}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":false,\"request\":null}",
            "{\"intent\":\"UNKNOWN\",\"status\":\"INTERPRETED\",\"clarificationQuestion\":null,\"request\":null}",
            "{\"intent\":\"REPORT_REQUEST\",\"status\":\"NEEDS_CLARIFICATION\",\"clarificationQuestion\":null,\"request\":null}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":\"¿Año?\",\"request\":null}",
            // request where none is allowed, or missing where required
            "{\"intent\":\"REPORT_REQUEST\",\"status\":\"INTERPRETED\",\"clarificationQuestion\":null,\"request\":null}",
            "{\"intent\":\"OUT_OF_SCOPE\",\"status\":\"UNSUPPORTED\",\"clarificationQuestion\":null,\"request\":"
                    + "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}}",
            "{\"intent\":\"REPORT_REQUEST\",\"status\":\"INTERPRETED\",\"clarificationQuestion\":null,\"request\":\"m1\"}"})
    void rejectsAnythingButOneValidInterpretationObject(String output) {
        assertInvalid(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // missing, extra, empty or non-array parts
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[]}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null,\"sql\":\"x\"}",
            "{\"metrics\":[],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":\"m1\",\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            // aliases outside the catalog, of the wrong kind, ids, keys or physical names
            "{\"metrics\":[\"m3\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"d1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[\"m2\"],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"xm1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"total-sales\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"sales.amount\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[1],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            // structure the domain forbids
            "{\"metrics\":[\"m1\",\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[{\"dimension\":\"d2\","
                    + "\"granularity\":\"MONTH\"}],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[\"d2\"],\"filters\":[],\"granularities\":[{\"dimension\":\"d2\","
                    + "\"granularity\":\"HOUR\"}],\"period\":null}",
            // filters
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"LIKE\","
                    + "\"values\":[\"L%\"]}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"EQ\","
                    + "\"values\":[]}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"EQ\","
                    + "\"values\":[null]}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"EQ\","
                    + "\"values\":[{\"sql\":\"1=1\"}]}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"EQ\","
                    + "\"values\":[\"Li\\u0000ma\"]}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"d1\",\"operator\":\"EQ\","
                    + "\"values\":[\"Lima\"],\"expression\":\"region = 'Lima'\"}],\"granularities\":[],\"period\":null}",
            "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[{\"target\":\"region\",\"operator\":\"EQ\","
                    + "\"values\":[\"Lima\"]}],\"granularities\":[],\"period\":null}"})
    void rejectsInvalidRequests(String request) {
        assertInvalid(interpreted(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"kind\":\"YEAR\"",
            "\"kind\":\"YEAR\",\"year\":2024,\"month\":3",
            "\"kind\":\"YEAR\",\"year\":1800",
            "\"kind\":\"YEAR\",\"year\":2024.5",
            "\"kind\":\"YEAR\",\"year\":\"2024\"",
            "\"kind\":\"QUARTER\",\"year\":2024,\"quarter\":5",
            "\"kind\":\"MONTH\",\"year\":2024,\"month\":13",
            "\"kind\":\"DAY\",\"date\":\"2024-02-30\"",
            "\"kind\":\"DAY\",\"date\":\"29/02/2024\"",
            "\"kind\":\"RANGE\",\"from\":\"2024-03-15\",\"to\":\"2024-01-15\"",
            "\"kind\":\"RANGE\",\"from\":\"2024-03-15\"",
            "\"kind\":\"RELATIVE\",\"relative\":\"LAST_DECADE\"",
            "\"kind\":\"LAST\",\"count\":0,\"unit\":\"DAYS\"",
            "\"kind\":\"LAST\",\"count\":3,\"unit\":\"YEARS\"",
            "\"kind\":\"NOW\""})
    void rejectsInvalidPeriods(String fields) {
        assertInvalid(interpreted(periodRequest("d2", fields)));
    }

    @Test
    void periodsMustUseAnExistingDimensionAlias() {
        assertInvalid(interpreted(periodRequest("d9", "\"kind\":\"YEAR\",\"year\":2024")));
        assertInvalid(interpreted(periodRequest("m1", "\"kind\":\"YEAR\",\"year\":2024")));
    }

    @Test
    void errorMessagesNeverEchoTheModelOutput() {
        String secretLooking = "SELECT * FROM payroll WHERE company_id = 1042";

        assertThatThrownBy(() -> parse(secretLooking))
                .isInstanceOf(InterpretationUnavailableException.class)
                .message().doesNotContain("payroll").doesNotContain("1042");
        assertThatThrownBy(() -> parse(interpreted("{\"metrics\":[\"" + secretLooking + "\"],\"dimensions\":[],"
                + "\"filters\":[],\"granularities\":[],\"period\":null}")))
                .message().doesNotContain("payroll");
        assertThatThrownBy(() -> parse(interpreted("{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":["
                + "{\"target\":\"d1\",\"operator\":\"EQ\",\"values\":[\"" + "x".repeat(1001) + "\"]}],"
                + "\"granularities\":[],\"period\":null}")))
                .message().doesNotContain("xxxx");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    // ---- INT-3.7, V7: a question without a time range answered with a period whose fields do not match -----------

    /** P2 ("¿Cuántos pozos hay por estatus del pozo?") needs period null; these are the shapes that broke V7. */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"kind\":\"YEAR\"",
            "\"kind\":\"RANGE\"",
            "\"kind\":\"RANGE\",\"from\":\"2026-10-01\"",
            "\"kind\":\"DAY\",\"from\":\"2026-10-01\",\"to\":\"2026-10-07\"",
            "\"kind\":\"RELATIVE\",\"relative\":\"THIS_YEAR\",\"year\":2026",
            "\"kind\":\"LAST\",\"count\":3",
            "\"kind\":\"MONTH\",\"year\":2026"})
    void aPeriodWhoseFieldsDoNotMatchItsKindIsRejectedWithASafeShape(String fields) {
        String output = interpreted(periodRequest("d2", fields));

        assertThatThrownBy(() -> parse(output)).isInstanceOfSatisfying(RejectedModelOutputException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.INVALID_MODEL_OUTPUT);
            assertThat(e.getMessage()).isEqualTo("period fields do not match its kind");
            assertThat(e.shape()).hasValueSatisfying(shape -> assertThat(shape)
                    .startsWith("period kind=").contains("valuedFields=[")
                    .doesNotContain("2026").doesNotContain("THIS_YEAR").doesNotContain("d2"));
        });
    }

    @Test
    void theSameQuestionWithPeriodNullIsAccepted() {
        Interpretation result = parse(interpreted(
                "{\"metrics\":[\"m2\"],\"dimensions\":[\"d1\"],\"filters\":[],\"granularities\":[],\"period\":null}"));

        assertThat(result.request().orElseThrow().period()).isEmpty();
    }

    @Test
    void everyContentRejectionIsRepeatableAndCarriesOnlyAFixedRule() {
        String hostile = "Juan Pérez 1042";
        for (String output : List.of(hostile, "{\"intent\":\"" + hostile + "\"}",
                interpreted("{\"metrics\":[\"" + hostile + "\"],\"dimensions\":[],\"filters\":[],"
                        + "\"granularities\":[],\"period\":null}"))) {
            assertThatThrownBy(() -> parse(output)).isInstanceOfSatisfying(RejectedModelOutputException.class,
                    e -> assertThat(e.getMessage()).doesNotContain("Juan").doesNotContain("1042"));
        }
    }

    private Interpretation parse(String output) {
        return parser.parse(output, aliases);
    }

    private PeriodSpec period(String fields) {
        return parse(interpreted(periodRequest("d2", fields))).request().orElseThrow().period().orElseThrow().spec();
    }

    private static String periodRequest(String dimension, String fields) {
        StringBuilder period = new StringBuilder("{\"dimension\":\"" + dimension + "\"," + fields);
        for (String field : List.of("year", "quarter", "month", "date", "from", "to", "relative", "count", "unit")) {
            if (!fields.contains("\"" + field + "\"")) {
                period.append(",\"").append(field).append("\":null");
            }
        }
        if (!fields.contains("\"kind\"")) {
            period.append(",\"kind\":null");
        }
        return "{\"metrics\":[\"m1\"],\"dimensions\":[],\"filters\":[],\"granularities\":[],\"period\":"
                + period.append('}') + "}";
    }

    private static String interpreted(String request) {
        return "{\"intent\":\"REPORT_REQUEST\",\"status\":\"INTERPRETED\",\"clarificationQuestion\":null,"
                + "\"request\":" + request + "}";
    }

    private void assertInvalid(String output) {
        assertThatThrownBy(() -> parse(output))
                .isInstanceOfSatisfying(InterpretationUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_MODEL_OUTPUT));
    }

}
