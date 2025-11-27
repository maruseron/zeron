package com.maruseron.zeron.domain;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public record TypeDescriptor(String descriptor) {

    public boolean isSpecial() {
        return switch (descriptor) {
            case "<infer>" -> true;
            default -> false;
        };
    }

    public boolean isBuiltIn() {
        return switch (descriptor) {
            case "Never", "Unit", "Int", "Double", "Boolean", "String" -> true;
            default -> false;
        };
    }

    public boolean isFunction() {
        return descriptor.startsWith("$");
    }

    public boolean isNullable() {
        return descriptor().endsWith("?");
    }

    public boolean isMutable() {
        return descriptor().startsWith("&");
    }

    public boolean isArray() {
        return descriptor().endsWith("[]")
            || descriptor().endsWith("[]?");
    }

    public boolean isGeneric() {
        return descriptor().startsWith("@");
    }

    public Stream<String> bits() {
        final var delimiters = "\\&|\\?|(\\[\\])";
        return Stream.of(descriptor.split(
                String.format("((?=%s)|(?<=%s))", delimiters, delimiters)));
    }

    public TypeDescriptor nullable() {
        // Nothing     -> Nothing?     <- this type can only be null
        // Int         -> Int?
        // Employee    -> Employee?
        // String?     -> String?      <- nullable() on a nullable type is a no op
        // &Employee   -> &Employee?
        // String[]    -> String[]?
        // String?[][] -> String?[][]? <-     from array[array[nullable string]] to
        //                                nullable array[array[nullable string]]
        return isNullable() ? this : new TypeDescriptor(descriptor + "?");
    }

    public TypeDescriptor mutable() {
        // Nothing     -> Nothing        <\
        // Int         -> Int         <|-- mutable() on a builtin type is a no op.
        // Employee    -> &Employee       /         builtin types cannot be mutated
        // String?     -> String?       </
        // &Employee   -> &Employee     <- mutable() on a mutable type is a no op
        // String[]    -> &String[]     <- this modifies the base type, not the array type.
        //                                 TODO: how to mutable array, if at all?
        // String?[][] -> &String?[][]? <-    from array[array[        nullable string]] to
        //                                         array[array[mutable nullable string]]
        return isBuiltIn()
                ? this
                : isMutable()
                    ? this
                    : new TypeDescriptor("&" + descriptor);
    }

    public TypeDescriptor array() {
        // Nothing     -> Nothing[]      <- size is always 0. Nothing?[] can only hold nulls
        // Int         -> Int[]
        // Employee    -> Employee[]
        // String?     -> String?[]
        // &Employee   -> &Employee[]
        // String[]    -> &String[][]    <- array() on an array type nests the current type
        //                                  into a new one
        // String?[][] -> String?[][][] <-    from       array[array[nullable string]] to
        //                                         array[array[array[nullable string]]
        return new TypeDescriptor(descriptor + "[]");
    }

    public List<TypeDescriptor> functionParams() {
        if (!isFunction()) {
            throw new UnsupportedOperationException("Not a function descriptor");
        }

        // TODO: write parsing algorithm. how to parse this properly?
        // func(): () -> Unit           would be ()()Unit
        // func(() -> Unit): Unit       would be (()Unit)Unit
        // func(() -> Unit): () -> Unit would be (()Unit)()Unit
        return Arrays.stream(
                descriptor
                        .substring(1, descriptor.lastIndexOf(")"))
                        .split(";"))
                .map(TypeDescriptor::new)
                .toList();
    }

    @Override
    public String toString() {
        return descriptor;
    }
}
