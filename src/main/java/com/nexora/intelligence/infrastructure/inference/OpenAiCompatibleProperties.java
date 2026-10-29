package com.nexora.intelligence.infrastructure.inference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Connection to an inference server exposing an OpenAI-compatible Chat Completions API (Ollama, vLLM, llama.cpp,
 * LM Studio...). Everything is external configuration; secrets only come from the environment.
 *
 * @param baseUrl          API base, e.g. {@code http://localhost:11434/v1}; {@code /chat/completions} is appended
 * @param model            model name as the server knows it, e.g. {@code gpt-oss:20b}
 * @param connectTimeout   time to establish the connection
 * @param readTimeout      time to wait for the complete answer
 * @param maxRetries       retries of transient failures only (connection failures, 502/503/504): 0 or 1
 * @param invalidOutputRetries 0 or 1: how many times a complete answer rejected by the strict validation is asked
 *                         again, with a fixed note naming the broken rule (never the rejected answer). Never applied
 *                         to HTTP errors, timeouts, truncated or filtered answers, oversized responses or context
 *                         limits. The new answer is validated just as strictly. Default 1
 * @param structuredOutput ask the server to enforce the JSON schema ({@code response_format}); the answer is
 *                         validated strictly either way
 * @param maxTokens        upper bound of generated tokens (reasoning models may count their reasoning); reserved in
 *                         the context window before anything is sent
 * @param contextTokens    context window of the model <em>as configured on the server</em> (for Ollama,
 *                         {@code OLLAMA_CONTEXT_LENGTH}); prompt and reserved answer must fit in it
 * @param reasoningEffort  optional {@code low}, {@code medium} or {@code high} for reasoning models; not sent if empty
 * @param apiKey           optional bearer token, only from the environment; never logged
 */
@ConfigurationProperties("nexora.intelligence.inference")
public record OpenAiCompatibleProperties(
        URI baseUrl,
        String model,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout,
        @DefaultValue("0") int maxRetries,
        @DefaultValue("1") int invalidOutputRetries,
        @DefaultValue("true") boolean structuredOutput,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("8192") int contextTokens,
        String reasoningEffort,
        String apiKey) {

    public static final int MAX_RETRIES = 1;
    public static final int MAX_INVALID_OUTPUT_RETRIES = 1;
    public static final int MIN_CONTEXT_TOKENS = 2048;
    public static final int MAX_CONTEXT_TOKENS = 1_048_576;
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMinutes(10);
    private static final Set<String> REASONING_EFFORTS = Set.of("low", "medium", "high");

    public OpenAiCompatibleProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "nexora.intelligence.inference.base-url must be an http(s) URL without user info, query or fragment");
        }
        String base = baseUrl.toString();
        baseUrl = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        if (model == null || model.isBlank() || model.length() > 200) {
            throw new IllegalArgumentException("nexora.intelligence.inference.model is required (at most 200 characters)");
        }
        model = model.strip();
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("nexora.intelligence.inference.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || readTimeout.compareTo(MAX_READ_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "nexora.intelligence.inference.read-timeout must be positive and at most " + MAX_READ_TIMEOUT);
        }
        if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("nexora.intelligence.inference.max-retries must be 0 or 1");
        }
        if (invalidOutputRetries < 0 || invalidOutputRetries > MAX_INVALID_OUTPUT_RETRIES) {
            throw new IllegalArgumentException("nexora.intelligence.inference.invalid-output-retries must be 0 or 1");
        }
        if (maxTokens < 64 || maxTokens > 32768) {
            throw new IllegalArgumentException("nexora.intelligence.inference.max-tokens must be between 64 and 32768");
        }
        if (contextTokens < MIN_CONTEXT_TOKENS || contextTokens > MAX_CONTEXT_TOKENS) {
            throw new IllegalArgumentException("nexora.intelligence.inference.context-tokens must be between "
                    + MIN_CONTEXT_TOKENS + " and " + MAX_CONTEXT_TOKENS);
        }
        if (maxTokens >= contextTokens) {
            throw new IllegalArgumentException(
                    "nexora.intelligence.inference.max-tokens must be lower than context-tokens");
        }
        reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
        if (reasoningEffort != null && !REASONING_EFFORTS.contains(reasoningEffort)) {
            throw new IllegalArgumentException(
                    "nexora.intelligence.inference.reasoning-effort must be low, medium or high");
        }
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleProperties[baseUrl=" + baseUrl + ", model=" + model + ", connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + ", maxRetries=" + maxRetries + ", invalidOutputRetries=" + invalidOutputRetries
                + ", structuredOutput=" + structuredOutput + ", maxTokens=" + maxTokens + ", contextTokens="
                + contextTokens + ", reasoningEffort="
                + reasoningEffort + ", apiKey=" + (apiKey == null ? "none" : "***") + "]";
    }
}
