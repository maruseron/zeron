package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayList;
import java.util.List;

final class ComprehensionLowerer {
    private ComprehensionLowerer() {}

    static Expr lower(final Expr.Pipeline comprehension) {
        final var clauses = comprehension.clauses();
        final var firstGenerator = (Expr.PipelineClause.Generator) clauses.getFirst();
        final var source = pipeline(firstGenerator.source(), firstGenerator.keyword());
        if (comprehension.result() instanceof Expr.PipelineResult.Collect collect) {
            final var projectedStream = lowerClauses(source, firstGenerator.binding(), clauses, 1,
                    new Expr.PipelineResult.Yield(collect.keyword(), collect.expression()));
            return collect(projectedStream, collect);
        }
        return lowerClauses(source, firstGenerator.binding(), clauses, 1, comprehension.result());
    }

    private static Expr lowerClauses(final Expr stream, final Token binding,
                                     final List<Expr.PipelineClause> clauses, final int index,
                                     final Expr.PipelineResult result) {
        if (index == clauses.size()) return lowerResult(stream, binding, result);

        final var clause = clauses.get(index);
        return switch (clause) {
            case Expr.PipelineClause.Guard guard -> {
                final var predicate = lambda(guard.keyword(), List.of(binding),
                        List.of(new Stmt.Return(guard.condition(), guard.keyword())));
                final var filtered = call(stream, guard.keyword(), "filter", List.of(predicate));
                yield lowerClauses(filtered, binding, clauses, index + 1, result);
            }
            case Expr.PipelineClause.Let let -> {
                final var singleton = pipeline(new Expr.ArrayLiteral(
                        List.of(variable(binding)), TypeDescriptor.ofInfer()), let.keyword());
                final var body = new ArrayList<Stmt>();
                body.add(new Stmt.Var(let.binding(), TypeDescriptor.ofInfer(), let.expression(),
                        BindingMutability.IMMUTABLE));
                final var continuationBody =
                        lowerClauses(singleton, binding, clauses, index + 1, result);
                body.add(new Stmt.Return(continuationBody, let.keyword()));
                final var continuation = lambda(let.keyword(), List.of(binding), body);
                yield flatMap(stream, let.keyword(), binding, continuation, continuationBody);
            }
            case Expr.PipelineClause.Generator generator -> {
                final var nestedSource = pipeline(generator.source(), generator.keyword());
                final var body = lowerClauses(nestedSource, generator.binding(), clauses,
                        index + 1, result);
                final var continuation = lambda(generator.keyword(), List.of(binding),
                        List.of(new Stmt.Return(body, generator.keyword())));
                yield flatMap(stream, generator.keyword(), binding, continuation, body);
            }
        };
    }

    private static Expr lowerResult(final Expr stream, final Token binding,
                                    final Expr.PipelineResult result) {
        return switch (result) {
            case Expr.PipelineResult.Yield yielded -> {
                final var projection = lambda(yielded.keyword(), List.of(binding),
                        List.of(new Stmt.Return(yielded.expression(), yielded.keyword())));
                yield call(stream, yielded.keyword(), "map", List.of(projection));
            }
            case Expr.PipelineResult.Collect _ -> throw new IllegalStateException(
                    "Collection must be lowered after the complete lazy pipeline.");
        };
    }

    private static Expr collect(final Expr stream, final Expr.PipelineResult.Collect collect) {
        final var accumulator = syntheticToken(collect.into(), "acc");
        final var value = syntheticToken(collect.into(), "value");
        final var add = call(variable(accumulator), collect.into(), "add", List.of(variable(value)));
        final var fold = lambda(collect.into(), List.of(accumulator, value), List.of(
                new Stmt.Expression(add),
                new Stmt.Return(variable(accumulator), collect.into())));
        return call(stream, collect.keyword(), "fold", List.of(collect.sink(), fold));
    }

    private static Expr.Pipeline pipeline(final Expr source, final Token keyword) {
        return new Expr.Pipeline(keyword, source, List.of(), false);
    }

    private static Expr.Pipeline flatMap(final Expr stream, final Token keyword,
                                         final Token binding, final Expr.Lambda lambda,
                                         final Expr body) {
        final var stage = new Expr.PipelineStage.FlatMap(keyword, binding, false, body);
        stage.setLambda(lambda);
        return new Expr.Pipeline(keyword, stream, List.of(stage), false);
    }

    private static Expr call(final Expr receiver, final Token location, final String method,
                             final List<Expr> arguments) {
        final var name = new Token(TokenType.IDENTIFIER, method, null, location.span());
        return new Expr.MemberCall(receiver, name, location, arguments, List.of(), TypeDescriptor.ofInfer());
    }

    private static Expr.Lambda lambda(final Token location, final List<Token> parameters,
                                      final List<Stmt> body) {
        return new Expr.Lambda(location, parameters, body, TypeDescriptor.ofInfer());
    }

    private static Expr.Variable variable(final Token name) {
        return new Expr.Variable(name, TypeDescriptor.ofInfer());
    }

    private static Token syntheticToken(final Token location, final String name) {
        return new Token(TokenType.IDENTIFIER, "$zeron$comprehension$" + name,
                null, location.span());
    }
}
