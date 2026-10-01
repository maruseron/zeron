package com.maruseron.zeron.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class FunctionShapeKey {
    private static final String ENCODING_VERSION = "zeron-function-shape-v1;";

    private final String canonicalEncoding;

    private FunctionShapeKey(final String canonicalEncoding) {
        this.canonicalEncoding = canonicalEncoding;
    }

    public static FunctionShapeKey of(final FunctionDescriptor functionType) {
        Objects.requireNonNull(functionType);
        final var encoding = new StringBuilder(ENCODING_VERSION);
        appendFunction(encoding, functionType);
        return new FunctionShapeKey(encoding.toString());
    }

    public String canonicalEncoding() {
        return canonicalEncoding;
    }

    private static void appendFunction(final StringBuilder encoding,
                                       final FunctionDescriptor functionType) {
        encoding.append('F').append(functionType.arity()).append('(');
        for (final var parameter : functionType.parameters()) {
            appendType(encoding, parameter);
        }
        encoding.append(')');
        appendType(encoding, functionType.returnType());
    }

    private static void appendType(final StringBuilder encoding, final TypeDescriptor type) {
        switch (type) {
            case IntDescriptor _ -> encoding.append("i;");
            case FloatDescriptor _ -> encoding.append("d;");
            case BooleanDescriptor _ -> encoding.append("b;");
            case StringDescriptor _ -> encoding.append("s;");
            case UnitDescriptor _ -> encoding.append("u;");
            case NeverDescriptor _ -> encoding.append("v;");
            case NullDescriptor _ -> encoding.append("z;");
            case TypeParameterDescriptor parameter -> {
                encoding.append('p').append(parameter.scopeId()).append(':');
                appendNominalName(encoding, parameter.name());
            }
            case NominalDescriptor nominal -> appendNominal(encoding, nominal);
            case NullableDescriptor nullable -> {
                encoding.append("q(");
                appendType(encoding, nullable.baseType());
                encoding.append(')');
            }
            case FunctionDescriptor function -> appendFunction(encoding, function);
            case ArrayDescriptor array -> {
                encoding.append("a(");
                appendType(encoding, array.elementType());
                encoding.append(')');
            }
            case ReferenceDescriptor reference -> {
                encoding.append('&');
                appendType(encoding, reference.baseType());
            }
            case GenericDescriptor generic -> {
                encoding.append('g').append(generic.typeParameters().size()).append('(');
                appendNominal(encoding, generic.baseType());
                for (final var parameter : generic.typeParameters()) {
                    appendType(encoding, parameter);
                }
                encoding.append(')');
            }
            case InferDescriptor _ ->
                    throw new IllegalArgumentException("Cannot canonicalize an inferred function shape");
        }
    }

    private static void appendNominal(final StringBuilder encoding,
                                     final NominalDescriptor nominal) {
        appendNominalName(encoding, nominal.name());
    }

    private static void appendNominalName(final StringBuilder encoding,
                                          final String value) {
        final var name = value.getBytes(StandardCharsets.UTF_8);
        encoding.append('n').append(name.length).append(':');
        for (final byte nameByte : name) {
            encoding.append(Character.forDigit((nameByte >>> 4) & 0x0f, 16));
            encoding.append(Character.forDigit(nameByte & 0x0f, 16));
        }
        encoding.append(';');
    }

    @Override
    public boolean equals(final Object other) {
        return this == other
                || other instanceof FunctionShapeKey that
                && canonicalEncoding.equals(that.canonicalEncoding);
    }

    @Override
    public int hashCode() {
        return canonicalEncoding.hashCode();
    }

    @Override
    public String toString() {
        return canonicalEncoding;
    }
}