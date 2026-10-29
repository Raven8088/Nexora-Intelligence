package com.nexora.intelligence.domain.period;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodResolverTest {

    /** Wednesday 2025-05-14, second quarter. */
    private static final LocalDate TODAY = LocalDate.of(2025, 5, 14);

    @ParameterizedTest
    @CsvSource({
            "TODAY,        2025-05-14, 2025-05-15",
            "YESTERDAY,    2025-05-13, 2025-05-14",
            "THIS_WEEK,    2025-05-12, 2025-05-15",
            "LAST_WEEK,    2025-05-05, 2025-05-12",
            "THIS_MONTH,   2025-05-01, 2025-05-15",
            "LAST_MONTH,   2025-04-01, 2025-05-01",
            "THIS_QUARTER, 2025-04-01, 2025-05-15",
            "LAST_QUARTER, 2025-01-01, 2025-04-01",
            "THIS_YEAR,    2025-01-01, 2025-05-15",
            "LAST_YEAR,    2024-01-01, 2025-01-01"})
    void relativePeriodsAreHalfOpenAndThisPeriodsIncludeToday(RelativePeriod period, LocalDate from, LocalDate to) {
        assertThat(PeriodResolver.resolve(new PeriodSpec.Relative(period), TODAY)).isEqualTo(new DateRange(from, to));
    }

    @Test
    void calendarEdges() {
        LocalDate newYearsDay = LocalDate.of(2025, 1, 1); // a Wednesday
        assertThat(resolve(RelativePeriod.LAST_MONTH, newYearsDay))
                .isEqualTo(new DateRange(LocalDate.of(2024, 12, 1), LocalDate.of(2025, 1, 1)));
        assertThat(resolve(RelativePeriod.LAST_QUARTER, newYearsDay))
                .isEqualTo(new DateRange(LocalDate.of(2024, 10, 1), LocalDate.of(2025, 1, 1)));
        assertThat(resolve(RelativePeriod.YESTERDAY, newYearsDay))
                .isEqualTo(new DateRange(LocalDate.of(2024, 12, 31), newYearsDay));
        // ISO week of 2025-01-01 starts on Monday 2024-12-30
        assertThat(resolve(RelativePeriod.THIS_WEEK, newYearsDay))
                .isEqualTo(new DateRange(LocalDate.of(2024, 12, 30), LocalDate.of(2025, 1, 2)));
        LocalDate sunday = LocalDate.of(2025, 5, 18);
        assertThat(resolve(RelativePeriod.THIS_WEEK, sunday))
                .isEqualTo(new DateRange(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 19)));
        LocalDate monday = LocalDate.of(2025, 5, 12);
        assertThat(resolve(RelativePeriod.LAST_WEEK, monday))
                .isEqualTo(new DateRange(LocalDate.of(2025, 5, 5), monday));
        assertThat(resolve(RelativePeriod.THIS_QUARTER, LocalDate.of(2025, 12, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    void absolutePeriods() {
        assertThat(PeriodResolver.resolve(new PeriodSpec.Year(2024), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Quarter(2024, 4), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 10, 1), LocalDate.of(2025, 1, 1)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Month(2024, 2), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Day(LocalDate.of(2024, 2, 29)), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Range(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 15)),
                TODAY)).isEqualTo(new DateRange(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 16)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Range(TODAY, TODAY), TODAY))
                .isEqualTo(new DateRange(TODAY, TODAY.plusDays(1)));
    }

    @Test
    void lastNPeriodsIncludeToday() {
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(1, PeriodUnit.DAYS), TODAY))
                .isEqualTo(new DateRange(TODAY, TODAY.plusDays(1)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(7, PeriodUnit.DAYS), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 5, 8), LocalDate.of(2025, 5, 15)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(2, PeriodUnit.WEEKS), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 15)));
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(3, PeriodUnit.MONTHS), TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 2, 15), LocalDate.of(2025, 5, 15)));
        // end of month: the last month from March 31st starts on March 1st
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(1, PeriodUnit.MONTHS), LocalDate.of(2025, 3, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 4, 1)));
        // leap year
        assertThat(PeriodResolver.resolve(new PeriodSpec.Last(366, PeriodUnit.DAYS), LocalDate.of(2024, 12, 31)))
                .isEqualTo(new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)));
    }

    @Test
    void specsAreValidated() {
        assertThatThrownBy(() -> new PeriodSpec.Year(1899)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Year(2101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Quarter(2025, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Quarter(2025, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Month(2025, 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Month(2025, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Day(LocalDate.of(1850, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Range(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 1, 31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Last(0, PeriodUnit.DAYS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Last(367, PeriodUnit.DAYS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeriodSpec.Last(1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PeriodSpec.Relative(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DateRange(TODAY, TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PeriodResolver.resolve(null, TODAY)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void todayDependsOnTheZoneAndDefaultsToUtc() {
        Instant lateEveningInLima = Instant.parse("2025-05-15T03:30:00Z"); // 22:30 on the 14th in Lima
        TemporalContext utc = TemporalContext.utc(lateEveningInLima);
        TemporalContext lima = new TemporalContext(lateEveningInLima, ZoneId.of("America/Lima"));

        assertThat(utc.sourceZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(utc.today(utc.zoneFor(Optional.empty()))).isEqualTo(LocalDate.of(2025, 5, 15));
        assertThat(lima.today(lima.zoneFor(Optional.empty()))).isEqualTo(LocalDate.of(2025, 5, 14));
        assertThat(utc.zoneFor(Optional.of(ZoneId.of("Europe/Madrid")))).isEqualTo(ZoneId.of("Europe/Madrid"));
        assertThatThrownBy(() -> new TemporalContext(null, ZoneOffset.UTC)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemporalContext(lateEveningInLima, null)).isInstanceOf(NullPointerException.class);
    }

    private static DateRange resolve(RelativePeriod period, LocalDate today) {
        return PeriodResolver.resolve(new PeriodSpec.Relative(period), today);
    }
}
