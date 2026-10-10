package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class ExpressionFlowEmitter {
    static void emitLogical(CompilationContext context, final CodeBuilder composer, final Expr.Logical logical) {
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

    static void emitCoalesce(CompilationContext context, final CodeBuilder composer, final Expr.Coalesce coalesce) {
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
                                        final Expr.CoalesceAssignment assignment) {
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

    static void emitIfExpression(CompilationContext context, final CodeBuilder composer, final Expr.If iff) {
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

    static void emitForExpression(CompilationContext context, final CodeBuilder composer, final Expr.Pipeline pipeline) {
        beginScope(context);
        try {
            if (pipeline.isTerminal) {
                emitCollectedPipeline(context, composer, pipeline);
            } else {
                emitLazyPipeline(context, composer, pipeline);
            }
        } finally {
            endScope(context);
        }
    }

    private static final ClassDesc STREAM_CLASS = ClassDesc.of("zeron.collections.Stream");
    private static final ClassDesc ITERABLE_CLASS = ClassDesc.of("zeron.collections.Iterable");
    private static final ClassDesc ITERATOR_CLASS = ClassDesc.of("zeron.collections.Iterator");
    private static final ClassDesc OPTION_CLASS = ClassDesc.of("zeron.lang.Option");
    private static final ClassDesc SINK_CLASS = ClassDesc.of("zeron.collections.Sink");

    private record PipelineCapture(Token token, TypeDescriptor type, Bind value) {}

    private static void emitLazyPipeline(final CompilationContext context, final CodeBuilder composer,
                                         final Expr.Pipeline pipeline) {
        final var sourceType = pipeline.source.getType() instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : pipeline.source.getType();
        emitExpr(context, composer, pipeline.source);
        if (sourceType instanceof ArrayDescriptor arrayType) {
            final var erasedArrayType = TypeSubstitution.erase(arrayType);
            composer.invokestatic(STREAM_CLASS, "fromArray",
                    MethodTypeDesc.of(STREAM_CLASS, TypeDescriptor.toJavaClassDesc(erasedArrayType)));
        } else {
            final var iterableType = TypeDescriptor.genericOf(
                    TypeDescriptor.ofName("zeron.collections.Iterable"), pipeline.sourceElementType());
            emitConversion(context, composer, context.lastEmittedType, iterableType);
            composer.invokestatic(STREAM_CLASS, "from",
                    MethodTypeDesc.of(STREAM_CLASS, ITERABLE_CLASS));
        }

        var elementType = pipeline.sourceElementType();
        for (final var stage : pipeline.stages) {
            final Expr.Lambda lambda;
            final String methodName;
            final TypeDescriptor resultElementType;
            switch (stage) {
                case Expr.PipelineStage.Map map -> {
                    lambda = map.lambda();
                    methodName = "map";
                    resultElementType = map.type();
                }
                case Expr.PipelineStage.Filter filter -> {
                    lambda = filter.lambda();
                    methodName = "filter";
                    resultElementType = elementType;
                }
                case Expr.PipelineStage.FlatMap flatMap -> {
                    lambda = flatMap.lambda();
                    methodName = "flatMap";
                    resultElementType = flatMap.type();
                }
                case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                        "A lazy for-expression cannot contain a collect stage.");
            }
            final var functionType = (FunctionDescriptor) lambda.getType();
            final var invocationFunctionType = TypeDescriptor.functionOf("", methodName.equals("map")
                    ? TypeDescriptor.ofName("java.lang.Object")
                    : methodName.equals("filter") ? TypeDescriptor.ofBoolean()
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.collections.Iterable"),
                    TypeDescriptor.ofAny()), TypeDescriptor.ofName("java.lang.Object"));
            emitExpr(context, composer, lambda);
            emitConversion(context, composer, functionType, invocationFunctionType);
            composer.invokevirtual(STREAM_CLASS, methodName,
                    MethodTypeDesc.of(STREAM_CLASS, TypeDescriptor.toJavaClassDesc(invocationFunctionType)));
            elementType = resultElementType;
        }
        context.lastEmittedType = pipeline.getType();
    }

    private static void emitCollectedPipeline(final CompilationContext context, final CodeBuilder composer,
                                              final Expr.Pipeline pipeline) {
        final var sourceType = pipeline.source.getType() instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : pipeline.source.getType();
        final var isArray = sourceType instanceof ArrayDescriptor;
        final var sourceElementType = pipeline.sourceElementType();
        final var initialValueToken = nextLoopTemporary(context, "value");
        final var initialValue = declareLoopLocal(context, initialValueToken, sourceElementType);
        final Bind arrayLocal;
        final Bind indexLocal;
        final Bind iteratorLocal;
        final Bind optionLocal;

        if (isArray) {
            final var arrayType = (ArrayDescriptor) sourceType;
            emitExpr(context, composer, pipeline.source);
            emitConversion(context, composer, context.lastEmittedType, arrayType);
            arrayLocal = declareLoopLocal(context, nextLoopTemporary(context, "array"), arrayType);
            composer.astore(arrayLocal.lvt() + context.localSlotOffset);
            composer.iconst_0();
            indexLocal = declareLoopLocal(context, nextLoopTemporary(context, "index"), TypeDescriptor.ofInt());
            composer.istore(indexLocal.lvt() + context.localSlotOffset);
            iteratorLocal = null;
            optionLocal = null;
        } else {
            final var iterableType = TypeDescriptor.genericOf(
                    TypeDescriptor.ofName("zeron.collections.Iterable"), sourceElementType);
            emitExpr(context, composer, pipeline.source);
            emitConversion(context, composer, context.lastEmittedType, iterableType);
            composer.invokeinterface(ITERABLE_CLASS, "iterator", MethodTypeDesc.of(ITERATOR_CLASS));
            iteratorLocal = declareLoopLocal(context, nextLoopTemporary(context, "iterator"),
                    new ReferenceDescriptor(TypeDescriptor.genericOf(
                            TypeDescriptor.ofName("zeron.collections.Iterator"), sourceElementType)));
            composer.astore(iteratorLocal.lvt() + context.localSlotOffset);
            optionLocal = declareLoopLocal(context, nextLoopTemporary(context, "option"),
                    TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"), sourceElementType));
            arrayLocal = null;
            indexLocal = null;
        }

        final var collect = (Expr.PipelineStage.Collect) pipeline.stages.getLast();
        emitExpr(context, composer, collect.expression());
        final var sinkLocal = declareLoopLocal(context, nextLoopTemporary(context, "sink"),
                collect.expression().getType());
        composer.astore(sinkLocal.lvt() + context.localSlotOffset);

        final var stageCaptures = new IdentityHashMap<Expr.PipelineStage, List<PipelineCapture>>();
        for (final var stage : pipeline.stages) {
            if (stage instanceof Expr.PipelineStage.Collect) continue;
            final var lambda = switch (stage) {
                case Expr.PipelineStage.Map map -> map.lambda();
                case Expr.PipelineStage.FlatMap flatMap -> flatMap.lambda();
                case Expr.PipelineStage.Filter filter -> filter.lambda();
                case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                        "A collect stage does not have a callback.");
            };
            final var captures = new ArrayList<PipelineCapture>();
            final var captureTokens = context.lambdaPlan.captures(lambda);
            final var captureTypes = context.lambdaPlan.captureTypes(lambda);
            for (int i = 0; i < captureTokens.size(); i++) {
                final var token = captureTokens.get(i);
                final var captureType = captureTypes.get(i);
                emitVariable(context, composer, token);
                emitConversion(context, composer, context.lastEmittedType, captureType);
                final var value = declareLoopLocal(context, nextLoopTemporary(context, "capture"), captureType);
                storePipelineValue(context, composer, value, captureType);
                captures.add(new PipelineCapture(token, captureType, value));
            }
            stageCaptures.put(stage, List.copyOf(captures));
        }

        final var loopStart = composer.newLabel();
        final var loopContinue = composer.newLabel();
        final var loopExit = composer.newLabel();
        composer.labelBinding(loopStart);
        if (isArray) {
            final var arrayType = (ArrayDescriptor) sourceType;
            composer.iload(indexLocal.lvt() + context.localSlotOffset);
            composer.aload(arrayLocal.lvt() + context.localSlotOffset);
            composer.arraylength();
            composer.if_icmpge(loopExit);
            composer.aload(arrayLocal.lvt() + context.localSlotOffset);
            composer.iload(indexLocal.lvt() + context.localSlotOffset);
            composer.aaload();
            emitArrayReadConversion(context, composer, arrayType.elementType());
            storePipelineValue(context, composer, initialValue, sourceElementType);
        } else {
            composer.aload(iteratorLocal.lvt() + context.localSlotOffset);
            composer.invokeinterface(ITERATOR_CLASS, "next", MethodTypeDesc.of(OPTION_CLASS));
            composer.astore(optionLocal.lvt() + context.localSlotOffset);
            composer.aload(optionLocal.lvt() + context.localSlotOffset);
            composer.invokeinterface(OPTION_CLASS, "isSome", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            composer.ifeq(loopExit);
            composer.aload(optionLocal.lvt() + context.localSlotOffset);
            composer.aconst_null();
            composer.invokeinterface(OPTION_CLASS, "getOrElse",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
            emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), sourceElementType);
            storePipelineValue(context, composer, initialValue, sourceElementType);
        }

        emitCollectedStages(context, composer, pipeline, 0, initialValue, sourceElementType,
                stageCaptures, sinkLocal, loopContinue);

        composer.labelBinding(loopContinue);
        if (isArray) {
            composer.iload(indexLocal.lvt() + context.localSlotOffset);
            composer.iconst_1();
            composer.iadd();
            composer.istore(indexLocal.lvt() + context.localSlotOffset);
        }
        composer.goto_(loopStart);
        composer.labelBinding(loopExit);
        composer.aload(sinkLocal.lvt() + context.localSlotOffset);
        context.lastEmittedType = pipeline.getType();
    }

    private static void emitCollectedStages(final CompilationContext context, final CodeBuilder composer,
                                            final Expr.Pipeline pipeline, final int stageIndex,
                                            final Bind currentValue, final TypeDescriptor currentType,
                                            final IdentityHashMap<Expr.PipelineStage, List<PipelineCapture>> stageCaptures,
                                            final Bind sinkLocal, final Label continueLabel) {
        final var collect = (Expr.PipelineStage.Collect) pipeline.stages.getLast();
        if (stageIndex == pipeline.stages.size() - 1) {
            composer.aload(sinkLocal.lvt() + context.localSlotOffset);
            final var sinkInterfaceType = TypeDescriptor.genericOf(
                    TypeDescriptor.ofName("zeron.collections.Sink"), TypeDescriptor.ofAny());
            emitConversion(context, composer, collect.expression().getType(), sinkInterfaceType);
            loadPipelineValue(context, composer, currentValue, currentType);
            emitConversion(context, composer, currentType, TypeDescriptor.ofAny());
            composer.invokeinterface(SINK_CLASS, "add",
                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(TypeDescriptor.ofUnit()),
                            ConstantDescs.CD_Object));
            emitPop(context, composer, TypeDescriptor.ofUnit());
            composer.goto_(continueLabel);
            return;
        }

        final var stage = pipeline.stages.get(stageIndex);
        final var lambda = switch (stage) {
            case Expr.PipelineStage.Map map -> map.lambda();
            case Expr.PipelineStage.FlatMap flatMap -> flatMap.lambda();
            case Expr.PipelineStage.Filter filter -> filter.lambda();
            case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                    "A collect stage must be the final pipeline stage.");
        };
        final var functionType = (FunctionDescriptor) lambda.getType();
        final var captures = stageCaptures.get(stage);
        beginScope(context);
        try {
            for (final var capture : captures) {
                final var binding = declareLoopLocal(context, capture.token(), capture.type());
                loadPipelineValue(context, composer, capture.value(), capture.type());
                storePipelineValue(context, composer, binding, capture.type());
            }
            final var parameter = declareLoopLocal(
                    context, lambda.params.getFirst(), functionType.parameters().getFirst());
            loadPipelineValue(context, composer, currentValue, currentType);
            emitConversion(context, composer, currentType, functionType.parameters().getFirst());
            storePipelineValue(context, composer, parameter, functionType.parameters().getFirst());
            emitExpr(context, composer, stage.expression());
            emitConversion(context, composer, context.lastEmittedType, functionType.returnType());
        } finally {
            endScope(context);
        }

        switch (stage) {
            case Expr.PipelineStage.Map map -> {
                final var mappedType = map.type();
                final var mappedValue = declareLoopLocal(context, nextLoopTemporary(context, "mapped"), mappedType);
                storePipelineValue(context, composer, mappedValue, mappedType);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, mappedValue, mappedType,
                        stageCaptures, sinkLocal, continueLabel);
            }
            case Expr.PipelineStage.Filter _ -> {
                final var next = composer.newLabel();
                composer.ifeq(continueLabel);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                        stageCaptures, sinkLocal, continueLabel);
                composer.labelBinding(next);
            }
            case Expr.PipelineStage.FlatMap flatMap -> {
                final var flattenedType = flatMap.type();
                final var iterableType = TypeDescriptor.genericOf(
                        TypeDescriptor.ofName("zeron.collections.Iterable"), flattenedType);
                emitConversion(context, composer, functionType.returnType(), iterableType);
                composer.invokeinterface(ITERABLE_CLASS, "iterator", MethodTypeDesc.of(ITERATOR_CLASS));
                final var iteratorLocal = declareLoopLocal(context, nextLoopTemporary(context, "flatIterator"),
                        new ReferenceDescriptor(TypeDescriptor.genericOf(
                                TypeDescriptor.ofName("zeron.collections.Iterator"), flattenedType)));
                composer.astore(iteratorLocal.lvt() + context.localSlotOffset);
                final var optionLocal = declareLoopLocal(context, nextLoopTemporary(context, "flatOption"),
                        TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"), flattenedType));
                final var innerStart = composer.newLabel();
                final var innerExit = composer.newLabel();
                composer.labelBinding(innerStart);
                composer.aload(iteratorLocal.lvt() + context.localSlotOffset);
                composer.invokeinterface(ITERATOR_CLASS, "next", MethodTypeDesc.of(OPTION_CLASS));
                composer.astore(optionLocal.lvt() + context.localSlotOffset);
                composer.aload(optionLocal.lvt() + context.localSlotOffset);
                composer.invokeinterface(OPTION_CLASS, "isSome",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
                composer.ifeq(innerExit);
                composer.aload(optionLocal.lvt() + context.localSlotOffset);
                composer.aconst_null();
                composer.invokeinterface(OPTION_CLASS, "getOrElse",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), flattenedType);
                final var flattenedValue =
                        declareLoopLocal(context, nextLoopTemporary(context, "flatValue"), flattenedType);
                storePipelineValue(context, composer, flattenedValue, flattenedType);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, flattenedValue, flattenedType,
                        stageCaptures, sinkLocal, innerStart);
                composer.goto_(innerStart);
                composer.labelBinding(innerExit);
            }
            case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                    "A collect stage must be the final pipeline stage.");
        }
    }

    private static void storePipelineValue(final CompilationContext context, final CodeBuilder composer,
                                           final Bind binding, final TypeDescriptor type) {
        composer.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(type).descriptorString()),
                binding.lvt() + context.localSlotOffset);
        context.lastEmittedType = type;
    }

    private static void loadPipelineValue(final CompilationContext context, final CodeBuilder composer,
                                          final Bind binding, final TypeDescriptor type) {
        composer.loadLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(type).descriptorString()),
                binding.lvt() + context.localSlotOffset);
        context.lastEmittedType = type;
    }

    private static Bind declareLoopLocal(CompilationContext context, final Token name, final TypeDescriptor type ){
        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, name, type, BindingMutability.IMMUTABLE);
        context.symbols.define(name);
        return context.symbols.getSymbol(name);
    }

    private static Token nextLoopTemporary(CompilationContext context, final String name ){
        return new Token(TokenType.IDENTIFIER, "$for$" + name + "$" + context.loopTemporaryCount++, null, -1);
    }

    static void emitMatchExpression(CompilationContext context, final CodeBuilder composer, final Expr.Match match) {
        beginScope(context);
        final var scrutinee = emitMatchTemporary(context, "scrutinee", match.scrutinee.getType());
        emitExpr(context, composer, match.scrutinee);
        storePipelineValue(context, composer, scrutinee, match.scrutinee.getType());
        final var done = composer.newLabel();
        final var noMatch = composer.newLabel();
        loadPipelineValue(context, composer, scrutinee, match.scrutinee.getType());
        composer.ifnull(noMatch);
        for (final var arm : match.arms) {
            final var next = composer.newLabel();
            beginScope(context);
            try {
                declareMatchBindings(context, arm.pattern());
                if (!arm.pattern().wildcard()) {
                    emitMatchPattern(context, composer, arm.pattern(), scrutinee, next);
                }
                if (arm.guard() != null) {
                    emitExpr(context, composer, arm.guard());
                    composer.ifeq(next);
                }
                emitExpr(context, composer, arm.expression());
                emitConversion(context, composer, context.lastEmittedType, match.getType());
                composer.goto_(done);
            } finally {
                endScope(context);
            }
            composer.labelBinding(next);
        }

        composer.labelBinding(noMatch);
        final var exception = ClassDesc.of("java.lang.IllegalStateException");
        composer.new_(exception);
        composer.dup();
        composer.ldc("No match case accepted the runtime value.");
        composer.invokespecial(exception, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
        composer.athrow();
        composer.labelBinding(done);
        endScope(context);
        context.lastEmittedType = match.getType();
    }

    private static Bind emitMatchTemporary(final CompilationContext context,
                                           final String name,
                                           final TypeDescriptor type) {
        return declareLoopLocal(context, nextLoopTemporary(context, "match$" + name), type);
    }

    private static void declareMatchBindings(final CompilationContext context, final Expr.MatchPattern pattern) {
        if (!pattern.alternatives().isEmpty()) {
            declareMatchBindings(context, pattern.alternatives().getFirst());
            return;
        }
        if (pattern.alias() != null) declareMatchBinding(context, pattern.alias(), pattern.resolvedType());
        if (pattern.binding() != null) declareMatchBinding(context, pattern.binding(), pattern.resolvedType());
        for (final var argument : pattern.arguments()) declareMatchBindings(context, argument);
    }

    private static void declareMatchBinding(final CompilationContext context,
                                            final Token name,
                                            final TypeDescriptor type) {
        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, name, type, BindingMutability.IMMUTABLE);
        context.symbols.define(name);
    }

    private static void emitMatchPattern(final CompilationContext context,
                                         final CodeBuilder composer,
                                         final Expr.MatchPattern pattern,
                                         final Bind value,
                                         final Label failure) {
        if (!pattern.alternatives().isEmpty()) {
            final var matched = composer.newLabel();
            for (int i = 0; i < pattern.alternatives().size(); i++) {
                final var alternativeFailure = i == pattern.alternatives().size() - 1
                        ? failure : composer.newLabel();
                emitMatchPattern(context, composer, pattern.alternatives().get(i), value, alternativeFailure);
                composer.goto_(matched);
                if (i != pattern.alternatives().size() - 1) {
                    composer.labelBinding(alternativeFailure);
                }
            }
            composer.labelBinding(matched);
            return;
        }
        if (pattern.wildcard()) return;
        if (pattern.binding() != null) {
            storeMatchBinding(context, composer, pattern.binding(), pattern.resolvedType(), value);
            return;
        }

        final var matchType = pattern.resolvedType();
        final var targetClass = TypeDescriptor.toJavaClassDesc(matchType);
        loadPipelineValue(context, composer, value, value.type());
        composer.instanceOf(targetClass);
        composer.ifeq(failure);

        final var matchedValue = emitMatchTemporary(context, "value", matchType);
        loadPipelineValue(context, composer, value, value.type());
        composer.checkcast(targetClass);
        storePipelineValue(context, composer, matchedValue, matchType);
        if (pattern.alias() != null) {
            storeMatchBinding(context, composer, pattern.alias(), matchType, matchedValue);
        }
        if (pattern.extractor() == null) return;

        final var outputValues = new ArrayList<Bind>();
        if (pattern.legacyProperty()) {
            final var propertyType = TypeSubstitution.erase(pattern.declaredPatternType());
            final var getterName = Stmt.propertyGetterName(pattern.extractor().lexeme());
            loadPipelineValue(context, composer, matchedValue, matchType);
            composer.invokevirtual(targetClass, getterName,
                    toJavaMethodDescriptor(TypeDescriptor.functionOf(getterName, propertyType)));
            emitConversion(context, composer, propertyType, pattern.resolvedPatternType());
            final var output = emitMatchTemporary(context, "output", pattern.resolvedPatternType());
            storePipelineValue(context, composer, output, pattern.resolvedPatternType());
            outputValues.add(output);
        } else {
            loadPipelineValue(context, composer, matchedValue, matchType);
            composer.invokevirtual(targetClass,
                    Stmt.Pattern.helperName(pattern.extractor().lexeme()),
                    MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()));
            final var outputArray = emitMatchTemporary(context, "outputs", TypeDescriptor.arrayOf(TypeDescriptor.ofAny()));
            storePipelineValue(context, composer, outputArray, TypeDescriptor.arrayOf(TypeDescriptor.ofAny()));
            loadPipelineValue(context, composer, outputArray, TypeDescriptor.arrayOf(TypeDescriptor.ofAny()));
            composer.ifnull(failure);
            for (int i = 0; i < pattern.arguments().size(); i++) {
                final var output = emitMatchTemporary(context, "output", TypeDescriptor.ofAny());
                loadPipelineValue(context, composer, outputArray, TypeDescriptor.arrayOf(TypeDescriptor.ofAny()));
                composer.ldc(i);
                composer.aaload();
                storePipelineValue(context, composer, output, TypeDescriptor.ofAny());
                outputValues.add(output);
            }
        }

        for (int i = 0; i < pattern.arguments().size(); i++) {
            final var argument = pattern.arguments().get(i);
            final var outputValue = outputValues.get(i);
            if (pattern.legacyProperty()) {
                emitMatchPattern(context, composer, argument, outputValue, failure);
            } else {
                final var expectedType = argument.resolvedType();
                final var typedOutput = emitMatchTemporary(context, "typedOutput", expectedType);
                loadPipelineValue(context, composer, outputValue, TypeDescriptor.ofAny());
                emitConversion(context, composer, TypeDescriptor.ofAny(), expectedType);
                storePipelineValue(context, composer, typedOutput, expectedType);
                emitMatchPattern(context, composer, argument, typedOutput, failure);
            }
        }
    }

    private static void storeMatchBinding(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final Token name,
                                          final TypeDescriptor type,
                                          final Bind value) {
        loadPipelineValue(context, composer, value, value.type());
        emitConversion(context, composer, value.type(), type);
        final var binding = context.symbols.getSymbol(name);
        composer.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(type).descriptorString()),
                binding.lvt() + context.localSlotOffset);
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

    static void emitTypeTest(CompilationContext context, final CodeBuilder composer, final Expr.TypeTest test) {
        emitExpr(context, composer, test.value);
        emitBox(context, composer, context.lastEmittedType);
        composer.instanceOf(runtimeTypeTestClass(context, test.targetType));
        context.lastEmittedType = TypeDescriptor.ofBoolean();
    }

    static void emitCast(CompilationContext context, final CodeBuilder composer, final Expr.Cast cast) {
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

    private static ClassDesc runtimeTypeTestClass(CompilationContext context, final TypeDescriptor type) {
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

    static void emitComparison(CompilationContext context, final CodeBuilder composer, final Expr.Binary binary) {
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
                                        final Label target) {
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
