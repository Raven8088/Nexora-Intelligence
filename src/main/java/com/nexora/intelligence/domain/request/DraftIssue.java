package com.nexora.intelligence.domain.request;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A reason a {@link RequestDraft} cannot become a {@link SemanticRequest}, with the category that tells who it is
 * attributable to. The field is a path in the draft; no value is ever included.
 */
public record DraftIssue(Code code, String field) {

    private static final Pattern FIELD = Pattern.compile("[a-zA-Z]+(\\[\\d{1,3}])?(\\.[a-zA-Z]+(\\[\\d{1,3}])?)*");

    /** Who an issue is attributable to. */
    public enum Category {
        /** The proposal is internally wrong or invents something: a generation error, never the user's fault. */
        MODEL_DEFECT,
        /** The question admits more than one reading; the user must clarify. */
        AMBIGUOUS,
        /** A valid proposal that Nexora cannot accept as is (limits or missing catalog information). */
        VALIDATION
    }

    public enum Code {
        /** A metric, dimension or filter target that is not in the catalog with that kind. */
        UNKNOWN_REFERENCE(Category.MODEL_DEFECT),
        /** A filter on a TEMPORAL dimension: periods are expressed only through the period, never as raw dates. */
        TEMPORAL_FILTER_NOT_ALLOWED(Category.MODEL_DEFECT),
        PERIOD_DIMENSION_NOT_TEMPORAL(Category.MODEL_DEFECT),
        /** A text value that does not appear in the question. */
        TEXT_NOT_IN_QUESTION(Category.MODEL_DEFECT),
        /** A number that does not appear as a numeral in the question. */
        NUMBER_NOT_IN_QUESTION(Category.MODEL_DEFECT),
        /** A boolean value on a dimension not known to be BOOLEAN. */
        BOOLEAN_NOT_APPLICABLE(Category.MODEL_DEFECT),
        /** Values or filters that collapse into repetitions once taken from the question. */
        DUPLICATE_AFTER_NORMALIZATION(Category.MODEL_DEFECT),
        /** The numeral in the question reads differently with a decimal comma or a decimal point. */
        NUMBER_AMBIGUOUS(Category.AMBIGUOUS),
        /** The filters plus the two period bounds exceed Semantic's limit. */
        TOO_MANY_FILTERS(Category.VALIDATION),
        /** The period's dimension has no known value type, so its bounds cannot be written safely. */
        PERIOD_TYPE_UNKNOWN(Category.VALIDATION);

        private final Category category;

        Code(Category category) {
            this.category = category;
        }

        public Category category() {
            return category;
        }
    }

    public DraftIssue {
        Objects.requireNonNull(code, "code");
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("invalid issue field");
        }
    }

    public Category category() {
        return code.category();
    }
}
