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
                                    : " performs " + formatEffectSet(function.raisedEffects()));
            case FunctionDescriptor function -> (function.receiverType() == null ? ""
                    : format(function.receiverType()) + ".")
                    + (function.explicitTypeParameters().isEmpty() ? "" :
                    function.explicitTypeParameters().stream()
                            .map(parameter -> (parameter.isReified() ? "reified " : "")
                                    + parameter.name()
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
            final var builder = new StringBuilder();
            if (parameter.isReified()) builder.append("reified _");
            builder.append(':');
            if (!parameter.bounds().isEmpty()) {
                builder.append(' ').append(parameter.bounds().stream()
                        .map(TypeFormatter::format)
                        .collect(Collectors.joining(" + ")));
            }
            final var markers = admissibilityMarkers(parameter);
            if (!markers.isEmpty()) {
                builder.append(" admits ").append(String.join(" + ", markers));
            }
            return builder.toString();
        }
        return format(type);
    }

    private static String typeParameterConstraints(final TypeParameterDescriptor parameter) {
        final var constraints = new java.util.ArrayList<String>();
        if (!parameter.bounds().isEmpty()) {
            parameter.bounds().stream().map(TypeFormatter::format).forEach(constraints::add);
        }
        final var result = new StringBuilder();
        if (!constraints.isEmpty()) {
            result.append(": ").append(String.join(" + ", constraints));
        }
        final var markers = admissibilityMarkers(parameter);
        if (!markers.isEmpty()) {
            result.append(" admits ").append(String.join(" + ", markers));
        }
        return result.toString();
    }

    private static java.util.List<String> admissibilityMarkers(
            final TypeParameterDescriptor parameter) {
        final var markers = new java.util.ArrayList<String>();
        if (parameter.admitsNullableArguments()) markers.add("nullable");
        if (parameter.admitsMutableArguments()) markers.add("mutable");
        if (parameter.isEffectKind()) markers.add("effect");
        if (parameter.isEffectRowKind()) markers.add("effect...");
        return markers;
    }
}
