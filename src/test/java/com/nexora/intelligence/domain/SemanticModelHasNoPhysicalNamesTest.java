package com.nexora.intelligence.domain;

import com.nexora.intelligence.domain.outcome.PlanId;
import com.nexora.intelligence.domain.outcome.QuestionOutcome;
import com.nexora.intelligence.domain.period.DateRange;
import com.nexora.intelligence.domain.period.TemporalContext;
import com.nexora.intelligence.domain.request.DraftIssue;
import com.nexora.intelligence.domain.request.DraftResolution;
import com.nexora.intelligence.domain.request.FilterCondition;
import com.nexora.intelligence.domain.request.PeriodSelection;
import com.nexora.intelligence.domain.request.RequestDraft;
import com.nexora.intelligence.domain.request.FilterValue;
import com.nexora.intelligence.domain.request.GranularityChoice;
import com.nexora.intelligence.domain.request.RequestViolation;
import com.nexora.intelligence.domain.request.SemanticRequest;
import com.nexora.intelligence.domain.semantic.CatalogDimension;
import com.nexora.intelligence.domain.semantic.CatalogMetric;
import com.nexora.intelligence.domain.semantic.CatalogRelationship;
import com.nexora.intelligence.domain.semantic.SemanticCatalog;
import com.nexora.intelligence.domain.shared.ClarificationText;
import com.nexora.intelligence.domain.shared.SemanticScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard against leaking the physical model: no component of the semantic model may hold table, column, schema,
 * natural-key, SQL or expression data. Semantic ids and logical names only.
 */
class SemanticModelHasNoPhysicalNamesTest {

    private static final Pattern PHYSICAL = Pattern.compile(
            ".*(table|column|schema|naturalkey|relation(object)?id|sql|expression|query|physical|join).*");

    @Test
    void noRecordComponentDescribesThePhysicalModel() {
        List<Class<?>> types = List.of(SemanticScope.class, SemanticCatalog.class, CatalogMetric.class,
                CatalogDimension.class, CatalogRelationship.class, SemanticRequest.class, FilterCondition.class,
                FilterValue.Text.class, FilterValue.Number.class, FilterValue.Bool.class, GranularityChoice.class,
                RequestViolation.class, PlanId.class, QuestionOutcome.PlanCreated.class,
                QuestionOutcome.NeedsClarification.class, QuestionOutcome.Rejected.class,
                QuestionOutcome.Rejected.Cause.class, RequestDraft.class, PeriodSelection.class, DraftIssue.class,
                DateRange.class, TemporalContext.class, ClarificationText.class,
                DraftResolution.Resolved.class, DraftResolution.ModelDefect.class, DraftResolution.Ambiguous.class,
                DraftResolution.ValidationFailed.class,
                com.nexora.intelligence.domain.interpretation.Interpretation.class);

        List<String> offending = types.stream()
                .flatMap(type -> Stream.of(type.getRecordComponents())
                        .map(RecordComponent::getName)
                        .filter(name -> PHYSICAL.matcher(name.toLowerCase(Locale.ROOT)).matches())
                        .map(name -> type.getSimpleName() + "." + name))
                .toList();

        assertThat(offending).isEmpty();
    }
}
