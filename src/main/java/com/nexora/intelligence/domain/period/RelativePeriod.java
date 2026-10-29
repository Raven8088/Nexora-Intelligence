package com.nexora.intelligence.domain.period;

/**
 * Closed set of periods relative to "today". Weeks are ISO weeks (Monday to Sunday). {@code THIS_*} periods run
 * from their start up to and including today; {@code LAST_*} periods are the complete previous week, month,
 * quarter or year.
 */
public enum RelativePeriod {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    THIS_QUARTER,
    LAST_QUARTER,
    THIS_YEAR,
    LAST_YEAR
}
