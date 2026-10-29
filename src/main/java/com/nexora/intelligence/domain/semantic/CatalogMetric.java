package com.nexora.intelligence.domain.semantic;

import com.nexora.intelligence.domain.shared.TextRules;

import java.util.Objects;
import java.util.Optional;

/**
 * An APPROVED and usable metric of the catalog, with logical information only: no table, column or other physical
 * name is ever part of it.
 *
 * @param description optional business description; empty when absent
 */
public record CatalogMetric(DefinitionId id, DefinitionKey key, String name, Optional<String> description,
                            Aggregation aggregation) {

    public CatalogMetric {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        name = TextRules.requireLine(name, "metric name", SemanticCatalog.MAX_NAME_LENGTH);
        description = TextRules.optionalText(description, "metric description", SemanticCatalog.MAX_DESCRIPTION_LENGTH);
        Objects.requireNonNull(aggregation, "aggregation");
    }
}
