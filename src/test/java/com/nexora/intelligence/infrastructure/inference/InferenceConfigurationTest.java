package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter is registered only when an inference server is configured, and invalid settings stop startup. */
class InferenceConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InferenceConfiguration.class);

    @Test
    void noAdapterWithoutABaseUrl() {
        runner.withPropertyValues("nexora.intelligence.inference.model=gpt-oss:20b")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(QuestionInterpreter.class));
    }

    @Test
    void theAdapterIsRegisteredWhenABaseUrlIsConfigured() {
        runner.withPropertyValues(
                        "nexora.intelligence.inference.base-url=http://localhost:11434/v1/",
                        "nexora.intelligence.inference.model=gpt-oss:20b",
                        "nexora.intelligence.inference.read-timeout=90s",
                        "nexora.intelligence.inference.max-retries=1",
                        "nexora.intelligence.inference.reasoning-effort=low")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QuestionInterpreter.class);
                    assertThat(context.getBean(QuestionInterpreter.class))
                            .isInstanceOf(OpenAiCompatibleQuestionInterpreter.class);
                    OpenAiCompatibleProperties props = context.getBean(OpenAiCompatibleProperties.class);
                    assertThat(props.baseUrl()).isEqualTo(URI.create("http://localhost:11434/v1"));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.maxRetries()).isEqualTo(1);
                    assertThat(props.invalidOutputRetries()).isEqualTo(1);
                    assertThat(props.structuredOutput()).isTrue();
                    assertThat(props.maxTokens()).isEqualTo(4096);
                    assertThat(props.contextTokens()).isEqualTo(8192);
                    assertThat(props.reasoningEffort()).isEqualTo("low");
                    assertThat(props.apiKey()).isNull();
                });
    }

    @Test
    void theRepetitionOfRejectedAnswersCanBeTurnedOff() {
        runner.withPropertyValues(
                        "nexora.intelligence.inference.base-url=http://localhost:11434/v1",
                        "nexora.intelligence.inference.model=gpt-oss:20b",
                        "nexora.intelligence.inference.invalid-output-retries=0")
                .run(context -> assertThat(context.getBean(OpenAiCompatibleProperties.class).invalidOutputRetries())
                        .isZero());
    }

    @Test
    void invalidSettingsFailStartup() {
        String[][] invalid = {
                {"base-url=ftp://localhost/v1"},
                {"base-url=http://user:pass@localhost:11434/v1"},
                {"base-url=http://localhost:11434/v1?key=x"},
                {"base-url=http://localhost:11434/v1", "model="},
                {"base-url=http://localhost:11434/v1", "model=m", "max-retries=2"},
                {"base-url=http://localhost:11434/v1", "model=m", "max-retries=-1"},
                {"base-url=http://localhost:11434/v1", "model=m", "invalid-output-retries=2"},
                {"base-url=http://localhost:11434/v1", "model=m", "invalid-output-retries=-1"},
                {"base-url=http://localhost:11434/v1", "model=m", "read-timeout=0s"},
                {"base-url=http://localhost:11434/v1", "model=m", "read-timeout=11m"},
                {"base-url=http://localhost:11434/v1", "model=m", "connect-timeout=-1s"},
                {"base-url=http://localhost:11434/v1", "model=m", "max-tokens=10"},
                {"base-url=http://localhost:11434/v1", "model=m", "reasoning-effort=extreme"},
                {"base-url=http://localhost:11434/v1", "model=m", "context-tokens=1024"},
                {"base-url=http://localhost:11434/v1", "model=m", "context-tokens=4096"},
                {"base-url=http://localhost:11434/v1", "model=m", "max-tokens=8192", "context-tokens=8192"}};
        for (String[] settings : invalid) {
            String[] properties = new String[settings.length];
            for (int i = 0; i < settings.length; i++) {
                properties[i] = "nexora.intelligence.inference." + settings[i];
            }
            runner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void theApiKeyIsNeverPrinted() {
        OpenAiCompatibleProperties props = new OpenAiCompatibleProperties(URI.create("https://inference.local/v1"),
                "gpt-oss:20b", Duration.ofSeconds(2), Duration.ofSeconds(60), 0, 1, true, 2048, 8192, null,
                "  test-key-not-real  ");

        assertThat(props.apiKey()).isEqualTo("test-key-not-real");
        assertThat(props.toString()).doesNotContain("test-key-not-real").contains("apiKey=***");
        assertThatThrownBy(() -> new OpenAiCompatibleProperties(null, "m", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, 1, true, 2048, 8192, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
