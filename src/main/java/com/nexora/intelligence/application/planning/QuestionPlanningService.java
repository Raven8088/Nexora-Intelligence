package com.nexora.intelligence.application.planning;

import com.nexora.intelligence.application.port.out.InterpretationUnavailableException;
import com.nexora.intelligence.application.port.out.LogicalPlanClient;
import com.nexora.intelligence.application.port.out.ReportExecutionClient;
import com.nexora.intelligence.application.port.out.ReportsUnavailableException;
import com.nexora.intelligence.application.port.out.SemanticCatalogClient;
import com.nexora.intelligence.application.port.out.SemanticUnavailableException;
import com.nexora.intelligence.application.translation.QuestionTranslationService;
import com.nexora.intelligence.application.translation.Translation;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.outcome.QuestionOutcome.Rejected;
import com.nexora.intelligence.domain.question.NaturalLanguageQuestion;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.result.QuestionAnswer;
import com.nexora.intelligence.domain.result.ReportResult;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.SemanticScope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a question into a logical plan of Nexora Semantic and, when Reports is available, into its executed result,
 * for one tenant and data source:
 * <ol>
 *   <li>reads the current catalog of the scope (every time: no cache);</li>
 *   <li>a catalog without usable metrics is answered with {@link Rejected} ({@code CATALOG_EMPTY}, origin
 *       INTELLIGENCE) without calling the inference engine;</li>
 *   <li>translates the question with {@link QuestionTranslationService} (interpretation + deterministic rules), with
 *       the configured zone of the source;</li>
 *   <li>a ready request is sent to Semantic, which alone validates it again and creates the plan;</li>
 *   <li>{@link #answer}: the created plan is executed by Reports, and the result is bound to the definitions of the
 *       request (columns, kinds, plan id and fingerprint must match, or nothing is presented).</li>
 * </ol>
 * Failures of Semantic, Reports or the inference engine are thrown, never presented as outcomes. No HTTP endpoint
 * exposes this service yet.
 */
public class QuestionPlanningService {

    /** Rejection code of a catalog that offers no usable metric. */
    public static final String CATALOG_EMPTY = "CATALOG_EMPTY";

    private final SemanticCatalogClient catalogs;
    private final QuestionTranslationService translation;
    private final LogicalPlanClient plans;
    private final SourceZones zones;
    private final Optional<ReportExecutionClient> executions;

    /** Planning only: {@link #answer} is not available. */
    public QuestionPlanningService(SemanticCatalogClient catalogs, QuestionTranslationService translation,
                                   LogicalPlanClient plans, SourceZones zones) {
        this(catalogs, translation, plans, zones, Optional.empty());
    }

    public QuestionPlanningService(SemanticCatalogClient catalogs, QuestionTranslationService translation,
                                   LogicalPlanClient plans, SourceZones zones,
                                   Optional<ReportExecutionClient> executions) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.translation = Objects.requireNonNull(translation, "translation");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.zones = Objects.requireNonNull(zones, "zones");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /** Whether {@link #answer} can execute plans (Reports is configured). */
    public boolean canExecute() {
        return executions.isPresent();
    }

    /**
     * @param scope tenant and data source; it comes from trusted configuration or authentication, never from the
     *              question nor from a model answer
     * @throws SemanticUnavailableException       when Semantic cannot be used
     * @throws InterpretationUnavailableException when the inference engine fails or answers invalid output
     */
    public QuestionOutcome plan(NaturalLanguageQuestion question, SemanticScope scope) {
        return planned(question, scope).outcome();
    }

    /**
     * Plans the question and executes the plan: {@link QuestionAnswer.Reported} with the result bound to the
     * definitions of the question, {@link QuestionAnswer.NotExecuted} when Reports refuses the plan, or
     * {@link QuestionAnswer.NotPlanned} when there is no plan (clarification, unsupported, rejection).
     *
     * @throws IllegalStateException              when Reports is not configured
     * @throws SemanticUnavailableException       when Semantic cannot be used
     * @throws InterpretationUnavailableException when the inference engine fails or answers invalid output
     * @throws ReportsUnavailableException        when Reports cannot execute the plan, or its result does not match
     *                                            the plan and the request ({@code CONTRACT_VIOLATION})
     */
    public QuestionAnswer answer(NaturalLanguageQuestion question, SemanticScope scope) {
        ReportExecutionClient client = executions.orElseThrow(
                () -> new IllegalStateException("Nexora Reports is not configured"));
        Planned planned = planned(question, scope);
        if (!(planned.outcome() instanceof QuestionOutcome.PlanCreated plan)) {
            return new QuestionAnswer.NotPlanned(planned.outcome());
        }
        return switch (client.execute(plan, question.correlationId())) {
            case ReportExecutionClient.Refused refused -> new QuestionAnswer.NotExecuted(plan, refused.rejection());
            case ReportExecutionClient.Executed executed -> {
                try {
                    yield new QuestionAnswer.Reported(plan, ReportResult.bind(executed.execution(), plan,
                            planned.request().orElseThrow(), planned.catalog()));
                } catch (ReportResult.MismatchException e) {
                    throw new ReportsUnavailableException(ReportsUnavailableException.Reason.CONTRACT_VIOLATION,
                            "the execution does not match the plan: " + e.getMessage(), e);
                }
            }
        };
    }

    private record Planned(SemanticCatalog catalog, Optional<SemanticRequest> request, QuestionOutcome outcome) {
    }

    private Planned planned(NaturalLanguageQuestion question, SemanticScope scope) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(scope, "scope");
        SemanticCatalog catalog = catalogs.load(scope, question.correlationId());
        if (!catalog.scope().equals(scope)) {
            throw new SemanticUnavailableException(SemanticUnavailableException.Reason.CONTRACT_VIOLATION,
                    "the catalog does not belong to the requested scope");
        }
        if (catalog.metrics().isEmpty()) {
            return new Planned(catalog, Optional.empty(),
                    new Rejected(Rejected.Origin.INTELLIGENCE, List.of(Rejected.Cause.of(CATALOG_EMPTY))));
        }
        return switch (translation.translate(question, catalog, zones.zoneOf(scope.dataSourceId()))) {
            case Translation.Answered answered -> new Planned(catalog, Optional.empty(), answered.outcome());
            case Translation.Ready ready -> new Planned(catalog, Optional.of(ready.request()),
                    plans.create(ready.request(), question.correlationId()));
        };
    }
}
