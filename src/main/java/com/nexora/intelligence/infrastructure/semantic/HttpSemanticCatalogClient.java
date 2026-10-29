package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException.Reason;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogLimitExceededException;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.CatalogRelationship;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.SemanticScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Reads the catalog of a data source from Semantic on every call (no cache):
 * <ul>
 *   <li>{@code GET /api/v1/data-sources/{id}/definitions?kind=METRIC&status=APPROVED&page=n&size=200}, the same with
 *       {@code kind=DIMENSION}, and {@code GET …/relationships?status=APPROVED&page=n&size=200}: three requests for a
 *       catalog of up to 200 items of each kind, never one per column;</li>
 *   <li>all pages are read (at most {@value #MAX_PAGES} per kind); a listing that changes while it is read (total
 *       that moves, repeated ids, a count that does not add up) is read again once and otherwise fails with
 *       {@code CATALOG_INCONSISTENT};</li>
 *   <li>only usable items are kept ({@link CatalogResponseMapper}); more than the domain's limits fails with
 *       {@code CATALOG_TOO_LARGE}, never truncated.</li>
 * </ul>
 */
class HttpSemanticCatalogClient implements SemanticCatalogClient {

    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 5;
    private static final Logger log = LoggerFactory.getLogger(HttpSemanticCatalogClient.class);

    private final SemanticApi api;
    private final CatalogResponseMapper mapper;

    HttpSemanticCatalogClient(SemanticApi api, CatalogResponseMapper mapper) {
        this.api = Objects.requireNonNull(api, "api");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public SemanticCatalog load(SemanticScope scope, CorrelationId correlationId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(correlationId, "correlationId");
        String base = "/api/v1/data-sources/" + scope.dataSourceId() + "/";
        List<CatalogMetric> metrics = listing(scope, correlationId, base + "definitions?kind=METRIC&status=APPROVED",
                "list-metrics", mapper::metric);
        List<CatalogDimension> dimensions = listing(scope, correlationId,
                base + "definitions?kind=DIMENSION&status=APPROVED", "list-dimensions", mapper::dimension);
        List<CatalogRelationship> relationships = listing(scope, correlationId,
                base + "relationships?status=APPROVED", "list-relationships", mapper::relationship);
        try {
            SemanticCatalog catalog = new SemanticCatalog(scope, metrics, dimensions, relationships);
            log.info("semantic catalog correlationId={} dataSourceId={} metrics={} dimensions={} relationships={}",
                    correlationId, scope.dataSourceId(), metrics.size(), dimensions.size(), relationships.size());
            return catalog;
        } catch (CatalogLimitExceededException e) {
            throw CatalogResponseMapper.tooLarge("the usable catalog exceeds the limit of " + e.limit() + " "
                    + e.part());
        } catch (IllegalArgumentException e) {
            throw SemanticApi.contract("the catalog breaks its uniqueness rules");
        }
    }

    private <T> List<T> listing(SemanticScope scope, CorrelationId correlationId, String path, String operation,
                                BiFunction<JsonNode, com.nexora.intelligence.domain.shared.DataSourceId,
                                        Optional<T>> map) {
        for (int reading = 1; ; reading++) {
            Optional<List<JsonNode>> items = read(scope, correlationId, path, operation);
            if (items.isPresent()) {
                List<T> mapped = new ArrayList<>();
                for (JsonNode item : items.get()) {
                    map.apply(item, scope.dataSourceId()).ifPresent(mapped::add);
                }
                return mapped;
            }
            if (reading == 2) {
                throw new SemanticUnavailableException(Reason.CATALOG_INCONSISTENT,
                        operation + ": the listing changed while it was read");
            }
            log.info("semantic {} correlationId={}: listing changed while reading; reading it again", operation,
                    correlationId);
        }
    }

    /** All items of a listing, or empty when the pages were not consistent with each other. */
    private Optional<List<JsonNode>> read(SemanticScope scope, CorrelationId correlationId, String path,
                                          String operation) {
        List<JsonNode> all = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        long total = -1;
        for (int page = 0; ; page++) {
            URI uri = api.uri(path + "&page=" + page + "&size=" + PAGE_SIZE);
            SemanticHttp.Response response = api.get(scope.tenantId(), uri, operation, correlationId);
            if (response.status() != 200) {
                throw api.failure(response, operation);
            }
            CatalogResponseMapper.Page current = mapper.page(response.body(), page, PAGE_SIZE);
            if (total < 0) {
                total = current.totalItems();
                if (total > (long) PAGE_SIZE * MAX_PAGES) {
                    throw CatalogResponseMapper.tooLarge(operation + ": more than " + PAGE_SIZE * MAX_PAGES
                            + " approved items");
                }
            } else if (current.totalItems() != total) {
                return Optional.empty();
            }
            for (JsonNode item : current.items()) {
                if (!ids.add(mapper.id(item))) {
                    return Optional.empty();
                }
                all.add(item);
            }
            long expectedSoFar = Math.min(total, (long) (page + 1) * PAGE_SIZE);
            if (all.size() != expectedSoFar) {
                return Optional.empty();
            }
            if (all.size() == total) {
                return Optional.of(all);
            }
        }
    }
}
