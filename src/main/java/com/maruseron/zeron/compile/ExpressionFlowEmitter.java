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
        final var bindings = beginSumCaseTestBindings(context, composer, iff.condition);
        try {
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
        } finally {
            endSumCaseTestBindings(context, bindings);
        }
    }

    static List<Token> beginSumCaseTestBindings(final CompilationContext context,
                                               final CodeBuilder composer,
                                               final Expr condition) {
        final var candidate = condition instanceof Expr.Grouping grouping ? grouping.expression : condition;
        if (!(candidate instanceof Expr.SumCaseTest test)) return List.of();
        final var bindings = test.pattern.arguments().stream()
                .filter(argument -> argument.binding() != null).toList();
        if (bindings.isEmpty()) return List.of();
        context.symbols.beginScope();
        final var names = new ArrayList<Token>();
        for (final var argument : bindings) {
            final var name = argument.binding();
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, name,
                    argument.resolvedType(), BindingMutability.IMMUTABLE);
            context.symbols.define(name);
            final var type = argument.resolvedType();
            if (type instanceof IntDescriptor || type instanceof BooleanDescriptor) {
                composer.iconst_0();
            } else if (type instanceof LongDescriptor) {
                composer.lconst_0();
            } else if (type instanceof FloatDescriptor) {
                composer.ldc(0.0d);
            } else {
                composer.aconst_null();
            }
            composer.storeLocal(TypeKind.fromDescriptor(
                    TypeDescriptor.toJavaClassDesc(type).descriptorString()),
                    context.symbols.getSymbol(name).lvt() + context.localSlotOffset);
            names.add(name);
        }
        return names;
    }

    static void endSumCaseTestBindings(final CompilationContext context, final List<Token> bindings) {
        if (!bindings.isEmpty()) context.symbols.endScope();
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
    private record PipelineOperation(String methodName, TypeDescriptor resultElementType,
                                     Expr.Lambda lambda, Expr countExpression) {}

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
            final var operation = switch (stage) {
                case Expr.PipelineStage.Map map ->
                        new PipelineOperation("map", map.type(), map.lambda(), null);
                case Expr.PipelineStage.Filter filter ->
                        new PipelineOperation("filter", elementType, filter.lambda(), null);
                case Expr.PipelineStage.FlatMap flatMap ->
                        new PipelineOperation("flatMap", flatMap.type(), flatMap.lambda(), null);
                case Expr.PipelineStage.Drop _ ->
                        new PipelineOperation("drop", elementType, null, stage.expression());
                case Expr.PipelineStage.Take _ ->
                        new PipelineOperation("take", elementType, null, stage.expression());
                case Expr.PipelineStage.DropWhile dropWhile ->
                        new PipelineOperation("dropWhile", elementType, dropWhile.lambda(), null);
                case Expr.PipelineStage.TakeWhile takeWhile ->
                        new PipelineOperation("takeWhile", elementType, takeWhile.lambda(), null);
                case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                        "A lazy for-expression cannot contain a collect stage.");
            };
            if (operation.countExpression() != null) {
                emitExpr(context, composer, operation.countExpression());
                emitConversion(context, composer, context.lastEmittedType, TypeDescriptor.ofInt());
                composer.invokevirtual(STREAM_CLASS, operation.methodName(),
                        MethodTypeDesc.of(STREAM_CLASS, TypeDescriptor.toJavaClassDesc(TypeDescriptor.ofInt())));
            } else {
                final var functionType = (FunctionDescriptor) operation.lambda().getType();
                final var invocationFunctionType = TypeDescriptor.functionOf("", switch (operation.methodName()) {
                    case "map" -> TypeDescriptor.ofName("java.lang.Object");
                    case "filter", "dropWhile", "takeWhile" -> TypeDescriptor.ofBoolean();
                    case "flatMap" -> TypeDescriptor.genericOf(
                            TypeDescriptor.ofName("zeron.collections.Iterable"), TypeDescriptor.ofAny());
                    default -> throw new IllegalStateException("Unexpected callback pipeline operation.");
                }, TypeDescriptor.ofName("java.lang.Object"));
                emitExpr(context, composer, operation.lambda());
                emitConversion(context, composer, functionType, invocationFunctionType);
                composer.invokevirtual(STREAM_CLASS, operation.methodName(),
                        MethodTypeDesc.of(STREAM_CLASS, TypeDescriptor.toJavaClassDesc(invocationFunctionType)));
            }
            elementType = operation.resultElementType();
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
        final var stageState = new IdentityHashMap<Expr.PipelineStage, Bind>();
        for (final var stage : pipeline.stages) {
            if (stage instanceof Expr.PipelineStage.Collect) continue;
            if (stage instanceof Expr.PipelineStage.Drop
                    || stage instanceof Expr.PipelineStage.Take) {
                emitExpr(context, composer, stage.expression());
                emitConversion(context, composer, context.lastEmittedType, TypeDescriptor.ofInt());
                final var remaining = declareLoopLocal(context, nextLoopTemporary(context, "remaining"),
                        TypeDescriptor.ofInt());
                storePipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
                stageState.put(stage, remaining);
            } else if (stage instanceof Expr.PipelineStage.DropWhile) {
                composer.iconst_1();
                final var dropping = declareLoopLocal(context, nextLoopTemporary(context, "dropping"),
                        TypeDescriptor.ofBoolean());
                storePipelineValue(context, composer, dropping, TypeDescriptor.ofBoolean());
                stageState.put(stage, dropping);
            }
            final var lambda = pipelineLambda(stage);
            if (lambda != null) {
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
        }

        final var loopStart = composer.newLabel();
        final var loopContinue = composer.newLabel();
        final var loopExit = composer.newLabel();
        for (final var stage : pipeline.stages) {
            if (!(stage instanceof Expr.PipelineStage.Take)) continue;
            loadPipelineValue(context, composer, stageState.get(stage), TypeDescriptor.ofInt());
            composer.iconst_0();
            composer.if_icmple(loopExit);
        }
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
            final var someClass = ClassDesc.of("zeron.lang.Option$$sum$Some");
            composer.instanceOf(someClass);
            composer.ifeq(loopExit);
            composer.aload(optionLocal.lvt() + context.localSlotOffset);
            composer.checkcast(someClass);
            composer.invokevirtual(someClass, "$zeron$payload$0",
                    MethodTypeDesc.of(ConstantDescs.CD_Object));
            emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), sourceElementType);
            storePipelineValue(context, composer, initialValue, sourceElementType);
        }

        emitCollectedStages(context, composer, pipeline, 0, initialValue, sourceElementType,
                stageCaptures, stageState, sinkLocal, loopContinue, loopExit);

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
                                            final IdentityHashMap<Expr.PipelineStage, Bind> stageState,
                                            final Bind sinkLocal, final Label continueLabel,
                                            final Label pipelineExit) {
        final var collect = (Expr.PipelineStage.Collect) pipeline.stages.getLast();
        if (stageIndex == pipeline.stages.size() - 1) {
            composer.aload(sinkLocal.lvt() + context.localSlotOffset);
            final var sinkInterfaceType = TypeDescriptor.genericOf(
                    TypeDescriptor.ofName("zeron.collections.Sink"), TypeDescriptor.ofAny());
            emitConversion(context, composer, collect.expression().getType(), sinkInterfaceType);
            loadPipelineValue(context, composer, currentValue, currentType);
            emitConversion(context, composer, currentType, TypeDescriptor.ofAny());
            composer.invokeinterface(SINK_CLASS, "add",
                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(TypeDescriptor.ofBoolean()),
                            ConstantDescs.CD_Object));
            emitPop(context, composer, TypeDescriptor.ofBoolean());
            return;
        }

        final var stage = pipeline.stages.get(stageIndex);
        if (stage instanceof Expr.PipelineStage.Drop) {
            final var remaining = stageState.get(stage);
            final var pass = composer.newLabel();
            loadPipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
            composer.iconst_0();
            composer.if_icmple(pass);
            loadPipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
            composer.iconst_1();
            composer.isub();
            composer.istore(remaining.lvt() + context.localSlotOffset);
            composer.goto_(continueLabel);
            composer.labelBinding(pass);
            emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                    stageCaptures, stageState, sinkLocal, continueLabel, pipelineExit);
            return;
        }

        if (stage instanceof Expr.PipelineStage.Take) {
            final var remaining = stageState.get(stage);
            loadPipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
            composer.iconst_0();
            composer.if_icmple(pipelineExit);
            loadPipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
            composer.iconst_1();
            composer.isub();
            composer.istore(remaining.lvt() + context.localSlotOffset);
            final var downstreamComplete = composer.newLabel();
            emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                    stageCaptures, stageState, sinkLocal, downstreamComplete, pipelineExit);
            composer.labelBinding(downstreamComplete);
            loadPipelineValue(context, composer, remaining, TypeDescriptor.ofInt());
            composer.iconst_0();
            composer.if_icmple(pipelineExit);
            return;
        }

        if (stage instanceof Expr.PipelineStage.DropWhile) {
            final var dropping = stageState.get(stage);
            final var pass = composer.newLabel();
            loadPipelineValue(context, composer, dropping, TypeDescriptor.ofBoolean());
            composer.ifeq(pass);
            emitCollectedStageLambda(context, composer, stage, currentValue, currentType,
                    stageCaptures.get(stage));
            composer.ifne(continueLabel);
            composer.iconst_0();
            composer.istore(dropping.lvt() + context.localSlotOffset);
            composer.labelBinding(pass);
            emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                    stageCaptures, stageState, sinkLocal, continueLabel, pipelineExit);
            return;
        }

        if (stage instanceof Expr.PipelineStage.TakeWhile) {
            emitCollectedStageLambda(context, composer, stage, currentValue, currentType,
                    stageCaptures.get(stage));
            composer.ifeq(pipelineExit);
            emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                    stageCaptures, stageState, sinkLocal, continueLabel, pipelineExit);
            return;
        }

        final var lambda = pipelineLambda(stage);
        final var functionType = (FunctionDescriptor) lambda.getType();
        emitCollectedStageLambda(context, composer, stage, currentValue, currentType,
                stageCaptures.get(stage));

        switch (stage) {
            case Expr.PipelineStage.Map map -> {
                final var mappedType = map.type();
                final var mappedValue = declareLoopLocal(context, nextLoopTemporary(context, "mapped"), mappedType);
                storePipelineValue(context, composer, mappedValue, mappedType);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, mappedValue, mappedType,
                        stageCaptures, stageState, sinkLocal, continueLabel, pipelineExit);
            }
            case Expr.PipelineStage.Filter _ -> {
                composer.ifeq(continueLabel);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, currentValue, currentType,
                        stageCaptures, stageState, sinkLocal, continueLabel, pipelineExit);
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
                final var someClass = ClassDesc.of("zeron.lang.Option$$sum$Some");
                composer.instanceOf(someClass);
                composer.ifeq(innerExit);
                composer.aload(optionLocal.lvt() + context.localSlotOffset);
                composer.checkcast(someClass);
                composer.invokevirtual(someClass, "$zeron$payload$0",
                        MethodTypeDesc.of(ConstantDescs.CD_Object));
                emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"), flattenedType);
                final var flattenedValue =
                        declareLoopLocal(context, nextLoopTemporary(context, "flatValue"), flattenedType);
                storePipelineValue(context, composer, flattenedValue, flattenedType);
                emitCollectedStages(context, composer, pipeline, stageIndex + 1, flattenedValue, flattenedType,
                        stageCaptures, stageState, sinkLocal, innerStart, pipelineExit);
                composer.goto_(innerStart);
                composer.labelBinding(innerExit);
            }
            case Expr.PipelineStage.DropWhile _, Expr.PipelineStage.TakeWhile _ ->
                    throw new IllegalStateException("While stages are handled before callback emission.");
            case Expr.PipelineStage.Drop _, Expr.PipelineStage.Take _ ->
                    throw new IllegalStateException("Count stages are handled before callback emission.");
            case Expr.PipelineStage.Collect _ -> throw new IllegalStateException(
                    "A collect stage must be the final pipeline stage.");
        }
    }

    private static Expr.Lambda pipelineLambda(final Expr.PipelineStage stage) {
        return switch (stage) {
            case Expr.PipelineStage.Map map -> map.lambda();
            case Expr.PipelineStage.FlatMap flatMap -> flatMap.lambda();
            case Expr.PipelineStage.Filter filter -> filter.lambda();
            case Expr.PipelineStage.DropWhile dropWhile -> dropWhile.lambda();
            case Expr.PipelineStage.TakeWhile takeWhile -> takeWhile.lambda();
            case Expr.PipelineStage.Drop _, Expr.PipelineStage.Take _, Expr.PipelineStage.Collect _ -> null;
        };
    }

    private static void emitCollectedStageLambda(final CompilationContext context, final CodeBuilder composer,
                                                 final Expr.PipelineStage stage, final Bind currentValue,
                                                 final TypeDescriptor currentType,
                                                 final List<PipelineCapture> captures) {
        final var lambda = pipelineLambda(stage);
        final var functionType = (FunctionDescriptor) lambda.getType();
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
        final var scrutineeDescriptor = TypeDescriptor.toJavaClassDesc(match.scrutinee.getType())
                .descriptorString();
        if (scrutineeDescriptor.startsWith("L") || scrutineeDescriptor.startsWith("[")) {
            loadPipelineValue(context, composer, scrutinee, match.scrutinee.getType());
            composer.ifnull(noMatch);
        }
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
        if (pattern.literal() != null) {
            emitLiteralPattern(context, composer, pattern, value, failure);
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
        if (pattern.sumCase()) {
            final var caseDeclaration = context.resolution.classes().get(matchType.name());
            if (caseDeclaration == null) throw new IllegalStateException("Resolved sum case class not found.");
            for (int index = 0; index < pattern.arguments().size(); index++) {
                final var argument = pattern.arguments().get(index);
                if (argument.wildcard()) continue;
                final var payloadType = argument.resolvedType();
                final var erasedPayloadType = TypeSubstitution.erase(
                        caseDeclaration.fields().get(index).type());
                final var output = emitMatchTemporary(context, "sumPayload", payloadType);
                loadPipelineValue(context, composer, matchedValue, matchType);
                composer.invokevirtual(targetClass, "$zeron$payload$" + index,
                        MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedPayloadType)));
                emitConversion(context, composer, erasedPayloadType, payloadType);
                storePipelineValue(context, composer, output, payloadType);
                emitMatchPattern(context, composer, argument, output, failure);
            }
            return;
        }
        if (pattern.productPattern()) {
            final var productDeclaration = context.resolution.classes().get(matchType.name());
            if (productDeclaration == null) {
                throw new IllegalStateException("Resolved product class not found.");
            }
            for (int index = 0; index < pattern.arguments().size(); index++) {
                final var argument = pattern.arguments().get(index);
                if (argument.wildcard()) continue;
                final var component = productDeclaration.properties().get(index);
                final var erasedComponentType = TypeSubstitution.erase(component.type());
                final var componentType = argument.resolvedType();
                final var output = emitMatchTemporary(context, "productComponent", componentType);
                loadPipelineValue(context, composer, matchedValue, matchType);
                composer.invokevirtual(targetClass, Stmt.propertyGetterName(component.name().lexeme()),
                        MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedComponentType)));
                emitConversion(context, composer, erasedComponentType, componentType);
                storePipelineValue(context, composer, output, componentType);
                emitMatchPattern(context, composer, argument, output, failure);
            }
            return;
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

    private static void emitLiteralPattern(final CompilationContext context,
                                           final CodeBuilder composer,
                                           final Expr.MatchPattern pattern,
                                           final Bind value,
                                           final Label failure) {
        final var literal = pattern.literal();
        loadPipelineValue(context, composer, value, value.type());
        switch (literal.getType()) {
            case IntDescriptor _ -> {
                composer.ldc((Integer) literal.value);
                composer.if_icmpne(failure);
            }
            case LongDescriptor _ -> {
                composer.ldc((Long) literal.value);
                composer.lcmp();
                composer.ifne(failure);
            }
            case BooleanDescriptor _ -> {
                if ((Boolean) literal.value) composer.iconst_1();
                else composer.iconst_0();
                composer.if_icmpne(failure);
            }
            case StringDescriptor _ -> {
                composer.ldc((String) literal.value);
                composer.invokevirtual(ConstantDescs.CD_String, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
                composer.ifeq(failure);
            }
            default -> throw new IllegalStateException("Unsupported resolved literal pattern type.");
        }
        if (pattern.alias() != null) {
            storeMatchBinding(context, composer, pattern.alias(), pattern.resolvedType(), value);
        }
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
        beginScope(context);
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
        final var exception = declareLoopLocal(context, nextLoopTemporary(context, "handlerException"),
                TypeDescriptor.ofAny());
        final var payload = declareLoopLocal(context, nextLoopTemporary(context, "handlerPayload"),
                TypeDescriptor.ofAny());
        storePipelineValue(context, composer, exception, TypeDescriptor.ofAny());
        loadPipelineValue(context, composer, exception, TypeDescriptor.ofAny());
        composer.checkcast(carrier);
        composer.invokevirtual(carrier, "payload", MethodTypeDesc.of(ConstantDescs.CD_Object));
        storePipelineValue(context, composer, payload, TypeDescriptor.ofAny());

        for (final var arm : handle.arms) {
            final var next = composer.newLabel();
            beginScope(context);
            try {
                declareMatchBindings(context, arm.pattern());
                emitMatchPattern(context, composer, arm.pattern(), payload, next);
                if (arm.suppressedBinding() != null) {
                    final var suppressedType = TypeDescriptor.arrayOf(TypeDescriptor.ofAny().toNullable());
                    final var suppressed = declareLoopLocal(
                            context, arm.suppressedBinding(), suppressedType);
                    loadPipelineValue(context, composer, exception, TypeDescriptor.ofAny());
                    composer.checkcast(carrier);
                    composer.invokevirtual(carrier, "suppressedOutcomes",
                            MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()));
                    composer.astore(suppressed.lvt() + context.localSlotOffset);
                }
                if (arm.guard() != null) {
                    emitExpr(context, composer, arm.guard());
                    composer.ifeq(next);
                }
                emitExpr(context, composer, arm.expression());
                emitConversion(context, composer, context.lastEmittedType, handle.getType());
            } finally {
                endScope(context);
            }
            composer.goto_(done);
            composer.labelBinding(next);
        }

        loadPipelineValue(context, composer, exception, TypeDescriptor.ofAny());
        composer.checkcast(carrier);
        composer.athrow();
        composer.labelBinding(done);
        composer.exceptionCatch(start, end, handler, carrier);
        endScope(context);
        context.lastEmittedType = handle.getType();
    }

    static void emitTypeTest(CompilationContext context, final CodeBuilder composer, final Expr.TypeTest test) {
        emitExpr(context, composer, test.value);
        emitBox(context, composer, context.lastEmittedType);
        composer.instanceOf(runtimeTypeTestClass(context, test.resolvedTargetType()));
        context.lastEmittedType = TypeDescriptor.ofBoolean();
    }

    static void emitSumCaseTest(final CompilationContext context, final CodeBuilder composer,
                                final Expr.SumCaseTest test) {
        emitExpr(context, composer, test.value);
        final var valueType = context.lastEmittedType;
        final var value = emitMatchTemporary(context, "sumCaseTest", valueType);
        storePipelineValue(context, composer, value, valueType);
        final var failed = composer.newLabel();
        final var done = composer.newLabel();
        emitMatchPattern(context, composer, test.pattern, value, failed);
        composer.iconst_1();
        composer.goto_(done);
        composer.labelBinding(failed);
        composer.iconst_0();
        composer.labelBinding(done);
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
                || cast.targetType instanceof LongDescriptor
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
            case LongDescriptor _ -> ConstantDescs.CD_Long;
            case FloatDescriptor _ -> ConstantDescs.CD_Double;
            case BooleanDescriptor _ -> ConstantDescs.CD_Boolean;
            case AnyDescriptor _ -> ConstantDescs.CD_Object;
            case UnitDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case StringDescriptor _ -> TypeDescriptor.toJavaClassDesc(type);
            case GenericDescriptor generic -> TypeDescriptor.toJavaClassDesc(generic.baseType());
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
            case "J" -> {
                composer.lcmp();
                switch (binary.operator.type()) {
                    case EQUAL_EQUAL -> composer.ifeq(matched);
                    case BANG_EQUAL -> composer.ifne(matched);
                    case GREATER -> composer.ifgt(matched);
                    case GREATER_EQUAL -> composer.ifge(matched);
                    case LESS -> composer.iflt(matched);
                    case LESS_EQUAL -> composer.ifle(matched);
                    default -> throw new IllegalStateException("Unsupported Long comparison operator.");
                }
            }
            case "D" -> {
                if (binary.operator.type() == TokenType.EQUAL_EQUAL
                        || binary.operator.type() == TokenType.BANG_EQUAL) {
                    composer.invokestatic(ClassDesc.of("java.lang.Double"), "compare",
                            MethodTypeDesc.of(ConstantDescs.CD_int,
                                    ConstantDescs.CD_double, ConstantDescs.CD_double));
                    if (binary.operator.type() == TokenType.EQUAL_EQUAL) composer.ifeq(matched);
                    else composer.ifne(matched);
                } else {
                    switch (binary.operator.type()) {
                        case LESS, LESS_EQUAL -> composer.dcmpg();
                        default -> composer.dcmpl();
                    }
                    switch (binary.operator.type()) {
                        case GREATER -> composer.ifgt(matched);
                        case GREATER_EQUAL -> composer.ifge(matched);
                        case LESS -> composer.iflt(matched);
                        case LESS_EQUAL -> composer.ifle(matched);
                        default -> throw new IllegalStateException("Unsupported comparison operator.");
                    }
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

    static void emitOrderingComparison(final CompilationContext context, final CodeBuilder composer,
                                       final TokenType operator) {
        final var matched = composer.newLabel();
        final var unmatched = composer.newLabel();
        final var result = composer.newLabel();
        final var less = ClassDesc.of("zeron.lang.Ordering$$sum$LT");
        final var equal = ClassDesc.of("zeron.lang.Ordering$$sum$EQ");
        final var greater = ClassDesc.of("zeron.lang.Ordering$$sum$GT");
        final var checkEqual = composer.newLabel();
        final var checkGreater = composer.newLabel();

        composer.dup();
        composer.instanceOf(less);
        composer.ifeq(checkEqual);
        if (operator == TokenType.LESS || operator == TokenType.LESS_EQUAL) {
            composer.pop();
            composer.goto_(matched);
        } else composer.goto_(unmatched);

        composer.labelBinding(checkEqual);
        composer.dup();
        composer.instanceOf(equal);
        composer.ifeq(checkGreater);
        if (operator == TokenType.LESS_EQUAL || operator == TokenType.GREATER_EQUAL) {
            composer.pop();
            composer.goto_(matched);
        } else composer.goto_(unmatched);

        composer.labelBinding(checkGreater);
        composer.dup();
        composer.instanceOf(greater);
        composer.ifeq(unmatched);
        if (operator == TokenType.GREATER || operator == TokenType.GREATER_EQUAL) {
            composer.pop();
            composer.goto_(matched);
        } else composer.goto_(unmatched);

        composer.labelBinding(unmatched);
        composer.pop();
        composer.iconst_0();
        composer.goto_(result);
        composer.labelBinding(matched);
        composer.iconst_1();
        composer.labelBinding(result);
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
