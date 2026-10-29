package com.nexora.intelligence.domain.question;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    @Test
    void detectsSpanishAndEnglishFromFrequentWords() {
        assertThat(LanguageDetector.detect("Ventas totales por mes en Lima")).contains(Language.ES);
        assertThat(LanguageDetector.detect("Cuantos pedidos hubo el ano pasado")).contains(Language.ES);
        assertThat(LanguageDetector.detect("Total sales by month in Lima")).contains(Language.EN);
        assertThat(LanguageDetector.detect("How many orders were there last year?")).contains(Language.EN);
    }

    @Test
    void spanishOnlyCharactersDecide() {
        assertThat(LanguageDetector.detect("¿Total?")).contains(Language.ES);
        assertThat(LanguageDetector.detect("Pedidos de España")).contains(Language.ES);
    }

    @Test
    void undecidedTextsStayUndetermined() {
        assertThat(LanguageDetector.detect("Lima 2025")).isEmpty();
        assertThat(LanguageDetector.detect("total")).isEmpty();
        assertThat(LanguageDetector.detect("")).isEmpty();
        assertThat(LanguageDetector.detect(null)).isEmpty();
    }
}
