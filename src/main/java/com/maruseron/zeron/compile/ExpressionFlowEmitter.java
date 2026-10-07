package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.scan.TokenType;

import java.lang.classfile.*;
import java.lang.constant.*;
import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class ExpressionFlowEmitter {
    static void emitLogical(CompilationContext context, final CodeBuilder composer, final Expr.Logical logical ){
        final var shortCircuit = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(context, composer, logical.left);
        if (logical.operator.type() == TokenType.AND) composer.ifeq(shortCircuit);
        else composer.ifne(shortCircuit);
        emitExpr(context, composer, logical.right);
        composer.goto_(done);
        composer.labelBinding(shortCircuit);
        if (logical.operator.type() == TokenType.AND) composer.iconst_0();
        else composer.iconst_1();
        composer.labelBinding(done);
        context.lastEmittedType = TypeDescriptor.ofBoolean();
    }

    static void emitCoalesce(CompilationContext context, final CodeBuilder composer, final Expr.Coalesce coalesce ){
        if (coalesce.left.getType() instanceof NullDescriptor) {
            emitExpr(context, composer, coalesce.right);
            emitConversion(context, composer, context.lastEmittedType, coalesce.getType());
            return;
        }

        final var fallback = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(context, composer, coalesce.left);
        composer.dup();
        composer.ifnull(fallback);
        emitConversion(context, composer, context.lastEmittedType, coalesce.leftNonNullType());
        emitConversion(context, composer, coalesce.leftNonNullType(), coalesce.getType());
        composer.goto_(done);
        composer.labelBinding(fallback);
        composer.pop();
        emitExpr(context, composer, coalesce.right);
        emitConversion(context, composer, context.lastEmittedType, coalesce.getType());
        composer.labelBinding(done);
        context.lastEmittedType = coalesce.getType();
    }

    static void emitCoalesceAssignment(CompilationContext context, final CodeBuilder composer,
                                        final Expr.CoalesceAssignment assignment ){
        final var binding = context.symbols.getSymbol(assignment.name);
        final var fallback = composer.newLabel();
        final var done = composer.newLabel();
        final var local = binding.lvt() + context.localSlotOffset;
        composer.loadLocal(TypeKind.REFERENCE, local);
        composer.dup();
        composer.ifnull(fallback);
        composer.goto_(done);
        composer.labelBinding(fallback);
        composer.pop();
        emitExpr(context, composer, assignment.value);
        emitConversion(context, composer, context.lastEmittedType, binding.type());
        composer.dup();
        composer.storeLocal(TypeKind.REFERENCE, local);
        composer.labelBinding(done);
        context.lastEmittedType = assignment.getType();
    }

    static void emitIfExpression(CompilationContext context, final CodeBuilder composer, final Expr.If iff ){
        final var elseLabel = composer.newLabel();
        final var doneLabel = composer.newLabel();
        emitExpr(context, composer, iff.condition);
        composer.ifeq(elseLabel);
        emitExpr(context, composer, iff.thenExpr);
        emitConversion(context, composer, context.lastEmittedType, iff.getType());
        composer.goto_(doneLabel);
        composer.labelBinding(elseLabel);
        emitExpr(context, composer, iff.elseExpr);
        emitConversion(context, composer, context.lastEmittedType, iff.getType());
        composer.labelBinding(doneLabel);
        context.lastEmittedType = iff.getType();
    }

    static void emitMatchExpression(CompilationContext context, final CodeBuilder composer, final Expr.Match match ){
        final var done = composer.newLabel();
        emitExpr(context, composer, match.scrutinee);
        final var failure = composer.newLabel();
        composer.dup();
        composer.ifnull(failure);
        for (final var arm : match.arms) {
            if (arm.wildcard()) {
                composer.pop();
                emitExpr(context, composer, arm.expression());
                emitConversion(context, composer, context.lastEmittedType, match.getType());
                composer.goto_(done);
                break;
            }

            final var next = composer.newLabel();
            final var targetClass = TypeDescriptor.toJavaClassDesc(arm.patternType());
            composer.dup();
            composer.instanceOf(targetClass);
            composer.ifeq(next);

            beginScope(context);
            try {
                if (arm.alias() != null) {
                    final var aliasSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, arm.alias(),
                            arm.patternType(), BindingMutability.IMMUTABLE);
                    context.symbols.define(arm.alias());
                    composer.dup();
                    composer.checkcast(targetClass);
                    composer.storeLocal(TypeKind.REFERENCE, aliasSlot + context.localSlotOffset);
                }
                if (arm.namedPattern() != null) {
                    final var propertyType = arm.declaredPatternType();
                    final var erasedPropertyType = TypeSubstitution.erase(propertyType);
                    composer.dup();
                    composer.checkcast(targetClass);
                    final var getterName = Stmt.propertyGetterName(arm.namedPattern().lexeme());
                    composer.invokevirtual(targetClass, getterName,
                            toJavaMethodDescriptor(TypeDescriptor.functionOf(getterName, erasedPropertyType)));
                    context.lastEmittedType = erasedPropertyType;
                    emitConversion(context, composer, erasedPropertyType, arm.resolvedPatternType());
                    if (arm.binding() == null) {
                        composer.pop();
                    } else {
                        final var bindingSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, arm.binding(),
                                arm.resolvedPatternType(), BindingMutability.IMMUTABLE);
                        context.symbols.define(arm.binding());
                        composer.storeLocal(TypeKind.fromDescriptor(
                                TypeDescriptor.toJavaClassDesc(arm.resolvedPatternType()).descriptorString()),
                                bindingSlot + context.localSlotOffset);
                    }
                }
                if (arm.guard() != null) {
                    emitExpr(context, composer, arm.guard());
                    composer.ifeq(next);
                }
                composer.pop();
                emitExpr(context, composer, arm.expression());
                emitConversion(context, composer, context.lastEmittedType, match.getType());
                composer.goto_(done);
            } finally {
                endScope(context);
            }
            composer.labelBinding(next);
        }

        composer.labelBinding(failure);
        composer.pop();
        final var exception = ClassDesc.of("java.lang.IllegalStateException");
        composer.new_(exception);
        composer.dup();
        composer.ldc("No match case accepted the runtime value.");
        composer.invokespecial(exception, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
        composer.athrow();
        composer.labelBinding(done);
        context.lastEmittedType = match.getType();
    }

    static void emitRaise(final CompilationContext context, final CodeBuilder composer, final Expr.Raise raise) {
        final var carrier = ClassDesc.of("zeron.runtime.RaisedEffect");
        composer.new_(carrier);
        composer.dup();
        emitExpr(context, composer, raise.effect);
        emitConversion(context, composer, context.lastEmittedType, TypeDescriptor.ofAny());
        composer.invokespecial(carrier, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
        composer.athrow();
        context.lastEmittedType = TypeDescriptor.ofNever();
    }

    static void emitHandle(final CompilationContext context, final CodeBuilder composer, final Expr.Handle handle) {
        final var carrier = ClassDesc.of("zeron.runtime.RaisedEffect");
        final var start = composer.newLabel();
        final var end = composer.newLabel();
        final var handler = composer.newLabel();
        final var done = composer.newLabel();

        composer.labelBinding(start);
        emitExpr(context, composer, handle.expression);
        emitConversion(context, composer, context.lastEmittedType, handle.getType());
        composer.goto_(done);
        composer.labelBinding(end);
        composer.labelBinding(handler);
        final var exceptionSlot = composer.allocateLocal(TypeKind.REFERENCE);
        final var payloadSlot = composer.allocateLocal(TypeKind.REFERENCE);
        composer.storeLocal(TypeKind.REFERENCE, exceptionSlot);
        composer.loadLocal(TypeKind.REFERENCE, exceptionSlot);
        composer.invokevirtual(carrier, "payload", MethodTypeDesc.of(ConstantDescs.CD_Object));
        composer.storeLocal(TypeKind.REFERENCE, payloadSlot);

        for (final var arm : handle.arms) {
            final var next = composer.newLabel();
            final var effectClass = TypeDescriptor.toJavaClassDesc(arm.resolvedEffectType());
            composer.loadLocal(TypeKind.REFERENCE, payloadSlot);
            composer.instanceOf(effectClass);
            composer.ifeq(next);
            if (arm.alias() != null || arm.binding() != null) {
                beginScope(context);
                try {
                    if (arm.alias() != null) {
                        final var aliasSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, arm.alias(),
                                arm.resolvedEffectType(), BindingMutability.IMMUTABLE);
                        context.symbols.define(arm.alias());
                        composer.loadLocal(TypeKind.REFERENCE, payloadSlot);
                        composer.checkcast(effectClass);
                        composer.storeLocal(TypeKind.REFERENCE, aliasSlot + context.localSlotOffset);
                    }
                    if (arm.namedPattern() != null) {
                        final var bindingSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, arm.binding(),
                                arm.resolvedPatternType(), BindingMutability.IMMUTABLE);
                        context.symbols.define(arm.binding());
                        composer.loadLocal(TypeKind.REFERENCE, payloadSlot);
                        composer.checkcast(effectClass);
                        final var erasedType = TypeSubstitution.erase(arm.declaredPatternType());
                        composer.invokevirtual(effectClass, Stmt.propertyGetterName(arm.namedPattern().lexeme()),
                                toJavaMethodDescriptor(TypeDescriptor.functionOf(
                                        Stmt.propertyGetterName(arm.namedPattern().lexeme()), erasedType)));
                        emitConversion(context, composer, erasedType, arm.resolvedPatternType());
                        composer.storeLocal(TypeKind.fromDescriptor(
                                TypeDescriptor.toJavaClassDesc(arm.resolvedPatternType()).descriptorString()),
                                bindingSlot + context.localSlotOffset);
                    }
                    emitExpr(context, composer, arm.expression());
                    emitConversion(context, composer, context.lastEmittedType, handle.getType());
                } finally {
                    endScope(context);
                }
            } else {
                emitExpr(context, composer, arm.expression());
                emitConversion(context, composer, context.lastEmittedType, handle.getType());
            }
            composer.goto_(done);
            composer.labelBinding(next);
        }

        composer.loadLocal(TypeKind.REFERENCE, exceptionSlot);
        composer.athrow();
        composer.labelBinding(done);
        composer.exceptionCatch(start, end, handler, carrier);
        context.lastEmittedType = handle.getType();
    }

    static void emitTypeTest(CompilationContext context, final CodeBuilder composer, final Expr.TypeTest test ){
        emitExpr(context, composer, test.value);
        emitBox(context, composer, context.lastEmittedType);
        composer.instanceOf(runtimeTypeTestClass(context, test.targetType));
        context.lastEmittedType = TypeDescriptor.ofBoolean();
    }

    static void emitCast(CompilationContext context, final CodeBuilder composer, final Expr.Cast cast ){
        emitExpr(context, composer, cast.value);
        emitBox(context, composer, context.lastEmittedType);
        final var targetClass = runtimeTypeTestClass(context, cast.targetType);
        if (cast.safe) {
            final var failed = composer.newLabel();
            final var done = composer.newLabel();
            composer.dup();
            composer.instanceOf(targetClass);
            composer.ifeq(failed);
            composer.checkcast(targetClass);
            composer.goto_(done);
            composer.labelBinding(failed);
            composer.pop();
            composer.aconst_null();
            composer.labelBinding(done);
            context.lastEmittedType = cast.getType();
            return;
        }

        if (cast.targetType instanceof IntDescriptor
                || cast.targetType instanceof FloatDescriptor
                || cast.targetType instanceof BooleanDescriptor) {
            emitUnboxOrCast(context, composer, cast.targetType);
        } else {
            composer.checkcast(targetClass);
        }
        context.lastEmittedType = cast.getType();
    }

    private static ClassDesc runtimeTypeTestClass(CompilationContext context, final TypeDescriptor type ){
        return switch (type) {
            case IntDescriptor _ -> ConstantDescs.CD_Integer;
            case FloatDescriptor _ -> ConstantDescs.CD_Double;
            case BooleanDescriptor _ -> ConstantDescs.CD_Boolean;
            case AnyDescriptor _ -> ConstantDescs.CD_Object;
            case UnitDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case StringDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case NominalDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            default -> throw new IllegalStateException("Unsupported runtime type-test target: " + type);
        };
    }

    static void emitComparison(CompilationContext context, final CodeBuilder composer, final Expr.Binary binary ){
        final var operandDescriptor = TypeDescriptor.toJavaClassDesc(binary.left.getType()).descriptorString();
        final var matched = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(context, composer, binary.left);
        emitExpr(context, composer, binary.right);

        if (binary.operator.type() == TokenType.EQUAL_EQUAL_EQUAL) {
            composer.if_acmpeq(matched);
        } else switch (operandDescriptor) {
            case "I", "Z" -> branchIntegerComparison(context, composer, binary.operator.type(), matched);
            case "D" -> {
                switch (binary.operator.type()) {
                    case LESS, LESS_EQUAL -> composer.dcmpg();
                    default -> composer.dcmpl();
                }
                switch (binary.operator.type()) {
                    case EQUAL_EQUAL -> composer.ifeq(matched);
                    case BANG_EQUAL -> composer.ifne(matched);
                    case GREATER -> composer.ifgt(matched);
                    case GREATER_EQUAL -> composer.ifge(matched);
                    case LESS -> composer.iflt(matched);
                    case LESS_EQUAL -> composer.ifle(matched);
                    default -> throw new IllegalStateException("Unsupported comparison operator.");
                }
            }
            default -> {
                if (binary.operator.type() != TokenType.EQUAL_EQUAL
                        && binary.operator.type() != TokenType.BANG_EQUAL) {
                    throw new IllegalStateException("Relational comparison requires a numeric type.");
                }
                composer.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean,
                                ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                if (binary.operator.type() == TokenType.EQUAL_EQUAL) composer.ifne(matched);
                else composer.ifeq(matched);
            }
        }

        composer.iconst_0();
        composer.goto_(done);
        composer.labelBinding(matched);
        composer.iconst_1();
        composer.labelBinding(done);
        context.lastEmittedType = TypeDescriptor.ofBoolean();
    }

    private static void branchIntegerComparison(CompilationContext context, final CodeBuilder composer,
                                        final TokenType operator,
                                        final Label target ){
        switch (operator) {
            case EQUAL_EQUAL -> composer.if_icmpeq(target);
            case BANG_EQUAL -> composer.if_icmpne(target);
            case GREATER -> composer.if_icmpgt(target);
            case GREATER_EQUAL -> composer.if_icmpge(target);
            case LESS -> composer.if_icmplt(target);
            case LESS_EQUAL -> composer.if_icmple(target);
            default -> throw new IllegalStateException("Unsupported comparison operator.");
        }
    }

}
