package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class TypeResolver {
    static void validateFunctionTypes(final ResolutionContext context, final FunctionDescriptor function, final Token where) {
        for (final var parameter : function.parameters()) validateType(context, parameter, where);
        validateType(context, function.returnType(), where);
    }

    static void validateTypeParameterBound(final ResolutionContext context, final TypeParameterDescriptor parameter, final Token where) {
        for (final var bound : parameter.bounds()) {
            if (!(bound instanceof NominalDescriptor || bound instanceof GenericDescriptor)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        where, "A generic function or method bound must be a contract type."));
            }
            final var boundName = className(context, bound);
            final var contract = context.contracts.get(boundName);
            if (contract == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                        "Type parameter bound '" + boundName + "' is not a contract."));
            }
            ensureTypeAccessible(context, boundName, where, contract.isPublic());
            validateType(context, bound, where);
        }
    }

    static void validateTypeParameterBounds(final ResolutionContext context,
                                            final FunctionDescriptor function,
                                            final Token where) {
        function.typeParameters().forEach(parameter -> validateTypeParameterBound(context, parameter, where));
    }

    static void validateType(final ResolutionContext context, final TypeDescriptor type, final Token where) {
        switch (type) {
            case NominalDescriptor nominal -> {
                context.skipInvalidImportAlias(nominal.name());
                MemberInteropResolver.validateStarTypeAmbiguity(context, nominal.name(), where);
                if (!context.types.contains(nominal.name())) {
                    final var javaClass = context.javaClassPath.find(nominal.name());
                    if (javaClass == null) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, where,
                                "Unknown type '" + nominal.name() + "'."));
                    }
                    if (javaClass.hasGenericSignature()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                                where,
                                "Generic Java classes are not supported by the current interop slice."));
                    }
                    break;
                }
                final var classDeclaration = context.classes.get(nominal.name());
                final var contractDeclaration = context.contracts.get(nominal.name());
                if (classDeclaration != null) {
                    ensureTypeAccessible(context, nominal.name(), where, classDeclaration.isPublic());
                }
                if (contractDeclaration != null) {
                    ensureTypeAccessible(context, nominal.name(), where, contractDeclaration.isPublic());
                }
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration != null ? contractDeclaration.typeParameters().size() : 0;
                if (arity != 0) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            where,
                            "Type '" + nominal.name() + "' requires " + arity + " type arguments."));
                }
            }
            case ArrayDescriptor array -> validateType(context, array.elementType(), where);
            case NullableDescriptor nullable -> validateType(context, nullable.baseType(), where);
            case ReferenceDescriptor reference -> validateType(context, reference.baseType(), where);
            case FunctionDescriptor function -> validateFunctionTypes(context, function, where);
            case GenericDescriptor generic -> {
                final var name = generic.baseType().name();
                context.skipInvalidImportAlias(name);
                MemberInteropResolver.validateStarTypeAmbiguity(context, name, where);
                final var classDeclaration = context.classes.get(name);
                final var contractDeclaration = context.contracts.get(name);
                if (classDeclaration == null && contractDeclaration == null) {
                    if (context.javaClassPath.find(name) != null) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
                                where,
                                "Generic Java classes are not supported by the current interop slice."));
                    }
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                            where, "Unknown generic type '" + name + "'."));
                }
                if (classDeclaration != null) {
                    ensureTypeAccessible(context, name, where, classDeclaration.isPublic());
                }
                if (contractDeclaration != null) {
                    ensureTypeAccessible(context, name, where, contractDeclaration.isPublic());
                }
                final var arity = classDeclaration != null ? classDeclaration.typeParameters().size()
                        : contractDeclaration.typeParameters().size();
                if (arity == 0 || arity != generic.typeParameters().size()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            where,
                            "Type '" + name + "' expects " + arity + " type arguments, found "
                                    + generic.typeParameters().size() + "."));
                }
                generic.typeParameters().forEach(parameter -> validateType(context, parameter, where));
            }
            default -> {}
        }
    }

    static TypeDescriptor classType(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        if (declaration.typeParameters().isEmpty()) return TypeDescriptor.of(declaration.name().lexeme());
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(declaration.name().lexeme()),
                declaration.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    static Map<TypeParameterDescriptor, TypeDescriptor> substitutionsFor(
            final ResolutionContext context,
            final List<TypeParameterDescriptor> parameters, final TypeDescriptor receiverType) {
        final var baseType = receiverType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : receiverType;
        if (!(baseType instanceof GenericDescriptor generic)
                || parameters.size() != generic.typeParameters().size()) return Map.of();
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < parameters.size(); i++) {
            substitutions.put(parameters.get(i), generic.typeParameters().get(i));
        }
        return substitutions;
    }

}
