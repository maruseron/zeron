package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class TypeResolver {
    static String resolveTypeName(final ResolutionContext context, final String name, final Token where) {
        if (name.contains(".")) return name;
        context.skipInvalidImportAlias(name);

        final var currentPackageName = qualify(context.packageName, name);
        if (context.types.contains(currentPackageName)) return currentPackageName;

        final var importedName = context.currentImports.types().get(name);
        if (importedName != null && (context.types.contains(importedName)
                || context.javaClassPath.find(importedName) != null)) {
            return importedName;
        }

        final var starCandidates = context.currentImports.onDemandPackages().stream()
                .filter(importedPackage -> !importedPackage.equals(context.packageName))
                .map(importedPackage -> qualify(importedPackage, name))
                .filter(candidate -> {
                    final var classDeclaration = context.classes.get(candidate);
                    final var contractDeclaration = context.contracts.get(candidate);
                    return classDeclaration != null && classDeclaration.isPublic()
                            || contractDeclaration != null && contractDeclaration.isPublic();
                })
                .distinct()
                .toList();
        if (starCandidates.size() > 1) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_OR_CONFLICTING_IMPORT, where,
                    "Ambiguous type '" + name + "' from star imports; add an explicit import or alias."));
        }
        if (starCandidates.size() == 1) return starCandidates.getFirst();
        return name;
    }

    private static String qualify(final String packageName, final String name) {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }

    static void validateFunctionTypes(final ResolutionContext context, final FunctionDescriptor function, final Token where) {
        for (final var parameter : function.parameters()) validateType(context, parameter, where);
        validateType(context, function.returnType(), where);
        final var seenEffects = new HashSet<TypeDescriptor>();
        for (final var effect : function.raisedEffects()) {
            validateType(context, effect, where);
            if (!(effect instanceof NominalDescriptor nominal)
                    || context.classes.get(nominal.name()) == null
                    || !context.classes.get(nominal.name()).isEffect()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        where, "A raises clause must name a declared non-generic effect type."));
            }
            if (!seenEffects.add(effect)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_DECLARATION_COMPONENT,
                        where, "An effect may appear only once in a raises clause."));
            }
        }
    }

    static void validateTypeParameterBound(final ResolutionContext context, final TypeParameterDescriptor parameter, final Token where) {
        for (final var bound : parameter.bounds()) {
            if (!(bound instanceof NominalDescriptor || bound instanceof GenericDescriptor)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        where, "A type-parameter bound must be a contract type."));
            }
            validateType(context, bound, where);
            final var boundName = className(context, bound);
            final var contract = context.contracts.get(boundName);
            if (contract == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                        "Type parameter bound '" + boundName + "' is not a contract."));
            }
            ensureTypeAccessible(context, boundName, where, contract.isPublic());
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
                nominal.resolveName(resolveTypeName(context, nominal.name(), where));
                MemberInteropResolver.validateStarTypeAmbiguity(context, nominal.name(), where);
                if (!context.types.contains(nominal.name())) {
                    final var javaClass = context.javaClassPath.find(nominal.name());
                    if (javaClass == null) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND, where,
                                "Unknown type '" + nominal.name() + "'."));
                    }

                    if (javaClass.hasTypeParameters()) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
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
            case AppliedConstructorDescriptor applied -> {
                if (!applied.constructor().isHigherKinded()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            where, "Only a higher-kinded type parameter can be applied."));
                }
                validateType(context, applied.argument(), where);
            }
            case TypeConstructorDescriptor constructor -> {
                constructor.baseType().resolveName(resolveTypeName(
                        context, constructor.baseType().name(), where));
                final var name = constructor.baseType().name();
                final var classDeclaration = context.classes.get(name);
                final var sumDeclaration = context.sums.get(name);
                final var productDeclaration = context.products.get(name);
                final var contractDeclaration = context.contracts.get(name);
                final var intrinsicArray = name.equals("Array");
                final var parameters = classDeclaration != null ? classDeclaration.typeParameters()
                        : sumDeclaration != null ? sumDeclaration.typeParameters()
                        : productDeclaration != null ? productDeclaration.typeParameters()
                        : contractDeclaration != null ? contractDeclaration.typeParameters() : List.<TypeParameterDescriptor>of();
                if (intrinsicArray && constructor.arguments().size() != 1) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            where, "The Array type constructor expects one element type."));
                } else if (!intrinsicArray
                        && (parameters.isEmpty() || parameters.size() != constructor.arguments().size())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                            where, "Type constructor '" + name + "' has the wrong number of type arguments."));
                }
                if (!intrinsicArray && classDeclaration == null && sumDeclaration == null
                        && productDeclaration == null && contractDeclaration == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                            where, "Unknown type constructor '" + name + "'."));
                }
                for (final var argument : constructor.arguments()) {
                    if (!(argument instanceof InferDescriptor)) validateType(context, argument, where);
                }
            }
            case FunctionDescriptor function -> validateFunctionTypes(context, function, where);
            case GenericDescriptor generic -> {
                generic.baseType().resolveName(
                        resolveTypeName(context, generic.baseType().name(), where));
                final var name = generic.baseType().name();
                MemberInteropResolver.validateStarTypeAmbiguity(context, name, where);
                final var classDeclaration = context.classes.get(name);
                final var contractDeclaration = context.contracts.get(name);
                if (classDeclaration == null && contractDeclaration == null) {
                    if (context.javaClassPath.find(name) != null) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE,
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
                final var genericParameters = classDeclaration != null
                        ? classDeclaration.typeParameters() : contractDeclaration.typeParameters();
                for (int index = 0; index < genericParameters.size(); index++) {
                    final var argument = generic.typeParameters().get(index);
                    final var expectsConstructor = contractDeclaration != null
                            && contractDeclaration.higherKinded()
                            && genericParameters.get(index).isHigherKinded();
                    final var isConstructor = argument instanceof TypeConstructorDescriptor
                            || argument instanceof TypeParameterDescriptor parameter
                                    && parameter.isHigherKinded();
                    if (expectsConstructor != isConstructor) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE, where,
                                expectsConstructor
                                        ? "This HKT contract argument must be a type constructor."
                                        : "A type constructor cannot be used as an ordinary type argument."));
                    }
                }
                generic.typeParameters().forEach(parameter -> validateType(context, parameter, where));
                if (!genericParameters.isEmpty()) {
                    final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                    for (int index = 0; index < genericParameters.size(); index++) {
                        substitutions.put(genericParameters.get(index),
                                generic.typeParameters().get(index));
                    }
                    for (int index = 0; index < genericParameters.size(); index++) {
                        final var argument = generic.typeParameters().get(index);
                        for (final var bound : genericParameters.get(index).bounds()) {
                            final var requiredBound = TypeSubstitution.substitute(bound, substitutions);
                            if (!TypeClassEvidence.hasEvidence(context, requiredBound, argument, where)) {
                                Resolver.ensureAssignable(context, requiredBound, argument, where);
                            }
                        }
                    }
                }
            }
            default -> {}
        }
    }

    static TypeDescriptor requireEffectType(final ResolutionContext context, final TypeDescriptor type,
                                            final Token where) {
        validateType(context, type, where);
        if (!(type instanceof NominalDescriptor nominal)
                || context.classes.get(nominal.name()) == null
                || !context.classes.get(nominal.name()).isEffect()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    where, "Expected a declared non-generic effect type."));
        }
        return type;
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
