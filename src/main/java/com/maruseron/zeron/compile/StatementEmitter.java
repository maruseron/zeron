package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.lang.classfile.*;
import java.lang.classfile.CodeBuilder.BlockCodeBuilder;
import java.lang.constant.*;
import java.util.*;

import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class StatementEmitter {
    static void emitStmts(CompilationContext context, final CodeBuilder builder, final List<Stmt> statements ){
        for (final var statement : statements) {
            emitStmt(context, builder, statement);
        }
    }

    private static void emitStmt(CompilationContext context, final CodeBuilder composer, final Stmt statement ){
        switch (statement) {
            case Stmt.Break _ -> {
                if (context.loopExitLabels.isEmpty()) {
                    throw new IllegalStateException("Resolved break has no enclosing loop.");
                }
                emitUsingCleanupsToDepth(context, composer, context.loopUsingDepths.peek());
                composer.goto_(context.loopExitLabels.peek());
            }
            case Stmt.Continue _ -> {
                if (context.loopContinueLabels.isEmpty()) {
                    throw new IllegalStateException("Resolved continue has no enclosing loop.");
                }
                emitUsingCleanupsToDepth(context, composer, context.loopUsingDepths.peek());
                composer.goto_(context.loopContinueLabels.peek());
            }
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope(context);
                emitStmts(context, composer, statements);
                endScope(context);
            }
            case Stmt.Using using -> emitUsing(context, composer, using);
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var value = (Integer) ConstantFolder.fold(condition);
                if (value != null) {
                    if (value == 1) {
                        emitStmt(context, composer, thenBranch);
                    } else {
                        emitStmt(context, composer, elseBranch);
                    }
                } else {
                    final var bindings = ExpressionFlowEmitter.beginSumCaseTestBindings(context, composer, condition);
                    try {
                        emitExpr(context, composer, condition);
                        if (elseBranch != null) {
                            composer.ifThenElse(
                                c -> emitStmt(context, c, thenBranch),
                                c -> emitStmt(context, c, elseBranch));
                        } else {
                            composer.ifThen(c -> emitStmt(context, c, thenBranch));
                        }
                    } finally {
                        ExpressionFlowEmitter.endSumCaseTestBindings(context, bindings);
                    }
                }
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                final var loopStart = composer.newLabel();
                final var loopExit = composer.newLabel();
                composer.labelBinding(loopStart);
                final var bindings = ExpressionFlowEmitter.beginSumCaseTestBindings(context, composer, condition);
                try {
                    emitExpr(context, composer, condition);
                    composer.ifeq(loopExit);
                    context.loopExitLabels.push(loopExit);
                    context.loopContinueLabels.push(loopStart);
                    context.loopUsingDepths.push(context.activeUsingScopes.size());
                    try {
                        emitStmt(context, composer, body);
                    } finally {
                        context.loopUsingDepths.pop();
                        context.loopExitLabels.pop();
                        context.loopContinueLabels.pop();
                    }
                } finally {
                    ExpressionFlowEmitter.endSumCaseTestBindings(context, bindings);
                }
                composer.goto_(loopStart);
                composer.labelBinding(loopExit);
            }
            case Stmt.For(Token iterationBind, Token _, Expr iterable, Stmt body) ->
                    emitFor(context, composer, iterationBind, iterable, body);
            case Stmt.Expression(Expr expression) -> {
                if (expression instanceof Expr.PropertyAssignment assignment) {
                    emitPropertyAssignment(context, composer, assignment, false);
                } else if (expression instanceof Expr.IndexAssignment assignment) {
                    emitIntrinsicOperation(context, composer, assignment, false);
                } else {
                    emitExpr(context, composer, expression);
                    emitPop(context, composer, context.lastEmittedType);
                }
            }
            case Stmt.Return(Expr expression, Token _) -> {
                if (!context.activeUsingScopes.isEmpty()) {
                    if (expression == null) {
                        emitUnitValue(context, composer);
                        context.lastEmittedType = TypeDescriptor.ofUnit();
                    } else {
                        emitExpr(context, composer, expression);
                    }
                    emitConversion(context, composer, context.lastEmittedType, context.currentReturnType);
                    final var returnKind = TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(context.currentReturnType).descriptorString());
                    final var returnValue = declareLoopLocal(context,
                            nextLoopTemporary(context, "usingReturn"), context.currentReturnType);
                    composer.storeLocal(returnKind, returnValue.lvt() + context.localSlotOffset);
                    emitUsingCleanupsToDepth(context, composer, 0);
                    composer.loadLocal(returnKind, returnValue.lvt() + context.localSlotOffset);
                    composer.return_(returnKind);
                    break;
                }
                if (expression == null) {
                    emitUnitValue(context, composer);
                    context.lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(context, composer, expression);
                }
                emitConversion(context, composer, context.lastEmittedType, context.currentReturnType);
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(context.currentReturnType).descriptorString()));
            }
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer,
                    BindingMutability mutability, _) -> {
                if (initializer != null) {
                    final ConstantDesc value = ConstantFolder.fold(initializer);
                    if (value != null) {
                        emitConstant(context, composer, value);
                        context.lastEmittedType = initializer.getType();
                    } else {
                        emitExpr(context, composer, initializer);
                    }
                    final var storedType = type instanceof InferDescriptor ? context.lastEmittedType : type;
                    emitConversion(context, composer, context.lastEmittedType, storedType);
                    final var lvt = context.symbols.declareSymbol(statement, name, storedType, mutability);
                    composer.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(storedType).descriptorString()), lvt + context.localSlotOffset);
                    context.symbols.define(name);
                } else {
                    final var lvt = context.symbols.declareSymbol(statement, name, type, mutability);
                    if (type.isNullable()) {
                        composer.aconst_null();
                        composer.storeLocal(TypeKind.REFERENCE, lvt + context.localSlotOffset);
                        context.symbols.define(name);
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private static void emitUsing(final CompilationContext context, final CodeBuilder composer,
                                  final Stmt.Using using) {
        beginScope(context);
        final var resourcesType = TypeDescriptor.arrayOf(TypeDescriptor.ofAny());
        final var resources = declareLoopLocal(context,
                nextLoopTemporary(context, "usingResources"), resourcesType);
        composer.ldc(using.resources().size());
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        composer.astore(resources.lvt() + context.localSlotOffset);

        final var acquiredCount = declareLoopLocal(
                context, nextLoopTemporary(context, "usingAcquired"), TypeDescriptor.ofInt());
        composer.iconst_0();
        composer.istore(acquiredCount.lvt() + context.localSlotOffset);
        final var primary = declareLoopLocal(context, nextLoopTemporary(context, "usingFailure"),
                TypeDescriptor.ofAny().toNullable());
        composer.aconst_null();
        composer.astore(primary.lvt() + context.localSlotOffset);

        context.activeUsingScopes.push(new CompilationContext.ActiveUsing(resources, acquiredCount));
        final var start = composer.newLabel();
        final var end = composer.newLabel();
        final var handler = composer.newLabel();
        final var normalCompletion = composer.newLabel();
        composer.labelBinding(start);

        for (int i = 0; i < using.resources().size(); i++) {
            final var declaration = using.resources().get(i).declaration();
            emitStmt(context, composer, declaration);
            final var binding = context.symbols.getSymbol(declaration.name());
            composer.aload(resources.lvt() + context.localSlotOffset);
            composer.ldc(i);
            composer.loadLocal(TypeKind.fromDescriptor(
                    TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()),
                    binding.lvt() + context.localSlotOffset);
            composer.aastore();
            composer.iload(acquiredCount.lvt() + context.localSlotOffset);
            composer.iconst_1();
            composer.iadd();
            composer.istore(acquiredCount.lvt() + context.localSlotOffset);
        }
        emitStmts(context, composer, using.body());
        composer.labelBinding(end);

        if (canCompleteNormally(using.body())) {
            final var normalFailure = emitResourceDisposal(context, composer, resources, acquiredCount, null);
            composer.aload(normalFailure.lvt() + context.localSlotOffset);
            composer.ifnull(normalCompletion);
            composer.aload(normalFailure.lvt() + context.localSlotOffset);
            composer.checkcast(ClassDesc.of("java.lang.Throwable"));
            composer.athrow();
        } else {
            composer.aconst_null();
            composer.athrow();
        }

        composer.labelBinding(handler);
        composer.astore(primary.lvt() + context.localSlotOffset);
        final var exceptionalFailure =
                emitResourceDisposal(context, composer, resources, acquiredCount, primary);
        composer.aload(exceptionalFailure.lvt() + context.localSlotOffset);
        composer.checkcast(ClassDesc.of("java.lang.Throwable"));
        composer.athrow();
        composer.labelBinding(normalCompletion);
        composer.exceptionCatch(start, end, handler, ClassDesc.of("java.lang.Throwable"));
        context.activeUsingScopes.pop();
        endScope(context);
    }

    private static boolean canCompleteNormally(final List<Stmt> statements) {
        for (final var statement : statements) {
            if (!canCompleteNormally(statement)) return false;
        }
        return true;
    }

    private static boolean canCompleteNormally(final Stmt statement) {
        return switch (statement) {
            case Stmt.Return _, Stmt.Break _, Stmt.Continue _ -> false;
            case Stmt.Block(List<Stmt> statements) -> canCompleteNormally(statements);
            case Stmt.Using using -> canCompleteNormally(using.body());
            case Stmt.If(Token _, Expr _, Stmt thenBranch, Stmt elseBranch) ->
                    elseBranch == null || canCompleteNormally(thenBranch) || canCompleteNormally(elseBranch);
            case Stmt.Expression(Expr expression) -> !(expression.getType() instanceof NeverDescriptor);
            default -> true;
        };
    }

    private static void emitUsingCleanupsToDepth(final CompilationContext context,
                                                  final CodeBuilder composer,
                                                  final int depth) {
        var remaining = context.activeUsingScopes.size() - depth;
        for (final var active : context.activeUsingScopes) {
            if (remaining-- <= 0) break;
            final var failure = emitResourceDisposal(
                    context, composer, active.resources(), active.acquiredCount(), null);
            final var noFailure = composer.newLabel();
            composer.aload(failure.lvt() + context.localSlotOffset);
            composer.ifnull(noFailure);
            composer.aload(failure.lvt() + context.localSlotOffset);
            composer.checkcast(ClassDesc.of("java.lang.Throwable"));
            composer.athrow();
            composer.labelBinding(noFailure);
        }
    }

    private static Bind emitResourceDisposal(final CompilationContext context,
                                             final CodeBuilder composer,
                                             final Bind resources,
                                             final Bind acquiredCount,
                                             final Bind primary) {
        composer.aload(resources.lvt() + context.localSlotOffset);
        composer.iload(acquiredCount.lvt() + context.localSlotOffset);
        if (primary == null) composer.aconst_null();
        else composer.aload(primary.lvt() + context.localSlotOffset);
        composer.invokestatic(ClassDesc.of("zeron.runtime.ResourceSupport"), "dispose",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Throwable"),
                        ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_int,
                        ClassDesc.of("java.lang.Throwable")));
        final var failure = declareLoopLocal(context, nextLoopTemporary(context, "usingDisposalFailure"),
                TypeDescriptor.ofAny().toNullable());
        composer.astore(failure.lvt() + context.localSlotOffset);
        composer.iconst_0();
        composer.istore(acquiredCount.lvt() + context.localSlotOffset);
        return failure;
    }

    private static void emitFor(CompilationContext context, final CodeBuilder composer,
                         final Token iterationBind,
                         final Expr iterable,
                         final Stmt body) {
        beginScope(context);
        try {
            final var iterableType = iterable.getType() instanceof ReferenceDescriptor reference
                    ? reference.baseType()
                    : iterable.getType();
            if (iterableType instanceof ArrayDescriptor) {
                emitArrayFor(context, composer, iterationBind, iterable, body);
            } else {
                emitProtocolFor(context, composer, iterationBind, iterable, body);
            }
        } finally {
            endScope(context);
        }
    }

    private static void emitProtocolFor(CompilationContext context, final CodeBuilder composer,
                                final Token iterationBind,
                                final Expr iterable,
                                final Stmt body) {
        final var elementType = context.resolution.iterationElementType(iterationBind);
        final var protocol = context.resolution.iterationProtocol(iterationBind);
        final var iteratorName = protocol.iteratorName();
        final var iterableName = protocol.iterableName();
        final var iteratorType = new ReferenceDescriptor(TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iteratorName), elementType));
        final var iterableContractType = TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iterableName), elementType);
        final var stepType = TypeDescriptor.genericOf(
            TypeDescriptor.ofName("zeron.collections.Step"), elementType);

        emitExpr(context, composer, iterable);
        emitConversion(context, composer, context.lastEmittedType, iterableContractType);
        composer.invokeinterface(ClassDesc.of(iterableName), "iterator",
            MethodTypeDesc.of(ClassDesc.of(iteratorName)));
        context.lastEmittedType = iteratorType;
        final var iteratorLocal = declareLoopLocal(context, nextLoopTemporary(context, "iterator"), iteratorType);
        composer.astore(iteratorLocal.lvt() + context.localSlotOffset);
        final var stepLocal = declareLoopLocal(context, nextLoopTemporary(context, "step"), stepType);
        final var valueLocal = declareLoopLocal(context, iterationBind, elementType);

        final var loopStart = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        composer.aload(iteratorLocal.lvt() + context.localSlotOffset);
        composer.invokeinterface(ClassDesc.of(iteratorName), "next",
                MethodTypeDesc.of(ClassDesc.of("zeron.collections.Step")));
        composer.astore(stepLocal.lvt() + context.localSlotOffset);

        composer.aload(stepLocal.lvt() + context.localSlotOffset);
        final var doneClass = ClassDesc.of("zeron.collections.Step$$sum$Done");
        composer.instanceOf(doneClass);
        composer.ifne(loopExit);

        composer.aload(stepLocal.lvt() + context.localSlotOffset);
        final var yieldClass = ClassDesc.of("zeron.collections.Step$$sum$Yield");
        composer.checkcast(yieldClass);
        composer.invokevirtual(yieldClass, "$zeron$payload$0",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), elementType);
        composer.storeLocal(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(elementType).descriptorString()),
                valueLocal.lvt() + context.localSlotOffset);

        context.loopExitLabels.push(loopExit);
        context.loopContinueLabels.push(loopStart);
        context.loopUsingDepths.push(context.activeUsingScopes.size());
        try {
            emitStmt(context, composer, body);
        } finally {
            context.loopUsingDepths.pop();
            context.loopContinueLabels.pop();
            context.loopExitLabels.pop();
        }
        composer.goto_(loopStart);
        composer.labelBinding(loopExit);
    }

    private static void emitArrayFor(CompilationContext context, final CodeBuilder composer,
                              final Token iterationBind,
                              final Expr iterable,
                              final Stmt body ){
        final var iterableType = iterable.getType() instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : iterable.getType();
        if (!(iterableType instanceof ArrayDescriptor arrayType)) {
            throw new IllegalStateException("Resolved for-loop iterable is not an array.");
        }

        emitExpr(context, composer, iterable);
        emitConversion(context, composer, context.lastEmittedType, arrayType);
        final var arrayLocal = declareLoopLocal(context, nextLoopTemporary(context, "array"), arrayType);
        composer.astore(arrayLocal.lvt() + context.localSlotOffset);

        composer.iconst_0();
        final var indexLocal = declareLoopLocal(context, nextLoopTemporary(context, "index"), TypeDescriptor.ofInt());
        composer.istore(indexLocal.lvt() + context.localSlotOffset);
        final var valueLocal = declareLoopLocal(context, iterationBind, arrayType.elementType());

        final var loopStart = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        composer.iload(indexLocal.lvt() + context.localSlotOffset);
        composer.aload(arrayLocal.lvt() + context.localSlotOffset);
        composer.arraylength();
        composer.if_icmpge(loopExit);

        composer.aload(arrayLocal.lvt() + context.localSlotOffset);
        composer.iload(indexLocal.lvt() + context.localSlotOffset);
        composer.aaload();
        emitArrayReadConversion(context, composer, arrayType.elementType());
        composer.storeLocal(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(arrayType.elementType()).descriptorString()),
                valueLocal.lvt() + context.localSlotOffset);

        final var loopContinue = composer.newLabel();
        context.loopExitLabels.push(loopExit);
        context.loopContinueLabels.push(loopContinue);
        context.loopUsingDepths.push(context.activeUsingScopes.size());
        try {
            emitStmt(context, composer, body);
        } finally {
            context.loopUsingDepths.pop();
            context.loopContinueLabels.pop();
            context.loopExitLabels.pop();
        }

        composer.labelBinding(loopContinue);
        composer.iload(indexLocal.lvt() + context.localSlotOffset);
        composer.iconst_1();
        composer.iadd();
        composer.istore(indexLocal.lvt() + context.localSlotOffset);
        composer.goto_(loopStart);
        composer.labelBinding(loopExit);
    }

    private static Bind declareLoopLocal(CompilationContext context, final Token name, final TypeDescriptor type ){
        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, name, type, BindingMutability.IMMUTABLE);
        context.symbols.define(name);
        return context.symbols.getSymbol(name);
    }

    private static Token nextLoopTemporary(CompilationContext context, final String name ){
        return new Token(TokenType.IDENTIFIER, "$for$" + name + "$" + context.loopTemporaryCount++, null, -1);
    }

}
