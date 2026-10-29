package com.nexora.intelligence.infrastructure.inference;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * {@link QuestionInterpreter} over an OpenAI-compatible Chat Completions API (Ollama, vLLM, llama.cpp, LM Studio...).
 * Nothing in it is specific to one server or model: base URL, model and limits are configuration.
 * <ul>
 *   <li>One call per question. The catalog is sent as closed aliases ({@link CatalogAliases}) with logical names
 *       only, and the output schema is generated from those aliases; no id or physical name reaches the model.</li>
 *   <li>Before sending, the estimated prompt (rules, examples, catalog, question, schema) plus the reserved answer
 *       must fit the configured context window ({@link PromptBudget}); otherwise nothing is sent.</li>
 *   <li>The fixed rules travel in the system message; the question is sent alone as the user message.</li>
 *   <li>Only the final {@code content} of the answer is read (never a model's separate reasoning) and it is
 *       validated strictly ({@link ModelOutputParser}); invalid output is never repaired nor turned into a result.</li>
 *   <li>Retries, at most {@link OpenAiCompatibleProperties#MAX_RETRIES}, only for failures where the server did not
 *       process the request: connection refused or not established in time, and HTTP 502/503/504. Never for 4xx,
 *       read timeouts or invalid output.</li>
 *   <li>Repetition of a rejected answer, at most
 *       {@link OpenAiCompatibleProperties#MAX_INVALID_OUTPUT_RETRIES} ({@code invalid-output-retries}), only when a
 *       complete answer ({@code finish_reason=stop}) was rejected by {@link ModelOutputParser}: the same request is
 *       sent again with a fixed note naming the broken rule, never the rejected answer, and the new answer is
 *       validated just as strictly. Sampling is not perfectly deterministic on inference servers even at
 *       temperature 0, so an occasional deviation (INT-3.7, V7) does not become a failure. Never for HTTP errors,
 *       timeouts, truncated or filtered answers, oversized responses or context limits.</li>
 *   <li>Logs carry the correlation id, outcome, attempts and latency, and for a rejected answer the broken rule and
 *       its shape (enumerated values and field names); never the question nor the model's output.</li>
 * </ul>
 */
public class OpenAiCompatibleQuestionInterpreter implements QuestionInterpreter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleQuestionInterpreter.class);
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);
    private static final Duration RETRY_DELAY = Duration.ofMillis(250);

    private final OpenAiCompatibleProperties properties;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private final ModelOutputParser parser = new ModelOutputParser(json);

    public OpenAiCompatibleQuestionInterpreter(OpenAiCompatibleProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.baseUrl().toString());
        if (properties.apiKey() != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        this.http = builder.build();
    }

    @Override
    public Interpretation interpret(NaturalLanguageQuestion question, SemanticCatalog catalog) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(catalog, "catalog");
        long started = System.nanoTime();
        CatalogAliases aliases = new CatalogAliases(catalog);
        String system = InterpretationPrompt.systemMessage(aliases, question.language(), json);
        ObjectNode format = properties.structuredOutput() ? format(aliases, system, question) : null;
        String periodSchema = format == null ? "none" : exactPeriods(format) ? "exact" : "compact";
        int estimatedPromptTokens;
        try {
            estimatedPromptTokens = PromptBudget.check(properties.contextTokens(), properties.maxTokens(), system,
                    question.text(), format == null ? "" : json.writeValueAsString(format));
        } catch (InterpretationUnavailableException e) {
            throw failed(question, 0, started, e);
        }
        String request = requestBody(question, system, format);
        int attempt = 0;
        int transientRetries = 0;
        int invalidRetries = 0;
        while (true) {
            attempt++;
            try {
                String content = content(send(request));
                Interpretation interpretation;
                try {
                    interpretation = parser.parse(content, aliases);
                } catch (RejectedModelOutputException rejected) {
                    String retrySystem = InterpretationPrompt.retrySystemMessage(system, rejected.getMessage());
                    if (invalidRetries < properties.invalidOutputRetries()
                            && fits(retrySystem, question, format)) {
                        invalidRetries++;
                        log.warn("interpretation {} attempt {} rejected (rule: {}{}); asking once more latencyMs={}",
                                question.correlationId(), attempt, rejected.getMessage(),
                                rejected.shape().map(shape -> "; " + shape).orElse(""), elapsedMillis(started));
                        request = requestBody(question, retrySystem, format);
                        continue;
                    }
                    int last = attempt;
                    rejected.shape().ifPresent(shape -> log.warn(
                            "interpretation {} attempt {} rejected (rule: {}; {}) latencyMs={}",
                            question.correlationId(), last, rejected.getMessage(), shape, elapsedMillis(started)));
                    throw rejected;
                }
                log.info("interpretation {} completed: intent={} status={} attempts={} latencyMs={} "
                                + "catalogAliases={} estimatedPromptTokens={} periodSchema={}",
                        question.correlationId(), interpretation.intent(), interpretation.status(), attempt,
                        elapsedMillis(started), aliases.metricAliases().size() + aliases.dimensionAliases().size(),
                        estimatedPromptTokens, periodSchema);
                return interpretation;
            } catch (TransientFailure e) {
                if (transientRetries < properties.maxRetries()) {
                    transientRetries++;
                    log.warn("interpretation {} attempt {} failed transiently ({}); retrying",
                            question.correlationId(), attempt, e.failure.reason());
                    pause();
                    continue;
                }
                throw failed(question, attempt, started, e.failure);
            } catch (InterpretationUnavailableException e) {
                throw failed(question, attempt, started, e);
            }
        }
    }

    /**
     * The schema with the exact period shapes when the prompt, that schema and a possible repetition note fit the
     * window; otherwise the compact schema (the exact shapes add about 900 estimated tokens, which a demo-sized
     * catalog does not fit with context-tokens 8192 and max-tokens 4096). Validation is identical in both cases.
     */
    private ObjectNode format(CatalogAliases aliases, String system, NaturalLanguageQuestion question) {
        ObjectNode exact = InterpretationPrompt.responseFormat(aliases, json, true);
        String longest = InterpretationPrompt.retrySystemMessage(system, "x".repeat(RejectedModelOutputException.MAX_RULE_LENGTH));
        return fits(longest, question, exact) ? exact : InterpretationPrompt.responseFormat(aliases, json, false);
    }

    private static boolean exactPeriods(ObjectNode format) {
        JsonNode request = format.get("json_schema").get("schema").get("properties").get("request");
        JsonNode period = request.has("anyOf") ? request.get("anyOf").get(1).get("properties").get("period") : null;
        return period != null && period.has("anyOf") && period.get("anyOf").size() > 2;
    }

    /** The repetition is only sent if its longer prompt still fits the window (same budget as the first call). */
    private boolean fits(String system, NaturalLanguageQuestion question, ObjectNode format) {
        try {
            PromptBudget.check(properties.contextTokens(), properties.maxTokens(), system, question.text(),
                    format == null ? "" : json.writeValueAsString(format));
            return true;
        } catch (InterpretationUnavailableException e) {
            return false;
        }
    }

    private String requestBody(NaturalLanguageQuestion question, String system, ObjectNode format) {
        ObjectNode body = json.createObjectNode();
        body.put("model", properties.model());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", question.text());
        body.put("temperature", 0);
        body.put("max_tokens", properties.maxTokens());
        body.put("stream", false);
        if (format != null) {
            body.set("response_format", format);
        }
        if (properties.reasoningEffort() != null) {
            body.put("reasoning_effort", properties.reasoningEffort());
        }
        return json.writeValueAsString(body);
    }

    private String send(String request) {
        try {
            return http.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status != 200) {
                            InterpretationUnavailableException failure = new InterpretationUnavailableException(
                                    Reason.HTTP_ERROR, "inference server answered HTTP " + status);
                            if (TRANSIENT_STATUSES.contains(status)) {
                                throw new TransientFailure(failure);
                            }
                            throw failure;
                        }
                        return readLimited(res.getBody());
                    });
        } catch (ResourceAccessException e) {
            throw transportFailure(e);
        }
    }

    private static RuntimeException transportFailure(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) {
                return new TransientFailure(new InterpretationUnavailableException(Reason.TIMEOUT,
                        "connection to the inference server timed out", e));
            }
            if (cause instanceof ConnectException) {
                return new TransientFailure(new InterpretationUnavailableException(Reason.HTTP_ERROR,
                        "inference server unreachable", e));
            }
            if (cause instanceof HttpTimeoutException) {
                return new InterpretationUnavailableException(Reason.TIMEOUT,
                        "inference server did not answer in time", e);
            }
        }
        return new InterpretationUnavailableException(Reason.HTTP_ERROR, "inference request failed", e);
    }

    private static String readLimited(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new InterpretationUnavailableException(Reason.INVALID_MODEL_OUTPUT,
                    "inference response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The final answer of the first choice; reasoning fields some models add are ignored on purpose. */
    private String content(String response) {
        JsonNode root;
        try {
            root = json.readTree(response);
        } catch (JacksonException e) {
            throw invalid("inference response is not valid JSON");
        }
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw invalid("inference response has no choices");
        }
        checkUsage(root.get("usage"));
        JsonNode choice = choices.get(0);
        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull() && !"stop".equals(finish.asString(""))) {
            throw invalid("inference response did not finish normally");
        }
        JsonNode message = choice.get("message");
        JsonNode content = message == null ? null : message.get("content");
        if (content == null || !content.isString()) {
            throw invalid("inference response has no textual content");
        }
        return content.stringValue();
    }

    /**
     * Second line of defence after {@link PromptBudget}: when the server reports how many prompt tokens it used,
     * an answer whose prompt and reserved answer would not fit the window is not trusted (the server may have
     * truncated the prompt). Absent or malformed usage is ignored; the estimate already applied.
     */
    private void checkUsage(JsonNode usage) {
        JsonNode promptTokens = usage == null ? null : usage.get("prompt_tokens");
        if (promptTokens != null && promptTokens.isIntegralNumber()
                && promptTokens.longValue() + properties.maxTokens() > properties.contextTokens()) {
            throw new InterpretationUnavailableException(Reason.CONTEXT_LIMIT_EXCEEDED,
                    "the server reported a prompt that does not fit the configured context");
        }
    }

    private InterpretationUnavailableException failed(NaturalLanguageQuestion question, int attempts, long started,
                                                     InterpretationUnavailableException failure) {
        log.warn("interpretation {} failed: reason={} attempts={} latencyMs={}", question.correlationId(),
                failure.reason(), attempts, elapsedMillis(started));
        return failure;
    }

    private static InterpretationUnavailableException invalid(String message) {
        return new InterpretationUnavailableException(Reason.INVALID_MODEL_OUTPUT, message);
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterpretationUnavailableException(Reason.HTTP_ERROR, "interrupted while retrying", e);
        }
    }

    /** A failure where the server did not process the request, so one retry is safe. */
    private static final class TransientFailure extends RuntimeException {
        private final transient InterpretationUnavailableException failure;

        private TransientFailure(InterpretationUnavailableException failure) {
            super(failure.getMessage(), failure, false, false);
            this.failure = failure;
        }
    }
}
