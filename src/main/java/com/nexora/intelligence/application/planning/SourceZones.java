package com.nexora.intelligence.application.planning;

import com.nexora.intelligence.domain.shared.DataSourceId;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/**
 * Default time zone of each data source: the zone "today", "this month" or "last week" are measured in for its
 * dimensions that declare no zone of their own. A dimension's own zone (from the catalog) always takes precedence.
 * <p>
 * The fallback is a technical default (UTC unless configured); it is not assumed to be any source's business zone,
 * so each source used for periods should have its zone configured explicitly.
 */
public record SourceZones(ZoneId fallback, Map<DataSourceId, ZoneId> bySource) {

    public SourceZones {
        Objects.requireNonNull(fallback, "fallback");
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
    }

    /** UTC for every source. */
    public static SourceZones utc() {
        return new SourceZones(ZoneOffset.UTC, Map.of());
    }

    public ZoneId zoneOf(DataSourceId dataSource) {
        return bySource.getOrDefault(Objects.requireNonNull(dataSource, "dataSource"), fallback);
    }
}
