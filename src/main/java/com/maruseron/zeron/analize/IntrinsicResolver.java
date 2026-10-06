package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class IntrinsicResolver {
    static ArrayDescriptor resolveArrayType(final ResolutionContext context, TypeDescriptor type, Token where) {
        if (type instanceof ReferenceDescriptor reference) type = reference.baseType();
        if (type instanceof ArrayDescriptor arrayType) return arrayType;
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                where, "Expected a non-null Array<T> value."));
        throw new IllegalStateException("unreachable");
    }

    static TypeDescriptor arrayElementType(final ResolutionContext context, final TypeDescriptor resultType, final Token where) {
        TypeDescriptor arrayType = resultType;
        if (arrayType instanceof ReferenceDescriptor reference) arrayType = reference.baseType();
        if (arrayType instanceof ArrayDescriptor array) return array.elementType();
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS, where,
                "The internal array operation requires an array value."));
        throw new IllegalStateException("unreachable");
    }

    static ResolvedIntrinsicOperation resolveIntrinsic(final ResolutionContext context, final IntrinsicId id,
                                                final List<TypeDescriptor> typeArguments,
                                                final List<TypeDescriptor> actualParameterTypes,
                                                final Token where) {
        final var definition = context.intrinsics.require(id);
        final var signature = definition.signature();
        if (signature.typeParameters().size() != typeArguments.size()) {
            throw new IllegalStateException("Intrinsic type-argument arity mismatch for " + id.stableName());
        }
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < typeArguments.size(); i++) {
            substitutions.put(signature.typeParameters().get(i), typeArguments.get(i));
        }

        final var expectedParameterTypes = signature.parameterTypes().stream()
                .map(type -> TypeSubstitution.substitute(type, substitutions))
                .toList();
        final var minimumArity = signature.variadic()
                ? Math.max(0, expectedParameterTypes.size() - 1)
                : expectedParameterTypes.size();
        final var expectedArity = signature.variadic()
                ? actualParameterTypes.size() >= minimumArity
                : actualParameterTypes.size() == expectedParameterTypes.size();
        if (!expectedArity) {
            throw new IllegalStateException("Intrinsic operand arity mismatch for " + id.stableName());
        }
        final var instantiatedParameterTypes = new ArrayList<TypeDescriptor>(actualParameterTypes.size());
        for (int i = 0; i < actualParameterTypes.size(); i++) {
            final var expected = expectedParameterTypes.get(
                    signature.variadic() ? Math.min(i, expectedParameterTypes.size() - 1) : i);
            ensureAssignable(context, expected, actualParameterTypes.get(i), where);
            instantiatedParameterTypes.add(expected);
        }
        final var resultType = TypeSubstitution.substitute(signature.returnType(), substitutions);
        return new ResolvedIntrinsicOperation(id, instantiatedParameterTypes, resultType);
    }

}
