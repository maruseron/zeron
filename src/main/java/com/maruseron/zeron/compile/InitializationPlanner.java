package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;

import java.util.*;

final class InitializationPlanner {
    record Plan(List<Stmt.Var> order,
                Map<Stmt.Var, String> initializerNames,
                List<Diagnostic> diagnostics) {
        Plan {
            order = List.copyOf(order);
            initializerNames = Collections.unmodifiableMap(new IdentityHashMap<>(initializerNames));
            diagnostics = List.copyOf(diagnostics);
        }
    }

    Plan plan(final List<CompilationUnit> units, final List<Stmt> declarations) {
        final var values = declarations.stream().filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast).toList();
        final var stableKeys = new IdentityHashMap<Stmt.Var, String>();
        final var initializerNames = new IdentityHashMap<Stmt.Var, String>();
        for (final var unit : units) {
            for (var index = 0; index < unit.declarations().size(); index++) {
                final var declaration = unit.declarations().get(index);
                if (declaration instanceof Stmt.Var variable) {
                    stableKeys.put(variable, unit.packageName() + "\u0000"
                            + sourceRootOrder(unit.sourcePath()) + "\u0000"
                            + Objects.toString(unit.sourcePath(), "") + "\u0000" + index);
                    initializerNames.put(variable, "$zeron$init$" + variable.name().lexeme());
                }
            }
        }
        final var orderedValues = values.stream()
                .sorted(Comparator.comparing(stableKeys::get))
                .toList();
        final var valueSet = Collections.newSetFromMap(new IdentityHashMap<Stmt.Var, Boolean>());
        valueSet.addAll(values);
        final var functions = new LinkedHashMap<String, Stmt.Function>();
        for (final var unit : units) {
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.Function function) {
                    functions.put(qualifiedName(unit.packageName(), function.name().lexeme()), function);
                }
            }
        }

        final var dependencies = new IdentityHashMap<Stmt.Var, Set<Stmt.Var>>();
        for (final var value : values) {
            final var valueDependencies = Collections.newSetFromMap(new IdentityHashMap<Stmt.Var, Boolean>());
            collectDependencies(value.initializer(), valueDependencies, functions, new HashSet<>(), valueSet);
            dependencies.put(value, valueDependencies);
        }
        final var states = new IdentityHashMap<Stmt.Var, Integer>();
        final var result = new ArrayList<Stmt.Var>();
        final var stack = new ArrayDeque<Stmt.Var>();
        final var invalidValues = Collections.newSetFromMap(new IdentityHashMap<Stmt.Var, Boolean>());
        final var diagnostics = new ArrayList<Diagnostic>();
        final var sourcePaths = sourcePathByValue(units);
        for (final var value : orderedValues) {
            visit(value, dependencies, stableKeys, states, stack, result, invalidValues, diagnostics,
                    sourcePaths);
        }
        return new Plan(result, initializerNames, diagnostics);
    }

    private static IdentityHashMap<Stmt.Var, String> sourcePathByValue(final List<CompilationUnit> units) {
        final var sourcePaths = new IdentityHashMap<Stmt.Var, String>();
        for (final var unit : units) {
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.Var variable) sourcePaths.put(variable, unit.sourcePath());
            }
        }
        return sourcePaths;
    }

    private static int sourceRootOrder(final String sourcePath) {
        if (sourcePath == null || !sourcePath.startsWith("root")) return Integer.MAX_VALUE;
        final var separator = sourcePath.indexOf('/');
        if (separator < 5) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(sourcePath.substring(4, separator));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static void visit(
            final Stmt.Var value,
            final Map<Stmt.Var, Set<Stmt.Var>> dependencies,
            final Map<Stmt.Var, String> stableKeys,
            final IdentityHashMap<Stmt.Var, Integer> states,
            final Deque<Stmt.Var> stack,
            final List<Stmt.Var> result,
            final Set<Stmt.Var> invalidValues,
            final List<Diagnostic> diagnostics,
            final Map<Stmt.Var, String> sourcePaths) {
        final var currentState = states.getOrDefault(value, 0);
        if (currentState == 2) return;
        if (currentState == 1) {
            final var cycle = new ArrayList<String>();
            final var cycleValues = Collections.newSetFromMap(new IdentityHashMap<Stmt.Var, Boolean>());
            for (final var member : stack) {
                cycle.add(member.name().lexeme());
                cycleValues.add(member);
                if (member == value) break;
            }
            Collections.reverse(cycle);
            cycle.add(value.name().lexeme());
            final var newCycle = cycleValues.stream().anyMatch(member -> !invalidValues.contains(member));
            invalidValues.addAll(cycleValues);
            if (newCycle) {
                diagnostics.add(Diagnostic.atToken(DiagnosticCatalog.TOP_LEVEL_INITIALIZATION_CYCLE,
                        value.name(), sourcePaths.get(value),
                        "Top-level value initialization cycle: " + String.join(" -> ", cycle) + "."));
            }
            return;
        }
        states.put(value, 1);
        stack.push(value);
        dependencies.getOrDefault(value, Set.of()).stream()
                .sorted(Comparator.comparing(stableKeys::get))
                .forEach(dependency -> visit(dependency, dependencies, stableKeys, states, stack, result,
                        invalidValues, diagnostics, sourcePaths));
        stack.pop();
        states.put(value, 2);
        if (!invalidValues.contains(value)) result.add(value);
    }

    private static void collectDependencies(
            final Expr expression,
            final Set<Stmt.Var> dependencies,
            final Map<String, Stmt.Function> functions,
            final Set<String> visitedFunctions,
            final Set<Stmt.Var> projectValues) {
        if (expression == null) return;
        if (expression instanceof Expr.Variable variable
                && variable.resolvedValueDeclaration() != null
                && projectValues.contains(variable.resolvedValueDeclaration())) {
            dependencies.add(variable.resolvedValueDeclaration());
        }
        if (expression instanceof Expr.Call call && call.resolvedFunctionName() != null
                && visitedFunctions.add(call.resolvedFunctionName())) {
            final var function = functions.get(call.resolvedFunctionName());
            if (function != null) {
                for (final var statement : function.body()) {
                    collectDependencies(statement, dependencies, functions, visitedFunctions, projectValues);
                }
            }
        }
        for (final var field : expression.getClass().getFields()) {
            try {
                final var child = field.get(expression);
                if (child instanceof Expr childExpression) {
                    collectDependencies(childExpression, dependencies, functions, visitedFunctions, projectValues);
                } else if (child instanceof List<?> children) {
                    for (final var item : children) {
                        if (item instanceof Expr childExpression) {
                            collectDependencies(childExpression, dependencies, functions, visitedFunctions, projectValues);
                        } else if (item instanceof Stmt statement) {
                            collectDependencies(statement, dependencies, functions, visitedFunctions, projectValues);
                        }
                    }
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to inspect expression for value dependencies.", exception);
            }
        }
    }

    private static void collectDependencies(
            final Stmt statement,
            final Set<Stmt.Var> dependencies,
            final Map<String, Stmt.Function> functions,
            final Set<String> visitedFunctions,
            final Set<Stmt.Var> projectValues) {
        if (statement == null || !statement.getClass().isRecord()) return;
        for (final var component : statement.getClass().getRecordComponents()) {
            try {
                final var child = component.getAccessor().invoke(statement);
                if (child instanceof Expr expression) {
                    collectDependencies(expression, dependencies, functions, visitedFunctions, projectValues);
                } else if (child instanceof Stmt childStatement) {
                    collectDependencies(childStatement, dependencies, functions, visitedFunctions, projectValues);
                } else if (child instanceof List<?> children) {
                    for (final var item : children) {
                        if (item instanceof Expr expression) {
                            collectDependencies(expression, dependencies, functions, visitedFunctions, projectValues);
                        } else if (item instanceof Stmt childStatement) {
                            collectDependencies(childStatement, dependencies, functions, visitedFunctions, projectValues);
                        }
                    }
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to inspect statement for value dependencies.", exception);
            }
        }
    }

    private static String qualifiedName(final String ownerPackage, final String name) {
        return ownerPackage == null || ownerPackage.isEmpty() ? name : ownerPackage + "." + name;
    }
}
