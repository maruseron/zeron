package com.maruseron.zeron.domain;

import java.util.stream.Collectors;

public final class TypeFormatter {
    private TypeFormatter() {}

    public static String format(final TypeDescriptor type) {
        return switch (type) {
            case NeverDescriptor _ -> "Never";
            case AnyDescriptor _ -> "Any";
            case UnitDescriptor _ -> "Unit";
            case IntDescriptor _ -> "Int";
            case LongDescriptor _ -> "Long";
            case ByteDescriptor _ -> "Byte";
            case FloatDescriptor _ -> "Float";
            case BooleanDescriptor _ -> "Boolean";
            case StringDescriptor _ -> "String";
            case NullDescriptor _ -> "null";
            case InferDescriptor _ -> "<inferred>";
            case NominalDescriptor nominal -> nominal.name();
            case TypeParameterDescriptor parameter -> parameter.name();
            case ArrayDescriptor array -> "Array<" + format(array.elementType()) + ">";
            case ByteArrayDescriptor _ -> "ByteArray";
            case GenericDescriptor generic -> generic.baseType().name()
                    + generic.typeParameters().stream()
                    .map(TypeFormatter::format)
                    .collect(Collectors.joining(", ", "<", ">"));
            case NullableDescriptor nullable -> format(nullable.baseType()) + "?";
            case ReferenceDescriptor reference -> "&" + format(reference.baseType());
            case FunctionDescriptor function -> (function.typeParameters().isEmpty() ? "" :
                    function.typeParameters().stream()
                            .map(parameter -> parameter.name()
                                    + (parameter.bounds().isEmpty() ? "" : ": " + parameter.bounds().stream()
                                            .map(TypeFormatter::format).collect(Collectors.joining(" + "))))
                            .collect(Collectors.joining(", ", "<", ">")))
                    + function.parameters().stream()
                    .map(TypeFormatter::format)
                    .collect(Collectors.joining(", ", "(", ") -> "))
                    + format(function.returnType());
        };
    }
}
