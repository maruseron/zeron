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
            case CharDescriptor _ -> "Char";
            case LongDescriptor _ -> "Long";
            case ByteDescriptor _ -> "Byte";
            case FloatDescriptor _ -> "Float";
            case BooleanDescriptor _ -> "Boolean";
            case StringDescriptor _ -> "String";
            case NullDescriptor _ -> "null";
            case InferDescriptor _ -> "<inferred>";
            case NominalDescriptor nominal -> nominal.name();
            case TypeParameterDescriptor parameter ->
                    parameter.name() + (parameter.isHigherKinded() ? "<_>" : "");
            case AppliedConstructorDescriptor applied ->
                    applied.constructor().name() + "<" + format(applied.argument()) + ">";
            case TypeConstructorDescriptor constructor -> constructor.baseType().name()
                    + constructor.arguments().stream().map(argument ->
                            argument instanceof InferDescriptor ? "_" : format(argument))
                            .collect(Collectors.joining(", ", "<", ">"));
            case ArrayDescriptor array -> "Array<" + format(array.elementType()) + ">";
            case ByteArrayDescriptor _ -> "ByteArray";
            case GenericDescriptor generic -> generic.baseType().name()
                    + generic.typeParameters().stream()
                    .map(TypeFormatter::formatTypeArgument)
                    .collect(Collectors.joining(", ", "<", ">"));
            case TypeProjectionDescriptor projection ->
                    (projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "out " : "in ")
                            + format(projection.bound());
            case CapturedTypeDescriptor capture -> capture.name();
            case EffectRowDescriptor row -> row.name();
            case NullableDescriptor nullable -> format(nullable.baseType()) + "?";
            case ReferenceDescriptor reference -> "&" + format(reference.baseType());
            case FunctionDescriptor function when function.isHandler() ->
                    format(function.returnType()) + " ~> "
                            + formatEffectSet(function.handledEffects())
                            + (function.raisedEffects().isEmpty() ? ""
                                    : " raises " + formatEffectSet(function.raisedEffects()));
            case FunctionDescriptor function -> (function.receiverType() == null ? ""
                    : format(function.receiverType()) + ".")
                    + (function.explicitTypeParameters().isEmpty() ? "" :
                    function.explicitTypeParameters().stream()
                            .map(parameter -> parameter.name()
                                            + typeParameterConstraints(parameter))
                            .collect(Collectors.joining(", ", "<", ">")))
                    + formatParameters(function)
                    + format(function.returnType());
        };
    }

    private static String formatParameters(final FunctionDescriptor function) {
        if (function.receiverType() == null && function.typeParameters().isEmpty()
                && function.parameters().size() == 1
                && canUseUnaryShorthand(function.parameters().getFirst())) {
            return format(function.parameters().getFirst()) + " -> ";
        }
        return function.parameters().stream()
                .map(TypeFormatter::format)
                .collect(Collectors.joining(", ", "(", ") -> "));
    }

    private static boolean canUseUnaryShorthand(final TypeDescriptor parameter) {
        return switch (parameter) {
            case FunctionDescriptor function when !function.isHandler() -> false;
            case ReferenceDescriptor _ -> false;
            case EffectRowDescriptor _ -> false;
            default -> true;
        };
    }

    private static String formatEffectSet(final java.util.List<TypeDescriptor> effects) {
        return effects.stream().map(TypeFormatter::format).sorted()
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String formatTypeArgument(final TypeDescriptor type) {
        if (type instanceof TypeParameterDescriptor parameter && parameter.isAnonymousExistential()) {
            final var constraints = new java.util.ArrayList<String>();
            if (parameter.admitsNullableArguments()) constraints.add("nullable");
            if (parameter.isReified()) constraints.add("reified");
            if (parameter.isEffectKind()) constraints.add("effect");
            if (parameter.isEffectRowKind()) constraints.add("effect...");
            if (parameter.admitsMutableArguments()) constraints.add("mut");
            parameter.bounds().stream().map(TypeFormatter::format).forEach(constraints::add);
            return ": " + String.join(" + ", constraints);
        }
        return format(type);
    }

    private static String typeParameterConstraints(final TypeParameterDescriptor parameter) {
        final var constraints = new java.util.ArrayList<String>();
        if (parameter.admitsNullableArguments()) constraints.add("nullable");
        if (parameter.isReified()) constraints.add("reified");
        if (parameter.isEffectKind()) constraints.add("effect");
        if (parameter.isEffectRowKind()) constraints.add("effect...");
        if (parameter.admitsMutableArguments()) constraints.add("mut");
        parameter.bounds().stream().map(TypeFormatter::format).forEach(constraints::add);
        return constraints.isEmpty() ? "" : ": " + String.join(" + ", constraints);
    }
}
