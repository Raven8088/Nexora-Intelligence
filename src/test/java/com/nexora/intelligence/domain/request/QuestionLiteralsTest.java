package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.request.QuestionLiterals.NumberMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionLiteralsTest {

    private static QuestionLiterals es(String question) {
        return new QuestionLiterals(question, Optional.of(Language.ES));
    }

    // ---- text ----------------------------------------------------------------------------------------------------

    @Test
    void textIsFoundIgnoringCaseAndAccentsAndTakenAsWrittenInTheQuestion() {
        QuestionLiterals literals = es("Ventas de San Martín y LIMA en 2025");

        assertThat(literals.findText("lima")).contains("LIMA");
        assertThat(literals.findText("san martin")).contains("San Martín");
        assertThat(literals.findText("SAN MARTÍN")).contains("San Martín");
        assertThat(literals.findText("  Lima ")).contains("LIMA");
    }

    @Test
    void textMustBeAWholeWordSequence() {
        QuestionLiterals literals = es("Ventas en Limache y Perú");

        assertThat(literals.findText("Lima")).isEmpty();
        assertThat(literals.findText("Limache")).contains("Limache");
        assertThat(literals.findText("Peru")).contains("Perú");
        assertThat(literals.findText("Per")).isEmpty();
    }

    @Test
    void inventedOrTranslatedTextIsNotFound() {
        QuestionLiterals literals = es("Ventas en el norte");

        assertThat(literals.findText("North")).isEmpty();
        assertThat(literals.findText("norte'; DROP TABLE x")).isEmpty();
        assertThat(literals.findText("")).isEmpty();
        assertThat(literals.findText("   ")).isEmpty();
    }

    @Test
    void decomposedAccentsAndNonLatinLettersKeepTheirOriginalSpan() {
        QuestionLiterals literals = es("Clientes de Pérez y de Москва");

        assertThat(literals.findText("perez")).contains("Pérez");
        assertThat(literals.findText("москва")).contains("Москва");
    }

    // ---- numbers -------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ES | más de 1.000 pedidos   | 1000",
            "ES | más de 1000 pedidos    | 1000",
            "ES | importe mayor a 2,5    | 2.5",
            "ES | importe mayor a 1.5    | 1.5",
            "ES | más de 1.234.567,89    | 1234567.89",
            "EN | more than 1,000 orders | 1000",
            "EN | amount above 2.5       | 2.5",
            "EN | amount above 2,5       | 2.5",
            "EN | above 1,234,567.89     | 1234567.89",
            "ES | saldo menor que -50    | -50",
            "ES | descuento del 10%      | 10"})
    void numbersAreFoundAsNumeralsFollowingTheLanguage(Language language, String question, BigDecimal value) {
        assertThat(new QuestionLiterals(question, Optional.of(language)).findNumber(value))
                .isEqualTo(NumberMatch.FOUND);
    }

    @Test
    void theLanguageDecidesThousandsSeparators() {
        assertThat(es("más de 1.000").findNumber(BigDecimal.ONE)).isEqualTo(NumberMatch.NOT_FOUND);
        assertThat(new QuestionLiterals("more than 1,000", Optional.of(Language.EN)).findNumber(BigDecimal.ONE))
                .isEqualTo(NumberMatch.NOT_FOUND);
    }

    @Test
    void withoutALanguageDifferentReadingsAreAmbiguous() {
        QuestionLiterals unknown = new QuestionLiterals("más de 1.000 y menos de 2,5", Optional.empty());

        assertThat(unknown.findNumber(new BigDecimal("1000"))).isEqualTo(NumberMatch.AMBIGUOUS);
        assertThat(unknown.findNumber(BigDecimal.ONE)).isEqualTo(NumberMatch.AMBIGUOUS);
        assertThat(unknown.findNumber(new BigDecimal("2.5"))).isEqualTo(NumberMatch.FOUND);
        assertThat(new QuestionLiterals("top 20", null).findNumber(new BigDecimal("20")))
                .isEqualTo(NumberMatch.FOUND);
        assertThat(new QuestionLiterals("1.000 o 1000", Optional.empty()).findNumber(new BigDecimal("1000")))
                .isEqualTo(NumberMatch.FOUND);
    }

    @Test
    void numbersNotWrittenAsNumeralsAreNotFound() {
        QuestionLiterals literals = es("más de mil pedidos en el Q1 de 2025, entre 2024-2025");

        assertThat(literals.findNumber(new BigDecimal("1000"))).isEqualTo(NumberMatch.NOT_FOUND);
        assertThat(literals.findNumber(BigDecimal.ONE)).isEqualTo(NumberMatch.NOT_FOUND);
        assertThat(literals.findNumber(new BigDecimal("-2025"))).isEqualTo(NumberMatch.NOT_FOUND);
        assertThat(literals.findNumber(new BigDecimal("2024"))).isEqualTo(NumberMatch.FOUND);
        assertThat(literals.findNumber(new BigDecimal("2025"))).isEqualTo(NumberMatch.FOUND);
        assertThat(literals.findNumber(new BigDecimal("2025.0"))).isEqualTo(NumberMatch.FOUND);
    }
}
