package com.nexora.intelligence.infrastructure.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The local question API (INT-3.6). Disabled unless {@code enabled=true}. It answers only requests from the loopback
 * interface and has no end-user authentication (pilot decision): it must never be exposed on a network.
 *
 * @param enabled                registers {@code POST /api/v1/questions}
 * @param maxBodyBytes           upper bound of a request body
 * @param maxConcurrentQuestions questions answered at the same time; more are refused with 429 (each one may use the
 *                               inference engine for a long time)
 * @param dataSources            the data sources a question may target, by alias: the tenant and data source always
 *                               come from here, never from the request
 */
@ConfigurationProperties("nexora.intelligence.api")
public record QuestionApiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("8192") int maxBodyBytes,
        @DefaultValue("2") int maxConcurrentQuestions,
        Map<String, DataSource> dataSources) {

    private static final String PREFIX = "nexora.intelligence.api.";
    static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** One allowed target: a tenant and one of its data sources in Nexora Semantic. */
    public record DataSource(UUID tenantId, UUID dataSourceId) {
        public DataSource {
            if (tenantId == null || dataSourceId == null) {
                throw new IllegalArgumentException(PREFIX + "data-sources.<alias> needs tenant-id and data-source-id");
            }
        }
    }

    public QuestionApiProperties {
        dataSources = dataSources == null ? Map.of() : Map.copyOf(dataSources);
        if (maxBodyBytes < 1024 || maxBodyBytes > 65536) {
            throw new IllegalArgumentException(PREFIX + "max-body-bytes must be between 1024 and 65536");
        }
        if (maxConcurrentQuestions < 1 || maxConcurrentQuestions > 16) {
            throw new IllegalArgumentException(PREFIX + "max-concurrent-questions must be between 1 and 16");
        }
        for (String alias : dataSources.keySet()) {
            if (!ALIAS.matcher(alias).matches()) {
                throw new IllegalArgumentException(PREFIX + "data-sources aliases must match " + ALIAS.pattern());
            }
        }
        if (enabled && dataSources.isEmpty()) {
            throw new IllegalArgumentException(PREFIX + "data-sources must define at least one data source");
        }
    }
}
