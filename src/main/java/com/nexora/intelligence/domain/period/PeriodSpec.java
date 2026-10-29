package com.nexora.intelligence.domain.period;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A period as proposed for a question, before any date is computed. Absolute forms name calendar units; relative
 * forms are resolved deterministically by {@link PeriodResolver} against a known "today", never by the model.
 * Years are limited to {@value #MIN_YEAR}..{@value #MAX_YEAR}.
 */
public sealed interface PeriodSpec {

    int MIN_YEAR = 1900;
    int MAX_YEAR = 2100;
    int MAX_LAST_COUNT = 366;

    record Year(int year) implements PeriodSpec {
        public Year {
            requireYear(year);
        }
    }

    record Quarter(int year, int quarter) implements PeriodSpec {
        public Quarter {
            requireYear(year);
            if (quarter < 1 || quarter > 4) {
                throw new IllegalArgumentException("quarter must be between 1 and 4");
            }
        }
    }

    record Month(int year, int month) implements PeriodSpec {
        public Month {
            requireYear(year);
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("month must be between 1 and 12");
            }
        }
    }

    record Day(LocalDate date) implements PeriodSpec {
        public Day {
            Objects.requireNonNull(date, "date");
            requireYear(date.getYear());
        }
    }

    /** From {@code first} to {@code last}, both days included. */
    record Range(LocalDate first, LocalDate last) implements PeriodSpec {
        public Range {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
            requireYear(first.getYear());
            requireYear(last.getYear());
            if (last.isBefore(first)) {
                throw new IllegalArgumentException("a range must not end before it starts");
            }
        }
    }

    record Relative(RelativePeriod period) implements PeriodSpec {
        public Relative {
            Objects.requireNonNull(period, "period");
        }
    }

    /**
     * The last {@code count} days, weeks or months, including today. These are <strong>rolling</strong> periods, not
     * calendar ones: {@code Last(3, MONTHS)} on 2025-05-14 is 2025-02-15..2025-05-14 (not February to April), and
     * {@code Last(2, WEEKS)} is the 14 days ending today (not the two previous ISO weeks; use
     * {@link RelativePeriod#LAST_WEEK} for a calendar week).
     */
    record Last(int count, PeriodUnit unit) implements PeriodSpec {
        public Last {
            Objects.requireNonNull(unit, "unit");
            if (count < 1 || count > MAX_LAST_COUNT) {
                throw new IllegalArgumentException("count must be between 1 and " + MAX_LAST_COUNT);
            }
        }
    }

    private static void requireYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("year must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
    }
}
