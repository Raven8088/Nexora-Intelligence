package com.nexora.intelligence.infrastructure.planning;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The planning service exists only when both the inference engine and Semantic are configured. */
class PlanningConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PlanningConfiguration.class)
            .withBean(QuestionInterpreter.class, () -> (QuestionInterpreter) (q, c) -> {
                throw new UnsupportedOperationException();
            })
            .withBean(SemanticCatalogClient.class, () -> (SemanticCatalogClient) (s, c) -> {
                throw new UnsupportedOperationException();
            })
            .withBean(LogicalPlanClient.class, () -> (LogicalPlanClient) (r, c) -> {
                throw new UnsupportedOperationException();
            });

    @Test
    void noServiceUnlessBothServicesAreConfigured() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(QuestionPlanningService.class));
        runner.withPropertyValues("nexora.intelligence.inference.base-url=http://localhost:11434/v1")
                .run(context -> assertThat(context).doesNotHaveBean(QuestionPlanningService.class));
        runner.withPropertyValues("nexora.intelligence.semantic.base-url=http://localhost:8080")
                .run(context -> assertThat(context).doesNotHaveBean(QuestionPlanningService.class));
    }

    @Test
    void theServiceIsWiredWithTheConfiguredZones() {
        UUID source = UUID.randomUUID();
        runner.withPropertyValues("nexora.intelligence.inference.base-url=http://localhost:11434/v1",
                        "nexora.intelligence.semantic.base-url=http://localhost:8080",
                        "nexora.intelligence.planning.source-zones." + source + "=America/Lima")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QuestionPlanningService.class);
                    PlanningProperties properties = context.getBean(PlanningProperties.class);
                    assertThat(properties.defaultZone()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(properties.sourceZones()).containsEntry(source, ZoneId.of("America/Lima"));
                });
    }

    @Test
    void anInvalidZoneStopsStartup() {
        runner.withPropertyValues("nexora.intelligence.inference.base-url=http://localhost:11434/v1",
                        "nexora.intelligence.semantic.base-url=http://localhost:8080",
                        "nexora.intelligence.planning.default-zone=Mars/Olympus")
                .run(context -> assertThat(context).hasFailed());
    }
}
