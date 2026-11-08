package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.ArrayDescriptor;
import com.maruseron.zeron.domain.AnnotationConstant;
import com.maruseron.zeron.domain.BooleanDescriptor;
import com.maruseron.zeron.domain.ByteDescriptor;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.domain.IntDescriptor;
import com.maruseron.zeron.domain.LongDescriptor;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.NullDescriptor;
import com.maruseron.zeron.domain.StringDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AnnotationResolver {
    private record Definition(String qualifiedName, String packageName,
                              Stmt.AnnotationDeclaration declaration) {}

    private record Constant(Object value, TypeDescriptor type) {}

    private AnnotationResolver() {}

    static void validateDefault(final ResolutionContext context, final CompilationUnit unit,
                                final Stmt.AnnotationParameter parameter) {
        try {
            parameter.resolveDefault(toAnnotationConstant(
                    constant(parameter.defaultValue(), parameter.type()).value()));
        } catch (IllegalArgumentException exception) {
            record(context, unit, parameter.name(), "Default for annotation parameter '"
                    + parameter.name().lexeme() + "' must be a supported constant of type "
                    + parameter.type() + ".");
        }
    }

    static void validate(final ResolutionContext context, final List<CompilationUnit> units) {
        final var definitions = new LinkedHashMap<String, Definition>();
        for (final var unit : units) {
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.AnnotationDeclaration declaration)) continue;
                final var qualifiedName = qualifiedName(unit.packageName(), member.namespaceName(),
                        declaration.name().lexeme());
                final var definition = new Definition(qualifiedName, unit.packageName(), declaration);
                final var previous = definitions.putIfAbsent(qualifiedName, definition);
                if (previous != null) {
                    record(context, unit, declaration.name(), "Duplicate annotation declaration '"
                            + qualifiedName + "'.");
                }
            }
        }

        for (final var unit : units) {
            final Set<Stmt.AnnotationApplication> visited =
                    Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (final var site : unit.annotationSites()) {
                validateSite(context, unit, definitions, site.target(), site.isPublic(),
                        site.applications(), visited);
            }
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (member.annotations().isEmpty()) continue;
                final var unvisited = member.annotations().stream().filter(visited::add).toList();
                if (!unvisited.isEmpty()) {
                    validateSite(context, unit, definitions, targetOf(member.declaration()),
                            isPublic(member.declaration()), unvisited, visited);
                }
            }
        }
    }

    private static void validateSite(final ResolutionContext context, final CompilationUnit unit,
                                     final Map<String, Definition> definitions,
                                     final Stmt.AnnotationTarget target, final boolean isPublic,
                                     final List<Stmt.AnnotationApplication> applications,
                                     final Set<Stmt.AnnotationApplication> visited) {
        final var seen = new HashSet<String>();
        for (final var application : applications) {
            visited.add(application);
            final var definition = resolve(unit, application.name().lexeme(), definitions);
            if (definition == null) {
                record(context, unit, application.name(), "Unknown annotation '"
                        + application.name().lexeme() + "'.");
                continue;
            }
            if (!definition.declaration().isPublic()
                    && !definition.packageName().equals(unit.packageName())) {
                record(context, unit, application.name(), "Annotation '"
                        + definition.qualifiedName() + "' is not public.");
                continue;
            }
            if (isPublic && !definition.declaration().isPublic()) {
                record(context, unit, application.name(), "Annotation '"
                        + definition.qualifiedName() + "' must be public when applied to public API.");
                continue;
            }
            if (target == null || !definition.declaration().targets().contains(target)) {
                record(context, unit, application.location(), "Annotation '"
                        + definition.qualifiedName() + "' does not target this declaration.");
                continue;
            }
            if (!seen.add(definition.qualifiedName())) {
                record(context, unit, application.location(), "Annotation '"
                        + definition.qualifiedName() + "' may only be applied once.");
                continue;
            }
            validateArguments(context, unit, definition, application);
        }
    }

    private static boolean isPublic(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl classDeclaration -> classDeclaration.isPublic();
            case Stmt.ExternalClass externalClass -> externalClass.isPublic();
            case Stmt.ContractDecl contractDeclaration -> contractDeclaration.isPublic();
            case Stmt.ExternalFunction externalFunction -> externalFunction.isPublic();
            case Stmt.FunctionDeclaration function -> function.isPublic();
            case Stmt.Var variable -> variable.isPublic();
            default -> false;
        };
    }

    private static String qualifiedName(final String packageName, final String namespaceName,
                                        final String declarationName) {
        final var simpleName = declarationName.substring(declarationName.lastIndexOf('.') + 1);
        final var packageAndNamespace = namespaceName == null || namespaceName.isEmpty()
                ? packageName
                : packageName.isEmpty() ? namespaceName : packageName + "." + namespaceName;
        return packageAndNamespace.isEmpty() ? simpleName : packageAndNamespace + "." + simpleName;
    }

    private static Definition resolve(final CompilationUnit unit, final String name,
                                      final Map<String, Definition> definitions) {
        if (name.contains(".")) return definitions.get(name);

        final var local = qualify(unit.packageName(), name);
        final var localDefinition = definitions.get(local);
        if (localDefinition != null) return localDefinition;

        final var imported = new ArrayList<Definition>();
        for (final var declaration : unit.imports()) {
            final var target = declaration.qualifiedName();
            if (!declaration.onDemand() && declaration.localName().equals(name)) {
                final var definition = definitions.get(target);
                if (definition != null) imported.add(definition);
            } else if (declaration.onDemand() && target.endsWith(".*")) {
                final var definition = definitions.get(target.substring(0, target.length() - 2) + "." + name);
                if (definition != null) imported.add(definition);
            }
        }
        return imported.size() == 1 ? imported.getFirst() : null;
    }

    private static String qualify(final String packageName, final String name) {
        return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
    }

    private static Stmt.AnnotationTarget targetOf(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl ignored -> Stmt.AnnotationTarget.CLASS;
            case Stmt.ExternalClass ignored -> Stmt.AnnotationTarget.CLASS;
            case Stmt.ContractDecl ignored -> Stmt.AnnotationTarget.CONTRACT;
            case Stmt.ExternalFunction ignored -> Stmt.AnnotationTarget.FUNCTION;
            case Stmt.FunctionDeclaration function ->
                    function instanceof Stmt.ExtensionMethod
                            ? Stmt.AnnotationTarget.METHOD : Stmt.AnnotationTarget.FUNCTION;
            default -> null;
        };
    }

    private static void validateArguments(final ResolutionContext context, final CompilationUnit unit,
                                         final Definition definition,
                                         final Stmt.AnnotationApplication application) {
        final var parameters = definition.declaration().parameters();
        final var supplied = new HashMap<String, Expr>();
        var valid = true;
        var positionalIndex = 0;
        for (final var argument : application.arguments()) {
            final var parameterName = argument.name() == null
                    ? positionalIndex < parameters.size() ? parameters.get(positionalIndex++).name().lexeme() : null
                    : argument.name().lexeme();
            if (parameterName == null || parameters.stream()
                    .noneMatch(parameter -> parameter.name().lexeme().equals(parameterName))) {
                record(context, unit, argument.name() == null ? application.location() : argument.name(),
                        "Unknown or excess argument for annotation '" + definition.qualifiedName() + "'.");
                valid = false;
                continue;
            }
            if (supplied.putIfAbsent(parameterName, argument.value()) != null) {
                record(context, unit, argument.name() == null ? application.location() : argument.name(),
                        "Annotation argument '" + parameterName + "' is supplied more than once.");
                valid = false;
            }
        }

        final var resolved = new LinkedHashMap<String, AnnotationConstant>();
        for (final var parameter : parameters) {
            var value = supplied.get(parameter.name().lexeme());
            if (value == null) value = parameter.defaultValue();
            if (value == null) {
                record(context, unit, application.location(), "Missing required annotation argument '"
                        + parameter.name().lexeme() + "'.");
                valid = false;
                continue;
            }
            try {
                resolved.put(parameter.name().lexeme(),
                        toAnnotationConstant(constant(value, parameter.type()).value()));
            } catch (IllegalArgumentException exception) {
                record(context, unit, value instanceof Expr.Literal literal
                                ? literal.numericToken() == null ? parameter.name() : literal.numericToken()
                                : parameter.name(),
                        "Annotation argument '" + parameter.name().lexeme()
                                + "' must be a supported constant of type " + parameter.type() + ".");
                valid = false;
            }
        }
        if (valid) {
            application.resolve(definition.qualifiedName(), resolved.entrySet().stream()
                    .map(entry -> new Stmt.ResolvedAnnotationArgument(entry.getKey(), entry.getValue()))
                    .toList());
        }
    }

    private static AnnotationConstant toAnnotationConstant(final Object value) {
        if (value == null) return new AnnotationConstant.NullValue();
        if (value instanceof String string) return new AnnotationConstant.StringValue(string);
        if (value instanceof Boolean bool) return new AnnotationConstant.BooleanValue(bool);
        if (value instanceof BigInteger integer) return new AnnotationConstant.IntegerValue(integer);
        if (value instanceof BigDecimal decimal) return new AnnotationConstant.DecimalValue(decimal);
        if (value instanceof List<?> values) return new AnnotationConstant.ArrayValue(values.stream()
                .map(AnnotationResolver::toAnnotationConstant).toList());
        throw new IllegalArgumentException("Unsupported annotation value.");
    }

    private static Constant constant(final Expr expression, final TypeDescriptor expected) {
        if (expression instanceof Expr.Grouping grouping) return constant(grouping.expression, expected);
        final var valueType = expected instanceof NullableDescriptor nullable
                ? nullable.baseType() : expected;
        if (expression instanceof Expr.Literal literal) {
            if (literal.value == null) {
                if (expected instanceof NullableDescriptor) return new Constant(null, expected);
                if (expected instanceof NullDescriptor) return new Constant(null, expected);
                throw new IllegalArgumentException();
            }
            if (literal.value instanceof String value && valueType instanceof StringDescriptor) {
                return new Constant(value, expected);
            }
            if (literal.value instanceof Boolean value && valueType instanceof BooleanDescriptor) {
                return new Constant(value, expected);
            }
            if (literal.exactInteger() != null) {
                return integer(literal.exactInteger(), valueType);
            }
            if (literal.exactDecimal() != null) {
                return decimal(literal.exactDecimal(), valueType);
            }
            throw new IllegalArgumentException();
        }
        if (expression instanceof Expr.Unary unary
                && (unary.operator.type() == TokenType.PLUS || unary.operator.type() == TokenType.MINUS)) {
            final var value = constant(unary.right, valueType);
            if (value.value() instanceof BigInteger integer) {
                final var signed = unary.operator.type() == TokenType.MINUS ? integer.negate() : integer;
                return integer(signed, valueType);
            }
            if (value.value() instanceof BigDecimal decimal) {
                final var signed = unary.operator.type() == TokenType.MINUS ? decimal.negate() : decimal;
                return decimal(signed, valueType);
            }
            throw new IllegalArgumentException();
        }
        if (expression instanceof Expr.ArrayLiteral array && valueType instanceof ArrayDescriptor descriptor) {
            final var values = new ArrayList<Object>(array.elements.size());
            for (final var element : array.elements) {
                values.add(constant(element, descriptor.elementType()).value());
            }
            return new Constant(Collections.unmodifiableList(values), expected);
        }
        throw new IllegalArgumentException();
    }

    private static Constant integer(final BigInteger value, final TypeDescriptor expected) {
        final var range = switch (expected) {
            case ByteDescriptor ignored -> new BigInteger[] {
                    BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE)};
            case IntDescriptor ignored -> new BigInteger[] {
                    BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)};
            case LongDescriptor ignored -> new BigInteger[] {
                    BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)};
            case FloatDescriptor ignored -> null;
            default -> throw new IllegalArgumentException();
        };
        if (expected instanceof FloatDescriptor && !Double.isFinite(new BigDecimal(value).doubleValue())) {
            throw new IllegalArgumentException();
        }
        if (range != null && (value.compareTo(range[0]) < 0 || value.compareTo(range[1]) > 0)) {
            throw new IllegalArgumentException();
        }
        return new Constant(value, expected);
    }

    private static Constant decimal(final BigDecimal value, final TypeDescriptor expected) {
        if (!(expected instanceof FloatDescriptor)) throw new IllegalArgumentException();
        if (!Double.isFinite(value.doubleValue())) throw new IllegalArgumentException();
        return new Constant(value, expected);
    }

    private static void record(final ResolutionContext context, final CompilationUnit unit,
                               final Token token, final String message) {
        context.diagnostics.record(new ResolutionError(DiagnosticCatalog.INVALID_DECLARATION_STRUCTURE,
                token, message), unit.sourcePath());
    }
}
