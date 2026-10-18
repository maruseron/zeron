package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.ArrayDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.List;

public sealed interface Stmt {
    String ANONYMOUS_CAPTURE_PREFIX = "$zeron$capture$";

    static String anonymousCaptureFieldName(final String sourceName) {
        return ANONYMOUS_CAPTURE_PREFIX + sourceName;
    }

    static boolean isAnonymousCaptureField(final Field field) {
        return field.name().lexeme().startsWith(ANONYMOUS_CAPTURE_PREFIX);
    }

    static String anonymousCaptureSourceName(final Field field) {
        return field.name().lexeme().substring(ANONYMOUS_CAPTURE_PREFIX.length());
    }

    static String propertyGetterName(final String name) {
        return "$zeron$get$" + name;
    }

    static String propertySetterName(final String name) {
        return "$zeron$set$" + name;
    }

    static String propertyBackingFieldName(final String name) {
        return "$zeron$property$" + name;
    }

    sealed interface Decl {}

    sealed interface FunctionDeclaration extends Stmt, Decl
            permits Function, ExternalFunction, ExtensionMethod {
        Token name();
        List<Token> parameters();
        List<Expr> defaultValues();
        int minimumArity();
        boolean variadic();
        FunctionDescriptor typeDescriptor();
        boolean isPublic();
    }

    record ExtensionMethod(Token name, List<Token> parameters,
                           FunctionDescriptor typeDescriptor, boolean isPublic,
                           boolean isMutating, List<Stmt> body, List<Expr> defaultValues,
                           int minimumArity, boolean variadic, TypeDescriptor receiverType,
                           List<TypeParameterDescriptor> receiverTypeParameters,
                           List<TypeParameterDescriptor> methodTypeParameters, boolean property)
            implements FunctionDeclaration {
        public ExtensionMethod {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            defaultValues = List.copyOf(defaultValues);
            receiverTypeParameters = List.copyOf(receiverTypeParameters);
            methodTypeParameters = List.copyOf(methodTypeParameters);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
            final var fixedArity = Stmt.fixedArity(parameters, variadic);
            if (parameters.isEmpty() || minimumArity < 1 || minimumArity > fixedArity
                    || defaultValues.size() > fixedArity - minimumArity
                    || property && (isMutating || parameters.size() != 1 || minimumArity != 1 || variadic)
                    || !(typeDescriptor.parameters().getFirst() instanceof ReferenceDescriptor reference
                        ? reference.baseType() : typeDescriptor.parameters().getFirst()).equals(receiverType)) {
                throw new IllegalArgumentException("Invalid extension method signature.");
            }
        }

        public ExtensionMethod(Token name, List<Token> parameters,
                               FunctionDescriptor typeDescriptor, boolean isPublic,
                               boolean isMutating, List<Stmt> body, List<Expr> defaultValues,
                               int minimumArity, boolean variadic, TypeDescriptor receiverType,
                               List<TypeParameterDescriptor> receiverTypeParameters,
                               List<TypeParameterDescriptor> methodTypeParameters) {
            this(name, parameters, typeDescriptor, isPublic, isMutating, body, defaultValues,
                    minimumArity, variadic, receiverType, receiverTypeParameters, methodTypeParameters, false);
        }

        public int minimumCallArity() {
            return minimumArity - 1;
        }

        public int fixedCallArity() {
            return Stmt.fixedArity(parameters, variadic) - 1;
        }
    }

    static int minimumArity(final List<Token> parameters, final List<Expr> defaultValues) {
        return parameters.size() - defaultValues.size();
    }

    static int fixedArity(final List<Token> parameters, final boolean variadic) {
        return parameters.size() - (variadic ? 1 : 0);
    }

    static void validateVariadic(final List<Token> parameters,
                                 final FunctionDescriptor typeDescriptor,
                                 final boolean variadic) {
        if (parameters.size() != typeDescriptor.arity()
                || variadic && (parameters.isEmpty()
                    || !(typeDescriptor.parameters().getLast() instanceof ArrayDescriptor))) {
            throw new IllegalArgumentException("Invalid variadic parameter signature.");
        }
    }

    record Block(List<Stmt> statements) implements Stmt {}

    record Using(Token keyword, List<UsingResource> resources, List<Stmt> body) implements Stmt {
        public Using {
            resources = List.copyOf(resources);
            body = List.copyOf(body);
        }
    }

    final class UsingResource {
        private final Var declaration;
        private Expr.MemberCall disposerCall;

        public UsingResource(final Var declaration) {
            this.declaration = declaration;
        }

        public Var declaration() { return declaration; }
        public Expr.MemberCall disposerCall() { return disposerCall; }
        public void setDisposerCall(final Expr.MemberCall call) { disposerCall = call; }
    }

    record Namespace(Token name, List<Stmt> members) implements Stmt {
        public Namespace {
            members = List.copyOf(members);
        }
    }

    final class ContractUse {
        private Token name;
        private final List<TypeDescriptor> typeArguments;

        public ContractUse(final Token name, final List<TypeDescriptor> typeArguments) {
            this.name = name;
            this.typeArguments = List.copyOf(typeArguments);
        }

        public Token name() {
            return name;
        }

        public List<TypeDescriptor> typeArguments() {
            return typeArguments;
        }

        public void resolveName(final String qualifiedName) {
            name = new Token(name.type(), qualifiedName, name.literal(), name.span());
        }
    }

    record SumDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                   List<SumCase> cases, boolean isPublic,
                   List<Token> derivedContracts) implements Stmt, Decl {
        public SumDecl {
            typeParameters = List.copyOf(typeParameters);
            cases = List.copyOf(cases);
            derivedContracts = List.copyOf(derivedContracts);
        }

        public SumDecl(final Token name, final List<TypeParameterDescriptor> typeParameters,
                       final List<SumCase> cases, final boolean isPublic) {
            this(name, typeParameters, cases, isPublic, List.of());
        }

        public String generatedCaseName(final SumCase sumCase) {
            return name.lexeme() + "$$sum$" + sumCase.name().lexeme();
        }
    }

    record SumCase(Token name, List<SumPayload> payloads) {
        public SumCase {
            payloads = List.copyOf(payloads);
        }
    }

    record SumPayload(Token name, TypeDescriptor type) {}

    record ProductDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                       List<ProductComponent> components, boolean isPublic,
                       List<Token> derivedContracts) implements Stmt, Decl {
        public ProductDecl {
            typeParameters = List.copyOf(typeParameters);
            components = List.copyOf(components);
            derivedContracts = List.copyOf(derivedContracts);
        }

        public ProductDecl(final Token name, final List<TypeParameterDescriptor> typeParameters,
                           final List<ProductComponent> components, final boolean isPublic) {
            this(name, typeParameters, components, isPublic, List.of());
        }
    }

    record ProductComponent(Token name, TypeDescriptor type) {}

    record NewtypeDecl(Token name, TypeDescriptor underlyingType, Expr validator,
                       boolean isPublic, List<Token> derivedContracts) implements Stmt, Decl {
        public NewtypeDecl {
            derivedContracts = List.copyOf(derivedContracts);
        }

        public NewtypeDecl(final Token name, final TypeDescriptor underlyingType,
                           final Expr validator, final boolean isPublic) {
            this(name, underlyingType, validator, isPublic, List.of());
        }
    }

    final class Witness implements Stmt, Decl {
        private final Token name;
        private final List<TypeParameterDescriptor> typeParameters;
        private final TypeDescriptor contractType;
        private final TypeDescriptor targetType;
        private final List<WitnessFactoryMapping> mappings;
        private final List<WitnessMethod> methods;
        private final boolean derived;
        private final List<WitnessRequirement> requirements;

        public Witness(final Token name, final List<TypeParameterDescriptor> typeParameters,
                       final TypeDescriptor contractType, final TypeDescriptor targetType,
                       final List<WitnessFactoryMapping> mappings,
                       final List<WitnessMethod> methods, final boolean derived,
                       final List<WitnessRequirement> requirements) {
            this.name = name;
            this.typeParameters = List.copyOf(typeParameters);
            this.contractType = contractType;
            this.targetType = targetType;
            this.mappings = List.copyOf(mappings);
            this.methods = new ArrayList<>(methods);
            this.derived = derived;
            this.requirements = List.copyOf(requirements);
        }

        public Witness(final Token name, final List<TypeParameterDescriptor> typeParameters,
                       final TypeDescriptor contractType, final TypeDescriptor targetType,
                       final List<WitnessFactoryMapping> mappings,
                       final List<WitnessMethod> methods) {
            this(name, typeParameters, contractType, targetType, mappings, methods, false, List.of());
        }

        public Witness(Token name, List<TypeParameterDescriptor> typeParameters,
                       TypeDescriptor contractType, TypeDescriptor targetType,
                       List<WitnessFactoryMapping> mappings) {
            this(name, typeParameters, contractType, targetType, mappings, List.of());
        }

        public Token name() { return name; }
        public List<TypeParameterDescriptor> typeParameters() { return typeParameters; }
        public TypeDescriptor contractType() { return contractType; }
        public TypeDescriptor targetType() { return targetType; }
        public List<WitnessFactoryMapping> mappings() { return mappings; }
        public List<WitnessMethod> methods() { return List.copyOf(methods); }
        public boolean derived() { return derived; }
        public List<WitnessRequirement> requirements() { return requirements; }
        public void addMethod(final WitnessMethod method) { methods.add(method); }
    }

    record WitnessRequirement(TypeDescriptor contractType, TypeDescriptor targetType) {}

    record WitnessFactoryMapping(Token requirementName, TypeDescriptor targetType,
                                 Token constructorName) {
    }

    record WitnessMethod(Token requirementName, Function methodSignature,
                         Function implementation) {
    }

    record ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                     List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                     Constructor constructor, List<NamedConstructor> namedConstructors,
                     List<Method> methods, List<Pattern> patterns,
                     boolean isPublic, boolean isEffect,
                     List<Token> derivedContracts, List<Token> storedStateOrder) implements Stmt, Decl {
        public ClassDecl {
            patterns = List.copyOf(patterns);
            derivedContracts = List.copyOf(derivedContracts);
            storedStateOrder = List.copyOf(storedStateOrder);
        }

        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, List<Pattern> patterns,
                         boolean isPublic, boolean isEffect) {
            this(name, typeParameters, contractUses, fields, properties, constructor,
                    namedConstructors, methods, patterns, isPublic, isEffect, List.of(), List.of());
        }

        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, List<Pattern> patterns,
                         boolean isPublic, boolean isEffect, List<Token> derivedContracts) {
            this(name, typeParameters, contractUses, fields, properties, constructor,
                    namedConstructors, methods, patterns, isPublic, isEffect, derivedContracts, List.of());
        }

        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, boolean isPublic, boolean isEffect) {
            this(name, typeParameters, contractUses, fields, properties, constructor,
                    namedConstructors, methods, List.of(), isPublic, isEffect);
        }

        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, boolean isPublic) {
            this(name, typeParameters, contractUses, fields, properties, constructor,
                    namedConstructors, methods, List.of(), isPublic, false);
        }

        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, boolean isPublic) {
            this(name, typeParameters, contractUses, fields, List.of(), constructor,
                    namedConstructors, methods, List.of(), isPublic, false);
        }

        public List<Token> contractNames() {
            return contractUses.stream().map(ContractUse::name).toList();
        }

        public List<TypeDescriptor> canonicalConstructorTypes() {
            final var types = new java.util.ArrayList<TypeDescriptor>();
            fields.stream().filter(field -> field.initializer() == null)
                    .map(Field::type).forEach(types::add);
            properties.stream().filter(Property::canonicalConstructorParameter)
                    .map(Property::type).forEach(types::add);
            return List.copyOf(types);
        }
    }

    record Field(Token name, TypeDescriptor type, Expr initializer) {
        public Field(final Token name, final TypeDescriptor type) {
            this(name, type, null);
        }
    }

    record Constructor(Token name, boolean isPublic) {}

    record NamedConstructor(Token name, List<Token> parameters,
                            FunctionDescriptor typeDescriptor, boolean isPublic,
                            List<Stmt> body, boolean variadic) {
        public NamedConstructor {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
        }

        public NamedConstructor(Token name, List<Token> parameters,
                                FunctionDescriptor typeDescriptor, boolean isPublic,
                                List<Stmt> body) {
            this(name, parameters, typeDescriptor, isPublic, body, false);
        }
    }

    record Method(Token name, List<Token> parameters,
                  FunctionDescriptor typeDescriptor, boolean isPublic,
                  boolean isMutating, List<Stmt> body, List<Expr> defaultValues,
                  int minimumArity, boolean variadic) {
        public Method {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            defaultValues = List.copyOf(defaultValues);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
            final var fixedArity = Stmt.fixedArity(parameters, variadic);
            if (minimumArity < 0 || minimumArity > fixedArity
                    || defaultValues.size() > fixedArity - minimumArity) {
                throw new IllegalArgumentException("Invalid method minimum arity.");
            }
        }

        public Method(Token name, List<Token> parameters,
                      FunctionDescriptor typeDescriptor, boolean isPublic,
                      boolean isMutating, List<Stmt> body) {
            this(name, parameters, typeDescriptor, isPublic, isMutating, body, List.of(),
                    parameters.size(), false);
        }

        public Method(Token name, List<Token> parameters,
                      FunctionDescriptor typeDescriptor, boolean isPublic,
                      boolean isMutating, List<Stmt> body, List<Expr> defaultValues) {
            this(name, parameters, typeDescriptor, isPublic, isMutating, body, defaultValues,
                    Stmt.minimumArity(parameters, defaultValues), false);
        }

        public Method(Token name, List<Token> parameters, FunctionDescriptor typeDescriptor,
                      boolean isPublic, boolean isMutating, List<Stmt> body,
                      List<Expr> defaultValues, int minimumArity) {
            this(name, parameters, typeDescriptor, isPublic, isMutating, body, defaultValues,
                    minimumArity, false);
        }
    }

    record Pattern(Token name, List<PatternOutput> outputs, Expr condition,
                   List<Stmt> body, boolean isPublic, boolean refutable) {
        public Pattern {
            outputs = List.copyOf(outputs);
            body = List.copyOf(body);
        }

        public Pattern(Token name, List<PatternOutput> outputs, Expr condition,
                       List<Stmt> body, boolean isPublic) {
            this(name, outputs, condition, body, isPublic, condition != null);
        }

        public static String helperName(final String patternName) {
            return "$zeron$pattern$" + patternName;
        }
    }

    record PatternOutput(Token name, TypeDescriptor type) {}

    record ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                        List<ContractUse> requiredContracts,
                        List<NamedContractConstructor> namedConstructors,
                        List<ContractMethod> methods, List<ContractProperty> properties,
                        boolean isPublic, boolean isSealed,
                        List<ContractUse> permittedClasses) implements Stmt, Decl {
        public ContractDecl {
            requiredContracts = List.copyOf(requiredContracts);
            permittedClasses = List.copyOf(permittedClasses);
        }

        public ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                            List<NamedContractConstructor> namedConstructors,
                            List<ContractMethod> methods, List<ContractProperty> properties,
                            boolean isPublic, boolean isSealed, List<ContractUse> permittedClasses) {
            this(name, typeParameters, List.of(), namedConstructors, methods, properties,
                    isPublic, isSealed, permittedClasses);
        }

        public ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters, 
                            List<NamedContractConstructor> namedConstructors,
                            List<ContractMethod> methods, boolean isPublic) {
            this(name, typeParameters, List.of(), namedConstructors, methods, List.of(),
                    isPublic, false, List.of());
        }

        public ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters, 
                            List<NamedContractConstructor> namedConstructors,
                            List<ContractMethod> methods, List<ContractProperty> properties,
                            boolean isPublic) {
            this(name, typeParameters, List.of(), namedConstructors, methods, properties,
                    isPublic, false, List.of());
        }
    }

    record NamedContractConstructor(Token name, List<Token> parameters,
                            FunctionDescriptor typeDescriptor, boolean variadic) {
        public NamedContractConstructor {
            parameters = List.copyOf(parameters);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
        }

        public NamedContractConstructor(Token name, List<Token> parameters,
                                FunctionDescriptor typeDescriptor) {
            this(name, parameters, typeDescriptor, false);
        }
    }

    record ContractMethod(Token name, List<Token> parameters,
                          FunctionDescriptor typeDescriptor, boolean isMutating,
                          boolean isDefault, List<Stmt> body, List<Expr> defaultValues,
                          int minimumArity, boolean variadic) {
        public ContractMethod {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            defaultValues = List.copyOf(defaultValues);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
            final var fixedArity = Stmt.fixedArity(parameters, variadic);
            if (minimumArity < 0 || minimumArity > fixedArity
                    || defaultValues.size() > fixedArity - minimumArity) {
                throw new IllegalArgumentException("Invalid contract-method minimum arity.");
            }
        }

        public ContractMethod(Token name, List<Token> parameters,
                              FunctionDescriptor typeDescriptor, boolean isMutating) {
            this(name, parameters, typeDescriptor, isMutating, false, List.of(), List.of(),
                    parameters.size(), false);
        }

        public ContractMethod(Token name, List<Token> parameters,
                              FunctionDescriptor typeDescriptor, boolean isMutating,
                              boolean isDefault, List<Stmt> body) {
            this(name, parameters, typeDescriptor, isMutating, isDefault, body, List.of(),
                    parameters.size(), false);
        }

        public ContractMethod(Token name, List<Token> parameters,
                              FunctionDescriptor typeDescriptor, boolean isMutating,
                              boolean isDefault, List<Stmt> body, List<Expr> defaultValues) {
            this(name, parameters, typeDescriptor, isMutating, isDefault, body, defaultValues,
                    Stmt.minimumArity(parameters, defaultValues), false);
        }

        public ContractMethod(Token name, List<Token> parameters,
                              FunctionDescriptor typeDescriptor, boolean isMutating,
                              boolean isDefault, List<Stmt> body, List<Expr> defaultValues,
                              int minimumArity) {
            this(name, parameters, typeDescriptor, isMutating, isDefault, body, defaultValues,
                    minimumArity, false);
        }
    }

    record ContractProperty(Token name, TypeDescriptor type, boolean isMutating) {}

    record Property(Token name, TypeDescriptor type, Expr initializer,
                    boolean isPublic, boolean isMutating,
                    List<Stmt> getterBody, Token setterParameter, List<Stmt> setterBody,
                    boolean canonicalConstructorParameter) {
        public Property(Token name, TypeDescriptor type, Expr initializer,
                        boolean isPublic, boolean isMutating,
                        List<Stmt> getterBody, Token setterParameter, List<Stmt> setterBody) {
            this(name, type, initializer, isPublic, isMutating,
                    getterBody, setterParameter, setterBody,
                    initializer == null && getterBody == null && setterBody == null);
        }

        public boolean isCustom() {
            return getterBody != null || setterBody != null;
        }
    }

    record Break(Token keyword) implements Stmt {}
    record Continue(Token keyword) implements Stmt {}

    record Expression(Expr expression) implements Stmt {}

    record For(Token iterationBind, Token in, Expr iterable, Stmt body) implements Stmt {}

    record Function(Token name, List<Token> parameters,
                    FunctionDescriptor typeDescriptor, List<Stmt> body,
                    boolean isPublic, List<Expr> defaultValues,
                    int minimumArity, boolean variadic) implements FunctionDeclaration {
        public Function {
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            defaultValues = List.copyOf(defaultValues);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
            final var fixedArity = Stmt.fixedArity(parameters, variadic);
            if (minimumArity < 0 || minimumArity > fixedArity
                    || defaultValues.size() > fixedArity - minimumArity) {
                throw new IllegalArgumentException("Invalid function minimum arity.");
            }
        }

        public Function(Token name, List<Token> parameters,
                        FunctionDescriptor typeDescriptor, List<Stmt> body, boolean isPublic) {
            this(name, parameters, typeDescriptor, body, isPublic, List.of(), parameters.size(), false);
        }

        public Function(Token name, List<Token> parameters,
                        FunctionDescriptor typeDescriptor, List<Stmt> body, boolean isPublic,
                        List<Expr> defaultValues) {
            this(name, parameters, typeDescriptor, body, isPublic, defaultValues,
                    Stmt.minimumArity(parameters, defaultValues), false);
        }

        public Function(Token name, List<Token> parameters, FunctionDescriptor typeDescriptor,
                        List<Stmt> body, boolean isPublic, List<Expr> defaultValues, int minimumArity) {
            this(name, parameters, typeDescriptor, body, isPublic, defaultValues, minimumArity, false);
        }
    }

    record ExternalFunction(Token name, List<Token> parameters,
                            FunctionDescriptor typeDescriptor, boolean isPublic,
                            List<Expr> defaultValues,
                            int minimumArity, boolean variadic) implements FunctionDeclaration {
        public ExternalFunction {
            parameters = List.copyOf(parameters);
            defaultValues = List.copyOf(defaultValues);
            Stmt.validateVariadic(parameters, typeDescriptor, variadic);
            final var fixedArity = Stmt.fixedArity(parameters, variadic);
            if (minimumArity < 0 || minimumArity > fixedArity
                    || defaultValues.size() > fixedArity - minimumArity) {
                throw new IllegalArgumentException("Invalid external-function minimum arity.");
            }
        }

        public ExternalFunction(Token name, List<Token> parameters,
                                FunctionDescriptor typeDescriptor, boolean isPublic) {
            this(name, parameters, typeDescriptor, isPublic, List.of(), parameters.size(), false);
        }

        public ExternalFunction(Token name, List<Token> parameters,
                                FunctionDescriptor typeDescriptor, boolean isPublic,
                                List<Expr> defaultValues) {
            this(name, parameters, typeDescriptor, isPublic, defaultValues,
                    Stmt.minimumArity(parameters, defaultValues), false);
        }

        public ExternalFunction(Token name, List<Token> parameters, FunctionDescriptor typeDescriptor,
                                boolean isPublic, List<Expr> defaultValues, int minimumArity) {
            this(name, parameters, typeDescriptor, isPublic, defaultValues, minimumArity, false);
        }
    }

    record ExternalClass(Token name, String javaBinaryName, boolean isPublic,
                         List<ExternalConstructor> constructors,
                         List<ExternalMethod> methods,
                         List<ExternalStaticProperty> staticProperties)
            implements Stmt, Decl {
        public ExternalClass {
            constructors = List.copyOf(constructors);
            methods = List.copyOf(methods);
            staticProperties = List.copyOf(staticProperties);
        }
    }

    record ExternalConstructor(Token name, List<Token> parameters, List<TypeDescriptor> parameterTypes,
                               boolean isPublic) {
        public ExternalConstructor {
            parameters = List.copyOf(parameters);
            parameterTypes = List.copyOf(parameterTypes);
            if (parameters.size() != parameterTypes.size()) {
                throw new IllegalArgumentException("External constructor parameter names and types must align.");
            }
        }
    }

    record ExternalMethod(Token name, List<Token> parameters, FunctionDescriptor typeDescriptor,
                          boolean isPublic, boolean isStatic, boolean isMutating, List<Expr> defaultValues,
                          List<ExternalExceptionMapping> exceptionMappings) {
        public ExternalMethod {
            parameters = List.copyOf(parameters);
            defaultValues = List.copyOf(defaultValues);
            exceptionMappings = List.copyOf(exceptionMappings);
        }

        public ExternalMethod(Token name, List<Token> parameters, FunctionDescriptor typeDescriptor,
                              boolean isPublic, boolean isMutating) {
            this(name, parameters, typeDescriptor, isPublic, false, isMutating, List.of(), List.of());
        }
    }

    record ExternalExceptionMapping(TypeDescriptor javaExceptionType,
                                    TypeDescriptor effectType,
                                    Token constructorName) {}

    record ExternalStaticProperty(Token name, TypeDescriptor type, boolean isPublic) {}

    record If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}

    record Return(Expr value, Token location) implements Stmt {}

    record Var(Token name, TypeDescriptor type, Expr initializer,
               BindingMutability mutability, boolean isPublic) implements Stmt, Decl {
        public Var(final Token name, final TypeDescriptor type, final Expr initializer,
                   final BindingMutability mutability) {
            this(name, type, initializer, mutability, false);
        }
    }

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {}
}
