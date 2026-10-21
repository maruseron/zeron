package com.maruseron.zeron.api;

import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.util.*;

/**
 * Immutable, editor-independent catalog of public Zeron type members.
 *
 * <p>Source and library catalogs share the same semantic model. Source locations are supplementary
 * and use zero-based UTF-16 offsets, lines, and characters.</p>
 */
public final class PublicMemberCatalog {
    public enum TypeKind { CLASS, CONTRACT, SUM, PRODUCT, NEWTYPE }
    public enum MemberKind { METHOD, PROPERTY, CONSTRUCTOR }
    public enum MemberOrigin { DIRECT, CONTRACT_REQUIREMENT, CONTRACT_DEFAULT, EXTENSION }

    public sealed interface TypeRef permits NamedType, GenericType, TypeVariable,
            ArrayType, NullableType, ReferenceType, FunctionType, HandlerType, ProjectedType {
        static TypeRef named(final String name) {
            return new NamedType(name);
        }

        private static TypeRef from(final TypeDescriptor descriptor) {
            return switch (descriptor) {
                case TypeParameterDescriptor parameter -> new TypeVariable(parameter.name(),
                        parameter.bounds().stream().map(TypeRef::from).toList(),
                        parameter.admitsNullableArguments(), parameter.isEffectKind(),
                        parameter.admitsMutableArguments());
                case TypeProjectionDescriptor projection -> new ProjectedType(projection.kind(),
                        TypeRef.from(projection.bound()));
                case GenericDescriptor generic -> new GenericType(generic.baseType().name(),
                        generic.typeParameters().stream().map(TypeRef::from).toList());
                case ArrayDescriptor array -> new ArrayType(from(array.elementType()));
                case NullableDescriptor nullable -> new NullableType(from(nullable.baseType()));
                case ReferenceDescriptor reference -> new ReferenceType(from(reference.baseType()));
                case FunctionDescriptor function when function.isHandler() -> new HandlerType(
                        from(function.returnType()),
                        function.handledEffects().stream().map(TypeRef::from).toList(),
                        function.raisedEffects().stream().map(TypeRef::from).toList());
                case FunctionDescriptor function -> new FunctionType(function.name(),
                        function.typeParameters().stream().map(TypeParameter::from).toList(),
                        function.parameters().stream().map(TypeRef::from).toList(),
                        from(function.returnType()),
                        function.raisedEffects().stream().map(TypeRef::from).toList());
                case NominalDescriptor nominal -> new NamedType(nominal.name());
                default -> new NamedType(descriptor.name());
            };
        }
    }

    public record NamedType(String name) implements TypeRef {
        public NamedType { Objects.requireNonNull(name); }
    }

    public record GenericType(String name, List<TypeRef> arguments) implements TypeRef {
        public GenericType {
            Objects.requireNonNull(name);
            arguments = List.copyOf(arguments);
        }
    }

    public record TypeVariable(String name, List<TypeRef> bounds,
                               boolean admitsNullableArguments, boolean effectKind,
                               boolean admitsMutableArguments) implements TypeRef {
        public TypeVariable {
            Objects.requireNonNull(name);
            bounds = List.copyOf(bounds);
        }

        public TypeVariable(final String name, final List<TypeRef> bounds) {
            this(name, bounds, false, false, false);
        }

        public TypeVariable(final String name, final List<TypeRef> bounds,
                            final boolean admitsNullableArguments, final boolean effectKind) {
            this(name, bounds, admitsNullableArguments, effectKind, false);
        }
    }

    public record ProjectedType(TypeProjectionDescriptor.Kind kind, TypeRef bound) implements TypeRef {
        public ProjectedType {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(bound);
        }
    }

    public record ArrayType(TypeRef elementType) implements TypeRef {
        public ArrayType { Objects.requireNonNull(elementType); }
    }

    public record NullableType(TypeRef baseType) implements TypeRef {
        public NullableType { Objects.requireNonNull(baseType); }
    }

    public record ReferenceType(TypeRef baseType) implements TypeRef {
        public ReferenceType { Objects.requireNonNull(baseType); }
    }

    public record FunctionType(String name, List<TypeParameter> typeParameters,
                               List<TypeRef> parameters, TypeRef returnType,
                               List<TypeRef> raisedEffects) implements TypeRef {
        public FunctionType {
            Objects.requireNonNull(name);
            typeParameters = List.copyOf(typeParameters);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType);
            raisedEffects = List.copyOf(raisedEffects);
        }
    }

    public record HandlerType(TypeRef returnType, List<TypeRef> handledEffects,
                              List<TypeRef> raisedEffects) implements TypeRef {
        public HandlerType {
            Objects.requireNonNull(returnType);
            handledEffects = List.copyOf(handledEffects);
            raisedEffects = List.copyOf(raisedEffects);
        }
    }

    public record TypeParameter(String name, List<TypeRef> bounds,
                                boolean admitsNullableArguments, boolean effectKind,
                                boolean admitsMutableArguments) {
        public TypeParameter {
            Objects.requireNonNull(name);
            bounds = List.copyOf(bounds);
        }

        public TypeParameter(final String name, final List<TypeRef> bounds) {
            this(name, bounds, false, false, false);
        }

        public TypeParameter(final String name, final List<TypeRef> bounds,
                             final boolean admitsNullableArguments, final boolean effectKind) {
            this(name, bounds, admitsNullableArguments, effectKind, false);
        }

        private static TypeParameter from(final TypeParameterDescriptor parameter) {
            return new TypeParameter(parameter.name(),
                    parameter.bounds().stream().map(TypeRef::from).toList(),
                    parameter.admitsNullableArguments(), parameter.isEffectKind(),
                    parameter.admitsMutableArguments());
        }
    }

    public record FunctionSignature(List<TypeParameter> typeParameters, List<Parameter> parameters,
                                    TypeRef returnType, List<TypeRef> raisedEffects) {
        public FunctionSignature {
            typeParameters = List.copyOf(typeParameters);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType);
            raisedEffects = List.copyOf(raisedEffects);
        }
    }

    public record SourceRange(int startOffset, int endOffset,
                              int startLine, int startCharacter,
                              int endLine, int endCharacter) {
        public SourceRange {
            if (startOffset < 0 || endOffset < startOffset
                    || startLine < 0 || startCharacter < 0
                    || endLine < startLine || endCharacter < 0) {
                throw new IllegalArgumentException("Invalid source range.");
            }
        }
    }

    public record Provenance(String sourceUri, Optional<SourceRange> range) {
        public Provenance {
            Objects.requireNonNull(sourceUri);
            range = Objects.requireNonNull(range);
        }
    }

    public record Parameter(Optional<String> name, TypeRef type,
                            boolean hasDefault, boolean variadic) {
        public Parameter {
            name = Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record Member(String declarationId, String declaringType, String name,
                         MemberKind kind, MemberOrigin origin, String originType,
                         String canonicalSignature, Optional<FunctionSignature> signature,
                         List<TypeParameter> typeParameters,
                         List<Parameter> parameters, List<Parameter> appliedParameters,
                         Optional<TypeRef> valueType,
                         Optional<TypeRef> returnType, List<TypeRef> raisedEffects,
                         boolean mutating, boolean readable, boolean writable,
                         int minimumArity, boolean variadic,
                         Map<String, TypeRef> appliedSubstitutions,
                         Optional<FunctionSignature> appliedSignature, Optional<TypeRef> appliedValueType,
                         Optional<TypeRef> appliedReturnType,
                         String activationImport, String activationNamespace,
                         Optional<Provenance> provenance) {
        public Member {
            Objects.requireNonNull(declarationId);
            Objects.requireNonNull(declaringType);
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(originType);
            Objects.requireNonNull(canonicalSignature);
            typeParameters = List.copyOf(typeParameters);
            parameters = List.copyOf(parameters);
            appliedParameters = List.copyOf(appliedParameters);
            raisedEffects = List.copyOf(raisedEffects);
            appliedSubstitutions = Collections.unmodifiableMap(new LinkedHashMap<>(appliedSubstitutions));
            provenance = Objects.requireNonNull(provenance);
            if (minimumArity < 0 || minimumArity > parameters.size()) {
                throw new IllegalArgumentException("Invalid member minimum arity.");
            }
        }
    }

    public record TypeView(String qualifiedName, TypeKind kind,
                           List<TypeParameter> typeParameters,
                           List<TypeRef> appliedArguments,
                           Map<String, TypeRef> appliedSubstitutions,
                           List<Member> directMembers, List<Member> contractMembers,
                           List<Member> extensions, Optional<Provenance> provenance) {
        public TypeView {
            Objects.requireNonNull(qualifiedName);
            Objects.requireNonNull(kind);
            typeParameters = List.copyOf(typeParameters);
            appliedArguments = List.copyOf(appliedArguments);
            appliedSubstitutions = Collections.unmodifiableMap(new LinkedHashMap<>(appliedSubstitutions));
            directMembers = List.copyOf(directMembers);
            contractMembers = List.copyOf(contractMembers);
            extensions = List.copyOf(extensions);
            provenance = Objects.requireNonNull(provenance);
        }
    }

    private final Map<String, TypeSpec> types;
    private final List<ExtensionSpec> extensions;

    private PublicMemberCatalog(final Map<String, TypeSpec> types, final List<ExtensionSpec> extensions) {
        this.types = Collections.unmodifiableMap(new TreeMap<>(types));
        this.extensions = extensions.stream()
                .sorted(Comparator.comparing(ExtensionSpec::qualifiedName)
                        .thenComparing(spec -> canonicalFunction(spec.signature(), spec.minimumArity(),
                                spec.variadic(), spec.mutating())))
                .toList();
    }

    public static PublicMemberCatalog fromSource(final ResolutionResult resolution) {
        Objects.requireNonNull(resolution);
        final var types = new LinkedHashMap<String, TypeSpec>();
        final var generatedSumCases = new HashSet<String>();
        resolution.sums().values().forEach(sum -> sum.cases().stream()
                .map(sum::generatedCaseName).forEach(generatedSumCases::add));
        for (final var entry : resolution.classes().entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic()
                    || generatedSumCases.stream().anyMatch(name -> sameSourceName(name,
                            declaration.name().lexeme()))
                    || resolution.products().values().stream().anyMatch(product ->
                            sameSourceName(product.name().lexeme(), declaration.name().lexeme()))
                    || resolution.newtypes().values().stream().anyMatch(newtype ->
                            sameSourceName(newtype.name().lexeme(), declaration.name().lexeme()))) continue;
            final var name = qualifySourceName(resolution.typePackages().getOrDefault(
                    declaration.name().lexeme(), resolution.packageName()), declaration.name().lexeme());
            final var members = new ArrayList<MemberSpec>();
            if (declaration.constructor().isPublic()) {
                final var params = new ArrayList<ParameterSpec>();
                for (final var field : declaration.fields()) {
                    if (field.initializer() == null) {
                        params.add(new ParameterSpec(field.name().lexeme(), field.type(), false, false));
                    }
                }
                for (final var property : declaration.properties()) {
                    if (property.canonicalConstructorParameter()) {
                        params.add(new ParameterSpec(property.name().lexeme(), property.type(), false, false));
                    }
                }
                members.add(MemberSpec.constructor(name, "new", params, false,
                        provenance(declaration.constructor().name())));
            }
            declaration.namedConstructors().stream().filter(Stmt.NamedConstructor::isPublic)
                    .forEach(constructor -> members.add(MemberSpec.callable(name, constructor.name().lexeme(),
                            MemberKind.CONSTRUCTOR, constructor.typeDescriptor(),
                            constructor.parameters().stream().map(Token::lexeme).toList(),
                            constructor.typeDescriptor().arity() - (constructor.variadic() ? 1 : 0),
                            constructor.variadic(), false, provenance(constructor.name()))));
            declaration.methods().stream().filter(Stmt.Method::isPublic)
                    .forEach(method -> members.add(MemberSpec.callable(name, method.name().lexeme(),
                            MemberKind.METHOD, method.typeDescriptor(),
                            method.parameters().stream().map(Token::lexeme).toList(),
                            method.minimumArity(), method.variadic(), method.isMutating(),
                            provenance(method.name()))));
            declaration.properties().stream().filter(Stmt.Property::isPublic)
                    .forEach(property -> members.add(MemberSpec.property(name, property.name().lexeme(),
                            property.type(), property.isMutating(), provenance(property.name()))));
            final var uses = declaration.contractUses().stream()
                    .map(use -> new UseSpec(use.name().lexeme(), use.typeArguments())).toList();
            types.put(name, new TypeSpec(name, TypeKind.CLASS, declaration.typeParameters(), uses,
                    members, provenance(declaration.name())));
        }
        for (final var entry : resolution.contracts().entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic() || resolution.sums().values().stream().anyMatch(sum ->
                    sameSourceName(sum.name().lexeme(), declaration.name().lexeme()))) continue;
            final var name = qualifySourceName(resolution.typePackages().getOrDefault(
                    declaration.name().lexeme(), resolution.packageName()), declaration.name().lexeme());
            final var members = new ArrayList<MemberSpec>();
            declaration.methods().forEach(method -> members.add(MemberSpec.callable(name,
                    method.name().lexeme(), MemberKind.METHOD, method.typeDescriptor(),
                    method.parameters().stream().map(Token::lexeme).toList(), method.minimumArity(),
                    method.variadic(), method.isMutating(), provenance(method.name()),
                    method.isDefault() ? MemberOrigin.CONTRACT_DEFAULT : MemberOrigin.CONTRACT_REQUIREMENT)));
            declaration.properties().forEach(property -> members.add(MemberSpec.property(name,
                    property.name().lexeme(), property.type(), property.isMutating(),
                    provenance(property.name()), MemberOrigin.CONTRACT_REQUIREMENT)));
            final var uses = declaration.requiredContracts().stream()
                    .map(use -> new UseSpec(use.name().lexeme(), use.typeArguments())).toList();
            types.put(name, new TypeSpec(name, TypeKind.CONTRACT, declaration.typeParameters(),
                    uses, members, provenance(declaration.name())));
        }
        for (final var entry : resolution.sums().entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic()) continue;
            final var name = qualifySourceName(resolution.typePackages().getOrDefault(
                    declaration.name().lexeme(), resolution.packageName()), declaration.name().lexeme());
            final var members = declaration.cases().stream().map(sumCase -> MemberSpec.constructor(name,
                    sumCase.name().lexeme(), sumCase.payloads().stream()
                            .map(payload -> new ParameterSpec(payload.name().lexeme(), payload.type(),
                                    false, false)).toList(), false, provenance(sumCase.name())))
                    .toList();
            types.put(name, new TypeSpec(name, TypeKind.SUM, declaration.typeParameters(),
                    List.of(), members, provenance(declaration.name())));
        }
        for (final var entry : resolution.products().entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic()) continue;
            final var name = qualifySourceName(resolution.typePackages().getOrDefault(
                    declaration.name().lexeme(), resolution.packageName()), declaration.name().lexeme());
            final var members = new ArrayList<MemberSpec>();
            final var constructorParameters = new ArrayList<ParameterSpec>();
            for (final var component : declaration.components()) {
                members.add(MemberSpec.property(name, component.name().lexeme(), component.type(),
                        false, provenance(component.name())));
                constructorParameters.add(new ParameterSpec(component.name().lexeme(), component.type(),
                        false, false));
            }
            members.add(MemberSpec.constructor(name, "new", constructorParameters,
                    false, provenance(declaration.name())));
            types.put(name, new TypeSpec(name, TypeKind.PRODUCT, declaration.typeParameters(),
                    List.of(), members, provenance(declaration.name())));
        }
        for (final var entry : resolution.newtypes().entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic()) continue;
            final var name = qualifySourceName(resolution.typePackages().getOrDefault(
                    declaration.name().lexeme(), resolution.packageName()), declaration.name().lexeme());
            final var members = new ArrayList<MemberSpec>();
            members.add(MemberSpec.property(name, "value", declaration.underlyingType(),
                    false, provenance(declaration.name())));
            if (declaration.validator() == null) {
                members.add(MemberSpec.constructor(name, "new",
                        List.of(new ParameterSpec("value", declaration.underlyingType(), false, false)),
                        false, provenance(declaration.name())));
            }
            types.put(name, new TypeSpec(name, TypeKind.NEWTYPE, List.of(),
                    List.of(), members, provenance(declaration.name())));
        }
        final var extensions = new ArrayList<ExtensionSpec>();
        for (final var extension : resolution.extensions()) {
            if (!extension.isPublic()) continue;
            final var qname = resolution.functionNamesByDeclaration().get(extension.name());
            if (qname == null) continue;
            final var namespace = namespaceOf(qname);
            extensions.add(new ExtensionSpec(qname, namespace, extension.receiverType(),
                    extension.receiverTypeParameters(), extension.typeDescriptor(),
                    extension.parameters().stream().map(Token::lexeme).toList(),
                    extension.minimumCallArity(), extension.variadic(), extension.isMutating(),
                    extension.property(), provenance(extension.name())));
        }
        return new PublicMemberCatalog(types, extensions);
    }

    public static PublicMemberCatalog fromLibrary(final ZeronLibraryIndex library) {
        Objects.requireNonNull(library);
        final var types = new LinkedHashMap<String, TypeSpec>();
        final var extensions = new ArrayList<ExtensionSpec>();
        for (final var exported : library.declarations()) {
            switch (exported) {
                case ZeronLibraryIndex.ClassExport declaration -> {
                    final var members = new ArrayList<MemberSpec>();
                    if (declaration.canonicalConstructorPublic()) {
                        final var params = new ArrayList<ParameterSpec>();
                        for (int i = 0; i < declaration.canonicalConstructorParameters().size(); i++) {
                            final var name = declaration.canonicalConstructorParameterNames().isEmpty()
                                    ? null : declaration.canonicalConstructorParameterNames().get(i);
                            params.add(new ParameterSpec(name,
                                    declaration.canonicalConstructorParameters().get(i), false, false));
                        }
                        members.add(MemberSpec.constructor(declaration.qualifiedName(), "new", params,
                                false, Optional.empty()));
                    }
                    declaration.namedConstructors().forEach(constructor -> members.add(MemberSpec.callable(
                            declaration.qualifiedName(), constructor.name(), MemberKind.CONSTRUCTOR,
                            constructor.signature(), constructor.parameterNames(),
                            constructor.signature().arity() - (constructor.variadic() ? 1 : 0),
                            constructor.variadic(), false, Optional.empty())));
                    declaration.methods().forEach(method -> members.add(MemberSpec.callable(
                            declaration.qualifiedName(), method.name(), MemberKind.METHOD,
                            method.signature(), method.parameterNames(), method.minimumArity(),
                            method.variadic(), method.mutating(), Optional.empty())));
                    declaration.properties().forEach(property -> members.add(MemberSpec.property(
                            declaration.qualifiedName(), property.name(), property.type(),
                            property.mutating(), Optional.empty())));
                    final var uses = declaration.contracts().stream()
                            .map(use -> new UseSpec(use.qualifiedName(), use.typeArguments())).toList();
                    types.put(declaration.qualifiedName(), new TypeSpec(declaration.qualifiedName(),
                            TypeKind.CLASS, declaration.typeParameters(), uses, members, Optional.empty()));
                }
                case ZeronLibraryIndex.ContractExport declaration -> {
                    final var members = new ArrayList<MemberSpec>();
                    declaration.methods().forEach(method -> members.add(MemberSpec.callable(
                            declaration.qualifiedName(), method.name(), MemberKind.METHOD,
                            method.signature(), method.parameterNames(), method.minimumArity(),
                            method.variadic(), method.mutating(), Optional.empty(),
                            method.defaultMethod() ? MemberOrigin.CONTRACT_DEFAULT
                                    : MemberOrigin.CONTRACT_REQUIREMENT)));
                    declaration.properties().forEach(property -> members.add(MemberSpec.property(
                            declaration.qualifiedName(), property.name(), property.type(),
                            property.mutating(), Optional.empty(), MemberOrigin.CONTRACT_REQUIREMENT)));
                    final var uses = declaration.requiredContracts().stream()
                            .map(use -> new UseSpec(use.qualifiedName(), use.typeArguments())).toList();
                    types.put(declaration.qualifiedName(), new TypeSpec(declaration.qualifiedName(),
                            TypeKind.CONTRACT, declaration.typeParameters(), uses, members, Optional.empty()));
                }
                case ZeronLibraryIndex.SumExport declaration -> {
                    final var members = declaration.cases().stream().map(sumCase ->
                            MemberSpec.constructor(declaration.qualifiedName(), sumCase.name(),
                                    sumCase.payloads().stream().map(payload -> new ParameterSpec(
                                            payload.name(), payload.type(), false, false)).toList(),
                                    false, Optional.empty())).toList();
                    types.put(declaration.qualifiedName(), new TypeSpec(declaration.qualifiedName(),
                            TypeKind.SUM, declaration.typeParameters(), List.of(), members, Optional.empty()));
                }
                case ZeronLibraryIndex.ProductExport declaration -> {
                    final var members = new ArrayList<MemberSpec>();
                    final var constructorParameters = new ArrayList<ParameterSpec>();
                    for (final var component : declaration.components()) {
                        members.add(MemberSpec.property(declaration.qualifiedName(), component.name(),
                                component.type(), false, Optional.empty()));
                        constructorParameters.add(new ParameterSpec(component.name(), component.type(),
                                false, false));
                    }
                    members.add(MemberSpec.constructor(declaration.qualifiedName(), "new",
                            constructorParameters, false, Optional.empty()));
                    types.put(declaration.qualifiedName(), new TypeSpec(declaration.qualifiedName(),
                            TypeKind.PRODUCT, declaration.typeParameters(), List.of(), members, Optional.empty()));
                }
                case ZeronLibraryIndex.NewtypeExport declaration -> {
                    final var members = new ArrayList<MemberSpec>();
                    members.add(MemberSpec.property(declaration.qualifiedName(), "value",
                            declaration.underlyingType(), false, Optional.empty()));
                    if (!declaration.validated()) {
                        members.add(MemberSpec.constructor(declaration.qualifiedName(), "new",
                                List.of(new ParameterSpec("value", declaration.underlyingType(),
                                        false, false)), false, Optional.empty()));
                    }
                    types.put(declaration.qualifiedName(), new TypeSpec(declaration.qualifiedName(),
                            TypeKind.NEWTYPE, List.of(), List.of(), members, Optional.empty()));
                }
                case ZeronLibraryIndex.ExtensionExport extension -> extensions.add(new ExtensionSpec(
                        extension.qualifiedName(), namespaceOf(extension.qualifiedName()),
                        extension.receiverType(),
                        extension.signature().typeParameters().subList(0,
                                extension.receiverTypeParameterCount()),
                        extension.signature(), extension.parameterNames(), extension.minimumArity(),
                        extension.variadic(), extension.mutating(), extension.property(), Optional.empty()));
                default -> { }
            }
        }
        return new PublicMemberCatalog(types, extensions);
    }

    public List<String> typeNames() {
        return List.copyOf(types.keySet());
    }

    /** All exported extensions, always separate from directly available type members. */
    public List<Member> extensions() {
        return extensions.stream().map(extension -> extension.member(Map.of())).toList();
    }

    public Optional<TypeView> findType(final String qualifiedName) {
        return findType(qualifiedName, List.of());
    }

    /**
     * Queries a public type. An empty type-argument list denotes its open declaration; otherwise
     * the number of arguments must match the declaration's generic arity.
     */
    public Optional<TypeView> findType(final String qualifiedName,
                                       final List<TypeRef> appliedArguments) {
        Objects.requireNonNull(qualifiedName);
        final var queryArguments = appliedArguments.stream().map(PublicMemberCatalog::toDescriptor).toList();
        final var spec = lookupType(qualifiedName);
        if (spec == null || !queryArguments.isEmpty()
                && queryArguments.size() != spec.typeParameters().size()) return Optional.empty();
        final var arguments = queryArguments.isEmpty()
                ? spec.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList()
                : queryArguments;
        final var substitutions = substitutions(spec.typeParameters(), arguments);
        final var direct = spec.members().stream()
                .map(member -> member.toPublic(spec.qualifiedName(), MemberOrigin.DIRECT,
                        spec.qualifiedName(), substitutions, null, null))
                .sorted(MEMBER_ORDER).toList();
        final var contractMembers = spec.kind() == TypeKind.CLASS
                ? availableContractMembers(spec, substitutions) : List.<Member>of();
        final var receiver = appliedType(spec, arguments);
        final var extensionMembers = extensions.stream()
                .map(extension -> extension.match(receiver))
                .filter(Objects::nonNull)
                .map(match -> match.extension().member(match.substitutions()))
                .sorted(MEMBER_ORDER).toList();
        return Optional.of(new TypeView(spec.qualifiedName(), spec.kind(),
                spec.typeParameters().stream().map(TypeParameter::from).toList(),
                arguments.stream().map(TypeRef::from).toList(),
                descriptorSubstitutions(spec.typeParameters(), arguments),
                direct, contractMembers, extensionMembers, spec.provenance()));
    }

    private List<Member> availableContractMembers(final TypeSpec classType,
                                                  final Map<TypeParameterDescriptor, TypeDescriptor> classSubstitutions) {
        final var result = new ArrayList<Member>();
        final var closure = new LinkedHashMap<String, AppliedContract>();
        for (final var use : classType.contracts()) {
            final var arguments = use.arguments().stream()
                    .map(type -> TypeSubstitution.substitute(type, classSubstitutions)).toList();
            collectContract(new UseSpec(use.name(), arguments), closure, new HashSet<>());
        }
        for (final var applied : closure.values()) {
            for (final var requirement : applied.spec().members()) {
                if (requirement.kind() == MemberKind.METHOD) {
                    final var requiredFunction = (FunctionDescriptor) TypeSubstitution.substitute(
                            requirement.signature(), applied.substitutions());
                    final var implementation = classType.members().stream()
                            .filter(candidate -> candidate.kind() == MemberKind.METHOD
                                    && candidate.name().equals(requirement.name()))
                            .filter(candidate -> compatible(requiredFunction,
                                    (FunctionDescriptor) TypeSubstitution.substitute(
                                            candidate.signature(), classSubstitutions),
                                    requirement.mutating(), candidate.mutating(),
                                    requirement.variadic(), candidate.variadic()))
                            .findFirst().orElse(null);
                    if (implementation != null) {
                        result.add(requirement.toPublic(classType.qualifiedName(),
                                MemberOrigin.CONTRACT_REQUIREMENT, applied.spec().qualifiedName(),
                                applied.substitutions(), null, null));
                    } else if (requirement.origin() == MemberOrigin.CONTRACT_DEFAULT
                            && classType.members().stream().noneMatch(candidate ->
                                    candidate.kind() == MemberKind.METHOD
                                            && candidate.name().equals(requirement.name()))) {
                        final var conflictingDefaults = matchingDefaultCount(
                                requirement.name(), requiredFunction, closure);
                        if (conflictingDefaults == 1) {
                            result.add(requirement.toPublic(classType.qualifiedName(),
                                    MemberOrigin.CONTRACT_DEFAULT, applied.spec().qualifiedName(),
                                    applied.substitutions(), null, null));
                        }
                    }
                } else if (requirement.kind() == MemberKind.PROPERTY) {
                    final var property = classType.members().stream()
                            .filter(candidate -> candidate.kind() == MemberKind.PROPERTY
                                    && candidate.name().equals(requirement.name())
                                    && TypeSubstitution.substitute(candidate.valueType(), classSubstitutions)
                                            .equals(TypeSubstitution.substitute(
                                            requirement.valueType(), applied.substitutions()))
                                    && (!requirement.writable() || candidate.writable()))
                            .findFirst().orElse(null);
                    if (property != null) {
                        result.add(requirement.toPublic(classType.qualifiedName(),
                                MemberOrigin.CONTRACT_REQUIREMENT, applied.spec().qualifiedName(),
                                applied.substitutions(), null, null));
                    }
                } else if (requirement.kind() == MemberKind.CONSTRUCTOR) {
                    final var requiredFunction = (FunctionDescriptor) TypeSubstitution.substitute(
                            requirement.signature(), applied.substitutions());
                    final var implemented = classType.members().stream()
                            .filter(candidate -> candidate.kind() == MemberKind.CONSTRUCTOR
                                    && candidate.name().equals(requirement.name())
                                    && candidate.signature() != null)
                            .anyMatch(candidate -> sameConstructorShape(requiredFunction,
                                    (FunctionDescriptor) TypeSubstitution.substitute(
                                            candidate.signature(), classSubstitutions)));
                    if (implemented) {
                        result.add(requirement.toPublic(classType.qualifiedName(),
                                MemberOrigin.CONTRACT_REQUIREMENT, applied.spec().qualifiedName(),
                                applied.substitutions(), null, null));
                    }
                }
            }
        }
        return result.stream().distinct().sorted(MEMBER_ORDER).toList();
    }

    private static long matchingDefaultCount(final String name, final FunctionDescriptor required,
                                             final Map<String, AppliedContract> closure) {
        return closure.values().stream().flatMap(contract -> contract.spec().members().stream()
                        .filter(candidate -> candidate.kind() == MemberKind.METHOD
                                && candidate.origin() == MemberOrigin.CONTRACT_DEFAULT
                                && candidate.name().equals(name))
                        .map(candidate -> (FunctionDescriptor) TypeSubstitution.substitute(
                                candidate.signature(), contract.substitutions())))
                .filter(candidate -> sameCallableShape(required, candidate))
                .count();
    }

    private void collectContract(final UseSpec use, final LinkedHashMap<String, AppliedContract> result,
                                 final Set<String> path) {
        final var contract = lookupType(use.name());
        if (contract == null || contract.kind() != TypeKind.CONTRACT
                || contract.typeParameters().size() != use.arguments().size()) return;
        final var key = contract.qualifiedName() + use.arguments().stream()
                .map(PublicMemberCatalog::canonicalType).toList();
        if (path.contains(key) || result.containsKey(key)) return;
        final var substitutions = substitutions(contract.typeParameters(), use.arguments());
        result.put(key, new AppliedContract(contract, substitutions));
        final var nextPath = new HashSet<>(path);
        nextPath.add(key);
        for (final var parent : contract.contracts()) {
            final var arguments = parent.arguments().stream()
                    .map(type -> TypeSubstitution.substitute(type, substitutions)).toList();
            collectContract(new UseSpec(parent.name(), arguments), result, nextPath);
        }
    }

    private TypeSpec lookupType(final String name) {
        final var exact = types.get(name);
        if (exact != null) return exact;
        final var matches = types.values().stream()
                .filter(type -> simpleName(type.qualifiedName()).equals(simpleName(name))).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean compatible(final FunctionDescriptor required, final FunctionDescriptor implementation,
                               final boolean requiredMutating, final boolean implementationMutating,
                               final boolean requiredVariadic, final boolean implementationVariadic) {
        if (requiredMutating != implementationMutating || requiredVariadic != implementationVariadic
                || !sameCallableParameters(required, implementation)) return false;
        final var requiredEffects = required.raisedEffects().stream().map(PublicMemberCatalog::canonicalType)
                .sorted().toList();
        final var implementationEffects = implementation.raisedEffects().stream()
                .map(PublicMemberCatalog::canonicalType).sorted().toList();
        return requiredEffects.containsAll(implementationEffects)
                && isAssignable(required.returnType(), implementation.returnType());
    }

    private static boolean sameCallableShape(final FunctionDescriptor required,
                                             final FunctionDescriptor implementation) {
        if (required == null || implementation == null
                || !sameCallableParameters(required, implementation)
                || !canonicalType(required.returnType()).equals(canonicalType(implementation.returnType()))) {
            return false;
        }
        final var requiredEffects = required.raisedEffects().stream()
                .map(PublicMemberCatalog::canonicalType).sorted().toList();
        final var implementationEffects = implementation.raisedEffects().stream()
                .map(PublicMemberCatalog::canonicalType).sorted().toList();
        return requiredEffects.containsAll(implementationEffects);
    }

    private static boolean sameCallableParameters(final FunctionDescriptor required,
                                                  final FunctionDescriptor implementation) {
        if (required == null || implementation == null
                || required.typeParameters().size() != implementation.typeParameters().size()
                || required.arity() != implementation.arity()) return false;
        final var methodSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < required.typeParameters().size(); i++) {
            if (required.typeParameters().get(i).admitsMutableArguments()
                    != implementation.typeParameters().get(i).admitsMutableArguments()) return false;
            methodSubstitutions.put(required.typeParameters().get(i), implementation.typeParameters().get(i));
        }
        for (int i = 0; i < required.arity(); i++) {
            if (!canonicalType(TypeSubstitution.substitute(required.parameters().get(i), methodSubstitutions))
                    .equals(canonicalType(implementation.parameters().get(i)))) return false;
        }
        return true;
    }

    private boolean isAssignable(final TypeDescriptor expected, final TypeDescriptor actual) {
        if (expected.equals(actual) || actual instanceof NeverDescriptor) return true;
        if (expected instanceof AnyDescriptor) {
            return !(actual instanceof NullableDescriptor || actual instanceof NullDescriptor);
        }
        if (expected instanceof NullableDescriptor nullableAny
                && nullableAny.baseType() instanceof AnyDescriptor) return true;
        if (expected instanceof NullableDescriptor && actual instanceof NullDescriptor) return true;
        if (expected instanceof ReferenceDescriptor expectedReference) {
            return actual instanceof ReferenceDescriptor actualReference
                    && isAssignable(expectedReference.baseType(), actualReference.baseType());
        }
        if (actual instanceof ReferenceDescriptor actualReference
                && expected.equals(actualReference.baseType())) return true;
        if (actual instanceof TypeParameterDescriptor parameter) {
            return parameter.bounds().stream().anyMatch(bound -> isAssignable(expected, bound));
        }
        if (expected instanceof NullableDescriptor nullable) {
            return isAssignable(nullable.baseType(), actual)
                    || actual instanceof NullableDescriptor actualNullable
                    && isAssignable(nullable.baseType(), actualNullable.baseType());
        }
        final var expectedType = nominalType(expected);
        final var actualType = nominalType(actual);
        if (expectedType == null || actualType == null) return false;
        final var expectedSpec = lookupType(expectedType.name());
        final var actualSpec = lookupType(actualType.name());
        if (expectedSpec == null || actualSpec == null || expectedSpec.kind() != TypeKind.CONTRACT
                || actualSpec.kind() != TypeKind.CLASS) return false;
        final var actualArguments = actualType.arguments().isEmpty()
                ? actualSpec.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList()
                : actualType.arguments();
        if (actualArguments.size() != actualSpec.typeParameters().size()) return false;
        final var substitutions = substitutions(actualSpec.typeParameters(), actualArguments);
        final var closure = new LinkedHashMap<String, AppliedContract>();
        for (final var use : actualSpec.contracts()) {
            collectContract(new UseSpec(use.name(), use.arguments().stream()
                    .map(argument -> TypeSubstitution.substitute(argument, substitutions)).toList()),
                    closure, new HashSet<>());
        }
        return closure.values().stream().anyMatch(applied -> applied.spec().qualifiedName()
                .equals(expectedSpec.qualifiedName())
                && genericArgumentsAssignable(expectedSpec,
                        expectedType.arguments(),
                        applied.spec().typeParameters().stream().map(parameter ->
                                TypeSubstitution.substitute(parameter, applied.substitutions())).toList()));
    }

    private boolean genericArgumentsAssignable(final TypeSpec declaration,
                                               final List<TypeDescriptor> expectedArguments,
                                               final List<TypeDescriptor> actualArguments) {
        if (expectedArguments.size() != actualArguments.size()
                || expectedArguments.size() != declaration.typeParameters().size()) return false;
        for (int index = 0; index < expectedArguments.size(); index++) {
            final var expected = expectedArguments.get(index);
            final var actual = actualArguments.get(index);
            if (expected instanceof TypeProjectionDescriptor expectedProjection) {
                if (actual instanceof TypeProjectionDescriptor actualProjection) {
                    if (expectedProjection.kind() != actualProjection.kind()) return false;
                    final var compatible = expectedProjection.kind() == TypeProjectionDescriptor.Kind.OUT
                            ? isAssignable(expectedProjection.bound(), actualProjection.bound())
                            : isAssignable(actualProjection.bound(), expectedProjection.bound());
                    if (!compatible) return false;
                } else {
                    final var compatible = expectedProjection.kind() == TypeProjectionDescriptor.Kind.OUT
                            ? isAssignable(expectedProjection.bound(), actual)
                            : isAssignable(actual, expectedProjection.bound());
                    if (!compatible) return false;
                }
            } else if (actual instanceof TypeProjectionDescriptor) {
                return false;
            } else if (declaration.typeParameters().get(index).variance()
                    == TypeParameterDescriptor.Variance.COVARIANT) {
                if (!isAssignable(expected, actual)) return false;
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static NominalType nominalType(final TypeDescriptor type) {
        if (type instanceof NominalDescriptor nominal) return new NominalType(nominal.name(), List.of());
        if (type instanceof GenericDescriptor generic) {
            return new NominalType(generic.baseType().name(), generic.typeParameters());
        }
        return null;
    }

    private static boolean sameConstructorShape(final FunctionDescriptor required,
                                                final FunctionDescriptor implementation) {
        if (required.typeParameters().size() != implementation.typeParameters().size()
                || required.arity() != implementation.arity()) return false;
        final var methodSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < required.typeParameters().size(); i++) {
            if (required.typeParameters().get(i).admitsMutableArguments()
                    != implementation.typeParameters().get(i).admitsMutableArguments()) return false;
            methodSubstitutions.put(required.typeParameters().get(i), implementation.typeParameters().get(i));
        }
        for (int i = 0; i < required.arity(); i++) {
            if (!canonicalType(TypeSubstitution.substitute(required.parameters().get(i), methodSubstitutions))
                    .equals(canonicalType(implementation.parameters().get(i)))) return false;
        }
        return true;
    }

    private static TypeDescriptor appliedType(final TypeSpec spec, final List<TypeDescriptor> arguments) {
        return arguments.isEmpty() ? TypeDescriptor.ofName(spec.qualifiedName())
                : TypeDescriptor.genericOf(new NominalDescriptor(spec.qualifiedName()), arguments);
    }

    private static Map<TypeParameterDescriptor, TypeDescriptor> substitutions(
            final List<TypeParameterDescriptor> parameters, final List<TypeDescriptor> arguments) {
        final var result = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < parameters.size(); i++) result.put(parameters.get(i), arguments.get(i));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, TypeRef> descriptorSubstitutions(
            final List<TypeParameterDescriptor> parameters, final List<TypeDescriptor> arguments) {
        final var result = new LinkedHashMap<String, TypeRef>();
        for (int i = 0; i < parameters.size(); i++) {
            result.put(parameters.get(i).name(), TypeRef.from(arguments.get(i)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static FunctionSignature publicSignature(final FunctionDescriptor function,
                                                     final List<Parameter> parameters) {
        return new FunctionSignature(function.typeParameters().stream()
                .map(TypeParameter::from).toList(), parameters,
                TypeRef.from(function.returnType()),
                function.raisedEffects().stream().map(TypeRef::from).toList());
    }

    private static TypeDescriptor toDescriptor(final TypeRef type) {
        return switch (type) {
            case NamedType named -> TypeDescriptor.ofName(named.name());
            case GenericType generic -> TypeDescriptor.genericOf(new NominalDescriptor(generic.name()),
                    generic.arguments().stream().map(PublicMemberCatalog::toDescriptor).toList());
            case TypeVariable variable -> new TypeParameterDescriptor(-1, variable.name(),
                    variable.bounds().stream().map(PublicMemberCatalog::toDescriptor).toList(), false,
                    TypeParameterDescriptor.Variance.INVARIANT,
                    variable.admitsNullableArguments(), variable.effectKind(),
                    variable.admitsMutableArguments());
            case ProjectedType projection -> new TypeProjectionDescriptor(
                    projection.kind(), toDescriptor(projection.bound()));
            case ArrayType array -> TypeDescriptor.arrayOf(toDescriptor(array.elementType()));
            case NullableType nullable -> toDescriptor(nullable.baseType()).toNullable();
            case ReferenceType reference -> new ReferenceDescriptor(toDescriptor(reference.baseType()));
            case FunctionType function -> TypeDescriptor.functionWithEffectsOf(function.name(),
                    toDescriptor(function.returnType()),
                    function.parameters().stream().map(PublicMemberCatalog::toDescriptor).toList(),
                    function.typeParameters().stream().map(parameter -> new TypeParameterDescriptor(-1,
                            parameter.name(), parameter.bounds().stream()
                                    .map(PublicMemberCatalog::toDescriptor).toList(), false,
                            TypeParameterDescriptor.Variance.INVARIANT,
                            parameter.admitsNullableArguments(), parameter.effectKind(),
                            parameter.admitsMutableArguments())).toList(),
                    function.raisedEffects().stream().map(PublicMemberCatalog::toDescriptor).toList());
            case HandlerType handler -> TypeDescriptor.handlerOf(toDescriptor(handler.returnType()),
                    handler.handledEffects().stream().map(PublicMemberCatalog::toDescriptor).toList(),
                    handler.raisedEffects().stream().map(PublicMemberCatalog::toDescriptor).toList());
        };
    }

    private static TypeDescriptor copyType(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> new TypeParameterDescriptor(parameter.scopeId(),
                    parameter.name(), parameter.bounds().stream().map(PublicMemberCatalog::copyType).toList(),
                    parameter.isHigherKinded(), parameter.variance(),
                    parameter.admitsNullableArguments(), parameter.isEffectKind(),
                    parameter.admitsMutableArguments());
            case AppliedConstructorDescriptor applied -> new AppliedConstructorDescriptor(
                    (TypeParameterDescriptor) copyType(applied.constructor()), copyType(applied.argument()));
            case TypeConstructorDescriptor constructor -> new TypeConstructorDescriptor(
                    new NominalDescriptor(constructor.baseType().name()),
                    constructor.arguments().stream().map(PublicMemberCatalog::copyType).toList());
            case NominalDescriptor nominal -> new NominalDescriptor(nominal.name());
            case GenericDescriptor generic -> TypeDescriptor.genericOf(
                    new NominalDescriptor(generic.baseType().name()),
                    generic.typeParameters().stream().map(PublicMemberCatalog::copyType).toList());
            case TypeProjectionDescriptor projection -> new TypeProjectionDescriptor(
                    projection.kind(), copyType(projection.bound()));
            case ArrayDescriptor array -> TypeDescriptor.arrayOf(copyType(array.elementType()));
            case NullableDescriptor nullable -> copyType(nullable.baseType()).toNullable();
            case ReferenceDescriptor reference -> new ReferenceDescriptor(copyType(reference.baseType()));
            case FunctionDescriptor function when function.isHandler() -> TypeDescriptor.handlerOf(
                    copyType(function.returnType()),
                    function.handledEffects().stream().map(PublicMemberCatalog::copyType).toList(),
                    function.raisedEffects().stream().map(PublicMemberCatalog::copyType).toList());
            case FunctionDescriptor function -> TypeDescriptor.functionWithEffectsOf(function.name(),
                    copyType(function.returnType()),
                    function.parameters().stream().map(PublicMemberCatalog::copyType).toList(),
                    function.typeParameters().stream().map(parameter -> (TypeParameterDescriptor)
                            copyType(parameter)).toList(),
                    function.raisedEffects().stream().map(PublicMemberCatalog::copyType).toList());
            default -> type;
        };
    }

    private static Optional<Provenance> provenance(final Token token) {
        final var span = token.span();
        final var path = span.sourcePath();
        if (path == null) return Optional.empty();
        final var start = span.start();
        final var end = span.end();
        final var range = start.column() == null || start.offset() == null
                || end.column() == null || end.offset() == null
                ? Optional.<SourceRange>empty()
                : Optional.of(new SourceRange(start.offset(), end.offset(),
                        start.line() - 1, start.column() - 1, end.line() - 1, end.column() - 1));
        return Optional.of(new Provenance(path, range));
    }

    private static String canonicalFunction(final FunctionDescriptor function, final int minimumArity,
                                            final boolean variadic, final boolean mutating) {
        return function == null ? "" : canonicalSignature(function, minimumArity, variadic, mutating);
    }

    private static String canonicalSignature(final FunctionDescriptor function, final int minimumArity,
                                             final boolean variadic, final boolean mutating) {
        final var parameters = function.typeParameters().stream()
                .map(parameter -> parameter.name() + ":"
                        + (parameter.admitsMutableArguments() ? "mut+" : "")
                        + parameter.bounds().stream()
                                .map(PublicMemberCatalog::canonicalType)
                                .collect(java.util.stream.Collectors.joining("&")))
                .collect(java.util.stream.Collectors.joining(",", "<", ">"));
        final var args = function.parameters().stream().map(PublicMemberCatalog::canonicalType)
                .collect(java.util.stream.Collectors.joining(","));
        final var effects = function.raisedEffects().stream().map(PublicMemberCatalog::canonicalType)
                .sorted().collect(java.util.stream.Collectors.joining(","));
        return parameters + "(" + args + ")->" + canonicalType(function.returnType())
                + "!" + effects + ";min=" + minimumArity + ";var=" + variadic + ";mut=" + mutating;
    }

    private static String canonicalType(final TypeDescriptor type) {
        return switch (type) {
            case TypeParameterDescriptor parameter -> "T:" + parameter.name();
            case NullableDescriptor nullable -> canonicalType(nullable.baseType()) + "?";
            case ReferenceDescriptor reference -> "&" + canonicalType(reference.baseType());
            case ArrayDescriptor array -> "Array<" + canonicalType(array.elementType()) + ">";
            case GenericDescriptor generic -> generic.baseType().name() + "<"
                    + generic.typeParameters().stream().map(PublicMemberCatalog::canonicalType)
                            .collect(java.util.stream.Collectors.joining(",")) + ">";
            case TypeProjectionDescriptor projection ->
                    (projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "out " : "in ")
                            + canonicalType(projection.bound());
            case FunctionDescriptor function -> canonicalSignature(function, function.arity(), false, false);
            case NominalDescriptor nominal -> nominal.name();
            default -> type.name();
        };
    }

    private static String qualifySourceName(final String packageName, final String name) {
        return packageName == null || packageName.isEmpty() || name.contains(".")
                ? name : packageName + "." + name;
    }

    private static String namespaceOf(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private static boolean sameSourceName(final String left, final String right) {
        return left.equals(right) || simpleName(left).equals(simpleName(right));
    }

    private static final Comparator<Member> MEMBER_ORDER =
            Comparator.comparing(Member::name).thenComparing(Member::kind)
                    .thenComparing(Member::declarationId);

    private record ParameterSpec(String name, TypeDescriptor type, boolean hasDefault, boolean variadic) {}
    private record UseSpec(String name, List<TypeDescriptor> arguments) {
        private UseSpec {
            arguments = arguments.stream().map(PublicMemberCatalog::copyType).toList();
        }
    }
    private record AppliedContract(TypeSpec spec, Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {}
    private record NominalType(String name, List<TypeDescriptor> arguments) {}

    private record TypeSpec(String qualifiedName, TypeKind kind, List<TypeParameterDescriptor> typeParameters,
                            List<UseSpec> contracts, List<MemberSpec> members,
                            Optional<Provenance> provenance) {
        private TypeSpec {
            typeParameters = typeParameters.stream()
                    .map(parameter -> (TypeParameterDescriptor) copyType(parameter)).toList();
            contracts = List.copyOf(contracts);
            members = List.copyOf(members);
            provenance = Objects.requireNonNull(provenance);
        }
    }

    private record MemberSpec(String owner, String name, MemberKind kind, FunctionDescriptor signature,
                              List<ParameterSpec> parameters, TypeDescriptor valueType,
                              boolean mutating, boolean readable, boolean writable, int minimumArity,
                              boolean variadic, MemberOrigin origin, Optional<Provenance> provenance,
                              String identitySuffix) {
        private MemberSpec {
            parameters = parameters.stream().map(parameter -> new ParameterSpec(parameter.name(),
                    copyType(parameter.type()), parameter.hasDefault(), parameter.variadic())).toList();
            signature = signature == null ? null : (FunctionDescriptor) copyType(signature);
            valueType = valueType == null ? null : copyType(valueType);
            provenance = Objects.requireNonNull(provenance);
        }

        private static MemberSpec callable(String owner, String name, MemberKind kind,
                                           FunctionDescriptor signature, List<String> parameterNames,
                                           int minimumArity, boolean variadic, boolean mutating,
                                           Optional<Provenance> provenance) {
            return callable(owner, name, kind, signature, parameterNames, minimumArity,
                    variadic, mutating, provenance, MemberOrigin.DIRECT);
        }

        private static MemberSpec callable(String owner, String name, MemberKind kind,
                                           FunctionDescriptor signature, List<String> parameterNames,
                                           int minimumArity, boolean variadic, boolean mutating,
                                           Optional<Provenance> provenance, MemberOrigin origin) {
            final var params = new ArrayList<ParameterSpec>();
            final var fixedArity = signature.arity() - (variadic ? 1 : 0);
            for (int i = 0; i < signature.arity(); i++) {
                final var parameterName = parameterNames.isEmpty() || i >= parameterNames.size()
                        ? null : parameterNames.get(i);
                params.add(new ParameterSpec(parameterName, signature.parameters().get(i),
                        i >= minimumArity && i < fixedArity, variadic && i == signature.arity() - 1));
            }
            return new MemberSpec(owner, name, kind, signature, params, null, mutating,
                    false, false, minimumArity, variadic, origin, provenance, "");
        }

        private static MemberSpec constructor(String owner, String name, List<ParameterSpec> parameters,
                                             boolean variadic, Optional<Provenance> provenance) {
            return new MemberSpec(owner, name, MemberKind.CONSTRUCTOR, null, parameters, null,
                    false, false, false, parameters.size() - (variadic ? 1 : 0),
                    variadic, MemberOrigin.DIRECT, provenance, "");
        }

        private static MemberSpec property(String owner, String name, TypeDescriptor type,
                                           boolean writable, Optional<Provenance> provenance) {
            return property(owner, name, type, writable, provenance, MemberOrigin.DIRECT);
        }

        private static MemberSpec property(String owner, String name, TypeDescriptor type,
                                           boolean writable, Optional<Provenance> provenance,
                                           MemberOrigin origin) {
            return new MemberSpec(owner, name, MemberKind.PROPERTY, null, List.of(), type,
                    writable, true, writable, 0, false, origin, provenance, "");
        }

        private Member toPublic(String queriedType, MemberOrigin resultOrigin, String originType,
                                Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                String activationImport, String activationNamespace) {
            final var canonical = signature == null
                    ? kind == MemberKind.PROPERTY
                        ? "property:" + canonicalType(valueType) + ";mut=" + writable
                        : "constructor:" + parameters.stream().map(parameter -> canonicalType(parameter.type()))
                                .collect(java.util.stream.Collectors.joining(",", "(", ")"))
                                + ";var=" + variadic
                    : canonicalSignature(signature, minimumArity, variadic, mutating);
            final var fullCanonical = identitySuffix.isEmpty()
                    ? canonical : canonical + ";identity=" + identitySuffix;
            final var id = owner + "#" + kind + ":" + name + ":" + fullCanonical;
            final var declarationParameters = parameters.stream().map(parameter -> new Parameter(
                    Optional.ofNullable(parameter.name()), TypeRef.from(parameter.type()),
                    parameter.hasDefault(), parameter.variadic())).toList();
            final var appliedParameters = parameters.stream().map(parameter -> new Parameter(
                    Optional.ofNullable(parameter.name()),
                    TypeRef.from(TypeSubstitution.substitute(parameter.type(), substitutions)),
                    parameter.hasDefault(), parameter.variadic())).toList();
            final var appliedSignature = signature == null ? null
                    : (FunctionDescriptor) TypeSubstitution.substitute(signature, substitutions);
            final var appliedValueType = valueType == null ? null
                    : TypeSubstitution.substitute(valueType, substitutions);
            final var appliedReturnType = signature == null ? null
                    : TypeSubstitution.substitute(signature.returnType(), substitutions);
            return new Member(id, queriedType, name, kind, resultOrigin, originType, fullCanonical,
                    Optional.ofNullable(signature == null ? null
                            : publicSignature(signature, declarationParameters)),
                    signature == null ? List.of() : signature.typeParameters().stream()
                            .map(TypeParameter::from).toList(),
                    declarationParameters, appliedParameters,
                    Optional.ofNullable(valueType == null ? null : TypeRef.from(valueType)),
                    Optional.ofNullable(signature == null ? null : TypeRef.from(signature.returnType())),
                    signature == null ? List.of() : signature.raisedEffects().stream().map(TypeRef::from).toList(),
                    mutating, readable, writable, minimumArity, variadic,
                    descriptorSubstitutions(new ArrayList<>(substitutions.keySet()),
                            new ArrayList<>(substitutions.values())),
                    Optional.ofNullable(appliedSignature == null ? null
                            : publicSignature(appliedSignature, appliedParameters)),
                    Optional.ofNullable(appliedValueType == null ? null : TypeRef.from(appliedValueType)),
                    Optional.ofNullable(appliedReturnType == null ? null : TypeRef.from(appliedReturnType)),
                    activationImport, activationNamespace, provenance);
        }
    }

    private record ExtensionSpec(String qualifiedName, String namespace, TypeDescriptor receiverType,
                                 List<TypeParameterDescriptor> receiverParameters,
                                 FunctionDescriptor signature, List<String> parameterNames,
                                 int minimumArity, boolean variadic, boolean mutating, boolean property,
                                 Optional<Provenance> provenance) {
        private ExtensionSpec {
            receiverParameters = receiverParameters.stream()
                    .map(parameter -> (TypeParameterDescriptor) copyType(parameter)).toList();
            receiverType = copyType(receiverType);
            signature = (FunctionDescriptor) copyType(signature);
            parameterNames = List.copyOf(parameterNames);
            provenance = Objects.requireNonNull(provenance);
        }

        private Match match(TypeDescriptor receiver) {
            final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            if (!unify(receiverType, receiver, receiverParameters, substitutions)) return null;
            return new Match(this, substitutions);
        }

        private Member member(Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
            if (property) {
                final var propertySpec = new MemberSpec(namespace, simpleName(qualifiedName),
                        MemberKind.PROPERTY, null, List.of(), signature.returnType(), false,
                        true, false, 0, false, MemberOrigin.EXTENSION, provenance,
                        "receiver=" + canonicalType(receiverType));
                final var member = propertySpec.toPublic(namespace, MemberOrigin.EXTENSION,
                        receiverType.name(), substitutions, qualifiedName, namespace);
                return new Member(member.declarationId() + "@import:" + qualifiedName,
                        member.declaringType(), member.name(), member.kind(), member.origin(),
                        member.originType(), member.canonicalSignature(), member.signature(),
                        member.typeParameters(), member.parameters(), member.appliedParameters(), member.valueType(),
                        member.returnType(), member.raisedEffects(), member.mutating(),
                        member.readable(), member.writable(), member.minimumArity(), member.variadic(),
                        member.appliedSubstitutions(), member.appliedSignature(), member.appliedValueType(),
                        member.appliedReturnType(), qualifiedName, namespace, provenance);
            }
            final var exposedParameters = parameterNames.isEmpty()
                    ? List.<String>of() : parameterNames.subList(Math.min(1, parameterNames.size()),
                            parameterNames.size());
            final var exposedTypes = signature.parameters().size() <= 1
                    ? List.<TypeDescriptor>of() : signature.parameters().subList(1, signature.parameters().size());
            final var callable = TypeDescriptor.functionWithEffectsOf(signature.name(),
                    signature.returnType(), exposedTypes, signature.typeParameters(), signature.raisedEffects());
            final var params = new ArrayList<ParameterSpec>();
            final var callFixedArity = exposedTypes.size() - (variadic ? 1 : 0);
            for (int i = 0; i < exposedTypes.size(); i++) {
                params.add(new ParameterSpec(i < exposedParameters.size() ? exposedParameters.get(i) : null,
                        exposedTypes.get(i), i >= minimumArity && i < callFixedArity,
                        variadic && i == exposedTypes.size() - 1));
            }
            final var spec = new MemberSpec(namespace, simpleName(qualifiedName), MemberKind.METHOD,
                    callable, params, null, mutating, false, false, minimumArity, variadic,
                    MemberOrigin.EXTENSION, provenance, "receiver=" + canonicalType(receiverType));
            final var member = spec.toPublic(namespace, MemberOrigin.EXTENSION, receiverType.name(),
                    substitutions, qualifiedName, namespace);
            return new Member(member.declarationId() + "@import:" + qualifiedName,
                    member.declaringType(), member.name(), member.kind(), member.origin(), member.originType(),
                    member.canonicalSignature(),
                    member.signature(), member.typeParameters(), member.parameters(), member.appliedParameters(),
                    member.valueType(),
                    member.returnType(), member.raisedEffects(), member.mutating(), property || member.readable(),
                    member.writable(), member.minimumArity(), member.variadic(),
                    member.appliedSubstitutions(), member.appliedSignature(),
                    member.appliedValueType(), member.appliedReturnType(), qualifiedName, namespace,
                    provenance);
        }
    }

    private record Match(ExtensionSpec extension, Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {}

    private static boolean unify(TypeDescriptor pattern, TypeDescriptor actual,
                                 List<TypeParameterDescriptor> bindable,
                                 Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        if (pattern instanceof TypeParameterDescriptor parameter && bindable.contains(parameter)) {
            final var previous = substitutions.putIfAbsent(parameter, actual);
            return previous == null || previous.equals(actual);
        }
        if (pattern instanceof GenericDescriptor p && actual instanceof GenericDescriptor a) {
            if (!p.baseType().name().equals(a.baseType().name())
                    || p.typeParameters().size() != a.typeParameters().size()) return false;
            for (int i = 0; i < p.typeParameters().size(); i++) {
                if (!unify(p.typeParameters().get(i), a.typeParameters().get(i), bindable, substitutions)) return false;
            }
            return true;
        }
        if (pattern instanceof ArrayDescriptor p && actual instanceof ArrayDescriptor a) {
            return unify(p.elementType(), a.elementType(), bindable, substitutions);
        }
        if (pattern instanceof NullableDescriptor p && actual instanceof NullableDescriptor a) {
            return unify(p.baseType(), a.baseType(), bindable, substitutions);
        }
        if (pattern instanceof ReferenceDescriptor p && actual instanceof ReferenceDescriptor a) {
            return unify(p.baseType(), a.baseType(), bindable, substitutions);
        }
        return pattern.equals(actual) || canonicalType(pattern).equals(canonicalType(actual));
    }
}
