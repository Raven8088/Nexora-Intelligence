package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.TextRules;

import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An APPROVED and usable dimension of the catalog, with logical information only.
 * <ul>
 *   <li>TEMPORAL dimensions allow at least one granularity; CATEGORICAL ones allow none.</li>
 *   <li>{@code valueType} is optional: when known it lets Intelligence check operators and literals early; when
 *       empty those checks are left to Semantic, which is the final authority in any case.</li>
 *   <li>{@code timeZone} is optional and, as in Semantic, only applies to TEMPORAL dimensions over timestamps with
 *       time zone: it is the zone their days, weeks and months are measured in.</li>
 * </ul>
 */
public record CatalogDimension(DefinitionId id, DefinitionKey key, String name, Optional<String> description,
                               DimensionType type, Optional<ValueType> valueType, Set<TimeGranularity> granularities,
                               Optional<ZoneId> timeZone) {

    public CatalogDimension {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        name = TextRules.requireLine(name, "dimension name", SemanticCatalog.MAX_NAME_LENGTH);
        description = TextRules.optionalText(description, "dimension description",
                SemanticCatalog.MAX_DESCRIPTION_LENGTH);
        Objects.requireNonNull(type, "type");
        valueType = valueType == null ? Optional.empty() : valueType;
        timeZone = timeZone == null ? Optional.empty() : timeZone;
        Set<TimeGranularity> copy = EnumSet.noneOf(TimeGranularity.class);
        if (granularities != null) {
            granularities.forEach(g -> copy.add(Objects.requireNonNull(g, "granularity")));
        }
        if (type == DimensionType.TEMPORAL) {
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("a TEMPORAL dimension must allow at least one granularity");
            }
            if (valueType.isPresent() && !valueType.get().isTemporal()) {
                throw new IllegalArgumentException("a TEMPORAL dimension must have a DATE or TIMESTAMP value type");
            }
            if (timeZone.isPresent() && valueType.isPresent() && valueType.get() != ValueType.TIMESTAMP_TZ) {
                throw new IllegalArgumentException("a time zone applies only to timestamps with time zone");
            }
        } else {
            if (!copy.isEmpty()) {
                throw new IllegalArgumentException("granularities apply only to TEMPORAL dimensions");
            }
            if (timeZone.isPresent()) {
                throw new IllegalArgumentException("a time zone applies only to TEMPORAL dimensions");
            }
        }
        granularities = Collections.unmodifiableSet(copy);
    }

    /** A dimension without a declared time zone. */
    public CatalogDimension(DefinitionId id, DefinitionKey key, String name, Optional<String> description,
                            DimensionType type, Optional<ValueType> valueType, Set<TimeGranularity> granularities) {
        this(id, key, name, description, type, valueType, granularities, Optional.empty());
    }

    public boolean allows(TimeGranularity granularity) {
        return granularities.contains(granularity);
    }
}
