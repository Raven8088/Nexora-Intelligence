package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.application.planning.QuestionPlanningService;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.domain.question.CorrelationId;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.result.QuestionAnswer;
import com.nexora.intelligence.domain.shared.DataSourceId;
import com.nexora.intelligence.domain.shared.SemanticScope;
import com.nexora.intelligence.domain.shared.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * {@code POST /api/v1/questions}: answers a question about one configured data source with
 * {@link QuestionPlanningService#answer} (catalog → interpretation → plan in Semantic → execution in Reports).
 * <ul>
 *   <li>Only registered with {@code nexora.intelligence.api.enabled=true}, and then only when the planning service can
 *       execute plans (inference, Semantic and Reports configured); otherwise startup fails.</li>
 *   <li>Only answers the loopback interface ({@link LoopbackOnlyFilter}); there is no end-user authentication
 *       (pilot decision). The tenant and the data source come from the configured alias, never from the request.</li>
 *   <li>Every answer (report, clarification, unsupported, rejection) is a 200 with {@code status}; every failure is a
 *       problem document with a controlled code ({@link ApiProblems}). The correlation id is assigned here and
 *       returned in {@code X-Request-Id}; one sent by the client is ignored.</li>
 *   <li>Logs carry the correlation id, the alias, the status and the duration; never the question, values or
 *       tokens.</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(prefix = "nexora.intelligence.api", name = "enabled", havingValue = "true")
class QuestionController {

    static final String PATH = "/api/v1/questions";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    private final QuestionPlanningService service;
    private final QuestionApiProperties properties;
    private final Semaphore slots;
    private final QuestionRequestReader reader = new QuestionRequestReader();
    private final QuestionAnswerWriter writer = new QuestionAnswerWriter();

    QuestionController(ObjectProvider<QuestionPlanningService> service, QuestionApiProperties properties) {
        QuestionPlanningService planning = service.getIfAvailable();
        if (planning == null || !planning.canExecute()) {
            throw new IllegalStateException("nexora.intelligence.api.enabled requires the inference engine, "
                    + "Nexora Semantic and Nexora Reports to be configured");
        }
        this.service = planning;
        this.properties = properties;
        this.slots = new Semaphore(properties.maxConcurrentQuestions());
    }

    @PostMapping(path = PATH)
    ResponseEntity<String> ask(HttpServletRequest request) {
        CorrelationId correlationId = CorrelationId.generate();
        long started = System.nanoTime();
        if (!isJson(request.getContentType())) {
            return problem(ApiProblems.Problem.UNSUPPORTED_MEDIA_TYPE, correlationId, null);
        }
        byte[] body;
        try {
            body = readBody(request);
        } catch (IOException e) {
            return problem(ApiProblems.Problem.MALFORMED_REQUEST, correlationId, null);
        }
        if (body == null) {
            return problem(ApiProblems.Problem.PAYLOAD_TOO_LARGE, correlationId, null);
        }
        QuestionRequestReader.QuestionRequest parsed;
        try {
            parsed = reader.read(body);
        } catch (QuestionRequestReader.MalformedException e) {
            return problem(ApiProblems.Problem.MALFORMED_REQUEST, correlationId, null);
        } catch (QuestionRequestReader.InvalidException e) {
            return problem(ApiProblems.Problem.VALIDATION_FAILED, correlationId, e.errors());
        }
        QuestionApiProperties.DataSource target = properties.dataSources().get(parsed.dataSource());
        if (target == null) {
            return problem(ApiProblems.Problem.VALIDATION_FAILED, correlationId, List.of(QuestionRequestReader.error(
                    "context.dataSource", "is required and must be a configured data source alias")));
        }
        NaturalLanguageQuestion question;
        try {
            question = new NaturalLanguageQuestion(parsed.question(), parsed.language(), correlationId);
        } catch (IllegalArgumentException e) {
            return problem(ApiProblems.Problem.VALIDATION_FAILED, correlationId, List.of(QuestionRequestReader.error(
                    "question", "must have 1 to " + NaturalLanguageQuestion.MAX_TEXT_LENGTH
                            + " characters and no control characters other than line breaks")));
        }
        SemanticScope scope = new SemanticScope(new TenantId(target.tenantId()),
                new DataSourceId(target.dataSourceId()));
        if (!slots.tryAcquire()) {
            return problem(ApiProblems.Problem.TOO_MANY_QUESTIONS, correlationId, null);
        }
        try {
            QuestionAnswer answer = service.answer(question, scope);
            log.info("question correlationId={} dataSource={} answer={} durationMs={}", correlationId,
                    parsed.dataSource(), answer.getClass().getSimpleName(), millis(started));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .header(REQUEST_ID, correlationId.toString())
                    .body(writer.write(answer, correlationId, parsed.dataSource()));
        } catch (InterpretationUnavailableException e) {
            return failure(ApiProblems.of(e), correlationId, parsed.dataSource(), e, started);
        } catch (SemanticUnavailableException e) {
            return failure(ApiProblems.of(e), correlationId, parsed.dataSource(), e, started);
        } catch (ReportsUnavailableException e) {
            return failure(ApiProblems.of(e), correlationId, parsed.dataSource(), e, started);
        } catch (RuntimeException e) {
            log.error("question correlationId={} dataSource={} failed unexpectedly", correlationId,
                    parsed.dataSource(), e);
            return problem(ApiProblems.Problem.INTERNAL_ERROR, correlationId, null);
        } finally {
            slots.release();
        }
    }

    private ResponseEntity<String> failure(ApiProblems.Problem problem, CorrelationId correlationId, String dataSource,
                                           RuntimeException e, long started) {
        log.warn("question correlationId={} dataSource={} failed code={} reason={} durationMs={}", correlationId,
                dataSource, problem.code(), e.getMessage(), millis(started));
        return problem(problem, correlationId, null);
    }

    /** The body, or null when it exceeds the limit. */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > properties.maxBodyBytes()) {
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(properties.maxBodyBytes() + 1);
            return body.length > properties.maxBodyBytes() ? null : body;
        }
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)
                    && Optional.ofNullable(type.getCharset()).map(c -> c.name().equals("UTF-8")).orElse(true);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ResponseEntity<String> problem(ApiProblems.Problem problem, CorrelationId correlationId,
                                                  List<Map<String, String>> errors) {
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(REQUEST_ID, correlationId.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiProblems.body(problem, correlationId, errors));
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
