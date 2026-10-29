package com.nexora.intelligence.domain.result;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * One cell of a report result, kept with its exact type: decimals as {@link BigDecimal} with their scale, integers as
 * {@link BigInteger}, temporal values as {@code java.time} types. Values are data from the customer's database and are
 * never logged.
 */
public sealed interface ResultValue {

    /** Whether this value can appear in a column of {@code kind}. NULL fits every kind. */
    boolean fits(ValueKind kind);

    /** SQL NULL. */
    record Null() implements ResultValue {
        public static final Null INSTANCE = new Null();

        @Override
        public boolean fits(ValueKind kind) {
            return true;
        }
    }

    record Text(String value) implements ResultValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.TEXT;
        }
    }

    /** An exact decimal; its scale is kept ({@code 200.500} stays {@code 200.500}). */
    record Decimal(BigDecimal value) implements ResultValue {
        public Decimal {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.NUMERIC;
        }
    }

    record Integer(BigInteger value) implements ResultValue {
        public Integer {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.INTEGER;
        }
    }

    record Bool(boolean value) implements ResultValue {
        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.BOOLEAN;
        }
    }

    record Date(LocalDate value) implements ResultValue {
        public Date {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.DATE;
        }
    }

    record Timestamp(LocalDateTime value) implements ResultValue {
        public Timestamp {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.TIMESTAMP;
        }
    }

    record TimestampTz(Instant value) implements ResultValue {
        public TimestampTz {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.TIMESTAMP_TZ;
        }
    }

    record Time(LocalTime value) implements ResultValue {
        public Time {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.TIME;
        }
    }

    record Uuid(java.util.UUID value) implements ResultValue {
        public Uuid {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean fits(ValueKind kind) {
            return kind == ValueKind.UUID;
        }
    }
}
