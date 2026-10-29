package com.nexora.intelligence.domain.shared;

import java.util.Objects;

/**
 * Where a semantic catalog, request or outcome belongs: one tenant and one of its data sources. A catalog only
 * validates requests of the same scope.
 * <p>
 * This is <strong>not</strong> company-level isolation: Nexora has none inside a tenant yet. Any future business
 * scope must be added explicitly (for instance as a new component) rather than inferred from this record.
 */
public record SemanticScope(TenantId tenantId, DataSourceId dataSourceId) {

    public SemanticScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
    }
}
