package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.ImportDeclaration;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Produces advisory diagnostics after resolution. The pass intentionally relies on resolved
/// reference targets so it never reports a declaration that a resolved use actually reaches.
final class WarningAnalysis {
    private WarningAnalysis() {}

    static void analyze(final ResolutionContext context, final List<CompilationUnit> units) {
        final var usedDeclarations = new HashSet<Token>(context.referenceTargets.values());
        for (final var unit : units) {
            if (unit.metadataOnly() || isBundledStandardLibrary(unit)) continue;
            reportUnusedImports(unit);
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = member.declaration();
                if (declaration instanceof Stmt.Var variable) {
                    reportUnusedVariable(usedDeclarations, variable);
                } else if (declaration instanceof Stmt.Function function) {
                    reportUnusedFunction(usedDeclarations, function);
                    reportProjection(context, function.parameters(), function.typeDescriptor());
                    analyzeBody(context, function.body(), usedDeclarations);
                }
            }
        }
    }

    private static boolean isBundledStandardLibrary(final CompilationUnit unit) {
        final var path = unit.sourcePath();
        if (path == null) return false;
        final var normalized = path.replace('\\', '/');
        return normalized.startsWith("zeron/")
                || normalized.startsWith("/stdlib/")
                || normalized.startsWith("stdlib/");
    }

    private static void reportUnusedVariable(final Set<Token> used, final Stmt.Var variable) {
        final var name = variable.name();
        if (variable.isPublic() || isSynthetic(name) || name.lexeme().startsWith("_")
                || used.contains(name)) return;
        Zeron.warning(name, DiagnosticCatalog.UNUSED_LOCAL_VARIABLE,
                "Variable '" + name.lexeme() + "' is never used.");
    }

    private static void reportUnusedFunction(final Set<Token> used, final Stmt.Function function) {
        final var name = function.name();
        if (function.isPublic() || isSynthetic(name) || name.lexeme().equals("main")
                || name.lexeme().startsWith("_")
                || used.contains(name)) {
            return;
        }
        Zeron.warning(name, DiagnosticCatalog.UNUSED_FUNCTION,
                "Function '" + name.lexeme() + "' is never used.");
    }

    private static boolean isSynthetic(final Token name) {
        return name.lexeme().contains("$");
    }

    private static void reportUnusedImports(final CompilationUnit unit) {
        if (unit.imports().isEmpty() || unit.tokens().isEmpty()) return;
        final var identifierCounts = new HashMap<String, Integer>();
        for (final var token : unit.tokens()) {
            if (token.type() == TokenType.IDENTIFIER) {
                identifierCounts.merge(token.lexeme(), 1, Integer::sum);
            }
        }
        for (final var imported : unit.imports()) {
            if (imported.onDemand() || imported.localName().startsWith("_")) continue;
            if (identifierCounts.getOrDefault(imported.localName(), 0) > 1) continue;
            Zeron.warning(imported.location(), DiagnosticCatalog.UNUSED_IMPORT,
                    "Import '" + imported.localName() + "' is never used.");
        }
    }

    private static void reportProjection(final ResolutionContext context,
                                         final List<Token> parameters,
                                         final TypeDescriptor descriptor) {
        final var parameterTypes = descriptor instanceof FunctionDescriptor function
                ? function.parameters() : List.<TypeDescriptor>of();
        for (var index = 0; index < Math.min(parameters.size(), parameterTypes.size()); index++) {
            if (!(parameterTypes.get(index) instanceof ReferenceDescriptor)) continue;
            final var parameter = parameters.get(index);
            if (parameter.lexeme().startsWith("_")) continue;
            final var references = context.referenceTargets.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(parameter))
                    .map(Map.Entry::getKey).toList();
            if (references.isEmpty()
                    || references.stream().anyMatch(context.mutableUses::contains)) {
                continue;
            }
            Zeron.warning(parameter, DiagnosticCatalog.UNNECESSARY_MUTABLE_PROJECTION,
                    "Parameter '" + parameter.lexeme()
                            + "' is never used mutably; narrow its mutable reference to a read-only value.");
        }
    }

    private static void analyzeBody(final ResolutionContext context, final List<Stmt> body,
                                    final Set<Token> used) {
        var terminated = false;
        for (final var statement : body) {
            if (terminated) {
                final var token = statementToken(statement);
                if (token != null) {
                    Zeron.warning(token, DiagnosticCatalog.UNREACHABLE_STATEMENT,
                            "This statement is unreachable.");
                }
            }
            analyzeStatement(context, statement, used);
            terminated = terminated || terminates(statement);
        }
    }

    private static void analyzeStatement(final ResolutionContext context, final Stmt statement,
                                         final Set<Token> used) {
        if (statement instanceof Stmt.Block block) {
            analyzeBody(context, block.statements(), used);
        } else if (statement instanceof Stmt.If branch) {
            analyzeStatement(context, branch.thenBranch(), used);
            if (branch.elseBranch() != null) analyzeStatement(context, branch.elseBranch(), used);
        } else if (statement instanceof Stmt.While loop) {
            analyzeStatement(context, loop.body(), used);
        } else if (statement instanceof Stmt.For loop) {
            analyzeStatement(context, loop.body(), used);
        } else if (statement instanceof Stmt.Using using) {
            for (final var resource : using.resources()) reportUnusedVariable(used, resource.declaration());
            analyzeBody(context, using.body(), used);
        } else if (statement instanceof Stmt.Var variable) {
            reportUnusedVariable(used, variable);
        }
    }

    private static boolean terminates(final Stmt statement) {
        if (statement instanceof Stmt.Return || statement instanceof Stmt.Break
                || statement instanceof Stmt.Continue) {
            return true;
        }
        if (statement instanceof Stmt.Block block) {
            return !block.statements().isEmpty() && terminates(block.statements().getLast());
        }
        return statement instanceof Stmt.If branch && branch.elseBranch() != null
                && terminates(branch.thenBranch()) && terminates(branch.elseBranch());
    }

    private static Token statementToken(final Stmt statement) {
        return switch (statement) {
            case Stmt.Var variable -> variable.name();
            case Stmt.If branch -> branch.paren();
            case Stmt.While loop -> loop.keyword();
            case Stmt.For loop -> loop.iterationBind();
            case Stmt.Return value -> value.location();
            case Stmt.Break value -> value.keyword();
            case Stmt.Continue value -> value.keyword();
            case Stmt.Expression expression -> expressionToken(expression.expression());
            case Stmt.Block block -> block.statements().isEmpty() ? null : statementToken(block.statements().getFirst());
            default -> null;
        };
    }

    private static Token expressionToken(final Expr expression) {
        return switch (expression) {
            case Expr.Variable variable -> variable.name;
            case Expr.Call call -> call.callee;
            case Expr.MemberCall call -> call.name;
            case Expr.Property property -> property.name;
            case Expr.Assignment assignment -> assignment.name;
            case Expr.CoalesceAssignment assignment -> assignment.name;
            case Expr.PropertyAssignment assignment -> assignment.property.name;
            case Expr.PropertyCompoundAssignment assignment -> assignment.property.name;
            case Expr.Index index -> expressionToken(index.array);
            case Expr.IndexAssignment assignment -> expressionToken(assignment.array);
            case Expr.Binary binary -> binary.operator;
            default -> null;
        };
    }
}
