package com.maruseron.zeron.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class JavaGenericSignatureParser {
    public record MethodSignature(List<TypeParameterDescriptor> typeParameters,
                                  List<TypeDescriptor> parameterTypes, TypeDescriptor returnType,
                                  List<TypeDescriptor> throwsTypes) {
        public MethodSignature {
            typeParameters = List.copyOf(typeParameters);
            parameterTypes = List.copyOf(parameterTypes);
            throwsTypes = List.copyOf(throwsTypes);
        }

        public boolean hasMethodTypeParameters() {
            return !typeParameters.isEmpty();
        }
    }

    private static final AtomicInteger SCOPE_IDS = new AtomicInteger();

    private final String signature;
    private final Map<String, TypeParameterDescriptor> typeParameters;
    private int offset;

    private JavaGenericSignatureParser(final String signature,
                                       final List<TypeParameterDescriptor> classParameters) {
        this.signature = signature;
        typeParameters = new HashMap<>();
        classParameters.forEach(parameter -> typeParameters.put(parameter.name(), parameter));
    }

    public static List<TypeParameterDescriptor> parseClassTypeParameters(final String signature) {
        if (signature == null || !signature.startsWith("<")) return List.of();
        final var parser = new JavaGenericSignatureParser(signature, List.of());
        return parser.readTypeParameters();
    }

    public static TypeDescriptor parseFieldType(final String signature,
                                                final List<TypeParameterDescriptor> classParameters) {
        final var parser = new JavaGenericSignatureParser(signature, classParameters);
        final var result = parser.readType();
        if (parser.offset != signature.length()) throw parser.invalidSignature();
        return result;
    }

    public static MethodSignature parseMethodSignature(final String signature,
                                                       final List<TypeParameterDescriptor> classParameters) {
        final var parser = new JavaGenericSignatureParser(signature, classParameters);
        final var methodParameters = parser.peek('<') ? parser.readTypeParameters() : List.<TypeParameterDescriptor>of();
        parser.consume('(');
        final var parameters = new ArrayList<TypeDescriptor>();
        while (!parser.peek(')')) parameters.add(parser.readType());
        parser.consume(')');
        final var returnType = parser.readType();
        final var throwsTypes = new ArrayList<TypeDescriptor>();
        while (parser.peek('^')) {
            parser.offset++;
            throwsTypes.add(parser.readType());
        }
        if (parser.offset != signature.length()) throw parser.invalidSignature();
        return new MethodSignature(methodParameters, parameters, returnType, throwsTypes);
    }

    private List<TypeParameterDescriptor> readTypeParameters() {
        consume('<');
        final var names = new ArrayList<String>();
        final var parameters = new ArrayList<TypeParameterDescriptor>();
        while (!peek('>')) {
            final var name = readUntil(':');
            consume(':');
            final var placeholder = new TypeParameterDescriptor(-SCOPE_IDS.incrementAndGet(), name);
            typeParameters.put(name, placeholder);
            names.add(name);

            final var bounds = new ArrayList<TypeDescriptor>();
            if (!peek(':')) bounds.add(readType());
            while (peek(':')) {
                consume(':');
                bounds.add(readType());
            }
            bounds.removeIf(bound -> bound instanceof NullableDescriptor nullable
                    && nullable.baseType() instanceof AnyDescriptor);
            final var parameter = new TypeParameterDescriptor(placeholder.scopeId(), name, bounds, false,
                    TypeParameterDescriptor.Variance.INVARIANT, true, false, true);
            typeParameters.put(name, parameter);
            parameters.add(parameter);
        }
        consume('>');
        return List.copyOf(parameters);
    }

    private TypeDescriptor readType() {
        if (offset >= signature.length()) throw invalidSignature();
        final var token = signature.charAt(offset++);
        return switch (token) {
            case 'V' -> TypeDescriptor.ofUnit();
            case 'Z' -> TypeDescriptor.ofBoolean();
            case 'B' -> TypeDescriptor.ofByte();
            case 'C' -> TypeDescriptor.ofChar();
            case 'I' -> TypeDescriptor.ofInt();
            case 'J' -> TypeDescriptor.ofLong();
            case 'F', 'D' -> TypeDescriptor.ofFloat();
            case 'T' -> readTypeVariable();
            case 'L' -> readClassType();
            default -> throw invalidSignature();
        };
    }

    private TypeDescriptor readTypeVariable() {
        final var name = readUntil(';');
        consume(';');
        final var parameter = typeParameters.get(name);
        if (parameter == null) throw invalidSignature();
        return parameter;
    }

    private TypeDescriptor readClassType() {
        var binaryName = new StringBuilder();
        var arguments = new ArrayList<TypeDescriptor>();
        while (offset < signature.length()) {
            final var token = signature.charAt(offset);
            if (token == ';') {
                offset++;
                final var base = TypeDescriptor.ofName(binaryName.toString().replace('/', '.'));
                return arguments.isEmpty() ? javaReference(base.name())
                        : TypeDescriptor.genericOf((NominalDescriptor) base, arguments).toNullable();
            }
            if (token == '<') {
                offset++;
                while (!peek('>')) arguments.add(readTypeArgument());
                consume('>');
                continue;
            }
            if (token == '.') {
                offset++;
                binaryName.append('$');
                continue;
            }
            binaryName.append(token);
            offset++;
        }
        throw invalidSignature();
    }

    private TypeDescriptor readTypeArgument() {
        if (peek('*')) {
            offset++;
            return new TypeProjectionDescriptor(TypeProjectionDescriptor.Kind.OUT,
                    TypeDescriptor.ofAny().toNullable());
        }
        if (peek('+')) {
            offset++;
            return new TypeProjectionDescriptor(TypeProjectionDescriptor.Kind.OUT, readType());
        }
        if (peek('-')) {
            offset++;
            return new TypeProjectionDescriptor(TypeProjectionDescriptor.Kind.IN, readType());
        }
        return readType();
    }

    private static TypeDescriptor javaReference(final String binaryName) {
        return JavaTypeMapping.toZeronType(java.lang.constant.ClassDesc.of(binaryName));
    }

    private String readUntil(final char delimiter) {
        final var start = offset;
        while (offset < signature.length() && signature.charAt(offset) != delimiter) offset++;
        if (start == offset || offset == signature.length()) throw invalidSignature();
        return signature.substring(start, offset);
    }

    private boolean peek(final char token) {
        return offset < signature.length() && signature.charAt(offset) == token;
    }

    private void consume(final char token) {
        if (!peek(token)) throw invalidSignature();
        offset++;
    }

    private IllegalArgumentException invalidSignature() {
        return new IllegalArgumentException("Unsupported Java generic signature: " + signature);
    }
}
