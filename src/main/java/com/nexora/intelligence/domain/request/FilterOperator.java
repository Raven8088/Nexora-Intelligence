package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.semantic.ValueType;

import java.util.EnumSet;
import java.util.Set;

/** Filter operators of Nexora Semantic. There is no free expression: only these operators and typed values. */
public enum FilterOperator {
    EQ(Arity.ONE),
    NE(Arity.ONE),
    GT(Arity.ONE),
    GTE(Arity.ONE),
    LT(Arity.ONE),
    LTE(Arity.ONE),
    IN(Arity.MANY),
    IS_NULL(Arity.NONE),
    IS_NOT_NULL(Arity.NONE);

    /** Number of values an operator takes. */
    public enum Arity { NONE, ONE, MANY }

    private static final Set<ValueType> ORDERED = EnumSet.of(ValueType.NUMERIC, ValueType.DATE, ValueType.TIMESTAMP,
            ValueType.TIMESTAMP_TZ, ValueType.TIME);
    private static final Set<ValueType> EQUALITY = EnumSet.of(ValueType.NUMERIC, ValueType.TEXT, ValueType.BOOLEAN,
            ValueType.DATE, ValueType.TIMESTAMP, ValueType.TIMESTAMP_TZ, ValueType.TIME, ValueType.UUID);
    private static final Set<FilterOperator> METRIC_OPERATORS = EnumSet.of(EQ, NE, GT, GTE, LT, LTE);

    private final Arity arity;

    FilterOperator(Arity arity) {
        this.arity = arity;
    }

    public Arity arity() {
        return arity;
    }

    /** Operators Semantic accepts on a dimension of this value category (same table as Semantic). */
    public boolean appliesTo(ValueType type) {
        return switch (this) {
            case IS_NULL, IS_NOT_NULL -> true;
            case EQ, NE -> EQUALITY.contains(type);
            case IN -> EQUALITY.contains(type) && type != ValueType.BOOLEAN;
            case GT, GTE, LT, LTE -> ORDERED.contains(type);
        };
    }

    /** Operators Semantic accepts on an aggregated metric value (post-aggregation filter). */
    public boolean appliesToMetrics() {
        return METRIC_OPERATORS.contains(this);
    }
}
