package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.GenericDescriptor;
import com.maruseron.zeron.domain.NeverDescriptor;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the response type an effect declaration adds with `responds`. */
final class EffectResponses {
    private EffectResponses() {}

    /** The declared response type, or `null` when the effect does not declare one. */
    static TypeDescriptor declaredResponse(final ResolutionContext context,
                                           final TypeDescriptor effectType) {
        final var declaration = declaration(context, effectType);
        return declaration == null ? null : declaration.responseType();
    }

    /** The response type after substituting the effect's type arguments; `Never` when absent. */
    static TypeDescriptor responseOf(final ResolutionContext context,
                                     final TypeDescriptor effectType) {
        final var declaration = declaration(context, effectType);
        if (declaration == null || declaration.responseType() == null) {
            return TypeDescriptor.ofNever();
        }
        return TypeSubstitution.substitute(declaration.responseType(),
                substitutions(declaration, effectType));
    }

    static boolean declaresResponse(final ResolutionContext context,
                                    final TypeDescriptor effectType) {
        return declaredResponse(context, effectType) != null;
    }

    private static Stmt.ClassDecl declaration(final ResolutionContext context,
                                              final TypeDescriptor effectType) {
        final var name = effectName(effectType);
        return name == null ? null : context.classes.get(name);
    }

    private static String effectName(final TypeDescriptor effectType) {
        final var base = effectType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : effectType instanceof NullableDescriptor nullable
                        ? nullable.baseType()
                        : effectType;
        return switch (base) {
            case NominalDescriptor nominal -> nominal.name();
            case GenericDescriptor generic -> generic.name();
            case TypeParameterDescriptor _ -> null;
            case NeverDescriptor _ -> null;
            default -> null;
        };
    }

    private static Map<TypeParameterDescriptor, TypeDescriptor> substitutions(
            final Stmt.ClassDecl declaration, final TypeDescriptor effectType) {
        final var base = effectType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : effectType instanceof NullableDescriptor nullable
                        ? nullable.baseType()
                        : effectType;
        if (!(base instanceof GenericDescriptor generic)) return Map.of();
        final var parameters = declaration.typeParameters();
        final var arguments = generic.typeParameters();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < parameters.size() && index < arguments.size(); index++) {
            substitutions.put(parameters.get(index), arguments.get(index));
        }
        return substitutions;
    }
}
