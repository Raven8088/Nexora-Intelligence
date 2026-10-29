package com.nexora.intelligence.domain.period;

import java.time.LocalDate;
import java.util.Objects;

/** A half-open range of calendar days: {@code from} included, {@code toExclusive} excluded. Never empty. */
public record DateRange(LocalDate from, LocalDate toExclusive) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!from.isBefore(toExclusive)) {
            throw new IllegalArgumentException("a date range must not be empty");
        }
    }

    /** The days from {@code first} to {@code last}, both included. */
    public static DateRange inclusive(LocalDate first, LocalDate last) {
        return new DateRange(first, last.plusDays(1));
    }
}
