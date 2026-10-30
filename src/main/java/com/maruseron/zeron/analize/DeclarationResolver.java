package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticHelp;
import com.maruseron.zeron.diagnostic.SourceSpan;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.*;

import static com.maruseron.zeron.analize.Resolver.*;

final class DeclarationResolver {
    static void resolveSum(final ResolutionContext context, final Stmt.SumDecl sum) {
        final var contract = context.contracts.get(sum.name().lexeme());
        resolveContract(context, contract);
        for (final var sumCase : sum.cases()) {
            final var caseClass = context.classes.get(sum.generatedCaseName(sumCase));
            resolveClass(context, caseClass);
        }
    }

    static void resolveProduct(final ResolutionContext context, final Stmt.ProductDecl product) {
        if (context.products.get(product.name().lexeme()) != product) return;
        final var declaration = context.classes.get(product.name().lexeme());
        if (declaration == null) return;
        resolveClass(context, declaration);
    }

    static void resolveNewtype(final ResolutionContext context, final Stmt.NewtypeDecl newtype) {
        if (context.newtypes.get(newtype.name().lexeme()) != newtype) return;
        TypeResolver.validateType(context, newtype.underlyingType(), newtype.name());
        final var declaration = context.classes.get(newtype.name().lexeme());
        if (declaration != null) resolveClass(context, declaration);
    }

    static void resolveContract(final ResolutionContext context, final Stmt.ContractDecl contract) {
        contract.typeParameters().forEach(parameter ->
                TypeResolver.validateTypeParameterBound(context, parameter, contract.name()));
        validateRequiredContracts(context, contract);
        validateSealedContract(context, contract);
        final var methodNames = new HashSet<String>();
        for (final var method : contract.methods()) {
            if (!methodNames.add(method.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        method.name(), "Contract method name '" + method.name().lexeme() + "' is already declared."));
            }
            TypeResolver.validateFunctionTypes(context, method.typeDescriptor(), method.name());
            TypeResolver.validateTypeParameterBounds(context, method.typeDescriptor(), method.name());
            if (method.isDefault() || !method.defaultValues().isEmpty()) {
                resolveContractMethod(context, contract, method);
            }
        }
        for (final var property : contract.properties()) {
            if (contract.methods().stream().anyMatch(method ->
                    method.name().lexeme().equals(property.name().lexeme()))
                    || !methodNames.add(property.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        property.name(), "Duplicate contract member."));
            }
            TypeResolver.validateType(context, property.type(), property.name());
        }
        VariancePositionValidator.validate(context, contract);
        validateContractComposition(context, contract);
    }

    private static void validateRequiredContracts(final ResolutionContext context,
                                                 final Stmt.ContractDecl contract) {
        for (final var required : contract.requiredContracts()) {
            final var requiredName = required.name();
            final var requiredContract = context.contracts.get(requiredName.lexeme());
            if (requiredContract == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                        requiredName, "Unknown required contract."));
            }
            ensureTypeAccessible(context, requiredName.lexeme(), requiredName, requiredContract.isPublic());
            if (required.typeArguments().size() != requiredContract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                        requiredName, "Expected " + requiredContract.typeParameters().size()
                                + " required contract type arguments, found "
                                + required.typeArguments().size() + "."));
            }
            validateContractUse(context, required);
            if (canReachContract(context, requiredName.lexeme(), contract.name().lexeme(), new HashSet<>())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        requiredName, "Contract requirements cannot contain a cycle."));
            }
        }
    }

    private static void validateContractComposition(final ResolutionContext context,
                                                    final Stmt.ContractDecl contract) {
            final var rootArguments = contract.typeParameters().stream()
                    .map(parameter -> (TypeDescriptor) parameter).toList();
            final var rootUse = new Stmt.ContractUse(contract.name(), rootArguments);
            final var closure = ContractComposition.closure(context, rootUse);
            final var methods = new LinkedHashMap<String, FunctionDescriptor>();
            final var methodMutability = new HashMap<String, Boolean>();
            final var methodVariadic = new HashMap<String, Boolean>();
            final var properties = new LinkedHashMap<String, TypeDescriptor>();

            for (final var applied : closure) {
                for (final var method : applied.declaration().methods()) {
                    final var signature = (FunctionDescriptor) TypeSubstitution.substitute(
                            method.typeDescriptor(), applied.substitutions());
                    final var name = method.name().lexeme();
                    final var previous = methods.putIfAbsent(name, signature);
                    final var previousMutating = methodMutability.putIfAbsent(name, method.isMutating());
                    final var previousVariadic = methodVariadic.putIfAbsent(name, method.variadic());
                    if (previous != null && (!compatibleMethodSignatures(context, previous, method.variadic(),
                            signature, method.variadic())
                            || previousVariadic != method.variadic()
                            || previousMutating != method.isMutating())) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTRACT_MEMBER,
                                method.name(), "Incompatible inherited contract method '" + name + "'." ));
                    }
                }
                for (final var property : applied.declaration().properties()) {
                    final var type = TypeSubstitution.substitute(property.type(), applied.substitutions());
                    final var previous = properties.putIfAbsent(property.name().lexeme(), type);
                    if (previous != null && !previous.equals(type)) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTRACT_MEMBER,
                                property.name(), "Incompatible inherited contract property '"
                                        + property.name().lexeme() + "'." ));
                    }
                }
            }

            final var inheritedMethods = ContractComposition.closure(context, rootUse).stream()
                    .skip(1)
                    .flatMap(applied -> applied.declaration().methods().stream().map(method ->
                            Map.entry(method, applied.substitutions())))
                    .toList();
            for (final var declared : contract.methods()) {
                final var inherited = inheritedMethods.stream()
                        .filter(entry -> entry.getKey().name().lexeme().equals(declared.name().lexeme()))
                        .findFirst().orElse(null);
                if (inherited == null) continue;
                final var inheritedType = (FunctionDescriptor) TypeSubstitution.substitute(
                        inherited.getKey().typeDescriptor(), inherited.getValue());
                if (declared.isMutating() != inherited.getKey().isMutating()
                        || !compatibleMethodSignatures(context, inheritedType, inherited.getKey().variadic(),
                                declared.typeDescriptor(), declared.variadic())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTRACT_MEMBER,
                            declared.name(), "A redeclared contract method must preserve its inherited signature." ));
                }
            }
            for (final var declared : contract.properties()) {
                if (methods.containsKey(declared.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTRACT_MEMBER,
                            declared.name(), "A contract property conflicts with an inherited or declared method." ));
                }
                final var inheritedType = properties.get(declared.name().lexeme());
                if (inheritedType != null && !inheritedType.equals(declared.type())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_CONTRACT_MEMBER,
                            declared.name(), "A redeclared contract property must preserve its inherited type." ));
                }
            }
    }

    private static boolean canReachContract(final ResolutionContext context, final String from,
                                            final String target, final Set<String> visited) {
        if (from.equals(target)) return true;
        if (!visited.add(from)) return false;
        final var contract = context.contracts.get(from);
        return contract != null && contract.requiredContracts().stream()
                .anyMatch(required -> canReachContract(context, required.name().lexeme(), target, visited));
    }

    private static void validateSealedContract(final ResolutionContext context, final Stmt.ContractDecl contract) {
        if (!contract.isSealed()) {
            if (!contract.permittedClasses().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        contract.name(),
                        "Only sealed contracts may declare permitted classes."));
            }
            return;
        }
        if (contract.permittedClasses().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                    contract.name(),
                    "A sealed contract must declare at least one permitted class."));
        }

        final var permittedNames = new HashSet<String>();
        for (final var permitted : contract.permittedClasses()) {
            final var className = permitted.name().lexeme();
            if (!permittedNames.add(className)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "Duplicate permitted class."));
            }
            final var implementation = context.classes.get(className);
            if (implementation == null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "Unknown permitted class."));
            }
            if (!packageOf(className).equals(packageOf(contract.name().lexeme()))) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must be in the sealed contract's package."));
            }
            if (contract.isPublic() && !implementation.isPublic()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A public sealed contract can only permit public classes."));
            }
            if (implementation.typeParameters().size() != contract.typeParameters().size()
                    || permitted.typeArguments().size() != contract.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must use the sealed contract's type parameters in order."));
            }
            for (int i = 0; i < contract.typeParameters().size(); i++) {
                if (!permitted.typeArguments().get(i).equals(contract.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            permitted.name(),
                            "A permitted class must use the sealed contract's type parameters in order."));
                }
            }
            final var conformance = implementation.contractUses().stream()
                    .filter(use -> use.name().lexeme().equals(contract.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            if (conformance == null || conformance.typeArguments().size() != implementation.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        permitted.name(),
                        "A permitted class must directly conform to the sealed contract."));
            }
            for (int i = 0; i < implementation.typeParameters().size(); i++) {
                if (!conformance.typeArguments().get(i).equals(implementation.typeParameters().get(i))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            permitted.name(),
                            "A permitted class must conform using its type parameters in order."));
                }
            }
        }
    }

    private static void resolveContractMethod(final ResolutionContext context, final Stmt.ContractDecl owner,
                                       final Stmt.ContractMethod method) {
        final var enclosingEffects = method.isDefault() ? RaisedEffectFlow.beginCallable(context) : null;
        final var methodType = owner.higherKinded() && method.isDefault()
                ? HktDefaultSupport.effectiveMethodType(context.contracts, owner, method)
                : method.typeDescriptor();
        final var previousTypeSubstitutions = context.activeTypeSubstitutions;
        context.activeTypeSubstitutions = owner.higherKinded() && method.isDefault()
                ? HktDefaultSupport.ownerParameterSubstitutions(context.contracts, owner)
                : Map.of();
        beginScope(context);
        context.frame.expectedReturnTypes.push(methodType.returnType());
        final var enclosingFlow = context.frame.flowState;
        final var enclosingLoopDepth = context.frame.loopDepth;
        context.frame.flowState = new FlowState();
        context.frame.loopDepth = 0;
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        if (!owner.higherKinded()) {
            final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
                    ? TypeDescriptor.of(owner.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                            owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
            final var thisType = method.isMutating()
                    ? new ReferenceDescriptor(ownerType)
                    : ownerType;
            declare(context, SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
            define(context, thisToken);
        }
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            if (i < Stmt.fixedArity(method.parameters(), method.variadic())
                    && !method.defaultValues().isEmpty() && i >= method.minimumArity()) {
                final var defaultValue = method.defaultValues().get(i - method.minimumArity());
                final var expected = methodType.parameters().get(i);
                final var resolvedDefault = MemberInteropResolver.resolveArgument(context, defaultValue, expected);
                ensureAssignable(context, expected, resolvedDefault, method.name());
            }
            declare(context, SYNTHETIC_VAR, parameter, methodType.parameters().get(i),
                    BindingMutability.IMMUTABLE);
            define(context, parameter);
        }
        try {
            if (method.isDefault()) {
                resolveStmts(context, method.body());
                ensureReturns(context, method.name(), methodType.returnType(), method.body());
                RaisedEffectFlow.verifyCallable(context, methodType.raisedEffects(), method.name());
            }
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            context.activeTypeSubstitutions = previousTypeSubstitutions;
            if (enclosingEffects != null) RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    private static void validateContractUse(final ResolutionContext context,
                                            final Stmt.ContractUse use) {
        final TypeDescriptor appliedType = use.typeArguments().isEmpty()
                ? TypeDescriptor.ofName(use.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(use.name().lexeme()),
                        use.typeArguments());
        TypeResolver.validateType(context, appliedType, use.name());
    }

    static void resolveClass(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        for (final var parameter : declaration.typeParameters()) {
            Resolver.attemptMember(context,
                    () -> TypeResolver.validateTypeParameterBound(context, parameter, declaration.name()));
        }
        if (declaration.isEffect() && (!declaration.typeParameters().isEmpty()
                || !declaration.contractUses().isEmpty() || !declaration.methods().isEmpty()
                || declaration.properties().stream().anyMatch(property -> !property.isPublic()))) {
            Resolver.attemptMember(context, () -> Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    declaration.name(),
                    "Effects are non-generic payload classes; they cannot implement contracts or declare methods, "
                            + "and payload properties must be public.")));
        }
        final var fieldNames = new HashSet<String>();
        final var invalidFields = Collections.newSetFromMap(new IdentityHashMap<Stmt.Field, Boolean>());
        for (final var field : declaration.fields()) {
            if (!Resolver.attemptMember(context, () -> {
                if (!fieldNames.add(field.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            field.name(), "Duplicate class member."));
                }
                TypeResolver.validateType(context, field.type(), field.name());
            })) invalidFields.add(field);
        }

        final var invalidNamedConstructors =
                Collections.newSetFromMap(new IdentityHashMap<Stmt.NamedConstructor, Boolean>());
        for (final var constructor : declaration.namedConstructors()) {
            if (!Resolver.attemptMember(context, () -> {
                if (declaration.namedConstructors().stream().anyMatch(existing -> existing != constructor
                        && existing.name().lexeme().equals(constructor.name().lexeme()))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            constructor.name(), "Duplicate class constructor name."));
                }
                TypeResolver.validateFunctionTypes(context, constructor.typeDescriptor(), constructor.name());
                TypeResolver.validateTypeParameterBounds(
                        context, constructor.typeDescriptor(), constructor.name());
            })) invalidNamedConstructors.add(constructor);
        }
        Resolver.attemptMember(context, () -> validateLiteralSuffixes(declaration));

        final var invalidProperties =
                Collections.newSetFromMap(new IdentityHashMap<Stmt.Property, Boolean>());
        for (final var property : declaration.properties()) {
            if (!Resolver.attemptMember(context, () -> {
                if (!fieldNames.add(property.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            property.name(), "Duplicate class member."));
                }
                TypeResolver.validateType(context, property.type(), property.name());
                if (property.isMutating() && property.setterBody() == null && property.isCustom()) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                            property.name(), "A writable custom property requires a setter."));
                }
            })) invalidProperties.add(property);
        }
        final var methodNames = new HashSet<String>();
        final var invalidMethods = Collections.newSetFromMap(new IdentityHashMap<Stmt.Method, Boolean>());
        for (final var method : declaration.methods()) {
            if (!Resolver.attemptMember(context, () -> {
                if (fieldNames.contains(method.name().lexeme()) || !methodNames.add(method.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            method.name(), "Duplicate class member."));
                }
                TypeResolver.validateFunctionTypes(context, method.typeDescriptor(), method.name());
                TypeResolver.validateTypeParameterBounds(context, method.typeDescriptor(), method.name());
            })) invalidMethods.add(method);
        }
        final var patternNames = new HashSet<String>();
        final var invalidPatterns = Collections.newSetFromMap(new IdentityHashMap<Stmt.Pattern, Boolean>());
        for (final var pattern : declaration.patterns()) {
            if (!Resolver.attemptMember(context, () -> {
                if (!patternNames.add(pattern.name().lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            pattern.name(), "Duplicate class pattern name."));
                }
                if (declaration.properties().stream()
                        .anyMatch(property -> property.name().lexeme().equals(pattern.name().lexeme()))) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            pattern.name(), "A class pattern cannot have the same name as a property."));
                }
                final var outputNames = new HashSet<String>();
                for (final var output : pattern.outputs()) {
                    if (!outputNames.add(output.name().lexeme())) {
                        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                                output.name(), "Duplicate pattern output name."));
                    }
                    TypeResolver.validateType(context, output.type(), output.name());
                }
            })) invalidPatterns.add(pattern);
        }

        final var contractNames = new HashSet<String>();
        final var invalidContractUses =
                Collections.newSetFromMap(new IdentityHashMap<Stmt.ContractUse, Boolean>());
        for (final var contractUse : declaration.contractUses()) {
            if (!Resolver.attemptMember(context, () -> {
                final var contractName = contractUse.name();
                if (!contractNames.add(contractName.lexeme())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            contractName, "Duplicate contract conformance."));
                }
                final var contract = context.contracts.get(contractName.lexeme());
                if (contract == null) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.NAME_NOT_FOUND,
                            contractName, "Unknown contract."));
                }
                ensureTypeAccessible(context, contractName.lexeme(), contractName, contract.isPublic());
                if (contract.typeParameters().size() != contractUse.typeArguments().size()) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
                            contractName,
                            "Expected " + contract.typeParameters().size() + " contract type arguments, found "
                                    + contractUse.typeArguments().size() + "."));
                }
                validateContractUse(context, contractUse);
                if (ContractComposition.hasStaticMethods(context, contractUse)) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                            contractName,
                            "Contracts with static methods must be implemented through a witness, not class "
                                    + "conformance. Remove this 'is' clause and declare a witness instead."));
                }
                if (contract.isSealed() && contract.permittedClasses().stream()
                        .noneMatch(permitted -> permitted.name().lexeme().equals(declaration.name().lexeme()))) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                            contractName, "Class is not listed in the sealed contract's permits clause."));
                }
            })) invalidContractUses.add(contractUse);
        }
        Resolver.attemptMember(context,
                () -> validateHigherKindedConformances(context, declaration, contractNames));
        Resolver.attemptMember(context, () -> VariancePositionValidator.validate(context, declaration));

        final var previousClass = context.frame.currentClassName;
        final var previousInitializerFields = context.frame.initializerVisibleFields;
        final var previousDataInitializerOwner = context.frame.dataInitializerOwner;
        final var previousWritableFields = context.frame.dataInitializerWritableFields;
        context.frame.currentClassName = declaration.name().lexeme();
        try {
            final var dataComponents = declaration.dataComponentFields();
            if (declaration.dataClass()) {
                Resolver.attemptMember(context,
                        () -> resolveDataClassDirectInitializers(context, declaration, dataComponents));
            } else {
                for (int fieldIndex = 0; fieldIndex < declaration.fields().size(); fieldIndex++) {
                    final var field = declaration.fields().get(fieldIndex);
                    if (field.initializer() == null || invalidFields.contains(field)) continue;
                    final var currentFieldIndex = fieldIndex;
                    final var earlierFields = new ArrayList<Stmt.Field>(dataComponents);
                    earlierFields.addAll(declaration.fields().subList(0, currentFieldIndex));
                    Resolver.attemptMember(context,
                            () -> resolveFieldInitializer(context, declaration, field, earlierFields));
                }
            }
            for (final var property : declaration.properties()) {
                if (invalidProperties.contains(property)) continue;
                Resolver.attemptMember(context, () -> {
                    if (declaration.dataClass() && !property.isCustom()
                            && !declaration.isDataComponent(property.name().lexeme())) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                                property.name(),
                                "Data classes support auxiliary fields and computed properties, not stored properties."));
                    }
                    if (property.initializer() != null && !declaration.dataClass()) {
                        final var earlierFields = new ArrayList<Stmt.Field>(dataComponents);
                        earlierFields.addAll(declaration.fields());
                        resolveFieldInitializer(context, declaration,
                                new Stmt.Field(property.name(), property.type(), property.initializer()),
                                earlierFields);
                    }
                    if (property.isCustom()) resolvePropertyAccessors(context, declaration, property);
                });
            }
            Resolver.attemptMember(context, () -> resolveDataClassInitializerBlocks(context, declaration));
            for (final var method : declaration.methods()) {
                if (invalidMethods.contains(method)) continue;
                Resolver.attemptMember(context, () -> resolveMethod(context, declaration, method));
            }
            for (final var pattern : declaration.patterns()) {
                if (invalidPatterns.contains(pattern)) continue;
                Resolver.attemptMember(context, () -> resolvePattern(context, declaration, pattern));
            }
            for (final var constructor : declaration.namedConstructors()) {
                if (invalidNamedConstructors.contains(constructor)) continue;
                Resolver.attemptMember(context, () -> resolveNamedConstructor(context, constructor));
            }
            for (final var contractUse : declaration.contractUses()) {
                if (invalidContractUses.contains(contractUse)) continue;
                final var contract = context.contracts.get(contractUse.name().lexeme());
                if (contract == null) continue;
                Resolver.attemptMember(context, () -> checkConformance(context, declaration, contractUse, contract));
            }
        } finally {
            context.frame.currentClassName = previousClass;
            context.frame.initializerVisibleFields = previousInitializerFields;
            context.frame.dataInitializerOwner = previousDataInitializerOwner;
            context.frame.dataInitializerWritableFields = previousWritableFields;
        }
    }

    private static void validateLiteralSuffixes(final Stmt.ClassDecl declaration) {
        if (declaration.literalSuffixes().isEmpty()) return;
        if (!declaration.typeParameters().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                    declaration.literalSuffixes().getFirst().name(),
                    "Numeric literal suffixes cannot be declared on generic classes."));
        }
        final var names = new HashSet<String>();
        for (final var suffix : declaration.literalSuffixes()) {
            if (!names.add(suffix.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        suffix.name(), "Duplicate class numeric literal suffix."));
            }
            if (suffix.name().lexeme().equals("L")) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        suffix.name(), "The built-in 'L' suffix is reserved."));
            }
            if (suffix.factories().isEmpty()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        suffix.name(), "A numeric literal suffix must declare at least one factory."));
            }
            final var categories = new HashSet<Stmt.LiteralSuffixCategory>();
            for (final var factory : suffix.factories()) {
                if (!categories.add(factory.category())) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                            factory.constructorName(),
                            "A numeric literal suffix can declare only one factory per category."));
                }
                final var constructor = declaration.namedConstructors().stream()
                        .filter(candidate -> candidate.name().lexeme().equals(factory.constructorName().lexeme()))
                        .findFirst().orElse(null);
                if (constructor == null || !constructor.isPublic()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                            factory.constructorName(),
                            "A numeric literal suffix factory must be a public named constructor."));
                }
                final var parameters = constructor.typeDescriptor().parameters();
                if (parameters.size() != 1) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                            factory.constructorName(),
                            "A numeric literal suffix factory must accept exactly one argument."));
                }
                final var parameterType = parameters.getFirst();
                final var accepted = factory.stringInput()
                        ? parameterType instanceof StringDescriptor
                        : factory.category() == Stmt.LiteralSuffixCategory.INTEGER
                                ? parameterType instanceof IntDescriptor || parameterType instanceof LongDescriptor
                                : parameterType instanceof FloatDescriptor;
                if (!accepted) {
                    final var expected = factory.stringInput() ? "String"
                            : factory.category() == Stmt.LiteralSuffixCategory.INTEGER ? "Int or Long" : "Float";
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                            factory.constructorName(),
                            "This numeric literal suffix factory must accept " + expected + "."));
                }
            }
        }
    }

    private static void validateHigherKindedConformances(final ResolutionContext context,
                                                         final Stmt.ClassDecl declaration,
                                                         final Set<String> contractNames) {
        final var declared = new LinkedHashMap<TypeDescriptor, Stmt.ContractUse>();
        for (final var use : declaration.higherKindedContractUses()) {
            final var contract = context.contracts.get(use.name().lexeme());
            if (!contractNames.add(use.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        use.name(), "Duplicate contract conformance."));
            }
            if (contract == null || !contract.higherKinded()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        use.name(), "Constructor-level conformance requires an HKT contract."));
            }
            ensureTypeAccessible(context, use.name().lexeme(), use.name(), contract.isPublic());
            if (contract.typeParameters().size() != 1 || use.typeArguments().size() != 1
                    || !(use.typeArguments().getFirst() instanceof TypeConstructorDescriptor constructor)
                    || !constructor.baseType().name().equals(declaration.name().lexeme())
                    || constructor.arguments().size() != declaration.typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_GENERIC_USE_OR_INFERENCE,
                        use.name(), "A class HKT conformance must use its own constructor pattern."));
            }
            final var conformanceType = TypeDescriptor.genericOf(
                    TypeDescriptor.ofName(use.name().lexeme()), use.typeArguments());
            TypeResolver.validateType(context, conformanceType, use.name());
            if (declared.putIfAbsent(conformanceType, use) != null) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        use.name(), "Duplicate constructor-level contract conformance."));
            }
        }

        final var implemented = new HashSet<TypeDescriptor>();
        for (final var witness : declaration.higherKindedImplementations()) {
            final var contractType = witness.contractType();
            if (!declared.containsKey(contractType)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        witness.name(), "An HKT implementation block must match a class-header conformance."));
            }
            if (!implemented.add(contractType)) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.DUPLICATE_OR_CONFLICTING_NAME,
                        witness.name(), "Duplicate HKT implementation block."));
            }
        }
        for (final var entry : declared.entrySet()) {
            if (!implemented.contains(entry.getKey())) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        entry.getValue().name(), "A class HKT conformance requires an implementation block."));
            }
        }
    }

    private static void resolveDataClassDirectInitializers(final ResolutionContext context,
                                                           final Stmt.ClassDecl declaration,
                                                           final List<Stmt.Field> componentFields) {
        final var fields = declaration.fields().stream()
                .collect(java.util.stream.Collectors.toMap(field -> field.name().lexeme(), field -> field));
        final var properties = declaration.properties().stream()
                .collect(java.util.stream.Collectors.toMap(
                        property -> property.name().lexeme(), property -> property));
        final var visibleFields = new ArrayList<>(componentFields);
        for (final var member : declaration.storedStateOrder()) {
            final var name = member.lexeme();
            if (declaration.isDataComponent(name)) continue;
            final var field = fields.get(name);
            if (field != null) {
                if (field.initializer() != null) {
                    resolveFieldInitializer(context, declaration, field, visibleFields);
                    visibleFields.add(field);
                }
                continue;
            }
            final var property = properties.get(name);
            if (property != null && property.initializer() != null) {
                final var initializedProperty = new Stmt.Field(
                        property.name(), property.type(), property.initializer());
                resolveFieldInitializer(context, declaration, initializedProperty, visibleFields);
                visibleFields.add(initializedProperty);
            }
        }
    }

    private static void resolvePattern(final ResolutionContext context,
                                       final Stmt.ClassDecl owner,
                                       final Stmt.Pattern pattern) {
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        final var enclosingFlow = context.frame.flowState;
        final var previousOwner = context.frame.currentMethodOwner;
        final var previousPattern = context.frame.resolvingPattern;
        final var previousPatternOutputs = context.frame.patternOutputNames;
        context.frame.currentMethodOwner = owner;
        context.frame.resolvingPattern = true;
        context.frame.patternOutputNames = pattern.outputs().stream()
                .map(output -> output.name().lexeme()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        context.frame.flowState = new FlowState();
        beginScope(context);
        final TypeDescriptor ownerType = TypeResolver.classType(context, owner);
        final var thisToken = new Token(TokenType.THIS, "this", null, pattern.name().span());
        declare(context, SYNTHETIC_VAR, thisToken, ownerType, BindingMutability.IMMUTABLE);
        define(context, thisToken);
        try {
            if (pattern.condition() != null) {
                final var conditionType = ExpressionFlowResolver.resolveExpression(context, pattern.condition());
                ensureAssignable(context, TypeDescriptor.ofBoolean(), conditionType, pattern.name());
            }
            for (final var output : pattern.outputs()) {
                declare(context, SYNTHETIC_VAR, output.name(), output.type(), BindingMutability.REASSIGNABLE);
            }
            context.statementResolver.resolveStatements(pattern.body());
            if (context.frame.flowState.isReachable()) {
                for (final var output : pattern.outputs()) {
                    final var writes = context.frame.flowState.writeCount(context.symbols.getSymbol(output.name()).name());
                    if (writes != 1) {
                        Zeron.resolutionError(new ResolutionError(
                                DiagnosticCatalog.INVALID_PATTERN_OUTPUT_ASSIGNMENT,
                                output.name(), writes == 0
                                        ? "Pattern output must be assigned on every completing path."
                                        : "Pattern output must be assigned exactly once on every completing path."));
                    }
                }
            }
            RaisedEffectFlow.verifyCallable(context, List.of(), pattern.name());
        } finally {
            context.frame.resolvingPattern = previousPattern;
            context.frame.patternOutputNames = previousPatternOutputs;
            context.frame.currentMethodOwner = previousOwner;
            context.frame.flowState = enclosingFlow;
            endScope(context);
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    private static void resolveFieldInitializer(final ResolutionContext context, final Stmt.ClassDecl owner,
                                        final Stmt.Field field,
                                        final List<Stmt.Field> earlierFields) {
        if (!owner.dataClass()) {
            validateFieldInitializer(context, field.initializer(), earlierFields, field.name());
        }
        final var previousMethodOwner = context.frame.currentMethodOwner;
        final var previousInitializerFields = context.frame.initializerVisibleFields;
        final var previousFlow = context.frame.flowState;
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        context.frame.currentMethodOwner = owner;
        context.frame.initializerVisibleFields = List.copyOf(earlierFields);
        context.frame.flowState = new FlowState();
        beginScope(context);
        final var thisToken = new Token(TokenType.THIS, "this", null, field.name().span());
        declare(context, SYNTHETIC_VAR, thisToken, TypeResolver.classType(context, owner), BindingMutability.IMMUTABLE);
        define(context, thisToken);
        try {
            final var initializerType = ExpressionFlowResolver.resolveExpression(context, field.initializer());
            ensureAssignable(context, field.type(), initializerType, field.name());
            RaisedEffectFlow.verifyCallable(context, List.of(), field.name());
        } finally {
            endScope(context);
            context.frame.currentMethodOwner = previousMethodOwner;
            context.frame.initializerVisibleFields = previousInitializerFields;
            context.frame.flowState = previousFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    private static void resolveDataClassInitializerBlocks(final ResolutionContext context,
                                                          final Stmt.ClassDecl declaration) {
        if (!declaration.dataClass()) return;
        final var initializedFields = new HashSet<String>();
        declaration.dataComponents().forEach(component -> initializedFields.add(component.name().lexeme()));
        declaration.fields().stream().filter(field -> field.initializer() != null)
                .map(field -> field.name().lexeme()).forEach(initializedFields::add);
        for (final var block : declaration.initializerBlocks()) {
            final var writableFields = new LinkedHashMap<String, Stmt.Field>();
            declaration.fields().stream()
                    .filter(field -> field.initializer() == null
                            && !initializedFields.contains(field.name().lexeme()))
                    .forEach(field -> writableFields.put(field.name().lexeme(), field));
            final var visibleFields = new ArrayList<Stmt.Field>(declaration.dataComponentFields());
            declaration.fields().stream()
                    .filter(field -> initializedFields.contains(field.name().lexeme()))
                    .forEach(visibleFields::add);
            final var previousOwner = context.frame.currentMethodOwner;
            final var previousVisibleFields = context.frame.initializerVisibleFields;
            final var previousDataOwner = context.frame.dataInitializerOwner;
            final var previousWritableFields = context.frame.dataInitializerWritableFields;
            final var previousFlow = context.frame.flowState;
            final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
            context.frame.currentMethodOwner = declaration;
            context.frame.initializerVisibleFields = List.copyOf(visibleFields);
            context.frame.dataInitializerOwner = declaration;
            context.frame.dataInitializerWritableFields = Map.copyOf(writableFields);
            context.frame.flowState = new FlowState();
            beginScope(context);
            final var thisToken = new Token(TokenType.THIS, "this", null, declaration.name().span());
            declare(context, SYNTHETIC_VAR, thisToken,
                    new ReferenceDescriptor(TypeResolver.classType(context, declaration)),
                    BindingMutability.IMMUTABLE);
            define(context, thisToken);
            try {
                context.statementResolver.resolveStatements(block.body());
                if (context.frame.flowState.isReachable()) {
                    for (final var field : writableFields.values()) {
                        final var writes = context.frame.flowState.writeCount(field.name());
                        if (writes > 0) {
                            if (writes != 1) {
                                Zeron.resolutionError(new ResolutionError(
                                        DiagnosticCatalog.INVALID_INITIALIZER, field.name(),
                                        "An auxiliary field must be initialized exactly once."));
                            }
                            initializedFields.add(field.name().lexeme());
                        }
                    }
                }
                RaisedEffectFlow.verifyCallable(context, block.raisedEffects(), declaration.name());
            } finally {
                endScope(context);
                context.frame.currentMethodOwner = previousOwner;
                context.frame.initializerVisibleFields = previousVisibleFields;
                context.frame.dataInitializerOwner = previousDataOwner;
                context.frame.dataInitializerWritableFields = previousWritableFields;
                context.frame.flowState = previousFlow;
                RaisedEffectFlow.endCallable(context, enclosingEffects);
            }
        }
        for (final var field : declaration.fields()) {
            if (field.initializer() == null && !initializedFields.contains(field.name().lexeme())) {
                Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_INITIALIZER,
                        field.name(), "An auxiliary field must be initialized before construction completes."));
            }
        }
    }

    private static void validateFieldInitializer(final ResolutionContext context, final Expr initializer,
                                          final List<Stmt.Field> earlierFields,
                                          final Token where) {
        switch (initializer) {
            case Expr.SuffixedLiteral _ -> {}
            case Expr.Literal _ -> {}
            case Expr.Grouping grouping ->
                    validateFieldInitializer(context, grouping.expression, earlierFields, where);
            case Expr.Unary unary ->
                    validateFieldInitializer(context, unary.right, earlierFields, where);
            case Expr.Binary binary -> {
                validateFieldInitializer(context, binary.left, earlierFields, where);
                validateFieldInitializer(context, binary.right, earlierFields, where);
            }
            case Expr.Logical logical -> {
                validateFieldInitializer(context, logical.left, earlierFields, where);
                validateFieldInitializer(context, logical.right, earlierFields, where);
            }
            case Expr.Variable variable -> {
                if (earlierFields.stream().noneMatch(candidate ->
                        candidate.name().lexeme().equals(variable.name.lexeme()))
                        || !variable.explicitFunctionTypeArguments.isEmpty()) {
                    Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_INITIALIZER,
                            variable.name,
                            "A field initializer may read only fields declared earlier."));
                }
            }
            default -> Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.INVALID_INITIALIZER, where,
                    "Field initializers currently allow only literals, operators, and reads of earlier fields."));
        }
    }

    private static void resolveMethod(final ResolutionContext context, final Stmt.ClassDecl owner, final Stmt.Method method) {
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        beginScope(context);
    context.frame.expectedReturnTypes.push(method.typeDescriptor().returnType());
        final var enclosingFlow = context.frame.flowState;
        final var enclosingMethodOwner = context.frame.currentMethodOwner;
        context.frame.currentMethodOwner = owner;
        context.frame.flowState = new FlowState();
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        final TypeDescriptor ownerType = TypeResolver.classType(context, owner);
        final TypeDescriptor thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
        declare(context, SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        define(context, thisToken);
        for (final var field : owner.fields()) {
            if (!Stmt.isAnonymousCaptureField(field)) continue;
            final var captureName = new Token(TokenType.IDENTIFIER,
                    Stmt.anonymousCaptureSourceName(field), null, field.name().span());
            declare(context, SYNTHETIC_VAR, captureName, field.type(), BindingMutability.IMMUTABLE);
            define(context, captureName);
        }
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            if (i < Stmt.fixedArity(method.parameters(), method.variadic())
                    && !method.defaultValues().isEmpty() && i >= method.minimumArity()) {
                final var defaultValue = method.defaultValues().get(i - method.minimumArity());
                final var expected = method.typeDescriptor().parameters().get(i);
                final var resolvedDefault = MemberInteropResolver.resolveArgument(context, defaultValue, expected);
                ensureAssignable(context, expected, resolvedDefault, method.name());
            }
            declare(context, SYNTHETIC_VAR, parameter, method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            define(context, parameter);
        }
        try {
            resolveStmts(context, method.body());
            ensureReturns(context, method.name(), method.typeDescriptor().returnType(), method.body());
            RaisedEffectFlow.verifyCallable(context, method.typeDescriptor().raisedEffects(), method.name());
        } finally {
            context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.flowState = enclosingFlow;
            context.frame.currentMethodOwner = enclosingMethodOwner;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    private static void resolvePropertyAccessors(final ResolutionContext context, final Stmt.ClassDecl owner,
                                          final Stmt.Property property) {
        if (property.getterBody() != null) {
            final var getterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertyGetterName(property.name().lexeme()), null, property.name().span());
            final var getterType = TypeDescriptor.functionOf(getterName.lexeme(), property.type());
            resolveMethod(context, owner, new Stmt.Method(getterName, List.of(), getterType,
                    property.isPublic(), false, property.getterBody()));
        }
        if (property.setterBody() != null) {
            final var setterName = new Token(TokenType.IDENTIFIER,
                    Stmt.propertySetterName(property.name().lexeme()), null, property.name().span());
            final var setterType = TypeDescriptor.functionOf(setterName.lexeme(),
                    TypeDescriptor.ofUnit(), property.type());
            resolveMethod(context, owner, new Stmt.Method(setterName, List.of(property.setterParameter()),
                    setterType, property.isPublic(), true, property.setterBody()));
        }
    }

    private static void resolveNamedConstructor(final ResolutionContext context, final Stmt.NamedConstructor constructor) {
        final var enclosingEffects = RaisedEffectFlow.beginCallable(context);
        beginScope(context);
        final var enclosingFlow = context.frame.flowState;
        final var enclosingLoopDepth = context.frame.loopDepth;
        context.frame.flowState = new FlowState();
        context.frame.loopDepth = 0;
        try {
            context.frame.expectedReturnTypes.push(constructor.typeDescriptor().returnType());
            for (int i = 0; i < constructor.parameters().size(); i++) {
                final var parameter = constructor.parameters().get(i);
                declare(context, SYNTHETIC_VAR, parameter, constructor.typeDescriptor().parameters().get(i),
                        BindingMutability.IMMUTABLE);
                define(context, parameter);
            }
            resolveStmts(context, constructor.body());
            if (context.frame.flowState.isReachable()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.INCOMPLETE_NAMED_CONSTRUCTOR, constructor.name(),
                        "Named constructor must return an instance on every normal path."));
            }
            RaisedEffectFlow.verifyCallable(context, constructor.typeDescriptor().raisedEffects(), constructor.name());
        } finally {
            if (!context.frame.expectedReturnTypes.isEmpty()) context.frame.expectedReturnTypes.pop();
            endScope(context);
            context.frame.loopDepth = enclosingLoopDepth;
            context.frame.flowState = enclosingFlow;
            RaisedEffectFlow.endCallable(context, enclosingEffects);
        }
    }

    private static void checkConformance(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                  final Stmt.ContractUse contractUse,
                                  final Stmt.ContractDecl contract) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        final var methodErrors = new ArrayList<ResolutionError>();
        ResolutionError firstMethodError = null;
        var firstMethodErrorIndex = -1;
        for (final var required : contract.methods()) {
            final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                    required.typeDescriptor(), substitutions);
            final var implementation = declaration.methods().stream()
                    .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                    .filter(method -> method.isPublic() && method.isMutating() == required.isMutating()
                            && compatibleMethodSignatures(context, requiredType, required.variadic(),
                                    method.typeDescriptor(), method.variadic()))
                    .findFirst()
                    .orElse(null);
            final var defaults = implementation == null
                    ? defaultMethodsFor(context, declaration, requiredType, required.variadic())
                    : List.<Resolver.DefaultMethodSelection>of();
            if (defaults.size() > 1) {
                methodErrors.add(new ResolutionError(DiagnosticCatalog.INVALID_SEALED_CONTRACT_OR_MATCH,
                        contractUse.name(),
                        "Multiple default contract methods named '" + required.name().lexeme()
                                + "' require an explicit class implementation."));
                continue;
            }
            final var defaultMethod = defaults.size() == 1
                    && defaults.getFirst().method().isMutating() == required.isMutating()
                    ? defaults.getFirst()
                    : null;
            if (implementation == null && defaultMethod == null
                    || implementation != null && (!implementation.isPublic()
                    || implementation.isMutating() != required.isMutating()
                    || !compatibleMethodSignatures(context, requiredType, required.variadic(),
                            implementation.typeDescriptor(), implementation.variadic()))) {
                final var candidates = declaration.methods().stream()
                        .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                        .map(DeclarationResolver::formatMethodCandidate)
                        .toList();
                final var contractType = contractUse.typeArguments().isEmpty()
                        ? TypeDescriptor.ofName(contractUse.name().lexeme())
                        : TypeDescriptor.genericOf(
                                TypeDescriptor.ofName(contractUse.name().lexeme()),
                                contractUse.typeArguments());
                final var candidateDetails = candidates.isEmpty()
                        ? "No method named '" + required.name().lexeme() + "' is declared."
                        : "Found " + String.join("; ", candidates) + ".";
                final var methodError = new ResolutionError(
                        DiagnosticCatalog.INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
                        contractUse.name(),
                        "Contract '" + TypeFormatter.format(contractType) + "' requires "
                                + formatMethodSignature(required.name().lexeme(), requiredType,
                                        required.isMutating())
                                + ", but this class does not provide a compatible public implementation. "
                                + candidateDetails
                                + " The implementation must match the required parameter and return types"
                                + (required.isMutating() ? " and be mutating." : "."));
                if (firstMethodError == null) {
                    firstMethodError = methodError;
                    firstMethodErrorIndex = methodErrors.size();
                }
                methodErrors.add(methodError);
            }
        }
        if (!methodErrors.isEmpty()) {
            final var missingMethodStubs = missingMethodStubs(context, declaration, contractUse);
            if (!missingMethodStubs.isEmpty() && firstMethodError != null) {
                final var closingBraceSpan = declaration.closingBrace().span();
                final var position = closingBraceSpan.start();
                firstMethodError = firstMethodError.withHelp(new DiagnosticHelp(
                        "Add missing contract method stubs",
                        new SourceSpan(closingBraceSpan.sourcePath(), position, position),
                        String.join("\n", missingMethodStubs)));
                methodErrors.set(firstMethodErrorIndex, firstMethodError);
            }
            methodErrors.forEach(error -> context.diagnostics.record(error, context.currentSourcePath));
        }

        final var propertyErrors = new ArrayList<ResolutionError>();
        for (final var required : contract.properties()) {
            final var implementation = declaration.properties().stream()
                    .filter(property -> property.name().lexeme().equals(required.name().lexeme()))
                    .findFirst()
                    .orElse(null);
            final var requiredType = TypeSubstitution.substitute(required.type(), substitutions);
            if (implementation == null || !implementation.isPublic()
                    || (required.isMutating() && !implementation.isMutating())
                    || !requiredType.equals(implementation.type())) {
                propertyErrors.add(new ResolutionError(DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE,
                        contractUse.name(),
                        "Class does not provide a compatible public contract property '"
                                + required.name().lexeme() + "'."));
            }
        }
        propertyErrors.forEach(error -> context.diagnostics.record(error, context.currentSourcePath));

        final var closure = ContractComposition.closure(context, contractUse);
        for (int index = 1; index < closure.size(); index++) {
            final var inherited = closure.get(index);
            checkConformance(context, declaration, inherited.use(), inherited.declaration());
        }
    }

    private static String formatMethodCandidate(final Stmt.Method method) {
        final var visibility = method.isPublic() ? "public " : "non-public ";
        return visibility + formatMethodSignature(method.name().lexeme(),
                method.typeDescriptor(), method.isMutating());
    }

    private static String formatMethodSignature(final String name, final FunctionDescriptor type,
                                                final boolean isMutating) {
        return (isMutating ? "mut " : "") + "'" + name + TypeFormatter.format(type) + "'";
    }

    private static String formatMethodStub(final Stmt.ContractMethod required,
                                           final FunctionDescriptor requiredType) {
        if (required.parameters().size() != requiredType.parameters().size()
                || required.parameters().stream().anyMatch(parameter ->
                        parameter.lexeme().equals("this"))) return null;
        final var formattedType = TypeFormatter.format(requiredType);
        final var genericEnd = requiredType.typeParameters().isEmpty()
                ? -1
                : formattedType.indexOf(">(");
        if (!requiredType.typeParameters().isEmpty() && genericEnd < 0) return null;
        final var genericParameters = genericEnd < 0 ? "" : formattedType.substring(0, genericEnd + 1);
        final var parameters = new ArrayList<String>();
        for (var index = 0; index < required.parameters().size(); index++) {
            parameters.add(required.parameters().get(index).lexeme() + ": "
                    + TypeFormatter.format(requiredType.parameters().get(index)));
        }
        final var effects = requiredType.raisedEffects().isEmpty()
                ? ""
                : " raises " + requiredType.raisedEffects().stream()
                        .map(TypeFormatter::format).collect(java.util.stream.Collectors.joining(", "));
        return "public " + (required.isMutating() ? "mut " : "") + required.name().lexeme()
                + genericParameters + parameters.stream().collect(
                        java.util.stream.Collectors.joining(", ", "(", ")"))
                + ": " + TypeFormatter.format(requiredType.returnType()) + effects
                + " {\n    // TODO: Implement.\n}";
    }

    private static List<String> missingMethodStubs(final ResolutionContext context,
                                                   final Stmt.ClassDecl declaration,
                                                   final Stmt.ContractUse rootContractUse) {
        if (declaration.closingBrace() == null) return List.of();
        final var stubs = new ArrayList<String>();
        final var stubNames = new HashSet<String>();
        for (final var applied : ContractComposition.closure(context, rootContractUse)) {
            for (final var required : applied.declaration().methods()) {
                if (ContractComposition.isShadowedByAncestor(context, rootContractUse,
                        applied.use(), required.name().lexeme())) continue;
                final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                        required.typeDescriptor(), applied.substitutions());
                final var implementation = declaration.methods().stream()
                        .filter(method -> method.name().lexeme().equals(required.name().lexeme()))
                        .filter(method -> method.isPublic() && method.isMutating() == required.isMutating()
                                && compatibleMethodSignatures(context, requiredType, required.variadic(),
                                        method.typeDescriptor(), method.variadic()))
                        .findFirst()
                        .orElse(null);
                final var defaults = implementation == null
                        ? defaultMethodsFor(context, declaration, requiredType, required.variadic())
                        : List.<Resolver.DefaultMethodSelection>of();
                final var hasDefault = defaults.size() == 1
                        && defaults.getFirst().method().isMutating() == required.isMutating();
                if (implementation != null || hasDefault) continue;
                if (defaults.size() > 1 || required.isStatic() || required.variadic()
                        || !required.defaultValues().isEmpty() || !stubNames.add(required.name().lexeme())) {
                    return List.of();
                }
                final var stub = formatMethodStub(required, requiredType);
                if (stub == null) return List.of();
                stubs.add(stub);
            }
        }
        return List.copyOf(stubs);
    }

    static Resolver.DefaultMethodSelection defaultMethodFor(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                            final Stmt.ContractUse requiredUse,
                                                            final Stmt.ContractMethod required) {
        final var substitutions = contractSubstitutions(context, requiredUse);
        final var requiredType = (FunctionDescriptor) TypeSubstitution.substitute(
                required.typeDescriptor(), substitutions);
        final var matches = defaultMethodsFor(context, declaration, requiredType, required.variadic());
        return matches.size() == 1
                && matches.getFirst().method().isMutating() == required.isMutating()
                ? matches.getFirst()
                : null;
    }

    private static List<Resolver.DefaultMethodSelection> defaultMethodsFor(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                                   final FunctionDescriptor requiredType,
                                                                   final boolean requiredVariadic) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        final var seen = new HashSet<String>();
        for (final var root : declaration.contractUses()) {
            for (final var applied : ContractComposition.closure(context, root)) {
                final var candidateContract = applied.declaration();
                final var substitutions = applied.substitutions();
                for (final var candidate : candidateContract.methods()) {
                    if (ContractComposition.isShadowedByAncestor(context, root, applied.use(),
                            candidate.name().lexeme())) continue;
                    final var key = applied.use().name().lexeme() + applied.use().typeArguments()
                            + "#" + candidate.name().lexeme();
                    if (!seen.add(key)) continue;
                if (!candidate.isDefault()
                        || !candidate.name().lexeme().equals(requiredType.name())) continue;
                final var candidateType = (FunctionDescriptor) TypeSubstitution.substitute(
                        candidate.typeDescriptor(), substitutions);
                if (compatibleMethodSignatures(context, requiredType, requiredVariadic,
                        candidateType, candidate.variadic())) {
                    matches.add(new Resolver.DefaultMethodSelection(candidateContract.name().lexeme(), candidate,
                            candidateType));
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    static List<Resolver.DefaultMethodSelection> defaultMethodsOnClass(final ResolutionContext context, final Stmt.ClassDecl declaration,
                                                                       final String methodName) {
        final var matches = new ArrayList<Resolver.DefaultMethodSelection>();
        final var seen = new HashSet<String>();
        for (final var root : declaration.contractUses()) {
            for (final var applied : ContractComposition.closure(context, root)) {
                final var contract = applied.declaration();
                final var substitutions = applied.substitutions();
                for (final var method : contract.methods()) {
                    if (ContractComposition.isShadowedByAncestor(context, root, applied.use(),
                            method.name().lexeme())) continue;
                    final var key = applied.use().name().lexeme() + applied.use().typeArguments()
                            + "#" + method.name().lexeme();
                    if (!seen.add(key)) continue;
                if ((!method.isDefault()
                        && method.minimumArity()
                            == Stmt.fixedArity(method.parameters(), method.variadic()))
                        || !method.name().lexeme().equals(methodName)) continue;
                final var type = (FunctionDescriptor) TypeSubstitution.substitute(
                        method.typeDescriptor(), substitutions);
                matches.add(new Resolver.DefaultMethodSelection(contract.name().lexeme(), method, type));
                }
            }
        }
        return List.copyOf(matches);
    }

    private static LinkedHashMap<TypeParameterDescriptor, TypeDescriptor> contractSubstitutions(
            final ResolutionContext context,
            final Stmt.ContractUse contractUse) {
        final var contract = context.contracts.get(contractUse.name().lexeme());
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), contractUse.typeArguments().get(i));
        }
        return substitutions;
    }

    static boolean compatibleMethodSignatures(final ResolutionContext context,
                                               final FunctionDescriptor required,
                                               final boolean requiredVariadic,
                                               final FunctionDescriptor implementation,
                                               final boolean implementationVariadic) {
        if (requiredVariadic != implementationVariadic
                || required.typeParameters().size() != implementation.typeParameters().size()
                || required.arity() != implementation.arity()) return false;
        final var methodSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < required.typeParameters().size(); i++) {
            methodSubstitutions.put(required.typeParameters().get(i),
                    implementation.typeParameters().get(i));
        }
        for (int i = 0; i < required.typeParameters().size(); i++) {
            final var requiredParameter = required.typeParameters().get(i);
            final var implementationParameter = implementation.typeParameters().get(i);
            if (requiredParameter.admitsNullableArguments()
                    != implementationParameter.admitsNullableArguments()
                    || requiredParameter.isEffectKind() != implementationParameter.isEffectKind()
                    || requiredParameter.admitsMutableArguments()
                            != implementationParameter.admitsMutableArguments()) {
                return false;
            }
            final var requiredBounds = required.typeParameters().get(i).bounds().stream()
                    .map(bound -> TypeSubstitution.substitute(bound, methodSubstitutions))
                    .toList();
            if (!requiredBounds.equals(implementationParameter.bounds())) return false;
        }
        for (int i = 0; i < required.arity(); i++) {
            if (!TypeSubstitution.substitute(required.parameters().get(i), methodSubstitutions)
                    .equals(implementation.parameters().get(i))) return false;
        }
        final var requiredReturn = TypeSubstitution.substitute(required.returnType(), methodSubstitutions);
        final var requiredEffects = required.raisedEffects().stream()
                .map(effect -> TypeSubstitution.substitute(effect, methodSubstitutions)).toList();
        return requiredEffects.containsAll(implementation.raisedEffects())
                && context.typeCompatibility.canAssign(requiredReturn, implementation.returnType());
    }

}
