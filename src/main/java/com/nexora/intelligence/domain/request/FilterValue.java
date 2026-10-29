package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.ValueType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A typed filter value. Text is always data, never an expression; dates and times travel as ISO-8601 text, as
 * Semantic expects, and Semantic parses them according to the dimension's type. There is no null value: null
 * checks use {@link FilterOperator#IS_NULL} and {@link FilterOperator#IS_NOT_NULL}.
 */
public sealed interface FilterValue {

    int MAX_TEXT_LENGTH = 1000;

    /** Canonical form used to detect repeated values; never logged. */
    String canonical();

    /** Whether Semantic can read this value for a dimension of the given category. */
    boolean fits(ValueType type);

    static FilterValue text(String value) {
        return new Text(value);
    }

    static FilterValue number(BigDecimal value) {
        return new Number(value);
    }

    static FilterValue bool(boolean value) {
        return new Bool(value);
    }

    /** Text up to {@value #MAX_TEXT_LENGTH} characters, without control characters; kept exactly as given. */
    record Text(String value) implements FilterValue {
        public Text {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("text filter value must not be empty");
            }
            if (value.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("text filter value must be at most " + MAX_TEXT_LENGTH
                        + " characters");
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("text filter value must not contain control characters");
            }
        }

        @Override
        public String canonical() {
            return "T:" + value;
        }

        @Override
        public boolean fits(ValueType type) {
            return type != ValueType.NUMERIC && type != ValueType.BOOLEAN && type != ValueType.OTHER;
        }
    }

    record Number(BigDecimal value) implements FilterValue {
        public Number {
            Objects.requireNonNull(value, "number filter value");
        }

        @Override
        public String canonical() {
            return "N:" + value.stripTrailingZeros().toPlainString();
        }

        @Override
        public boolean fits(ValueType type) {
            return type == ValueType.NUMERIC;
        }
    }

    record Bool(boolean value) implements FilterValue {
        @Override
        public String canonical() {
            return "B:" + value;
        }

        @Override
        public boolean fits(ValueType type) {
            return type == ValueType.BOOLEAN;
        }
    }
}
