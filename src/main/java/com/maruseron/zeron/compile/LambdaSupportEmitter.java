package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

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
            functionReferenceMethodType(helper).descriptorString());
        emitEvidenceArguments(context, composer, helper.evidenceArguments());
        emitReifiedArguments(context, composer, helper.reifiedArguments());
        final var capturedEvidence = java.util.Collections.nCopies(
                helper.evidenceArguments().size(), ClassDesc.of("java.lang.invoke.MethodHandle"))
                .toArray(ClassDesc[]::new);
        final var capturedReified = java.util.Collections.nCopies(
                helper.reifiedArguments().size(), RuntimeTypeDescriptorEmitter.DESCRIPTOR_CLASS)
                .toArray(ClassDesc[]::new);
        final var captured = new ClassDesc[capturedEvidence.length + capturedReified.length];
        System.arraycopy(capturedEvidence, 0, captured, 0, capturedEvidence.length);
        System.arraycopy(capturedReified, 0, captured, capturedEvidence.length, capturedReified.length);
        composer.invokedynamic(DynamicCallSiteDesc.of(
            metafactory,
            "invoke",
            MethodTypeDesc.of(generatedInterface, captured),
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
        var slot = reference.evidenceArguments().size() + reference.reifiedArguments().size();
        for (int i = 0; i < targetType.arity(); i++) {
            final var parameterType = targetType.parameters().get(i);
            NominalTypeEmitter.loadLocal(context, composer, slot, parameterType);
            emitConversion(context, composer, parameterType, sourceType.parameters().get(i));
            final var descriptor = TypeDescriptor.toJavaClassDesc(parameterType).descriptorString();
            slot += descriptor.equals("D") || descriptor.equals("J") ? 2 : 1;
        }
        final var owner = reference.declaration() == null
                ? context.metadata.functionOwner(reference.functionName())
                : context.metadata.functionOwner(reference.declaration());
        if (owner == null) {
            throw new IllegalStateException("Missing JVM owner for " + reference.functionName());
        }
        for (int index = 0; index < reference.evidenceArguments().size(); index++) {
            composer.aload(index);
        }
        var reifiedSlot = reference.evidenceArguments().size();
        for (int index = 0; index < reference.reifiedArguments().size(); index++) {
            composer.aload(reifiedSlot);
            reifiedSlot++;
        }
        final var targetEvidenceBase = slot;
        for (int index = 0; index < targetType.hiddenEvidenceCount(); index++) {
            composer.aload(targetEvidenceBase + index);
        }
        final var targetDescriptorBase = targetEvidenceBase + targetType.hiddenEvidenceCount();
        for (int index = 0; index < targetType.hiddenDescriptorCount(); index++) {
            composer.aload(targetDescriptorBase + index);
        }
        composer.invokestatic(ClassDesc.of(owner),
            reference.functionName().substring(reference.functionName().lastIndexOf('.') + 1),
            withHiddenParameters(toJavaMethodDescriptor(sourceType),
                    reference.evidenceArguments().size() + targetType.hiddenEvidenceCount(),
                    reference.reifiedArguments().size() + targetType.hiddenDescriptorCount()));
        emitConversion(context, composer, sourceType.returnType(), targetType.returnType());
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetType.returnType()).descriptorString()));
        }

    static MethodTypeDesc functionReferenceMethodType(
            final LambdaCompilationPlan.FunctionReference reference) {
        final var parameters = new ArrayList<TypeDescriptor>();
        for (int index = 0; index < reference.evidenceArguments().size(); index++) {
            parameters.add(TypeDescriptor.ofName("java.lang.invoke.MethodHandle"));
        }
        for (int index = 0; index < reference.reifiedArguments().size(); index++) {
            parameters.add(RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE);
        }
        parameters.addAll(reference.targetType().parameters());
        for (int index = 0; index < reference.targetType().hiddenEvidenceCount(); index++) {
            parameters.add(TypeDescriptor.ofName("java.lang.invoke.MethodHandle"));
        }
        for (int index = 0; index < reference.targetType().hiddenDescriptorCount(); index++) {
            parameters.add(RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE);
        }
        return toJavaMethodDescriptor(reference.targetType(), parameters);
    }


    static void emitLambdaImplementation(CompilationContext context, final CodeBuilder composer, final Expr.Lambda lambda) {
        if (!(lambda.getType() instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Lambda has no resolved function type");
        }

        final var captures = context.lambdaPlan.captures(lambda);
        final var evidenceFields = BytecodeEmitter.evidenceFields(
                context, functionType.typeParameters(), "lambda",
                context.lambdaPlan.lambdaMethodName(lambda));

        final var methodHandleType = TypeDescriptor.ofName("java.lang.invoke.MethodHandle");
        final var allParameters = new ArrayList<TypeDescriptor>();
        allParameters.addAll(context.lambdaPlan.captureTypes(lambda));
        for (final var parameter : functionType.invocationParameters()) {
            allParameters.add(parameter);
        }
        allParameters.addAll(Collections.nCopies(evidenceFields.size(), methodHandleType));
        allParameters.addAll(Collections.nCopies(functionType.hiddenDescriptorCount(),
                RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE));
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
            if (functionType.receiverType() != null) {
                final var receiverToken = lambda.receiverSymbolToken();
                if (receiverToken == null) {
                    throw new IllegalStateException("Receiver lambda has no receiver symbol.");
                }
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR,
                        receiverToken, functionType.receiverType(), BindingMutability.IMMUTABLE);
                context.symbols.define(receiverToken);
            }
            final var lambdaParameters = lambda.parameterTokens();
            for (int i = 0; i < lambdaParameters.size(); i++) {
                final var param = lambdaParameters.get(i);
                final var parameterType = functionType.parameters().get(i);
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, param, parameterType, BindingMutability.IMMUTABLE);
                context.symbols.define(param);
            }
            for (final var evidenceField : evidenceFields) {
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, evidenceField.token(),
                        methodHandleType, BindingMutability.IMMUTABLE);
                context.symbols.define(evidenceField.token());
            }
            for (final var parameter : functionType.typeParameters()) {
                if (!parameter.isReified()) continue;
                final var token = Expr.reifiedToken(parameter);
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, token,
                        RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE, BindingMutability.IMMUTABLE);
                context.symbols.define(token);
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

    static void emitLambdaTemplateFactory(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final Expr.Lambda lambda) {
        final var functionType = (FunctionDescriptor) lambda.getType();
        final var erasedFunctionType = (FunctionDescriptor) TypeSubstitution.erase(functionType);
        final var captures = context.lambdaPlan.captureTypes(lambda);
        final var evidenceCount = BytecodeEmitter.evidenceFields(
                context, functionType.typeParameters(), "lambda",
                context.lambdaPlan.lambdaMethodName(lambda)).size();
        final var methodHandleType = ClassDesc.of("java.lang.invoke.MethodHandle");
        final var capturedTypes = new ArrayList<TypeDescriptor>(captures);
        capturedTypes.addAll(Collections.nCopies(evidenceCount,
                TypeDescriptor.ofName("java.lang.invoke.MethodHandle")));

        final var invokedParameters = new ArrayList<ClassDesc>();
        for (int index = 0; index < captures.size(); index++) {
            composer.aload(0);
            composer.loadConstant(Integer.valueOf(index));
            composer.aaload();
            BytecodeEmitter.emitConversion(context, composer, TypeDescriptor.ofAny(), captures.get(index));
            invokedParameters.add(TypeDescriptor.toJavaClassDesc(captures.get(index)));
        }
        for (int index = 0; index < evidenceCount; index++) {
            composer.aload(1);
            composer.loadConstant(Integer.valueOf(index));
            composer.aaload();
            composer.checkcast(methodHandleType);
            invokedParameters.add(methodHandleType);
        }

        final var implementationParameters = new ArrayList<TypeDescriptor>(capturedTypes);
        implementationParameters.addAll(erasedFunctionType.invocationParameters());
        final var implementation = MethodHandleDesc.of(
                DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of(context.mainClassName),
                context.lambdaPlan.lambdaMethodName(lambda),
                toJavaMethodDescriptor(erasedFunctionType, implementationParameters).descriptorString());
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
        composer.invokedynamic(DynamicCallSiteDesc.of(
                metafactory,
                "invoke",
                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedFunctionType), invokedParameters),
                toJavaMethodDescriptor(erasedFunctionType),
                implementation,
                toJavaMethodDescriptor(erasedFunctionType)));
        composer.areturn();
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
        implementationParameters.addAll(targetType.invocationParameters());
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
        final var targetParameters = targetType.invocationParameters();
        final var sourceParameters = sourceType.invocationParameters();
        for (int i = 0; i < targetParameters.size(); i++) {
            final var targetParameter = targetParameters.get(i);
            NominalTypeEmitter.loadLocal(context, composer, slot, targetParameter);
            emitConversion(context, composer, targetParameter, sourceParameters.get(i));
            final var descriptor = TypeDescriptor.toJavaClassDesc(targetParameter).descriptorString();
            slot += descriptor.equals("D") || descriptor.equals("J") ? 2 : 1;
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(sourceType), "invoke",
            toJavaMethodDescriptor(sourceType));
        final var sourceReturn = sourceType.returnType();
        final var targetReturn = targetType.returnType();
        if (sourceReturn instanceof NeverDescriptor) {
            if (!(targetReturn instanceof NeverDescriptor)) {
                composer.aconst_null();
            }
        } else if (targetReturn instanceof NeverDescriptor) {
            emitPop(context, composer, sourceReturn);
        } else {
            emitConversion(context, composer, sourceReturn, targetReturn);
        }
        composer.return_(TypeKind.fromDescriptor(
            TypeDescriptor.toJavaClassDesc(targetReturn).descriptorString()));
    }

}
