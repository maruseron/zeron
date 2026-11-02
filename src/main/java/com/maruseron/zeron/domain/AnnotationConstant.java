package com.maruseron.zeron.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public sealed interface AnnotationConstant permits AnnotationConstant.StringValue,
        AnnotationConstant.BooleanValue, AnnotationConstant.IntegerValue,
        AnnotationConstant.DecimalValue, AnnotationConstant.NullValue,
        AnnotationConstant.ArrayValue {
    record StringValue(String value) implements AnnotationConstant {
        public StringValue {
            Objects.requireNonNull(value);
        }
    }

    record BooleanValue(boolean value) implements AnnotationConstant {}

    record IntegerValue(BigInteger value) implements AnnotationConstant {
        public IntegerValue {
            Objects.requireNonNull(value);
        }
    }

    record DecimalValue(BigDecimal value) implements AnnotationConstant {
        public DecimalValue {
            Objects.requireNonNull(value);
        }
    }

    record NullValue() implements AnnotationConstant {}

    record ArrayValue(List<AnnotationConstant> values) implements AnnotationConstant {
        public ArrayValue {
            values = List.copyOf(values);
        }
    }
}
