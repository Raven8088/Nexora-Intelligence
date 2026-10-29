package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.planning.SourceZones;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The API exists only when enabled, and then only with a complete planning flow and configured data sources. */
class QuestionApiConfigurationTest {

    private static final String ENABLED = "nexora.intelligence.api.enabled=true";
    private static final String TENANT = "nexora.intelligence.api.data-sources.demo.tenant-id=" + UUID.randomUUID();
    private static final String SOURCE = "nexora.intelligence.api.data-sources.demo.data-source-id=" + UUID.randomUUID();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(QuestionApiConfiguration.class, QuestionController.class);

    private static QuestionPlanningService service(boolean canExecute) {
        return new QuestionPlanningService((s, c) -> {
            throw new UnsupportedOperationException();
        }, new QuestionTranslationService((q, c) -> {
            throw new UnsupportedOperationException();
        }, Clock.systemUTC()), (r, c) -> {
            throw new UnsupportedOperationException();
        }, SourceZones.utc(), canExecute ? Optional.of((ReportExecutionClient) (p, c) -> {
            throw new UnsupportedOperationException();
        }) : Optional.empty());
    }

    @Test
    void disabledByDefault() {
        runner.withBean(QuestionPlanningService.class, () -> service(true))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(QuestionController.class)
                        .doesNotHaveBean(QuestionApiProperties.class));
    }

    @Test
    void enabledWithACompleteFlowAndDataSources() {
        runner.withBean(QuestionPlanningService.class, () -> service(true))
                .withPropertyValues(ENABLED, TENANT, SOURCE)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QuestionController.class);
                    QuestionApiProperties properties = context.getBean(QuestionApiProperties.class);
                    assertThat(properties.maxBodyBytes()).isEqualTo(8192);
                    assertThat(properties.maxConcurrentQuestions()).isEqualTo(2);
                    assertThat(properties.dataSources()).containsKey("demo");
                });
    }

    @Test
    void enabledWithoutReportsOrPlanningFailsStartup() {
        runner.withPropertyValues(ENABLED, TENANT, SOURCE).run(context -> assertThat(context).hasFailed());
        runner.withBean(QuestionPlanningService.class, () -> service(false))
                .withPropertyValues(ENABLED, TENANT, SOURCE).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidSettingsFailStartup() {
        String[][] invalid = {
                {ENABLED},
                {ENABLED, TENANT, SOURCE, "nexora.intelligence.api.max-body-bytes=100"},
                {ENABLED, TENANT, SOURCE, "nexora.intelligence.api.max-concurrent-questions=0"},
                {ENABLED, "nexora.intelligence.api.data-sources.Bad_Alias.tenant-id=" + UUID.randomUUID(),
                        "nexora.intelligence.api.data-sources.Bad_Alias.data-source-id=" + UUID.randomUUID()},
                {ENABLED, TENANT}};
        for (String[] settings : invalid) {
            runner.withBean(QuestionPlanningService.class, () -> service(true)).withPropertyValues(settings)
                    .run(context -> assertThat(context).as(String.join(",", settings)).hasFailed());
        }
    }
}
