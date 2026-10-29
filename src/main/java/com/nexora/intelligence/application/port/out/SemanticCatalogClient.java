package com.nexora.intelligence.application.port.out;

import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.SemanticScope;

/**
 * Driven port: the authorized semantic catalog of one data source, as Nexora Semantic publishes it. Implementations
 * are adapters for Semantic's API and live in the infrastructure layer.
 */
public interface SemanticCatalogClient {

    /**
     * Reads the current catalog of {@code scope}: only APPROVED and usable metrics, dimensions and relationships, with
     * logical information only. It is read on every call (no cache) and never truncated.
     *
     * @param correlationId Intelligence's identifier of the request, for traceability only
     * @return the catalog; it may have no metrics (callers decide how to answer then)
     * @throws SemanticUnavailableException when Semantic cannot be used or answers outside its contract
     */
    SemanticCatalog load(SemanticScope scope, CorrelationId correlationId);
}
