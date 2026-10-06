package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;

import java.util.List;

final class ClassSignatureEmitter {
    static String nominalSignature(final List<TypeParameterDescriptor> typeParameters,
                                           final List<Stmt.ContractUse> contractUses) {
        if (typeParameters.isEmpty()
                && contractUses.stream().noneMatch(use -> !use.typeArguments().isEmpty())) return null;
        final var signature = new StringBuilder(formalTypeParameters(typeParameters))
                .append("Ljava/lang/Object;");
        for (final var contractUse : contractUses) {
            signature.append(classTypeSignature(contractUse.name().lexeme(), contractUse.typeArguments()));
        }
        return signature.toString();
    }

    static String methodSignature(final FunctionDescriptor function) {
        final var hasTypeParameters = !function.typeParameters().isEmpty();
        final var containsTypeParameters = function.parameters().stream()
                .anyMatch(TypeSubstitution::containsTypeParameter)
                || TypeSubstitution.containsTypeParameter(function.returnType());
        if (!hasTypeParameters && !containsTypeParameters) return null;
        final var signature = new StringBuilder(formalTypeParameters(function.typeParameters())).append('(');
        for (final var parameter : function.parameters()) signature.append(typeSignature(parameter, false));
        return signature.append(')').append(typeSignature(function.returnType(), false)).toString();
    }

    private static String formalTypeParameters(final List<TypeParameterDescriptor> parameters) {
        if (parameters.isEmpty()) return "";
        final var signature = new StringBuilder("<");
        for (final var parameter : parameters) {
            signature.append(parameter.name()).append(":Ljava/lang/Object;");
            for (final var bound : parameter.bounds()) {
                signature.append(':').append(typeSignature(bound, true));
            }
        }
        return signature.append('>').toString();
    }

    private static String classTypeSignature(final String className,
                                             final List<TypeDescriptor> arguments) {
        final var signature = new StringBuilder("L").append(className.replace('.', '/'));
        if (!arguments.isEmpty()) {
            signature.append('<');
            for (final var argument : arguments) signature.append(typeSignature(argument, true));
            signature.append('>');
        }
        return signature.append(';').toString();
    }

    private static String typeSignature(final TypeDescriptor type, final boolean typeArgument) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> "T" + parameter.name() + ";";
            case NullableDescriptor nullable -> switch (nullable.baseType()) {
                case IntDescriptor _, FloatDescriptor _, BooleanDescriptor _, UnitDescriptor _ ->
                        referenceSignature(nullable);
                default -> typeSignature(nullable.baseType(), typeArgument);
            };
            case ReferenceDescriptor reference -> typeSignature(reference.baseType(), typeArgument);
            case ArrayDescriptor _ -> "[Ljava/lang/Object;";
            case GenericDescriptor generic -> classTypeSignature(
                    generic.baseType().name(), generic.typeParameters());
            case FunctionDescriptor function -> referenceDescriptorSignature(
                    TypeDescriptor.toJavaClassDesc((FunctionDescriptor) TypeSubstitution.erase(function))
                            .descriptorString());
            case IntDescriptor _ -> typeArgument ? "Ljava/lang/Integer;" : "I";
            case FloatDescriptor _ -> typeArgument ? "Ljava/lang/Double;" : "D";
            case BooleanDescriptor _ -> typeArgument ? "Ljava/lang/Boolean;" : "Z";
            case NeverDescriptor _ -> "V";
            case UnitDescriptor _ -> "Lzeron/lang/Unit;";
            case StringDescriptor _ -> "Ljava/lang/String;";
            case NominalDescriptor nominal -> referenceDescriptorSignature(
                    TypeDescriptor.toJavaClassDesc(nominal).descriptorString());
            case AnyDescriptor _, NullDescriptor _ -> "Ljava/lang/Object;";
            case InferDescriptor _ -> throw new IllegalArgumentException(
                    "Cannot emit a generic signature for an inferred type.");
        };
    }

    private static String referenceSignature(final NullableDescriptor nullable) {
        return referenceDescriptorSignature(TypeDescriptor.toJavaClassDesc(nullable).descriptorString());
    }

    private static String referenceDescriptorSignature(final String descriptor) {
        if (!descriptor.startsWith("L")) {
            throw new IllegalArgumentException("Expected a reference descriptor, found " + descriptor + ".");
        }
        return descriptor;
    }

}
