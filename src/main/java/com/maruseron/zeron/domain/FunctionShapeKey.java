package com.maruseron.zeron.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class FunctionShapeKey {
    private static final String ENCODING_VERSION = "zeron-function-shape-v1;";
    private static final int MAX_READABLE_SIGNATURE_LENGTH = 64;

    private final String canonicalEncoding;
    private final String readableSignature;

    private FunctionShapeKey(final String canonicalEncoding, final String readableSignature) {
        this.canonicalEncoding = canonicalEncoding;
        this.readableSignature = readableSignature;
    }

    public static FunctionShapeKey of(final FunctionDescriptor functionType) {
        Objects.requireNonNull(functionType);
        if (functionType.isHandler()) {
            return of(TypeDescriptor.functionOf("", functionType.returnType(), TypeDescriptor.ofAny()));
        }
        final var encoding = new StringBuilder(ENCODING_VERSION);
        appendFunction(encoding, functionType);
        return new FunctionShapeKey(encoding.toString(), readableSignature(functionType));
    }

    public String canonicalEncoding() {
        return canonicalEncoding;
    }

    public String readableSignature() {
        return readableSignature;
    }

    private static String readableSignature(final FunctionDescriptor functionType) {
        final var signature = new StringBuilder().append(functionType.arity());
        for (final var parameter : functionType.parameters()) {
            signature.append('$').append(readableType(parameter));
        }
        signature.append("$To$").append(readableType(functionType.returnType()));
        return signature.length() <= MAX_READABLE_SIGNATURE_LENGTH
                ? signature.toString()
                : signature.substring(0, MAX_READABLE_SIGNATURE_LENGTH);
    }

    private static String readableType(final TypeDescriptor type) {
        final var readable = switch (type) {
            case AnyDescriptor _ -> "Any";
            case IntDescriptor _ -> "Int";
            case CharDescriptor _ -> "Char";
            case LongDescriptor _ -> "Long";
            case ByteDescriptor _ -> "Byte";
            case ByteArrayDescriptor _ -> "ByteArray";
            case FloatDescriptor _ -> "Float";
            case BooleanDescriptor _ -> "Boolean";
            case StringDescriptor _ -> "String";
            case UnitDescriptor _ -> "Unit";
            case NeverDescriptor _ -> "Never";
            case NullDescriptor _ -> "Null";
            case TypeParameterDescriptor parameter -> parameter.name()
                    + (parameter.isHigherKinded() ? "$Constructor" : "");
            case AppliedConstructorDescriptor applied -> applied.constructor().name()
                    + "$" + readableType(applied.argument());
            case TypeConstructorDescriptor constructor -> simpleName(constructor.baseType().name())
                    + constructor.arguments().stream().map(argument ->
                            argument instanceof InferDescriptor ? "Hole" : readableType(argument))
                            .collect(java.util.stream.Collectors.joining("$", "$", ""));
            case NominalDescriptor nominal -> simpleName(nominal.name());
            case NullableDescriptor nullable -> "Nullable$" + readableType(nullable.baseType());
            case FunctionDescriptor function -> "Function" + function.arity();
            case ArrayDescriptor array -> "Array$" + readableType(array.elementType());
            case ReferenceDescriptor reference -> "Ref$" + readableType(reference.baseType());
            case TypeProjectionDescriptor projection ->
                    (projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "Out$" : "In$")
                            + readableType(projection.bound());
            case CapturedTypeDescriptor capture -> readableType(capture.upperBound());
            case GenericDescriptor generic -> {
                final var name = new StringBuilder(simpleName(generic.baseType().name()));
                for (final var parameter : generic.typeParameters()) {
                    name.append('$').append(readableType(parameter));
                }
                yield name.toString();
            }
            case InferDescriptor _ ->
                    throw new IllegalArgumentException("Cannot name an inferred function shape");
        };
        return readable.replaceAll("[^A-Za-z0-9_$]", "_");
    }

    private static String simpleName(final String name) {
        final var separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
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
            case AnyDescriptor _ -> encoding.append("y;");
            case IntDescriptor _ -> encoding.append("i;");
            case CharDescriptor _ -> encoding.append("h;");
            case LongDescriptor _ -> encoding.append("j;");
            case ByteDescriptor _ -> encoding.append("c;");
            case ByteArrayDescriptor _ -> encoding.append("A;");
            case FloatDescriptor _ -> encoding.append("d;");
            case BooleanDescriptor _ -> encoding.append("b;");
            case StringDescriptor _ -> encoding.append("s;");
            case UnitDescriptor _ -> encoding.append("u;");
            case NeverDescriptor _ -> encoding.append("v;");
            case NullDescriptor _ -> encoding.append("z;");
            case TypeParameterDescriptor parameter -> {
                encoding.append('p').append(parameter.scopeId())
                        .append(parameter.isHigherKinded() ? 'h' : 't').append(':');
                appendNominalName(encoding, parameter.name());
            }
            case AppliedConstructorDescriptor applied -> {
                encoding.append('k');
                appendType(encoding, applied.constructor());
                appendType(encoding, applied.argument());
            }
            case TypeConstructorDescriptor constructor -> {
                encoding.append('c').append(constructor.arguments().size()).append('(');
                appendNominal(encoding, constructor.baseType());
                for (final var argument : constructor.arguments()) {
                    if (argument instanceof InferDescriptor) encoding.append("_;");
                    else appendType(encoding, argument);
                }
                encoding.append(')');
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
            case TypeProjectionDescriptor projection -> {
                encoding.append(projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "+(" : "-(");
                appendType(encoding, projection.bound());
                encoding.append(')');
            }
            case CapturedTypeDescriptor capture -> appendType(encoding, capture.upperBound());
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