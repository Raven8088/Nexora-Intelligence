package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.domain.semantic.Aggregation;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.CatalogRelationship;
import com.nexora.intelligence.domain.semantic.DefinitionId;
import com.nexora.intelligence.domain.semantic.DefinitionKey;
import com.nexora.intelligence.domain.semantic.DimensionType;
import com.nexora.intelligence.domain.semantic.RelationshipId;
import com.nexora.intelligence.domain.semantic.TimeGranularity;
import com.nexora.intelligence.domain.semantic.ValueType;
import com.nexora.intelligence.domain.shared.DataSourceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Maps Semantic's catalog responses to Intelligence's catalog, keeping logical information only: ids, keys, names,
 * descriptions, aggregations, dimension types, value types, granularities and time zones. Column ids, natural keys,
 * concepts and every other physical reference are read past and never reach the domain.
 * <ul>
 *   <li><b>Fail closed</b> ({@code CONTRACT_VIOLATION}) on anything outside the contract that could make the catalog
 *       wrong: unreadable JSON, a page that does not match the request, an item without a valid id, of another data
 *       source, of another kind or not APPROVED.</li>
 *   <li><b>Excluded</b> (skipped and logged by id): definitions that are not usable, and usable ones Intelligence
 *       cannot represent safely (an aggregation or dimension type it does not know, no known granularity, an invalid
 *       time zone, or text Intelligence's rules refuse). Semantic remains the authority and re-validates every
 *       request, so an excluded definition can only make a question unanswerable, never wrong.</li>
 *   <li>An unknown {@code valueType} is kept as unknown: operator and literal checks are then left to Semantic.</li>
 * </ul>
 */
class CatalogResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(CatalogResponseMapper.class);

    /** One page of a Semantic listing. */
    record Page(List<JsonNode> items, long totalItems) {
    }

    private final JsonMapper json;

    CatalogResponseMapper(JsonMapper json) {
        this.json = json;
    }

    Page page(String body, int expectedPage, int expectedSize) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw SemanticApi.contract("catalog page is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw SemanticApi.contract("catalog page is not a JSON object");
        }
        JsonNode items = root.get("items");
        JsonNode page = root.get("page");
        JsonNode size = root.get("size");
        JsonNode total = root.get("totalItems");
        if (items == null || !items.isArray() || !isInt(page) || !isInt(size) || total == null
                || !total.isIntegralNumber() || total.longValue() < 0) {
            throw SemanticApi.contract("catalog page does not have items, page, size and totalItems");
        }
        if (page.intValue() != expectedPage || size.intValue() != expectedSize || items.size() > expectedSize) {
            throw SemanticApi.contract("catalog page does not match the request");
        }
        List<JsonNode> list = new ArrayList<>();
        items.forEach(list::add);
        return new Page(list, total.longValue());
    }

    /** The id of a listed item; every item must have one. */
    UUID id(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw SemanticApi.contract("catalog item is not a JSON object");
        }
        return uuid(item.get("id"), "id");
    }

    Optional<CatalogMetric> metric(JsonNode item, DataSourceId dataSource) {
        if (!usableItem(item, dataSource, "METRIC")) {
            return Optional.empty();
        }
        UUID id = id(item);
        JsonNode block = item.get("metric");
        if (block == null || !block.isObject()) {
            throw SemanticApi.contract("usable metric without its metric block");
        }
        Optional<Aggregation> aggregation = known(Aggregation.class, block.get("aggregation"));
        if (aggregation.isEmpty()) {
            return excluded(id, "UNKNOWN_AGGREGATION");
        }
        try {
            return Optional.of(new CatalogMetric(new DefinitionId(id), new DefinitionKey(text(item, "key")),
                    text(item, "name"), optionalText(item, "description"), aggregation.get()));
        } catch (IllegalArgumentException e) {
            return excluded(id, "NOT_REPRESENTABLE");
        }
    }

    Optional<CatalogDimension> dimension(JsonNode item, DataSourceId dataSource) {
        if (!usableItem(item, dataSource, "DIMENSION")) {
            return Optional.empty();
        }
        UUID id = id(item);
        JsonNode block = item.get("dimension");
        if (block == null || !block.isObject()) {
            throw SemanticApi.contract("usable dimension without its dimension block");
        }
        Optional<DimensionType> type = known(DimensionType.class, block.get("type"));
        if (type.isEmpty()) {
            return excluded(id, "UNKNOWN_DIMENSION_TYPE");
        }
        Optional<ValueType> valueType = known(ValueType.class, block.get("valueType"));
        if (valueType.isEmpty() && present(block.get("valueType"))) {
            log.info("semantic catalog: dimension {} has a value type unknown to Intelligence; checks left to Semantic",
                    id);
        }
        Set<TimeGranularity> granularities = EnumSet.noneOf(TimeGranularity.class);
        JsonNode list = block.get("granularities");
        if (present(list)) {
            if (!list.isArray()) {
                throw SemanticApi.contract("dimension granularities is not an array");
            }
            list.forEach(g -> known(TimeGranularity.class, g).ifPresent(granularities::add));
        }
        if (type.get() == DimensionType.TEMPORAL && granularities.isEmpty()) {
            return excluded(id, "NO_KNOWN_GRANULARITY");
        }
        Optional<ZoneId> zone = Optional.empty();
        JsonNode timeZone = block.get("timeZone");
        if (present(timeZone)) {
            try {
                zone = Optional.of(ZoneId.of(timeZone.isString() ? timeZone.asString() : ""));
            } catch (DateTimeException e) {
                return excluded(id, "INVALID_TIME_ZONE");
            }
        }
        try {
            return Optional.of(new CatalogDimension(new DefinitionId(id), new DefinitionKey(text(item, "key")),
                    text(item, "name"), optionalText(item, "description"), type.get(), valueType, granularities,
                    zone));
        } catch (IllegalArgumentException e) {
            return excluded(id, "NOT_REPRESENTABLE");
        }
    }

    Optional<CatalogRelationship> relationship(JsonNode item, DataSourceId dataSource) {
        if (!usableItem(item, dataSource, null)) {
            return Optional.empty();
        }
        UUID id = id(item);
        try {
            return Optional.of(new CatalogRelationship(new RelationshipId(id), text(item, "name")));
        } catch (IllegalArgumentException e) {
            return excluded(id, "NOT_REPRESENTABLE");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Checks the fields every listed item must have; false when the item is APPROVED but not usable. */
    private boolean usableItem(JsonNode item, DataSourceId dataSource, String expectedKind) {
        UUID id = id(item);
        if (!dataSource.value().equals(uuid(item.get("dataSourceId"), "dataSourceId"))) {
            throw SemanticApi.contract("catalog item of another data source");
        }
        if (expectedKind != null && !expectedKind.equals(textOrNull(item.get("kind")))) {
            throw SemanticApi.contract("catalog item of another kind");
        }
        if (!"APPROVED".equals(textOrNull(item.get("status")))) {
            throw SemanticApi.contract("catalog item that is not APPROVED");
        }
        JsonNode usable = item.get("usable");
        if (usable == null || !usable.isBoolean()) {
            throw SemanticApi.contract("catalog item without usable flag");
        }
        if (!usable.booleanValue()) {
            log.debug("semantic catalog: item {} is approved but not usable; excluded", id);
            return false;
        }
        return true;
    }

    private static <T> Optional<T> excluded(UUID id, String reason) {
        log.warn("semantic catalog: item {} excluded ({})", id, reason);
        return Optional.empty();
    }

    private static <E extends Enum<E>> Optional<E> known(Class<E> type, JsonNode node) {
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(node.asString())) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static UUID uuid(JsonNode node, String field) {
        if (node == null || !node.isString()) {
            throw SemanticApi.contract("catalog item " + field + " is missing");
        }
        try {
            UUID value = UUID.fromString(node.asString());
            if (!value.toString().equals(node.asString().toLowerCase(java.util.Locale.ROOT))) {
                throw SemanticApi.contract("catalog item " + field + " is not a canonical UUID");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw SemanticApi.contract("catalog item " + field + " is not a UUID");
        }
    }

    /** A required text; missing text is reported as not representable (IllegalArgumentException). */
    private static String text(JsonNode item, String field) {
        String value = textOrNull(item.get(field));
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }

    private static Optional<String> optionalText(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (!present(node)) {
            return Optional.empty();
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(field + " is not text");
        }
        return Optional.of(node.asString());
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static boolean isInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }

    static SemanticUnavailableException tooLarge(String message) {
        return new SemanticUnavailableException(SemanticUnavailableException.Reason.CATALOG_TOO_LARGE, message);
    }
}
