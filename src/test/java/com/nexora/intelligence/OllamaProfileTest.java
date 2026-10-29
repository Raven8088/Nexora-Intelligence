package com.nexora.intelligence;

import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.infrastructure.inference.OpenAiCompatibleProperties;
import com.nexora.intelligence.infrastructure.inference.OpenAiCompatibleQuestionInterpreter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code ollama} profile points the adapter to a local Ollama with gpt-oss:20b. Nothing is contacted at startup. */
@SpringBootTest
@ActiveProfiles("ollama")
class OllamaProfileTest {

    @Autowired
    private QuestionInterpreter interpreter;

    @Autowired
    private OpenAiCompatibleProperties properties;

    @Test
    void theOllamaProfileRegistersTheAdapterForTheLocalServer() {
        assertThat(interpreter).isInstanceOf(OpenAiCompatibleQuestionInterpreter.class);
        assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:11434/v1"));
        assertThat(properties.model()).isEqualTo("gpt-oss:20b");
        assertThat(properties.maxRetries()).isZero();
        assertThat(properties.structuredOutput()).isTrue();
    }
}
