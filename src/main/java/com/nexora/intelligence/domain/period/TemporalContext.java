package com.nexora.intelligence.domain.period;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * The moment a question is resolved and the default time zone of its data source. A dimension's own time zone, when
 * the catalog declares one, takes precedence. Without configuration the zone is UTC.
 */
public record TemporalContext(Instant now, ZoneId sourceZone) {

    public TemporalContext {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(sourceZone, "sourceZone");
    }

    public static TemporalContext utc(Instant now) {
        return new TemporalContext(now, ZoneOffset.UTC);
    }

    /** The zone that applies: the dimension's own, otherwise the data source's. */
    public ZoneId zoneFor(Optional<ZoneId> dimensionZone) {
        return dimensionZone.orElse(sourceZone);
    }

    /** Today's date in {@code zone}. */
    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now, zone);
    }
}
