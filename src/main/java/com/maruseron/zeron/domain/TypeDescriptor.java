package com.maruseron.zeron.domain;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record TypeDescriptor(String descriptor) {

    public TypeDescriptor(String descriptor) {
        this.descriptor = descriptor.strip();
    }

    public TypeDescriptor or(TypeDescriptor other) {
        return isInferred() ? other : this;
    }

    public static TypeDescriptor inferred() {
        return new TypeDescriptor("<infer>");
    }

    public static TypeDescriptor ofNever() {
        return new TypeDescriptor(":Never");
    }

    public static TypeDescriptor ofUnit() {
        return new TypeDescriptor(":Unit");
    }

    public static TypeDescriptor ofInt() {
        return new TypeDescriptor(":Int");
    }

    public static TypeDescriptor ofDouble() {
        return new TypeDescriptor(":Double");
    }

    public static TypeDescriptor ofBoolean() {
        return new TypeDescriptor(":Boolean");
    }

    public static TypeDescriptor ofString() {
        return new TypeDescriptor(":String");
    }

    public TypeDescriptor arrayOf() {
        return isArray() ? this : new TypeDescriptor("a" + descriptor);
    }

    public static TypeDescriptor arrayOf(final TypeDescriptor typeDescriptor) {
        return typeDescriptor.isArray() ? typeDescriptor : typeDescriptor.arrayOf();
    }

    public static TypeDescriptor arrayOf(final String descriptorString) {
        final var typeDescriptor = new TypeDescriptor(descriptorString);
        return typeDescriptor.isArray() ? typeDescriptor : typeDescriptor.arrayOf();
    }

    public TypeDescriptor mutable() {
        return isMutable() ? this : new TypeDescriptor("m" + descriptor);
    }

    public static TypeDescriptor mutable(final TypeDescriptor typeDescriptor) {
        return typeDescriptor.isMutable() ? typeDescriptor : typeDescriptor.mutable();
    }

    public static TypeDescriptor mutable(final String descriptorString) {
        final var typeDescriptor = new TypeDescriptor(descriptorString);
        return typeDescriptor.isMutable() ? typeDescriptor : typeDescriptor.mutable();
    }

    public TypeDescriptor nullable() {
        return isNullable() ? this : new TypeDescriptor("n" + descriptor);
    }

    public static TypeDescriptor nullable(final TypeDescriptor typeDescriptor) {
        return typeDescriptor.isNullable() ? typeDescriptor : typeDescriptor.nullable();
    }

    public static TypeDescriptor nullable(final String descriptorString) {
        final var typeDescriptor = new TypeDescriptor(descriptorString);
        return typeDescriptor.isMutable() ? typeDescriptor : typeDescriptor.nullable();
    }

    public static TypeDescriptor genericOf(final TypeDescriptor baseType,
                                    final TypeDescriptor... typeParameters) {
        final var types = Arrays
                .stream(typeParameters)
                .map(TypeDescriptor::toString)
                .collect(Collectors.joining(" "));
        return new TypeDescriptor("@ " + typeParameters.length + " " + baseType + " " + types);
    }

    public static TypeDescriptor functionOf(final String name,
                                     final TypeDescriptor returnType,
                                     final TypeDescriptor... parameterTypes) {
        final var types = Arrays
                .stream(parameterTypes)
                .map(TypeDescriptor::toString)
                .collect(Collectors.joining(" "));
        return new TypeDescriptor((name.isEmpty() ? "$ " : "\\ ")
                + parameterTypes.length + " " + types + " " + returnType);
    }

    public static TypeDescriptor lambdaOf(final TypeDescriptor returnType,
                                   final TypeDescriptor... parameterTypes) {
        return functionOf("", returnType, parameterTypes);
    }

    // CHECKERS

    public boolean isBuiltIn() {
        return switch (descriptor) {
            case ":Never", ":Unit", ":Int", ":Double", ":Boolean", ":String" -> true;
            default -> false;
        };
    }

    public boolean isInferred() {
        return descriptor.equals("<infer>");
    }

    public boolean isArray() {
        if (isFunction()) return false; // functions cannot be nullable
        if (isGeneric()) return baseType().isNullable();
        return descriptor.split(":")[0].contains("a");
    }

    public boolean isMutable() {
        if (isFunction()) return false; // functions cannot be nullable
        if (isGeneric()) return baseType().isNullable();
        return descriptor.split(":")[0].contains("m");
    }

    public boolean isNullable() {
        if (isFunction()) return false; // functions cannot be nullable
        if (isGeneric()) return baseType().isNullable();
        return descriptor.split(":")[0].contains("n");
    }

    public boolean isDoubleWidth() {
        return false;
    }

    // FUNCTION DESCRIPTOR SUPPORT

    public boolean isFunction() {
        return descriptor.startsWith("$") || descriptor.startsWith("\\");
    }

    public int arity() {
        if (!isFunction()) {
            throw new UnsupportedOperationException("Not a function descriptor.");
        }

        final var arityIndex = descriptor.indexOf("$") + 2;
        return Integer.parseInt(descriptor.substring(arityIndex, arityIndex + 1));
    }

    public List<TypeDescriptor> functionParams() {
        if (!isFunction()) {
            throw new UnsupportedOperationException("Not a function descriptor.");
        }

        final var functionTokens = Arrays
                .stream(descriptor.split(" "))
                .skip(1) // skip $
                .collect(Collectors.toCollection(LinkedList::new));

        final var arity = Integer.parseInt(functionTokens.removeFirst());
        final var args = new ArrayList<TypeDescriptor>();

        for (int i = 0; i < arity; i++) {
            args.add(new TypeDescriptor(Objects.requireNonNull(extractNext(functionTokens))));
        }

        return args;
    }

    public TypeDescriptor returnType() {
        if (!isFunction()) {
            throw new UnsupportedOperationException("Not a function descriptor.");
        }

        final var functionTokens = Arrays
                .stream(descriptor.split(" "))
                .skip(1) // skip $
                .collect(Collectors.toCollection(LinkedList::new));

        final var arity = Integer.parseInt(functionTokens.removeFirst()) + 1;
        final var args = new ArrayList<TypeDescriptor>();

        for (int i = 0; i < arity; i++) {
            args.add(new TypeDescriptor(Objects.requireNonNull(extractNext(functionTokens))));
        }

        return args.getLast();
    }

    public TypeDescriptor withReturnType(final TypeDescriptor returnType) {
        if (!isFunction()) {
            throw new UnsupportedOperationException("Not a function descriptor.");
        }

        final var streamBuilder = Stream.builder().add("$").add(arity());
        functionParams().forEach(streamBuilder);
        return new TypeDescriptor(streamBuilder
                .add(returnType)
                .build()
                .map(Object::toString)
                .collect(Collectors.joining(" ")));
    }

    // GENERIC DESCRIPTOR SUPPORT

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
            final var type = new StringBuilder("$ " + arity);
            for (int i = 0; i < arity + 1; i++) {
                type.append(" ").append(extractNext(tokens));
            }
            return type.toString().strip();
        }
        // generic
        if (token.startsWith("@")) {
            final var arity = Integer.parseInt(tokens.removeFirst());
            final var type = new StringBuilder("@ " + arity);
            for (int i = 0; i < arity + 1; i++) { // must extract arity + 1
                type.append(" ").append(extractNext(tokens));
            }
            return type.toString().strip();
        }

        // if neither, type is flat: split on wrapper flags (:)
        return token;
    }

    @Override
    public String toString() {
        return descriptor;
    }
}
