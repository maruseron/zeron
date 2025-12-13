package com.maruseron.zeron.domain;

import java.util.*;

public sealed interface TypeDescriptor permits
        Never, Infer, Unit, Nominal, Generic, Function {

    String descriptor();

    static Nominal ofName(String name) {
        return new Nominal(name, EnumSet.noneOf(TypeModifier.class));
    }

    static Infer ofInfer() {
        return Infer.INSTANCE;
    }

    static Never ofNever() { return Never.NEVER; }

    static Unit ofUnit() {
        return Unit.UNIT;
    }

    static Nominal ofInt() {
        return ofName("Int");
    }

    static Nominal ofFloat() {
        return ofName("Float");
    }

    static Nominal ofBoolean() {
        return ofName("Boolean");
    }

    static Nominal ofString() {
        return ofName("String");
    }

    static Generic genericOf(final Nominal baseType,
                             final List<TypeDescriptor> typeParams) {
        return new Generic(baseType, typeParams);
    }

    static Generic genericOf(final Nominal baseType,
                             final TypeDescriptor... typeParameters) {
        return genericOf(baseType, List.of(typeParameters));
    }

    static Nominal newTypeParameter(final String name) {
        return ofName(name).toTypeParameter();
    }

    static Function functionOf(final String name,
                                     final TypeDescriptor returnType,
                                     final TypeDescriptor... parameterTypes) {
        return new Function(name, returnType, List.of(parameterTypes));
    }

    static Function lambdaOf(final TypeDescriptor returnType,
                             final TypeDescriptor parameterType) {
        return functionOf("", returnType, parameterType == null
                ? new TypeDescriptor[]{}
                : new TypeDescriptor[]{parameterType});
    }

    // CHECKERS

    default TypeDescriptor or(TypeDescriptor other) {
        return this instanceof Infer ? other : this;
    }

    default boolean isDoubleWidth() {
        return false;
    }

    // FUNCTION DESCRIPTOR SUPPORT
    /*

    */

    // GENERIC DESCRIPTOR SUPPORT

    /*
    public boolean isGeneric() {
        return descriptor().startsWith("@");
    }

    public TypeDescriptor baseType() {
        if (!isGeneric()) {
            throw new UnsupportedOperationException("Not a generic descriptor.");
        }

        return Arrays
                .stream(descriptor.split(" "))
                .skip(2) // skip $ arity
                .findFirst()
                .map(TypeDescriptor::new)
                .orElseThrow();
    }

    public List<TypeDescriptor> typeParameters() {
        if (!isGeneric()) {
            throw new UnsupportedOperationException("Not a generic descriptor.");
        }

        final var genericTokens = Arrays
                .stream(descriptor.split(" "))
                .skip(1) // skip $
                .collect(Collectors.toCollection(LinkedList::new));

        // @ 1 List Person
        final var arity = Integer.parseInt(genericTokens.removeFirst());
        genericTokens.removeFirst();
        final var args = new ArrayList<TypeDescriptor>();

        for (int i = 0; i < arity; i++) {
            args.add(new TypeDescriptor(Objects.requireNonNull(extractNext(genericTokens))));
        }

        return args;
    }

    private Stream<String> bits() {
        final var tokens = Arrays
                .stream(descriptor.split(" "))
                .collect(Collectors.toCollection(LinkedList::new));
        return Stream.iterate(
                extractNext(tokens),
                Objects::nonNull,
                _ -> extractNext(tokens));
    }

    private String extractNext(final LinkedList<String> tokens) {
        if (tokens.isEmpty()) return null;

        final var token = tokens.removeFirst();
        // function
        if (token.startsWith("$")) {
            final var arity = Integer.parseInt(tokens.removeFirst());
            final var type = Stream.<String>builder().add("$ " + arity);
            for (int i = 0; i < arity + 1; i++) {
                type.add(extractNext(tokens));
            }
            return type.build().map(String::strip).collect(Collectors.joining(" "));
        }
        // generic
        if (token.startsWith("@")) {
            final var arity = Integer.parseInt(tokens.removeFirst());
            final var type = Stream.<String>builder().add("@ " + arity);
            for (int i = 0; i < arity + 1; i++) { // must extract arity + 1
                type.add(extractNext(tokens));
            }
            return type.build().map(String::strip).collect(Collectors.joining(" "));
        }

        // if neither, type is flat: split on wrapper flags (:)
        return token;
    }
    */
}
