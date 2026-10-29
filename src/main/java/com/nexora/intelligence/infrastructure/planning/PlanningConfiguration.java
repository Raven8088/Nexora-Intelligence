package com.nexora.intelligence.infrastructure.planning;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.planning.SourceZones;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.domain.shared.DataSourceId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Wires {@link QuestionPlanningService} when both an inference server and Semantic are configured; it can also execute
 * plans when Reports is configured. It has no HTTP endpoint: nothing outside the application can call it yet.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexora.intelligence.inference", name = "base-url")
@ConditionalOnProperty(prefix = "nexora.intelligence.semantic", name = "base-url")
@EnableConfigurationProperties(PlanningProperties.class)
class PlanningConfiguration {

    @Bean
    QuestionPlanningService questionPlanningService(SemanticCatalogClient catalogs, QuestionInterpreter interpreter,
                                                    LogicalPlanClient plans, PlanningProperties properties,
                                                    ObjectProvider<ReportExecutionClient> executions) {
        Map<DataSourceId, java.time.ZoneId> zones = new LinkedHashMap<>();
        properties.sourceZones().forEach((id, zone) -> zones.put(new DataSourceId(id), zone));
        return new QuestionPlanningService(catalogs, new QuestionTranslationService(interpreter, Clock.systemUTC()),
                plans, new SourceZones(properties.defaultZone(), zones),
                Optional.ofNullable(executions.getIfAvailable()));
    }
}
