package com.nexora.intelligence.infrastructure.web;

import com.nexora.intelligence.domain.question.Language;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a question request strictly:
 * <pre>
 * { "question": "¿Cuántos pozos hay por campo?",
 *   "language": "es",                              (optional: "es" or "en")
 *   "context": { "dataSource": "wellnode-demo" } } (an alias configured in nexora.intelligence.api.data-sources)
 * </pre>
 * Exactly these properties; unknown or repeated properties, trailing content, invalid UTF-8 and wrong types are
 * refused. The tenant and data source ids are never accepted from the request: the alias selects them from the
 * configuration. Error messages are fixed rule texts; they never echo the request.
 */
final class QuestionRequestReader {

    /** A request that passed the structural checks; the question text is validated by the domain afterwards. */
    record QuestionRequest(String question, Optional<Language> language, String dataSource) {
    }

    /** The body is not a readable JSON question. */
    static final class MalformedException extends RuntimeException {
        MalformedException() {
            super("malformed question request");
        }
    }

    /** The body is readable but some fields break their rules. */
    static final class InvalidException extends RuntimeException {
        private final List<Map<String, String>> errors;

        InvalidException(List<Map<String, String>> errors) {
            super("invalid question request");
            this.errors = List.copyOf(errors);
        }

        List<Map<String, String>> errors() {
            return errors;
        }
    }

    private static final Set<String> FIELDS = Set.of("question", "language", "context");
    private static final Set<String> CONTEXT_FIELDS = Set.of("dataSource");

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    QuestionRequest read(byte[] body) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedException();
        }
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (JacksonException e) {
            throw new MalformedException();
        }
        if (root == null || !root.isObject()) {
            throw new MalformedException();
        }
        for (String name : root.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw new MalformedException();
            }
        }
        List<Map<String, String>> errors = new ArrayList<>();
        JsonNode question = root.get("question");
        if (question == null || !question.isString()) {
            errors.add(error("question", "is required and must be a string"));
        }
        Optional<Language> language = Optional.empty();
        JsonNode lang = root.get("language");
        if (lang != null && !lang.isNull()) {
            if (lang.isString() && "es".equals(lang.asString())) {
                language = Optional.of(Language.ES);
            } else if (lang.isString() && "en".equals(lang.asString())) {
                language = Optional.of(Language.EN);
            } else {
                errors.add(error("language", "must be \"es\" or \"en\""));
            }
        }
        String dataSource = null;
        JsonNode context = root.get("context");
        if (context == null || !context.isObject()) {
            errors.add(error("context", "is required and must be an object"));
        } else {
            for (String name : context.propertyNames()) {
                if (!CONTEXT_FIELDS.contains(name)) {
                    throw new MalformedException();
                }
            }
            JsonNode alias = context.get("dataSource");
            if (alias == null || !alias.isString() || !QuestionApiProperties.ALIAS.matcher(alias.asString()).matches()) {
                errors.add(error("context.dataSource", "is required and must be a configured data source alias"));
            } else {
                dataSource = alias.asString();
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidException(errors);
        }
        return new QuestionRequest(question.asString(), language, dataSource);
    }

    static Map<String, String> error(String field, String message) {
        return Map.of("field", field, "message", message);
    }
}
