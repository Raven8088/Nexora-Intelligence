package com.nexora.intelligence.infrastructure.planning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Time zones of the data sources, used to measure periods ("this month", "last week") for dimensions that declare
 * no zone of their own; a dimension's zone from the catalog always takes precedence.
 *
 * @param defaultZone technical default for sources without a configured zone (UTC); it is not assumed to be the
 *                    business zone of any source
 * @param sourceZones IANA zone per data source id, e.g. {@code source-zones.<uuid>: America/Mexico_City}
 */
@ConfigurationProperties("nexora.intelligence.planning")
public record PlanningProperties(@DefaultValue("UTC") ZoneId defaultZone, Map<UUID, ZoneId> sourceZones) {

    public PlanningProperties {
        if (defaultZone == null) {
            throw new IllegalArgumentException("nexora.intelligence.planning.default-zone is required");
        }
        sourceZones = sourceZones == null ? Map.of() : Map.copyOf(sourceZones);
    }
}
