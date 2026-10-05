package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.ImportDeclaration;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.InferDescriptor;

import java.util.*;

final class TopLevelValuePlanner {
    record SourceValue(CompilationUnit unit, Stmt.Var declaration) {}

    private final Map<String, Stmt.Var> topLevelValues;

    TopLevelValuePlanner(final Map<String, Stmt.Var> topLevelValues) {
        this.topLevelValues = topLevelValues;
    }

    List<SourceValue> resolutionOrder(final List<CompilationUnit> units) {
        final var values = new ArrayList<SourceValue>();
        for (final var unit : units) {
            if (unit.metadataOnly()) continue;
            for (final var declaration : unit.declarations()) {
                if (declaration instanceof Stmt.Var variable) values.add(new SourceValue(unit, variable));
            }
        }
        final var valueByDeclaration = new IdentityHashMap<Stmt.Var, SourceValue>();
        values.forEach(value -> valueByDeclaration.put(value.declaration(), value));
        final var dependencies = new IdentityHashMap<Stmt.Var, Set<Stmt.Var>>();
        for (final var value : values) {
            final var names = new LinkedHashSet<String>();
            collectVariableNames(value.declaration().initializer(), names);
            final var dependenciesForValue = Collections.newSetFromMap(new IdentityHashMap<Stmt.Var, Boolean>());
            for (final var name : names) {
                final var targetName = importedValueName(value.unit(), name);
                if (targetName == null) continue;
                final var target = topLevelValues.get(targetName);
                if (target != null && target.type() instanceof InferDescriptor) {
                    dependenciesForValue.add(target);
                }
            }
            dependencies.put(value.declaration(), dependenciesForValue);
        }
        final var states = new IdentityHashMap<Stmt.Var, Integer>();
        final var stack = new ArrayDeque<Stmt.Var>();
        final var result = new ArrayList<SourceValue>();
        for (final var value : values) {
            visit(value.declaration(), valueByDeclaration, dependencies, states, stack, result);
        }
        return List.copyOf(result);
    }

    private String importedValueName(final CompilationUnit unit, final String simpleName) {
        final var localQualifiedName = qualify(unit.packageName(), simpleName);
        if (topLevelValues.containsKey(localQualifiedName)) return localQualifiedName;
        for (final var imported : unit.imports()) {
            if (!imported.onDemand() && imported.localName().equals(simpleName)
                    && topLevelValues.containsKey(imported.qualifiedName())) {
                return imported.qualifiedName();
            }
        }
        final var starCandidates = unit.imports().stream()
                .filter(ImportDeclaration::onDemand)
                .map(imported -> qualify(imported.qualifiedName(), simpleName))
                .filter(topLevelValues::containsKey)
                .filter(candidate -> topLevelValues.get(candidate).isPublic())
                .distinct()
                .toList();
        return starCandidates.size() == 1 ? starCandidates.getFirst() : null;
    }

    private void visit(final Stmt.Var declaration,
                       final Map<Stmt.Var, SourceValue> valueByDeclaration,
                       final Map<Stmt.Var, Set<Stmt.Var>> dependencies,
                       final IdentityHashMap<Stmt.Var, Integer> states,
                       final Deque<Stmt.Var> stack,
                       final List<SourceValue> result) {
        final var state = states.getOrDefault(declaration, 0);
        if (state == 2) return;
        if (state == 1) {
            final var cycle = new ArrayList<String>();
            for (final var member : stack) {
                cycle.add(member.name().lexeme());
                if (member == declaration) break;
            }
            Collections.reverse(cycle);
            cycle.add(declaration.name().lexeme());
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.TOP_LEVEL_INITIALIZATION_CYCLE,
                    declaration.name(),
                    "Top-level value initialization cycle: " + String.join(" -> ", cycle) + "."));
        }
        states.put(declaration, 1);
        stack.push(declaration);
        dependencies.getOrDefault(declaration, Set.of()).forEach(dependency ->
                visit(dependency, valueByDeclaration, dependencies, states, stack, result));
        stack.pop();
        states.put(declaration, 2);
        result.add(valueByDeclaration.get(declaration));
    }

    private void collectVariableNames(final Expr expression, final Set<String> names) {
        if (expression == null) return;
        if (expression instanceof Expr.Variable variable) names.add(variable.name.lexeme());
        if (expression instanceof Expr.Call call) names.add(call.callee.lexeme());
        for (final var field : expression.getClass().getFields()) {
            try {
                final var child = field.get(expression);
                if (child instanceof Expr childExpression) {
                    collectVariableNames(childExpression, names);
                } else if (child instanceof List<?> children) {
                    for (final var item : children) {
                        if (item instanceof Expr childExpression) collectVariableNames(childExpression, names);
                        else if (item instanceof Stmt statement) collectVariableNames(statement, names);
                    }
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to inspect value initializer.", exception);
            }
        }
    }

    private void collectVariableNames(final Stmt statement, final Set<String> names) {
        if (statement == null || !statement.getClass().isRecord()) return;
        for (final var component : statement.getClass().getRecordComponents()) {
            try {
                final var child = component.getAccessor().invoke(statement);
                if (child instanceof Expr expression) collectVariableNames(expression, names);
                else if (child instanceof Stmt childStatement) collectVariableNames(childStatement, names);
                else if (child instanceof List<?> children) {
                    for (final var item : children) {
                        if (item instanceof Expr expression) collectVariableNames(expression, names);
                        else if (item instanceof Stmt childStatement) collectVariableNames(childStatement, names);
                    }
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to inspect value initializer.", exception);
            }
        }
    }

    private static String qualify(final String ownerPackage, final String simpleName) {
        return ownerPackage == null || ownerPackage.isEmpty() ? simpleName : ownerPackage + "." + simpleName;
    }
}
