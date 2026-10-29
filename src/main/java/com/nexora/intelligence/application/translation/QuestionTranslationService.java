package com.nexora.intelligence.application.translation;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.InterpretationUnavailableException.Reason;
import com.nexora.intelligence.application.port.out.QuestionInterpreter;
import com.nexora.intelligence.domain.interpretation.Interpretation;
import com.nexora.intelligence.domain.interpretation.InterpretationStatus;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected.Cause;
import com.nexora.intelligence.domain.period.TemporalContext;
import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.question.LanguageDetector;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.DraftIssue;
import com.nexora.intelligence.domain.request.DraftResolution;
import com.nexora.intelligence.domain.request.RequestDraftResolver;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns a question into a request for Semantic with one call to the interpreter followed by the deterministic rules
 * of {@link RequestDraftResolver}. Outcomes:
 * <ul>
 *   <li>unsupported or clarification asked by the interpretation: answered as such;</li>
 *   <li>resolved draft: {@link Translation.Ready};</li>
 *   <li>model defect: {@link InterpretationUnavailableException} {@code INVALID_MODEL_OUTPUT}, never retried and
 *       never attributed to the user;</li>
 *   <li>ambiguity: {@link QuestionOutcome.NeedsClarification} in the declared language, else the detected one,
 *       else Spanish;</li>
 *   <li>validation failure: {@link Rejected} with origin INTELLIGENCE.</li>
 * </ul>
 * Periods are measured in the data source's zone (UTC unless given); a dimension's own zone takes precedence.
 * Nothing here calls Semantic.
 */
public class QuestionTranslationService {

    private final QuestionInterpreter interpreter;
    private final Clock clock;
    private final ZoneId sourceZone;

    public QuestionTranslationService(QuestionInterpreter interpreter, Clock clock) {
        this(interpreter, clock, ZoneOffset.UTC);
    }

    public QuestionTranslationService(QuestionInterpreter interpreter, Clock clock, ZoneId sourceZone) {
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    public Translation translate(NaturalLanguageQuestion question, SemanticCatalog catalog) {
        return translate(question, catalog, sourceZone);
    }

    /**
     * As {@link #translate(NaturalLanguageQuestion, SemanticCatalog)}, measuring periods in {@code sourceZone} (the
     * default zone of the catalog's data source); a dimension's own zone still takes precedence.
     */
    public Translation translate(NaturalLanguageQuestion question, SemanticCatalog catalog, ZoneId sourceZone) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(sourceZone, "sourceZone");
        Interpretation interpretation = interpreter.interpret(question, catalog);
        if (interpretation.status() == InterpretationStatus.UNSUPPORTED) {
            return new Translation.Answered(new QuestionOutcome.Unsupported());
        }
        if (interpretation.status() == InterpretationStatus.NEEDS_CLARIFICATION) {
            return new Translation.Answered(new QuestionOutcome.NeedsClarification(
                    interpretation.clarificationQuestion().orElseThrow()));
        }
        DraftResolution resolution = RequestDraftResolver.resolve(interpretation.request().orElseThrow(), catalog,
                question, new TemporalContext(clock.instant(), sourceZone));
        return switch (resolution) {
            case DraftResolution.Resolved resolved -> new Translation.Ready(resolved.request());
            case DraftResolution.ModelDefect defect -> throw new InterpretationUnavailableException(
                    Reason.INVALID_MODEL_OUTPUT, "the proposed request does not hold: " + codes(defect.issues()));
            case DraftResolution.Ambiguous ambiguous -> new Translation.Answered(new QuestionOutcome.NeedsClarification(
                    ClarificationTemplates.forIssues(ambiguous.issues(), language(question))));
            case DraftResolution.ValidationFailed failed -> new Translation.Answered(rejection(failed));
        };
    }

    private static Language language(NaturalLanguageQuestion question) {
        return question.language().or(() -> LanguageDetector.detect(question.text())).orElse(Language.ES);
    }

    private static Rejected rejection(DraftResolution.ValidationFailed failed) {
        List<Cause> causes = new ArrayList<>();
        failed.issues().forEach(i -> causes.add(Cause.of(i.code().name(), i.field())));
        failed.violations().forEach(v -> causes.add(Cause.of(v.code().name(), v.field())));
        return Rejected.capped(Rejected.Origin.INTELLIGENCE, causes);
    }

    private static String codes(List<DraftIssue> issues) {
        return issues.stream().map(i -> i.code().name()).distinct().collect(Collectors.joining(","));
    }
}
