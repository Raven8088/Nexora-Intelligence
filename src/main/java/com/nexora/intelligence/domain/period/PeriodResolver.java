package com.nexora.intelligence.domain.period;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * Deterministic translation of a {@link PeriodSpec} into a half-open {@link DateRange}, given today's date. Rules:
 * ISO weeks (Monday first); {@code THIS_*} up to and including today; {@code LAST_*} the whole previous unit;
 * {@code Last(n, unit)} the n most recent days, weeks (7n days) or months, including today.
 */
public final class PeriodResolver {

    private PeriodResolver() {
    }

    public static DateRange resolve(PeriodSpec spec, LocalDate today) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(today, "today");
        LocalDate tomorrow = today.plusDays(1);
        return switch (spec) {
            case PeriodSpec.Year y -> new DateRange(LocalDate.of(y.year(), 1, 1), LocalDate.of(y.year() + 1, 1, 1));
            case PeriodSpec.Quarter q -> {
                LocalDate start = LocalDate.of(q.year(), (q.quarter() - 1) * 3 + 1, 1);
                yield new DateRange(start, start.plusMonths(3));
            }
            case PeriodSpec.Month m -> {
                LocalDate start = LocalDate.of(m.year(), m.month(), 1);
                yield new DateRange(start, start.plusMonths(1));
            }
            case PeriodSpec.Day d -> DateRange.inclusive(d.date(), d.date());
            case PeriodSpec.Range r -> DateRange.inclusive(r.first(), r.last());
            case PeriodSpec.Last l -> new DateRange(switch (l.unit()) {
                case DAYS -> today.minusDays(l.count() - 1L);
                case WEEKS -> today.minusDays(7L * l.count() - 1);
                case MONTHS -> today.minusMonths(l.count()).plusDays(1);
            }, tomorrow);
            case PeriodSpec.Relative r -> relative(r.period(), today);
        };
    }

    private static DateRange relative(RelativePeriod period, LocalDate today) {
        LocalDate tomorrow = today.plusDays(1);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate quarterStart = today.with(IsoFields.DAY_OF_QUARTER, 1);
        LocalDate yearStart = today.withDayOfYear(1);
        return switch (period) {
            case TODAY -> new DateRange(today, tomorrow);
            case YESTERDAY -> new DateRange(today.minusDays(1), today);
            case THIS_WEEK -> new DateRange(weekStart, tomorrow);
            case LAST_WEEK -> new DateRange(weekStart.minusWeeks(1), weekStart);
            case THIS_MONTH -> new DateRange(monthStart, tomorrow);
            case LAST_MONTH -> new DateRange(monthStart.minusMonths(1), monthStart);
            case THIS_QUARTER -> new DateRange(quarterStart, tomorrow);
            case LAST_QUARTER -> new DateRange(quarterStart.minusMonths(3), quarterStart);
            case THIS_YEAR -> new DateRange(yearStart, tomorrow);
            case LAST_YEAR -> new DateRange(yearStart.minusYears(1), yearStart);
        };
    }
}
