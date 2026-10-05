package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.lang.classfile.*;
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
                composer.goto_(context.loopExitLabels.peek());
            }
            case Stmt.Continue _ -> {
                if (context.loopContinueLabels.isEmpty()) {
                    throw new IllegalStateException("Resolved continue has no enclosing loop.");
                }
                composer.goto_(context.loopContinueLabels.peek());
            }
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope(context);
                emitStmts(context, composer, statements);
                endScope(context);
            }
            case Stmt.If(Token _, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                final var value = (Integer) ConstantFolder.fold(condition);
                if (value != null) {
                    if (value == 1) {
                        emitStmt(context, composer, thenBranch);
                    } else {
                        emitStmt(context, composer, elseBranch);
                    }
                } else {
                    emitExpr(context, composer, condition);
                    if (elseBranch != null) {
                        composer.ifThenElse(c -> emitStmt(context, c, thenBranch), c -> emitStmt(context, c, elseBranch));
                    } else {
                        composer.ifThen(c -> emitStmt(context, c, thenBranch));
                    }
                }
            }
            case Stmt.While(Token _, Expr condition, Stmt body) -> {
                final var loopStart = composer.newLabel();
                final var loopExit = composer.newLabel();
                composer.labelBinding(loopStart);
                emitExpr(context, composer, condition);
                composer.ifeq(loopExit);
                context.loopExitLabels.push(loopExit);
                context.loopContinueLabels.push(loopStart);
                try {
                    emitStmt(context, composer, body);
                } finally {
                    context.loopContinueLabels.pop();
                    context.loopExitLabels.pop();
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

    private static void emitFor(CompilationContext context, final CodeBuilder composer,
                         final Token iterationBind,
                         final Expr iterable,
                         final Stmt body ){
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
                                final Stmt body ){
        final var elementType = context.resolution.iterationElementType(iterationBind);
        final var protocol = context.resolution.iterationProtocol(iterationBind);
        final var iteratorName = protocol.iteratorName();
        final var iterableName = protocol.iterableName();
        final var iteratorType = new ReferenceDescriptor(TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iteratorName), elementType));
        final var iterableContractType = TypeDescriptor.genericOf(
            TypeDescriptor.ofName(iterableName), elementType);
        final var optionType = TypeDescriptor.genericOf(
            TypeDescriptor.ofName("zeron.lang.Option"), elementType);

        emitExpr(context, composer, iterable);
        emitConversion(context, composer, context.lastEmittedType, iterableContractType);
        composer.invokeinterface(ClassDesc.of(iterableName), "iterator",
            MethodTypeDesc.of(ClassDesc.of(iteratorName)));
        context.lastEmittedType = iteratorType;
        final var iteratorLocal = declareLoopLocal(context, nextLoopTemporary(context, "iterator"), iteratorType);
        composer.astore(iteratorLocal.lvt() + context.localSlotOffset);
        final var optionLocal = declareLoopLocal(context, nextLoopTemporary(context, "option"), optionType);
        final var valueLocal = declareLoopLocal(context, iterationBind, elementType);

        final var loopStart = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        composer.aload(iteratorLocal.lvt() + context.localSlotOffset);
        composer.invokeinterface(ClassDesc.of(iteratorName), "next",
                MethodTypeDesc.of(ClassDesc.of("zeron.lang.Option")));
        composer.astore(optionLocal.lvt() + context.localSlotOffset);

        composer.aload(optionLocal.lvt() + context.localSlotOffset);
        composer.invokeinterface(ClassDesc.of("zeron.lang.Option"), "isSome",
                MethodTypeDesc.of(ConstantDescs.CD_boolean));
        composer.ifeq(loopExit);

        composer.aload(optionLocal.lvt() + context.localSlotOffset);
        composer.aconst_null();
        composer.invokeinterface(ClassDesc.of("zeron.lang.Option"), "getOrElse",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
        emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), elementType);
        composer.storeLocal(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(elementType).descriptorString()),
                valueLocal.lvt() + context.localSlotOffset);

        context.loopExitLabels.push(loopExit);
        context.loopContinueLabels.push(loopStart);
        try {
            emitStmt(context, composer, body);
        } finally {
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
        try {
            emitStmt(context, composer, body);
        } finally {
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
