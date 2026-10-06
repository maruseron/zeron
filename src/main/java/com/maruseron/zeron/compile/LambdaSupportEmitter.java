package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.util.*;

import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class LambdaSupportEmitter {
    static void emitFunctionReference(final CompilationContext context,
                                              final CodeBuilder composer,
                                              final Expr.Variable reference) {
        final var functionType = reference.specializedFunctionType();
        final var generatedInterface = TypeDescriptor.toJavaClassDesc(functionType);
        final var samMethodType = toJavaMethodDescriptor(functionType);
        final var helper = context.lambdaPlan.functionReference(reference);
        if (helper == null) {
            throw new IllegalStateException("Missing specialization bridge for "
                + reference.resolvedFunctionName());
        }
        final var metafactoryType = MethodTypeDesc.of(
            ClassDesc.of("java.lang.invoke.CallSite"),
            ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
            ConstantDescs.CD_String,
            ClassDesc.of("java.lang.invoke.MethodType"),
            ClassDesc.of("java.lang.invoke.MethodType"),
            ClassDesc.of("java.lang.invoke.MethodHandle"),
            ClassDesc.of("java.lang.invoke.MethodType"));
        final var metafactory = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            metafactoryType.descriptorString());
        final var implementation = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of(context.mainClassName),
            helper.helperName(),
            toJavaMethodDescriptor(helper.targetType()).descriptorString());
        composer.invokedynamic(DynamicCallSiteDesc.of(
            metafactory,
            "invoke",
            MethodTypeDesc.of(generatedInterface),
            samMethodType,
            implementation,
            samMethodType));
        context.lastEmittedType = functionType;
    }

    static void emitFunctionReferenceImplementation(final CompilationContext context,
                                                             final CodeBuilder composer,
                                                             final LambdaCompilationPlan.FunctionReference reference) {
        final var sourceType = reference.sourceType();
        final var targetType = reference.targetType();
        var slot = 0;
        for (int i = 0; i < targetType.arity(); i++) {
            final var parameterType = targetType.parameters().get(i);
            NominalTypeEmitter.loadLocal(context, composer, slot, parameterType);
            emitConversion(context, composer, parameterType, sourceType.parameters().get(i));
            slot += TypeDescriptor.toJavaClassDesc(parameterType).descriptorString().equals("D") ? 2 : 1;
        }
        final var owner = reference.declaration() == null
                ? context.metadata.functionOwner(reference.functionName())
                : context.metadata.functionOwner(reference.declaration());
        if (owner == null) {
            throw new IllegalStateException("Missing JVM owner for " + reference.functionName());
        }
        composer.invokestatic(ClassDesc.of(owner),
            reference.functionName().substring(reference.functionName().lastIndexOf('.') + 1),
            toJavaMethodDescriptor(sourceType));
        emitConversion(context, composer, sourceType.returnType(), targetType.returnType());
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetType.returnType()).descriptorString()));
        }


    static void emitLambdaImplementation(CompilationContext context, final CodeBuilder composer, final Expr.Lambda lambda ){
        if (!(lambda.getType() instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Lambda has no resolved function type");
        }

        final var captures = context.lambdaPlan.captures(lambda);
        final var allParameters = new ArrayList<TypeDescriptor>();
        allParameters.addAll(context.lambdaPlan.captureTypes(lambda));
        for (final var parameter : functionType.parameters()) {
            allParameters.add(parameter);
        }
        final var previousOffset = context.localSlotOffset;
        final var previousReturnType = context.currentReturnType;
        final var previousLambdaImplementation = context.emittingLambdaImplementation;
        beginScope(context);
        context.localSlotOffset = 0;
        context.currentReturnType = functionType.returnType();
        context.emittingLambdaImplementation = true;
        try {
            for (int i = 0; i < captures.size(); i++) {
                final var capture = captures.get(i);
                final var captureType = context.lambdaPlan.captureTypes(lambda).get(i);
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, capture, captureType, BindingMutability.IMMUTABLE);
                context.symbols.define(capture);
            }
            for (int i = 0; i < lambda.params.size(); i++) {
                final var param = lambda.params.get(i);
                final var parameterType = functionType.parameters().get(i);
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, param, parameterType, BindingMutability.IMMUTABLE);
                context.symbols.define(param);
            }

            if (lambda.body.size() == 1 && lambda.body.getFirst() instanceof Stmt.Return ret) {
                if (ret.value() == null) {
                    emitUnitValue(context, composer);
                    context.lastEmittedType = TypeDescriptor.ofUnit();
                } else {
                    emitExpr(context, composer, ret.value());
                }
                emitConversion(context, composer, context.lastEmittedType, functionType.returnType());
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            } else {
                StatementEmitter.emitStmts(context, composer, lambda.body);
                if (functionType.returnType() instanceof UnitDescriptor) {
                    emitUnitValue(context, composer);
                }
                composer.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(functionType.returnType()).descriptorString()));
            }
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
            context.currentReturnType = previousReturnType;
            context.emittingLambdaImplementation = previousLambdaImplementation;
        }
    }


    static void emitNullableFunctionAdapterImplementation(CompilationContext context, final CodeBuilder composer,
                                                           final FunctionDescriptor sourceType,
                                                           final FunctionDescriptor targetType ){
        final var nullValue = composer.newLabel();
        composer.aload(0);
        composer.invokestatic(ClassDesc.of("java.util.Objects"), "isNull",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
        composer.ifne(nullValue);
        composer.aload(0);
        emitFunctionAdapter(context, composer, sourceType, targetType);
        composer.return_(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(targetType).descriptorString()));
        composer.labelBinding(nullValue);
        composer.aconst_null();
        composer.return_(TypeKind.fromDescriptor(
                TypeDescriptor.toJavaClassDesc(targetType).descriptorString()));
    }

    static void emitFunctionAdapter(CompilationContext context, final CodeBuilder composer,
                                    final FunctionDescriptor sourceType,
                                    final FunctionDescriptor targetType ){
        final var sourceInterface = TypeDescriptor.toJavaClassDesc(sourceType);
        final var targetInterface = TypeDescriptor.toJavaClassDesc(targetType);
        final var targetMethodType = toJavaMethodDescriptor(targetType);
        final var adapterName = context.lambdaPlan.adapterName(sourceType, targetType);
        if (adapterName == null) {
            throw new IllegalStateException("Missing function adapter for " + sourceType + " to " + targetType);
        }
        final var implementationParameters = new ArrayList<TypeDescriptor>();
        implementationParameters.add(sourceType);
        implementationParameters.addAll(targetType.parameters());
        final var metafactoryType = MethodTypeDesc.of(
                ClassDesc.of("java.lang.invoke.CallSite"),
                ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                ConstantDescs.CD_String,
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodHandle"),
                ClassDesc.of("java.lang.invoke.MethodType"));
        final var metafactory = MethodHandleDesc.of(
                DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
                "metafactory",
                metafactoryType.descriptorString());
        final var implementation = MethodHandleDesc.of(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of(context.mainClassName),
            adapterName,
            toJavaMethodDescriptor(targetType, implementationParameters).descriptorString());
        composer.invokedynamic(DynamicCallSiteDesc.of(
                metafactory,
                "invoke",
                MethodTypeDesc.of(targetInterface, sourceInterface),
                targetMethodType,
                implementation,
            targetMethodType));
    }

    static void emitFunctionAdapterImplementation(final CompilationContext context,
                                                           final CodeBuilder composer,
                                                           final FunctionDescriptor sourceType,
                                                           final FunctionDescriptor targetType) {
        composer.aload(0);
        var slot = 1;
        for (int i = 0; i < targetType.arity(); i++) {
            final var targetParameter = targetType.parameters().get(i);
            NominalTypeEmitter.loadLocal(context, composer, slot, targetParameter);
            emitConversion(context, composer, targetParameter, sourceType.parameters().get(i));
            slot += TypeDescriptor.toJavaClassDesc(targetParameter).descriptorString().equals("D") ? 2 : 1;
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(sourceType), "invoke",
            toJavaMethodDescriptor(sourceType));
        emitConversion(context, composer, sourceType.returnType(), targetType.returnType());
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetType.returnType()).descriptorString()));
    }

}
