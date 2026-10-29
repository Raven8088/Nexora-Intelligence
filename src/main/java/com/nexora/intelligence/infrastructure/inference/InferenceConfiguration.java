package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the OpenAI-compatible adapter only when an inference server is configured
 * ({@code nexora.intelligence.inference.base-url}). Without it the application starts with no interpreter. Nothing
 * connects to the server at startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexora.intelligence.inference", name = "base-url")
@EnableConfigurationProperties(OpenAiCompatibleProperties.class)
class InferenceConfiguration {

    @Bean
    QuestionInterpreter questionInterpreter(OpenAiCompatibleProperties properties) {
        return new OpenAiCompatibleQuestionInterpreter(properties);
    }
}
