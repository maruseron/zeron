package com.maruseron.zeron.ast;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.diagnostic.DiagnosticLabel;
import com.maruseron.zeron.diagnostic.SourceSpan;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;
import com.maruseron.zeron.scan.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.maruseron.zeron.scan.TokenType.*;

public final class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current = 0;
    private String packageName = "";
    private String sourcePath;
    private final List<ImportDeclaration> imports = new ArrayList<>();
    private final Map<String, String> importedTypes = new LinkedHashMap<>();
    private final List<String> onDemandImports = new ArrayList<>();
    private final Set<String> localTypeNames = new java.util.HashSet<>();
    private static final AtomicInteger TYPE_PARAMETER_SCOPES = new AtomicInteger();
    private Map<String, TypeParameterDescriptor> activeTypeParameters = Map.of();

    private record LoopMarker(LoopMarker enclosing) {}
    private record LevelMarker(LevelMarker enclosing) {}

    private LoopMarker loopMarker   = null;
    private LevelMarker levelMarker = null;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Parser of(final List<Token> tokens) {
        return new Parser(tokens);
    }

    public static Parser of(final ScanResult scanResult) {
        final var parser = new Parser(scanResult.tokens());
        parser.diagnostics.addAll(scanResult.diagnostics());
        return parser;
    }

    public List<Stmt> parse() {
        return parseCompilationUnit(null).declarations();
    }

    public CompilationUnit parseCompilationUnit(final String sourcePath) {
        return parseCompilationUnit(sourcePath, "");
    }

    public CompilationUnit parseCompilationUnit(final String sourcePath,
                                                final String inheritedPackageName) {
        final var result = parseCompilationUnitWithDiagnostics(sourcePath, inheritedPackageName);
        result.diagnostics().forEach(com.maruseron.zeron.Zeron::reportParseDiagnostic);
        return result.compilationUnit();
    }

    public ParseResult parseCompilationUnitWithDiagnostics(final String sourcePath) {
        return parseCompilationUnitWithDiagnostics(sourcePath, "");
    }

    public ParseResult parseCompilationUnitWithDiagnostics(final String sourcePath,
                                                            final String inheritedPackageName) {
        this.sourcePath = sourcePath;
        packageName = inheritedPackageName == null ? "" : inheritedPackageName;
        if (match(PACKAGE)) {
            try {
                if (!packageName.isEmpty()) error(previous(), DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                        "Bundled declarations cannot override their package.");
                packageName = parseQualifiedName("Expect package name.");
                consume(SEMICOLON, "Expect ';' after package declaration.");
            } catch (ParseError _) {
                synchronizeHeader();
            }
        }
        while (match(IMPORT)) {
            try {
                final var target = advance();
                if (target.type() != IDENTIFIER) error(target, "Expect qualified import name.");
                final var qualifiedName = new StringBuilder(target.lexeme());
                var onDemand = false;
                while (match(DOT)) {
                    if (match(STAR)) {
                        onDemand = true;
                        break;
                    }
                    qualifiedName.append('.').append(
                            consume(IDENTIFIER, "Expect name after '.'.").lexeme());
                }
                final var localName = onDemand ? ""
                        : match(AS)
                            ? consume(IDENTIFIER, "Expect import alias.").lexeme()
                            : qualifiedName.substring(qualifiedName.lastIndexOf(".") + 1);
                if (onDemand && match(AS)) error(previous(), DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                        "Star imports cannot have aliases.");
                consume(SEMICOLON, "Expect ';' after import.");
                final var imported = new ImportDeclaration(qualifiedName.toString(), localName, target, onDemand);
                imports.add(imported);
                if (onDemand) onDemandImports.add(qualifiedName.toString());
                else importedTypes.putIfAbsent(localName, qualifiedName.toString());
            } catch (ParseError _) {
                synchronizeHeader();
            }
        }
        collectLocalTypeNames();
        final var statements = new ArrayList<Stmt>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }
        statements.removeIf(statement -> statement == null);

        return new ParseResult(new CompilationUnit(sourcePath, packageName, imports, statements), diagnostics);
    }

    private void collectLocalTypeNames() {
        var braceDepth = 0;
        for (var index = current; index + 1 < tokens.size(); index++) {
            final var token = tokens.get(index);
            if (braceDepth == 0 && (token.type() == CLASS || token.type() == CONTRACT)
                    && tokens.get(index + 1).type() == IDENTIFIER) {
                localTypeNames.add(tokens.get(index + 1).lexeme());
            }
            if (token.type() == LEFT_BRACE) braceDepth++;
            else if (token.type() == RIGHT_BRACE) braceDepth--;
        }
    }

    private Stmt declaration() {
        try {
            if (match(LET)) return letDeclaration();
            if (match(FN))  return fnDeclaration();
            if (match(EXTERNAL)) return externalFunctionDeclaration(false);
            if (levelMarker == null && match(PUBLIC)) {
                if (match(LET)) return letDeclaration(true);
                if (match(CLASS)) return classDeclaration(true);
                if (match(CONTRACT)) return contractDeclaration(true);
                if (match(SEALED)) {
                    consume(CONTRACT, DiagnosticCatalog.INVALID_SEALED_CONTRACT_DECLARATION,
                            "Only contracts can be sealed.");
                    return contractDeclaration(true, true);
                }
                if (match(EXTERNAL)) return externalFunctionDeclaration(true);
                if (match(FN)) return fnDeclaration(true);
                throw error(previous(), DiagnosticCatalog.INVALID_VISIBILITY,
                        "Only values, functions, classes, and contracts may be public.");
            }
            if (levelMarker == null && match(CLASS)) return classDeclaration(false);
            if (levelMarker == null && match(CONTRACT)) return contractDeclaration(false);
            if (levelMarker == null && match(SEALED)) {
                consume(CONTRACT, DiagnosticCatalog.INVALID_SEALED_CONTRACT_DECLARATION,
                        "Only contracts can be sealed.");
                return contractDeclaration(false, true);
            }

            if (levelMarker != null) return statement();
            throw error(peek(), DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                    "Expected declaration at top level.");
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt letDeclaration() {
        return letDeclaration(false);
    }

    private Stmt letDeclaration(final boolean isPublic) {
        final var mutability = match(MUT)
            ? BindingMutability.REASSIGNABLE
            : BindingMutability.IMMUTABLE;
        if (isPublic && mutability.isReassignable()) {
            error(peek(), DiagnosticCatalog.INVALID_VISIBILITY, "Public top-level values must be immutable.");
        }
        final var name = consume(IDENTIFIER, "Expect binding name.");

        TypeDescriptor type = TypeDescriptor.ofInfer();
        if (match(COLON)) {
            type = collectType();
        }

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, type, initializer, mutability, isPublic);
    }

    private Stmt.Function fnDeclaration() {
        return fnDeclaration(false);
    }

    private Stmt.Function fnDeclaration(final boolean isPublic) {
        final var name = consume(IDENTIFIER, "Expect function name.");
        final var typeParameters = typeParameterDeclaration(name, true);
        final var enclosingTypeParameters = activeTypeParameters;
        activeTypeParameters = typeParameters;
        try {
            return parseFunctionDeclaration(name, typeParameters, isPublic);
        } finally {
            activeTypeParameters = enclosingTypeParameters;
        }
    }

    private Stmt.ExternalFunction externalFunctionDeclaration(final boolean isPublic) {
        if (levelMarker != null) {
            throw error(previous(), DiagnosticCatalog.INVALID_EXTERNAL_FUNCTION_DECLARATION,
                    "External JVM functions are only allowed at top level.");
        }
        consume(FN, "Expect 'fn' after 'external'.");
        final var name = consume(IDENTIFIER, "Expect external function name.");
        if (check(LESS)) {
            throw error(peek(), DiagnosticCatalog.INVALID_EXTERNAL_FUNCTION_DECLARATION,
                    "External JVM functions cannot be generic in this implementation.");
        }
        consume(LEFT_PAREN, "Expect '(' after external function name.");
        final var parameterNames = new ArrayList<Token>();
        final var parameterTypes = new ArrayList<TypeDescriptor>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameterNames.size() >= 254) error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                        "Can't have more than 254 parameters.");
                parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                consume(COLON, "Expect ':' after parameter name.");
                parameterTypes.add(collectType());
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.");
        consume(COLON, "External JVM functions require an explicit return type.");
        final var returnType = collectType();
        consume(SEMICOLON, "Expect ';' after external function declaration.");
        final var descriptor = TypeDescriptor.functionOf(name.lexeme(), returnType,
                parameterTypes.toArray(TypeDescriptor[]::new));
        return new Stmt.ExternalFunction(name, List.copyOf(parameterNames), descriptor, isPublic);
    }

    private Stmt.Function parseFunctionDeclaration(
            final Token name,
            final Map<String, TypeParameterDescriptor> typeParameters,
            final boolean isPublic) {
        consume(LEFT_PAREN, "Expect '(' after function name.");
        final var enclosingLevelMarker = levelMarker;
        levelMarker = new LevelMarker(levelMarker);

        try {
            final var parameterNames = new ArrayList<Token>();
            final var parameterTypes = new ArrayList<TypeDescriptor>();
            if (!check(RIGHT_PAREN)) {
                do {
                    if (parameterNames.size() >= 254) {
                        error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                                "Can't have more than 254 parameters.");
                    }
                    parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                    consume(COLON, "Expect ':' after parameter name.");
                    parameterTypes.add(collectType());
                } while (match(COMMA));
            }
            consume(RIGHT_PAREN, "Expect ')' after parameters.");

            TypeDescriptor returnType = TypeDescriptor.ofUnit();
            final var hasExplicitReturnType = match(COLON);
            if (hasExplicitReturnType) {
                returnType = collectType();
            }
            if (!typeParameters.isEmpty() && !hasExplicitReturnType) {
                error(name, DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                        "Generic functions require an explicit return type.");
            }

            final List<Stmt> body;
            if (match(EQUAL)) {
                if (!hasExplicitReturnType) returnType = TypeDescriptor.ofInfer();
                body = List.of(new Stmt.Return(expression()));
                consume(SEMICOLON, "Expect ';' after expression.");
            } else {
                consume(LEFT_BRACE, "Expect '{' before function body.");
                body = block();
            }

            return new Stmt.Function(name, parameterNames,
                    TypeDescriptor.genericFunctionOf(name.lexeme(), returnType, parameterTypes,
                        List.copyOf(typeParameters.values())), body, isPublic);
        } finally {
            levelMarker = enclosingLevelMarker;
        }
    }

    private Map<String, TypeParameterDescriptor> typeParameterDeclaration(final Token declarationName,
                                                                          final boolean allowBounds) {
        return typeParameterDeclaration(declarationName, allowBounds, false);
    }

    private Map<String, TypeParameterDescriptor> typeParameterDeclaration(final Token declarationName,
                                                                          final boolean allowBounds,
                                                                          final boolean rejectShadowing) {
        if (!match(LESS)) return Map.of();
        final var scopeId = TYPE_PARAMETER_SCOPES.incrementAndGet();
        final var parameters = new LinkedHashMap<String, TypeParameterDescriptor>();
        do {
            final var parameter = consume(IDENTIFIER, "Expect type parameter name.");
            if (parameters.containsKey(parameter.lexeme())) {
                error(parameter, DiagnosticCatalog.DUPLICATE_DECLARATION_COMPONENT, "Duplicate type parameter.");
            }
            if (rejectShadowing && activeTypeParameters.containsKey(parameter.lexeme())) {
                error(parameter, DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                        "Method type parameters cannot shadow enclosing type parameters.");
            }
            var descriptor = new TypeParameterDescriptor(scopeId, parameter.lexeme());
            parameters.put(parameter.lexeme(), descriptor);
            if (match(COLON)) {
                if (!allowBounds) {
                    error(parameter, DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                            "Type-parameter bounds are supported only on top-level generic functions.");
                }
                final var enclosingTypeParameters = activeTypeParameters;
                activeTypeParameters = new LinkedHashMap<>(parameters);
                final var bound = collectType();
                activeTypeParameters = enclosingTypeParameters;
                descriptor = new TypeParameterDescriptor(scopeId, parameter.lexeme(), bound);
                parameters.put(parameter.lexeme(), descriptor);
            }
        } while (match(COMMA));
        consume(GREATER, "Expect '>' after type parameters.");
        if (parameters.isEmpty()) error(declarationName, DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                "A generic declaration must declare a type parameter.");
        return parameters;
    }

    private Stmt.ClassDecl classDeclaration(final boolean isTopLevelPublic) {
        final var name = qualifyDeclaredType(consume(IDENTIFIER, "Expect class name."));
        final var typeParameters = typeParameterDeclaration(name, false);
        final var enclosingTypeParameters = activeTypeParameters;
        activeTypeParameters = typeParameters;
        try {
            final var contractUses = new ArrayList<Stmt.ContractUse>();
            if (match(IS)) {
                do {
                        final var contractName = qualifyTypeToken(
                            consume(IDENTIFIER, "Expect contract name after 'is'."));
                    final var hasTypeArguments = match(LESS);
                    final var typeArguments = hasTypeArguments
                            ? collectTypeArguments()
                            : List.<TypeDescriptor>of();
                    if (hasTypeArguments) consume(GREATER, "Expect '>' after contract type arguments.");
                    contractUses.add(new Stmt.ContractUse(contractName, typeArguments));
                } while (match(COMMA));
            }
            consume(LEFT_BRACE, "Expect '{' before class members.");

            final var fields = new ArrayList<Stmt.Field>();
            final var properties = new ArrayList<Stmt.Property>();
            final var namedConstructors = new ArrayList<Stmt.NamedConstructor>();
            final var methods = new ArrayList<Stmt.Method>();
            Stmt.Constructor constructor = null;
            while (!check(RIGHT_BRACE) && !isAtEnd()) {
                if (match(PUBLIC, PRIVATE)) {
                    final var isPublic = previous().type() == PUBLIC;
                    if (match(CONSTRUCTOR)) {
                        final var constructorName = consume(IDENTIFIER, "Expect 'new' after 'constructor'.");
                        if (constructorName.lexeme().equals("new")) {
                            if (constructor != null) error(constructorName,
                                    DiagnosticCatalog.DUPLICATE_DECLARATION_COMPONENT,
                                    "A class can declare only one canonical constructor.");
                            consume(SEMICOLON, "Expect ';' after canonical constructor declaration.");
                            constructor = new Stmt.Constructor(constructorName, isPublic);
                        } else {
                            namedConstructors.add(namedConstructor(constructorName, isPublic, name,
                                    List.copyOf(typeParameters.values())));
                        }
                    } else {
                        final var isMutating = match(MUT);
                        if (match(PROPERTY)) {
                            final var propertyName = consume(IDENTIFIER, "Expect property name.");
                            consume(COLON, "Expect ':' after property name.");
                            properties.add(classProperty(propertyName, isPublic, isMutating));
                        } else {
                            final var memberName = consume(IDENTIFIER, "Expect method name or 'property'.");
                            methods.add(classMethod(isPublic, isMutating, memberName));
                        }
                    }
                } else if (match(CONSTRUCTOR)) {
                    error(previous(), DiagnosticCatalog.INVALID_VISIBILITY,
                            "Constructors must declare 'public' or 'private' visibility.");
                } else {
                    final var fieldName = consume(IDENTIFIER, "Expect field name or explicitly visible method.");
                    consume(COLON, "Expect ':' after field name.");
                    final var fieldType = collectType();
                    final var initializer = match(EQUAL) ? expression() : null;
                    consume(SEMICOLON, "Expect ';' after field declaration.");
                    fields.add(new Stmt.Field(fieldName, fieldType, initializer));
                }
            }
            consume(RIGHT_BRACE, "Expect '}' after class members.");
                if (constructor == null) {
                final var canonicalName = new Token(IDENTIFIER, "new", null, name.span());
                constructor = new Stmt.Constructor(canonicalName, true);
                }
            return new Stmt.ClassDecl(name, List.copyOf(typeParameters.values()), List.copyOf(contractUses),
                    List.copyOf(fields), List.copyOf(properties), constructor,
                    List.copyOf(namedConstructors), List.copyOf(methods),
                    isTopLevelPublic);
        } finally {
            activeTypeParameters = enclosingTypeParameters;
        }
    }

    private Stmt.Method classMethod(final boolean isPublic,
                                    final boolean isMutating,
                                    final Token name) {
        final var methodTypeParameters = typeParameterDeclaration(name, false, true);
        final var enclosingTypeParameters = activeTypeParameters;
        activeTypeParameters = new LinkedHashMap<>(enclosingTypeParameters);
        activeTypeParameters.putAll(methodTypeParameters);
        try {
            final var signature = methodSignature(name, false,
                    List.copyOf(methodTypeParameters.values()));
            return new Stmt.Method(name, signature.parameters(), signature.typeDescriptor(),
                    isPublic, isMutating, signature.body());
        } finally {
            activeTypeParameters = enclosingTypeParameters;
        }
    }

    private Stmt.Property classProperty(final Token name,
                                        final boolean isPublic,
                                        final boolean isMutating) {
        final var type = collectType();
        final var initializer = match(EQUAL) ? expression() : null;
        List<Stmt> getterBody = null;
        List<Stmt> setterBody = null;
        Token setterParameter = null;
        if (match(LEFT_BRACE)) {
            final var previousLevel = levelMarker;
            levelMarker = new LevelMarker(levelMarker);
            try {
                while (!check(RIGHT_BRACE) && !isAtEnd()) {
                    if (match(GET)) {
                        if (getterBody != null) error(previous(), DiagnosticCatalog.DUPLICATE_DECLARATION_COMPONENT,
                                "Duplicate property getter.");
                        getterBody = accessorBody("getter");
                    } else if (match(SET)) {
                        if (setterBody != null) error(previous(), DiagnosticCatalog.DUPLICATE_DECLARATION_COMPONENT,
                                "Duplicate property setter.");
                        consume(LEFT_PAREN, "Expect '(' after 'set'.");
                        setterParameter = consume(IDENTIFIER, "Expect setter value parameter.");
                        consume(RIGHT_PAREN, "Expect ')' after setter parameter.");
                        setterBody = accessorBody("setter");
                    } else {
                        throw error(peek(), "Expect 'get' or 'set' in property accessor block.");
                    }
                }
                consume(RIGHT_BRACE, "Expect '}' after property accessors.");
            } finally {
                levelMarker = previousLevel;
            }
        } else {
            consume(SEMICOLON, "Expect ';' after property declaration.");
        }
        if (getterBody != null && setterBody == null && isMutating) {
            error(name, DiagnosticCatalog.INVALID_PROPERTY_DECLARATION, "A 'mut' property requires a setter.");
        }
        if ((getterBody != null || setterBody != null) && getterBody == null) {
            error(name, DiagnosticCatalog.INVALID_PROPERTY_DECLARATION, "A custom property requires a getter.");
        }
        if (!isMutating && setterBody != null) {
            error(name, DiagnosticCatalog.INVALID_PROPERTY_DECLARATION, "A property setter requires 'mut'.");
        }
        if (getterBody == null && setterBody != null) {
            error(name, DiagnosticCatalog.INVALID_PROPERTY_DECLARATION, "A custom setter requires a getter.");
        }
        if (initializer != null && getterBody != null) {
            error(name, DiagnosticCatalog.INVALID_PROPERTY_DECLARATION, "A custom property cannot have an initializer.");
        }
        return new Stmt.Property(name, type, initializer, isPublic, isMutating,
                getterBody, setterParameter, setterBody);
    }

    private List<Stmt> accessorBody(final String accessorName) {
        if (match(EQUAL)) {
            final var expression = expression();
            consume(SEMICOLON, "Expect ';' after " + accessorName + " expression.");
            return List.of(accessorName.equals("getter")
                    ? new Stmt.Return(expression)
                    : new Stmt.Expression(expression));
        }
        consume(LEFT_BRACE, "Expect '=' or '{' before " + accessorName + " body.");
        return block();
    }

    private Stmt.NamedConstructor namedConstructor(final Token constructorName,
                                                   final boolean isPublic,
                                                   final Token className,
                                                   final List<TypeParameterDescriptor> classTypeParameters) {
        consume(LEFT_PAREN, "Expect '(' after named constructor name.");
        final var parameterNames = new ArrayList<Token>();
        final var parameterTypes = new ArrayList<TypeDescriptor>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameterNames.size() >= 254) error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                        "Can't have more than 254 parameters.");
                parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                consume(COLON, "Expect ':' after parameter name.");
                parameterTypes.add(collectType());
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after named constructor parameters.");

        final var ownerType = classType(className, classTypeParameters);
        final TypeDescriptor returnType = new ReferenceDescriptor(ownerType);
        final var descriptor = TypeDescriptor.functionOf(constructorName.lexeme(), returnType,
                parameterTypes.toArray(TypeDescriptor[]::new));
        final var enclosingLevelMarker = levelMarker;
        levelMarker = new LevelMarker(levelMarker);
        try {
            final List<Stmt> body;
            if (match(EQUAL)) {
                body = List.of(new Stmt.Return(expression()));
                consume(SEMICOLON, "Expect ';' after named constructor expression.");
            } else {
                consume(LEFT_BRACE, "Expect '=' or '{' before named constructor body.");
                body = block();
            }
            return new Stmt.NamedConstructor(constructorName, List.copyOf(parameterNames), descriptor,
                    isPublic, body);
        } finally {
            levelMarker = enclosingLevelMarker;
        }
    }

    private TypeDescriptor classType(final Token className,
                                    final List<TypeParameterDescriptor> classTypeParameters) {
        if (classTypeParameters.isEmpty()) return TypeDescriptor.of(className.lexeme());
        return TypeDescriptor.genericOf(TypeDescriptor.ofName(className.lexeme()),
                classTypeParameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private Stmt.ContractDecl contractDeclaration(final boolean isTopLevelPublic) {
        return contractDeclaration(isTopLevelPublic, false);
    }

    private Stmt.ContractDecl contractDeclaration(final boolean isTopLevelPublic,
                                                  final boolean isSealed) {
        final var name = qualifyDeclaredType(consume(IDENTIFIER, "Expect contract name."));
        final var typeParameters = typeParameterDeclaration(name, false);
        final var enclosingTypeParameters = activeTypeParameters;
        activeTypeParameters = typeParameters;
        try {
            final var permittedClasses = new ArrayList<Stmt.ContractUse>();
            if (match(PERMITS)) {
                do {
                    final var className = qualifyTypeToken(
                            consume(IDENTIFIER, "Expect permitted class name."));
                    final var hasTypeArguments = match(LESS);
                    final var typeArguments = hasTypeArguments
                            ? collectTypeArguments()
                            : List.<TypeDescriptor>of();
                    if (hasTypeArguments) consume(GREATER, "Expect '>' after permitted class type arguments.");
                    permittedClasses.add(new Stmt.ContractUse(className, typeArguments));
                } while (match(COMMA));
            }
            if (isSealed != !permittedClasses.isEmpty()) {
                error(name, DiagnosticCatalog.INVALID_SEALED_CONTRACT_DECLARATION, isSealed
                        ? "A sealed contract must declare at least one permitted class."
                        : "Only sealed contracts may declare permitted classes.");
            }
            consume(LEFT_BRACE, "Expect '{' before contract members.");
            final var methods = new ArrayList<Stmt.ContractMethod>();
            final var properties = new ArrayList<Stmt.ContractProperty>();
            while (!check(RIGHT_BRACE) && !isAtEnd()) {
                final var isDefault = match(DEFAULT);
                final var isMutating = match(MUT);
                if (match(PROPERTY)) {
                    if (isDefault) error(previous(), DiagnosticCatalog.INVALID_PROPERTY_DECLARATION,
                            "Contract properties cannot have default implementations.");
                    final var propertyName = consume(IDENTIFIER, "Expect property name.");
                    consume(COLON, "Expect ':' after property name.");
                    properties.add(new Stmt.ContractProperty(propertyName, collectType(), isMutating));
                    consume(SEMICOLON, "Expect ';' after contract property requirement.");
                    continue;
                }
                final var methodName = consume(IDENTIFIER, "Expect contract method name.");
                final var methodTypeParameters = typeParameterDeclaration(methodName, false, true);
                final var enclosingMethodTypeParameters = activeTypeParameters;
                activeTypeParameters = new LinkedHashMap<>(enclosingMethodTypeParameters);
                activeTypeParameters.putAll(methodTypeParameters);
                try {
                    final var signature = methodSignature(methodName, !isDefault,
                            List.copyOf(methodTypeParameters.values()));
                    methods.add(new Stmt.ContractMethod(methodName, signature.parameters(),
                            signature.typeDescriptor(), isMutating, isDefault,
                            isDefault ? signature.body() : List.of()));
                } finally {
                    activeTypeParameters = enclosingMethodTypeParameters;
                }
            }
            consume(RIGHT_BRACE, "Expect '}' after contract members.");
                return new Stmt.ContractDecl(name, List.copyOf(typeParameters.values()), List.copyOf(methods),
                    List.copyOf(properties), isTopLevelPublic, isSealed, permittedClasses);
        } finally {
            activeTypeParameters = enclosingTypeParameters;
        }
    }

    private record ParsedMethod(List<Token> parameters,
                                com.maruseron.zeron.domain.FunctionDescriptor typeDescriptor,
                                List<Stmt> body) {}

    private ParsedMethod methodSignature(final Token name,
                                         final boolean isContract,
                                         final List<TypeParameterDescriptor> typeParameters) {
        consume(LEFT_PAREN, "Expect '(' after method name.");
        final var parameterNames = new ArrayList<Token>();
        final var parameterTypes = new ArrayList<TypeDescriptor>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameterNames.size() >= 254) error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                        "Can't have more than 254 parameters.");
                parameterNames.add(consume(IDENTIFIER, "Expect parameter name."));
                consume(COLON, "Expect ':' after parameter name.");
                parameterTypes.add(collectType());
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.");

        final var hasReturnType = match(COLON);
        final var returnType = hasReturnType ? collectType() : TypeDescriptor.ofUnit();
        if (!hasReturnType) error(name, DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                "Class and contract methods require an explicit return type.");

        final var descriptor = TypeDescriptor.genericFunctionOf(name.lexeme(), returnType,
                parameterTypes, typeParameters);
        if (isContract) {
            consume(SEMICOLON, "Expect ';' after contract method signature.");
            return new ParsedMethod(List.copyOf(parameterNames), descriptor, List.of());
        }

        levelMarker = new LevelMarker(levelMarker);
        List<Stmt> body;
        if (match(EQUAL)) {
            body = List.of(new Stmt.Return(expression()));
            consume(SEMICOLON, "Expect ';' after method expression.");
            levelMarker = levelMarker.enclosing();
            return new ParsedMethod(List.copyOf(parameterNames),
                    TypeDescriptor.genericFunctionOf(name.lexeme(), returnType,
                            parameterTypes, typeParameters), body);
        }

        consume(LEFT_BRACE, "Expect '{' before method body.");
        body = block();
        levelMarker = levelMarker.enclosing();
        return new ParsedMethod(List.copyOf(parameterNames), descriptor, body);
    }

    private TypeDescriptor collectType() {
        final var isMutable = match(AMPERSAND);
        TypeDescriptor type;

        if (match(LEFT_PAREN)) {
            final var parameters = new ArrayList<TypeDescriptor>();
            if (!check(RIGHT_PAREN)) {
                do {
                    parameters.add(collectType());
                } while (match(COMMA));
            }
            consume(RIGHT_PAREN, "Expect ')' after lambda parameter types.");
            if (match(ARROW)) {
                final var returnType = collectType();
                type = TypeDescriptor.functionOf("", returnType,
                        parameters.toArray(TypeDescriptor[]::new));
            } else if (parameters.size() == 1) {
                type = parameters.getFirst();
            } else {
                error(previous(), DiagnosticCatalog.EXPECTED_SYNTAX,
                        "Expect '->' after function parameter types.");
                type = TypeDescriptor.ofInfer();
            }
        } else {
            final var firstTypeName = consume(IDENTIFIER, "Expect bind name.");
            final var qualifiedTypeName = qualifyTypeToken(firstTypeName);

            var isGeneric = false;
            List<TypeDescriptor> inner = null;
            while (match(LESS)) {
                isGeneric = true;
                inner = collectTypeArguments();
                consume(GREATER, "Expect '>' after type.");
            }

                type = qualifiedTypeName.lexeme().indexOf('.') < 0
                    ? activeTypeParameters.get(qualifiedTypeName.lexeme())
                    : null;
                if (type == null) type = TypeDescriptor.of(qualifiedTypeName.lexeme());
            if (isGeneric) {
                if (type.name().equals("Array")) {
                    if (inner.size() != 1) error(firstTypeName, DiagnosticCatalog.INVALID_ARRAY_TYPE_OR_LITERAL,
                            "Array expects one element type.");
                    type = TypeDescriptor.arrayOf(inner.getFirst());
                } else if (type instanceof NominalDescriptor nominal) {
                    type = TypeDescriptor.genericOf(nominal, inner);
                } else {
                    error(firstTypeName, DiagnosticCatalog.INVALID_GENERIC_DECLARATION,
                            "Only nominal types can have type arguments.");
                }
            } else if (type.name().equals("Array")) {
                error(firstTypeName, DiagnosticCatalog.INVALID_ARRAY_TYPE_OR_LITERAL,
                        "Array requires an element type.");
            }
        }

        if (isMutable) type = new ReferenceDescriptor(type);
        if (match(HUH)) type = type.toNullable();

        return type;
    }

    private List<TypeDescriptor> collectTypeArguments() {
        final var typeArgs = new ArrayList<TypeDescriptor>();
        do {
            typeArgs.add(collectType());
        } while (match(COMMA));
        return typeArgs;
    }

    private String parseQualifiedName(final String message) {
        final var name = new StringBuilder(consume(IDENTIFIER, message).lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENTIFIER, "Expect name after '.'.").lexeme());
        return name.toString();
    }

    private Token qualifyDeclaredType(final Token token) {
        return withLexeme(token, qualifyTypeName(token.lexeme()));
    }

    private Token qualifyTypeToken(final Token token) {
        final var name = new StringBuilder(token.lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENTIFIER, "Expect name after '.'.").lexeme());
        return withLexeme(token, qualifyTypeName(name.toString()));
    }

    private String qualifyTypeName(final String name) {
        if (name.indexOf('.') >= 0
            || Set.of("Never", "Any", "Infer", "Unit", "Int", "Float", "Boolean", "String", "Array")
                .contains(name)
            || activeTypeParameters.containsKey(name)) return name;
        if (importedTypes.containsKey(name)) return importedTypes.get(name);
        if (localTypeNames.contains(name)) return packageName.isEmpty() ? name : packageName + "." + name;
        if (!onDemandImports.isEmpty()) {
            final var explicitPackageType = onDemandImports.getFirst() + "." + name;
            return explicitPackageType;
        }
        return packageName.isEmpty() ? name : packageName + "." + name;
    }

    private Token withLexeme(final Token token, final String lexeme) {
        return new Token(token.type(), lexeme, token.literal(), token.span());
    }

    private Stmt statement() {
        if (match(BREAK)) return new Stmt.Break(break_());
        if (match(CONTINUE)) return new Stmt.Continue(continue_());
        if (match(RETURN)) return new Stmt.Return(return_());
        if (match(FOR)) return forStatement();
        if (match(IF)) return ifStatement();
        if (match(LOOP, WHILE, UNTIL)) return unboundLoopStatement();
        if (match(LEFT_BRACE)) return new Stmt.Block(block());

        return expressionStatement();
    }

    private Token break_() {
        if (loopMarker == null)
            error(previous(), DiagnosticCatalog.CONTROL_STATEMENT_OUTSIDE_CONTEXT,
                    "Can only break inside of a loop.");

        consume(SEMICOLON, "Expect ';' after break.");
        return previous();
    }

    private Token continue_() {
        if (loopMarker == null)
            error(previous(), DiagnosticCatalog.CONTROL_STATEMENT_OUTSIDE_CONTEXT,
                    "Can only continue inside of a loop.");

        consume(SEMICOLON, "Expect ';' after continue.");
        return previous();
    }

    private Expr return_() {
        if (levelMarker == null)
            error(previous(), DiagnosticCatalog.CONTROL_STATEMENT_OUTSIDE_CONTEXT,
                    "Can only return inside of a function.");

        final var expr = check(SEMICOLON) ? null : expression();
        consume(SEMICOLON, "Expect ';' after return.");
        return expr;
    }

    private Stmt forStatement() {
        // wrap into loop level
        this.loopMarker = new LoopMarker(loopMarker);
        consume(LEFT_PAREN, "Expect '(' after 'for'.");

        consume(LET, "Expect iteration bind after '('");
        final var iterationBind = consume(IDENTIFIER, "Expect bind name after 'let'.");
        final var in = consume(IN, "Expect 'in' after iteration bind.");
        final var expression  = expression();
        consume(RIGHT_PAREN, "Expect ')' after iterable expression.");

        final var body = statement();

        // unwrap into enclosing
        this.loopMarker = loopMarker.enclosing();
        return new Stmt.For(iterationBind, in, expression, body);
    }

    private Stmt ifStatement() {
        final var paren = consume(LEFT_PAREN, "Expect '(' after 'if'.");
        final var condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after if condition.");

        final var thenBranch = statement();
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(paren, condition, thenBranch, elseBranch);
    }

    private Stmt unboundLoopStatement() {
        // wrap into loop level
        this.loopMarker = new LoopMarker(loopMarker);
        final var keyword = previous();
        Expr condition = switch (keyword.type()) {
            // LOOP condition is always true
            case LOOP -> new Expr.Literal(true, TypeDescriptor.ofBoolean());
            case WHILE -> {
                consume(LEFT_PAREN, "Expect '(' after while.");
                final var res = expression();
                consume(RIGHT_PAREN, "Expect ')' after condition.");
                yield res;
            }
            case UNTIL -> {
                consume(LEFT_PAREN, "Expect '(' after until.");
                // UNTIL generates a synthetic negation for while.
                // It uses a fake NOT operator with "until" as lexeme
                final var res = new Expr.Unary(
                        new Token(NOT, previous().lexeme(), null, previous().span()),
                        expression(),
                        TypeDescriptor.ofInfer());
                consume(RIGHT_PAREN, "Expect ')' after condition.");
                yield res;
            }
            default -> throw new IllegalStateException("unreachable");
        };
        final var body = statement();
        // unwrap into enclosing
        this.loopMarker = loopMarker.enclosing();
        return new Stmt.While(keyword, condition, body);
    }

    private Stmt expressionStatement() {
        final var expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.Expression(expr);
    }

    private List<Stmt> block() {
        final var statements = new ArrayList<Stmt>();

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }

        consume(RIGHT_BRACE, "Expect '}' after block.");
        return statements;
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        var expr = coalesce();

        if (match(HUH_HUH_EQUAL)) {
            final var operator = previous();
            final var value = assignment();
            if (expr instanceof Expr.Variable variable) {
                return new Expr.CoalesceAssignment(variable.name, value);
            }
            error(operator, DiagnosticCatalog.INVALID_ASSIGNMENT_FORM,
                    "'??=' can only assign to a mutable local variable.");
        }

        if (match(PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
            AMPERSAND_EQUAL, PIPE_EQUAL, CARET_EQUAL, SHIFT_LEFT_EQUAL,
            SHIFT_RIGHT_EQUAL, UNSIGNED_SHIFT_RIGHT_EQUAL, EQUAL)) {
            final var operator = previous();
            final var value = assignment();

            if (expr instanceof Expr.Property property) {
                if (operator.type() != EQUAL) {
                    return new Expr.PropertyCompoundAssignment(
                            property, compoundOperator(operator), value);
                }
                return new Expr.PropertyAssignment(property, value, TypeDescriptor.ofInfer());
            }

            if (expr instanceof Expr.Index index) {
                if (operator.type() != EQUAL) error(operator, DiagnosticCatalog.INVALID_ASSIGNMENT_FORM,
                        "Indexed assignment only supports '='.");
                return new Expr.IndexAssignment(index.array, index.index, value, TypeDescriptor.ofUnit());
            }

            // left assign_op right === left = left op right
            if (expr instanceof Expr.Variable variable) {
                final var name = variable.name;
                return switch (operator.type()) {
                    case EQUAL -> new Expr.Assignment(name, value, null);
                    case PLUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic plus token from plus_equal
                                    new Token(PLUS, "+", null, operator.span()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case MINUS_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic minus token from minus_equal
                                    new Token(MINUS, "-", null, operator.span()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case STAR_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic star token from star_equal
                                    new Token(STAR, "*", null, operator.span()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                    case SLASH_EQUAL -> new Expr.Assignment(
                            name,
                            new Expr.Binary(
                                    expr,
                                    // synthetic slash token from slash_equal
                                    new Token(SLASH, "/", null, operator.span()),
                                    value,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                        case PERCENT_EQUAL -> compoundAssignment(name, expr, PERCENT, "%", value, operator);
                        case AMPERSAND_EQUAL -> compoundAssignment(name, expr, AMPERSAND, "&", value, operator);
                        case PIPE_EQUAL -> compoundAssignment(name, expr, PIPE, "|", value, operator);
                        case CARET_EQUAL -> compoundAssignment(name, expr, CARET, "^", value, operator);
                        case SHIFT_LEFT_EQUAL -> compoundAssignment(name, expr, SHIFT_LEFT, "<<", value, operator);
                        case SHIFT_RIGHT_EQUAL -> compoundAssignment(name, expr, SHIFT_RIGHT, ">>", value, operator);
                        case UNSIGNED_SHIFT_RIGHT_EQUAL ->
                            compoundAssignment(name, expr, UNSIGNED_SHIFT_RIGHT, ">>>", value, operator);
                    default -> throw new IllegalStateException("unreachable");
                };
            }

            error(operator, DiagnosticCatalog.INVALID_ASSIGNMENT_FORM, "Invalid assignment target.");
        }

        return expr;
    }

    private Token compoundOperator(final Token assignmentOperator) {
        final var operator = switch (assignmentOperator.type()) {
            case PLUS_EQUAL -> PLUS;
            case MINUS_EQUAL -> MINUS;
            case STAR_EQUAL -> STAR;
            case SLASH_EQUAL -> SLASH;
            case PERCENT_EQUAL -> PERCENT;
            case AMPERSAND_EQUAL -> AMPERSAND;
            case PIPE_EQUAL -> PIPE;
            case CARET_EQUAL -> CARET;
            case SHIFT_LEFT_EQUAL -> SHIFT_LEFT;
            case SHIFT_RIGHT_EQUAL -> SHIFT_RIGHT;
            case UNSIGNED_SHIFT_RIGHT_EQUAL -> UNSIGNED_SHIFT_RIGHT;
            default -> throw new IllegalArgumentException("Not a compound assignment operator.");
        };
        return new Token(operator, assignmentOperator.lexeme().substring(
                0, assignmentOperator.lexeme().length() - 1), null, assignmentOperator.span());
    }

    private Expr coalesce() {
        final var expr = or();
        if (match(HUH_HUH)) {
            final var operator = previous();
            return new Expr.Coalesce(expr, operator, coalesce());
        }
        return expr;
    }

    private Expr compoundAssignment(final Token name,
                                    final Expr target,
                                    final TokenType operatorType,
                                    final String operatorLexeme,
                                    final Expr value,
                                    final Token assignmentOperator) {
        return new Expr.Assignment(name,
                new Expr.Binary(target,
                        new Token(operatorType, operatorLexeme, null, assignmentOperator.span()),
                        value, TypeDescriptor.ofInfer()),
                TypeDescriptor.ofInfer());
    }

    private Expr or() {
        var expr = and();

        while (match(OR)) {
            final var operator = previous();
            final var right = and();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        var expr = bitwiseOr();

        while (match(AND)) {
            final var operator = previous();
            final var right = bitwiseOr();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr bitwiseOr() {
        var expr = bitwiseXor();
        while (match(PIPE)) {
            final var operator = previous();
            expr = new Expr.Binary(expr, operator, bitwiseXor(), TypeDescriptor.ofInfer());
        }
        return expr;
    }

    private Expr bitwiseXor() {
        var expr = bitwiseAnd();
        while (match(CARET)) {
            final var operator = previous();
            expr = new Expr.Binary(expr, operator, bitwiseAnd(), TypeDescriptor.ofInfer());
        }
        return expr;
    }

    private Expr bitwiseAnd() {
        var expr = equality();
        while (match(AMPERSAND)) {
            final var operator = previous();
            expr = new Expr.Binary(expr, operator, equality(), TypeDescriptor.ofInfer());
        }
        return expr;
    }

    private Expr equality() {
        var expr = typeTest();

        while (match(BANG_EQUAL, EQUAL_EQUAL, EQUAL_EQUAL_EQUAL)) {
            final var operator = previous();
            final var right = typeTest();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofBoolean());
        }

        return expr;
    }

    private Expr typeTest() {
        final var value = comparison();
        if (!match(IS)) return value;
        final var operator = previous();
        final var negation = match(NOT) ? previous() : null;
        final var test = new Expr.TypeTest(value, operator, collectType());
        return negation == null
                ? test
                : new Expr.Unary(negation, test, TypeDescriptor.ofBoolean());
    }

    private Expr comparison() {
        var expr = shift();

        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            final var operator = previous();
            final var right = shift();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofBoolean());
        }

        return expr;
    }

    private Expr shift() {
        var expr = term();
        while (check(SHIFT_LEFT) || check(GREATER) && checkNext(GREATER)) {
            final Token operator;
            if (match(SHIFT_LEFT)) {
                operator = previous();
            } else {
                final var first = advance();
                advance();
                if (match(GREATER)) {
                    operator = new Token(UNSIGNED_SHIFT_RIGHT, ">>>", null, first.span());
                } else {
                    operator = new Token(SHIFT_RIGHT, ">>", null, first.span());
                }
            }
            expr = new Expr.Binary(expr, operator, term(), TypeDescriptor.ofInfer());
        }
        return expr;
    }

    private Expr term() {
        var expr = factor();

        while (match(MINUS, PLUS)) {
            final var operator = previous();
            final var right = factor();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofInfer());
        }

        return expr;
    }

    private Expr factor() {
        var expr = unary();

        while (match(SLASH, STAR, PERCENT)) {
            final var operator = previous();
            final var right = unary();
            expr = new Expr.Binary(expr, operator, right, TypeDescriptor.ofInfer());
        }

        return expr;
    }

    private Expr unary() {
        if (match(NOT, MINUS, PLUS, TYPEOF, TILDE)) {
            final var operator = previous();
            final var right = unary();
            return new Expr.Unary(operator, right, TypeDescriptor.ofInfer());
        }

        var expr = call();
        while (match(AS)) {
            final var operator = previous();
            final var safe = match(HUH);
            expr = new Expr.Cast(expr, operator, collectType(), safe);
        }
        return expr;
    }

    private Expr call() {
        var expr = primary();

        while (true) {
            if (expr instanceof Expr.Variable variable && match(COLON_COLON)) {
                consume(LESS, "Expect '<' after '::' in a function specialization.");
                final var typeArguments = collectTypeArguments();
                consume(GREATER, "Expect '>' after function type arguments.");
                expr = new Expr.Variable(variable.name, TypeDescriptor.ofInfer(), typeArguments);
            } else if (expr instanceof Expr.Variable variable && check(LESS)
                    && looksLikeGenericFactoryCall()) {
                advance();
                final var typeArguments = collectTypeArguments();
                consume(GREATER, "Expect '>' after class type arguments.");
                consume(DOT, "Expect named constructor after class type arguments.");
                final var constructor = consume(IDENTIFIER, "Expect named constructor after '.'.");
                consume(LEFT_PAREN, "Expect '(' after constructor name.");
                expr = finishMemberCall(new Expr.Property(variable, constructor, TypeDescriptor.ofInfer()),
                        typeArguments);
            } else if (expr instanceof Expr.Variable variable && check(LESS)
                    && looksLikeTypeArgumentsCall()) {
                advance();
                final var typeArguments = collectTypeArguments();
                consume(GREATER, "Expect '>' after type arguments.");
                consume(LEFT_PAREN, "Expect '(' after type arguments.");
                expr = finishCall(variable.name, typeArguments);
            } else if (expr instanceof Expr.Property property && check(LESS)
                    && looksLikeTypeArgumentsCall()) {
                advance();
                final var typeArguments = collectTypeArguments();
                consume(GREATER, "Expect '>' after method type arguments.");
                consume(LEFT_PAREN, "Expect '(' after method type arguments.");
                expr = finishMemberCall(property, typeArguments);
            } else if (match(LEFT_PAREN)) {
                if (expr instanceof Expr.Variable variable) {
                    expr = finishCall(variable.name, variable.explicitFunctionTypeArguments);
                } else if (expr instanceof Expr.Property property) {
                    expr = finishMemberCall(property);
                } else {
                    error(previous(), DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                            "Only functions and named methods can be called.");
                }
            } else if (match(LEFT_BRACKET)) {
                final var index = expression();
                consume(RIGHT_BRACKET, "Expect ']' after array index.");
                expr = new Expr.Index(expr, index, TypeDescriptor.ofInfer());
            } else if (match(DOT, HUH_DOT)) {
                final var safeNavigation = previous().type() == HUH_DOT;
                final var property = consume(IDENTIFIER, "Expect member name after '.'.");
                expr = new Expr.Property(expr, property, TypeDescriptor.ofInfer(), safeNavigation);
            } else {
                break;
            }
        }

        return expr;
    }

    private Expr finishCall(final Token callee, final List<TypeDescriptor> typeArguments) {
        final var arguments = new ArrayList<Expr>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 254) {
                    error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                            "Can't have more than 254 arguments.");
                }
                arguments.add(expression());
                Zeron.debug("added argument to call: " + arguments.getLast());
            } while (match(COMMA));
        }

        final var paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");

        return new Expr.Call(callee, paren, arguments, typeArguments, TypeDescriptor.ofInfer());
    }

    private boolean looksLikeTypeArgumentsCall() {
        var depth = 0;
        for (var index = current; index < tokens.size(); index++) {
            final var type = tokens.get(index).type();
            if (type == LESS) depth++;
            else if (type == GREATER && --depth == 0) {
                return index + 1 < tokens.size() && tokens.get(index + 1).type() == LEFT_PAREN;
            }
        }
        return false;
    }

    private Expr finishMemberCall(final Expr.Property property) {
        return finishMemberCall(property, List.of());
    }

    private Expr finishMemberCall(final Expr.Property property, final List<TypeDescriptor> explicitTypeArguments) {
        final var arguments = new ArrayList<Expr>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 254) error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                        "Can't have more than 254 arguments.");
                arguments.add(expression());
            } while (match(COMMA));
        }
        final var paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");
        return new Expr.MemberCall(property.receiver, property.name, paren, arguments,
                explicitTypeArguments, TypeDescriptor.ofInfer(), property.safeNavigation());
    }

    private boolean looksLikeGenericFactoryCall() {
        var depth = 0;
        for (var index = current; index < tokens.size(); index++) {
            final var type = tokens.get(index).type();
            if (type == LESS) depth++;
            else if (type == GREATER && --depth == 0) {
                return index + 3 < tokens.size()
                        && tokens.get(index + 1).type() == DOT
                        && tokens.get(index + 2).type() == IDENTIFIER
                        && tokens.get(index + 3).type() == LEFT_PAREN;
            }
        }
        return false;
    }

    private Expr primary() {
        if (match(MATCH)) return matchExpression(previous());

        if (match(LEFT_BRACKET)) {
            final var elements = new ArrayList<Expr>();
            if (check(RIGHT_BRACKET)) error(peek(), DiagnosticCatalog.INVALID_ARRAY_TYPE_OR_LITERAL,
                    "Array literals must initialize at least one element.");
            do {
                elements.add(expression());
            } while (match(COMMA));
            consume(RIGHT_BRACKET, "Expect ']' after array elements.");
            return new Expr.ArrayLiteral(elements, TypeDescriptor.ofInfer());
        }

        if (match(FALSE)) return new Expr.Literal(false, TypeDescriptor.ofBoolean());
        if (match(TRUE))  return new Expr.Literal(true,  TypeDescriptor.ofBoolean());
        if (match(NULL))  return new Expr.Literal(null, TypeDescriptor.ofNull());
        if (match(THIS))  return new Expr.Variable(previous(), TypeDescriptor.ofInfer());

        if (match(INT)) {
            final var number = previous();
            if (match(DOT_DOT)) {
            final var operator = previous();
            final var end = consume(INT, "Expect Integer after range operator");
            final var className = new Token(IDENTIFIER, "zeron.ranges.IntRange", null, number.span());
            final var factoryName = new Token(IDENTIFIER, "closed", null, operator.span());
            final var receiver = new Expr.Variable(className, TypeDescriptor.ofInfer());
            final var arguments = List.<Expr>of(
                new Expr.Literal(number.literal(), TypeDescriptor.ofInt()),
                new Expr.Literal(end.literal(), TypeDescriptor.ofInt()));
            return new Expr.MemberCall(receiver, factoryName, end, arguments,
                List.of(), TypeDescriptor.ofInfer());
            }
            return new Expr.Literal(number.literal(), TypeDescriptor.ofInt());
        }

        if (match(DOUBLE, STRING)) {
            return new Expr.Literal(
                    previous().literal(),
                    previous().type() == DOUBLE
                            ? TypeDescriptor.ofFloat()
                            : TypeDescriptor.ofString());
        }

        if (match(IF)) {
            final var paren = consume(LEFT_PAREN, "Expect '(' after 'if'.");
            final var condition = expression();
            consume(RIGHT_PAREN, "Expect ')' after condition.");
            consume(THEN, "Expect 'then' after ')'.");
            final var thenExpr = expression();
            consume(ELSE, "'Expect 'else' after expression.");
            final var elseExpr = expression();
            return new Expr.If(paren, condition, thenExpr, elseExpr, TypeDescriptor.ofInfer());
        }

        if (match(IDENTIFIER)) {
            // `a -> ...` lambda
            final var ident = previous();
            if (check(ARROW)) {
                return finishLambda(List.of(ident));
            }
            return new Expr.Variable(ident, TypeDescriptor.ofInfer());
        }

        // Parentheses may start a lambda, a Unit literal, or a grouped expression.
        if (match(LEFT_PAREN)) {
            final var paren = previous();
            if (check(RIGHT_PAREN)) {
                advance();
                return check(ARROW)
                        ? finishLambda(List.of())
                        : new Expr.Literal(new UnitLiteral(), TypeDescriptor.ofUnit());
            }

            if (looksLikeParenthesizedLambda()) {
                final var params = new ArrayList<Token>();
                do {
                    if (params.size() >= 254) {
                        error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                                "Can't have more than 254 parameters.");
                    }
                    params.add(consume(IDENTIFIER, "Expect parameter name."));
                } while (match(COMMA));
                consume(RIGHT_PAREN, "Expect ')' after lambda parameters.");
                return finishLambda(params);
            }

            final var grouped = expression();
            consume(RIGHT_PAREN, "Expect ')' after expression.");
            return new Expr.Grouping(paren, grouped, TypeDescriptor.ofInfer());
        }

        /*
        // ( -> must disambiguate grouping vs lambda. how?
        if (match(LEFT_PAREN)) {
            final var paren = previous();
            // if identifier, can be a lambda
            // (a -> must check for (a,
            if (match(IDENTIFIER)) {
                final var ident = previous();
                // (a, -> definitely a lambda
                if (match(COMMA)) {
                    final var list = new ArrayList<Token>();
                    list.add(ident);
                    do {
                        if (list.size() >= 254) {
                            error(peek(), DiagnosticCatalog.TOO_MANY_PARAMETERS_OR_ARGUMENTS,
                                    "Can't have more than 254 arguments.");
                        }
                        list.add(consume(IDENTIFIER, "Expect parameter name."));
                    } while (match(COMMA));
                    consume(RIGHT_PAREN, "Expect ')' after lambda parameters");
                    return finishLambda(list);
                }
                // (a), must look for arrow
                else if (match(RIGHT_PAREN)) {
                    if (check(ARROW)) {
                        return finishLambda(List.of(ident));
                    }
                }
                // none of the others
                else {
                    consume(RIGHT_PAREN, "Expect ')' after expression.");
                    return new Expr.Grouping(
                            paren,
                            new Expr.Variable(
                                    ident,
                                    TypeDescriptor.ofInfer()),
                            TypeDescriptor.ofInfer());
                }
            // empty paren: definitely a no param lambda
            } else if (match(RIGHT_PAREN)) {
                return finishLambda(List.of());
            }
            consume(RIGHT_PAREN, "Expect ')' after expression.");
            return new Expr.Grouping(paren, expression(), TypeDescriptor.ofInfer());
        }
         */

        throw error(peek(), "Expect expression.");
    }

    private Expr matchExpression(final Token keyword) {
        consume(LEFT_PAREN, "Expect '(' after 'match'.");
        final var scrutinee = expression();
        consume(RIGHT_PAREN, "Expect ')' after match value.");
        consume(LEFT_BRACE, "Expect '{' before match cases.");
        final var arms = new ArrayList<Expr.MatchArm>();
        while (match(CASE)) {
            final var caseKeyword = previous();
            final boolean wildcard = check(IDENTIFIER) && peek().lexeme().equals("_");
            final TypeDescriptor patternType;
            final Token alias;
            if (wildcard) {
                advance();
                patternType = null;
                alias = null;
            } else {
                patternType = collectType();
                alias = match(AS) ? consume(IDENTIFIER, "Expect binding name after 'as'.") : null;
            }
            consume(ARROW, "Expect '->' after match pattern.");
            final var body = expression();
            consume(SEMICOLON, "Expect ';' after match arm.");
            arms.add(new Expr.MatchArm(caseKeyword, patternType, alias, wildcard, body));
        }
        consume(RIGHT_BRACE, "Expect '}' after match cases.");
        if (arms.isEmpty()) error(keyword, DiagnosticCatalog.INVALID_MATCH_EXPRESSION,
                "A match expression must contain at least one case.");
        return new Expr.Match(keyword, scrutinee, arms);
    }

    private boolean looksLikeParenthesizedLambda() {
        var index = current;
        while (index < tokens.size()) {
            if (tokens.get(index).type() != IDENTIFIER) return false;
            index++;
            if (index >= tokens.size()) return false;
            if (tokens.get(index).type() == RIGHT_PAREN) {
                return index + 1 < tokens.size() && tokens.get(index + 1).type() == ARROW;
            }
            if (tokens.get(index).type() != COMMA) return false;
            index++;
        }
        return false;
    }

    private Expr.Lambda finishLambda(final List<Token> params) {
        final var arrow = consume(ARROW, "Expect '->' after parameters.");
        levelMarker = new LevelMarker(levelMarker);
        List<Stmt> body;
        if (match(LEFT_BRACE)) {
            body = block();
        } else {
            body = List.of(new Stmt.Return(expression()));
        }
        levelMarker = levelMarker.enclosing();
        final var inferredParameters = params.stream()
                .map(_ -> TypeDescriptor.ofInfer())
                .toArray(TypeDescriptor[]::new);
        return new Expr.Lambda(arrow, params, body,
                TypeDescriptor.functionOf("", TypeDescriptor.ofInfer(), inferredParameters));
    }

    private boolean match(final TokenType... types) {
        for (final var type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }

        return false;
    }

    private Token consume(final TokenType type, final String message) {
        return consume(type, DiagnosticCatalog.EXPECTED_SYNTAX, message);
    }

    private Token consume(final TokenType type,
                          final DiagnosticCatalog.Entry entry,
                          final String message) {
        if (check(type)) return advance();

        throw error(peek(), entry, message);
    }

    private boolean check(final TokenType type) {
        if (isAtEnd()) return false;
        return peek().type() == type;
    }

    private boolean checkNext(final TokenType type) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type() == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseError error(final Token token, final String message) {
        return error(token, DiagnosticCatalog.EXPECTED_SYNTAX, message);
    }

    private ParseError error(final Token token,
                             final DiagnosticCatalog.Entry entry,
                             final String message) {
        final var tokenSpan = token.span();
        final var span = tokenSpan.sourcePath() != null || sourcePath == null
                ? tokenSpan
                : new SourceSpan(sourcePath, tokenSpan.start(), tokenSpan.end());
        final var location = token.type() == EOF ? "at end" : "at '" + token.lexeme() + "'";
        diagnostics.add(new Diagnostic(entry.code(), entry.severity(), message, span,
                List.of(new DiagnosticLabel(span, location)),
                List.of(), List.of()));
        return new ParseError();
    }

    private void synchronizeHeader() {
        while (!isAtEnd()) {
            if (previous().type() == SEMICOLON) return;
            if (check(IMPORT) || check(LET) || check(FN) || check(EXTERNAL)
                    || check(CLASS) || check(CONTRACT) || check(PUBLIC) || check(SEALED)) return;
            advance();
        }
    }

    private void synchronize() {
        advance();

        while (!isAtEnd()) {
            if (previous().type() == SEMICOLON) return;

            switch (peek().type()) {
                case BREAK, CLASS, CONTRACT, LET, FOR, IF,
                     WHILE, UNTIL, LOOP, RETURN -> { return; }
                default -> {}
            }

            advance();
        }
    }
}
