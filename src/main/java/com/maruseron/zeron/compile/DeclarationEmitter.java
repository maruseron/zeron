package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.lang.classfile.*;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.*;
import java.util.*;

import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class DeclarationEmitter {
    static void generateClass(CompilationContext context, final ClassBuilder classBuilder,
                               final List<Stmt> declarations,
                               final boolean entryHolder ){
        var hasLaunchableMain = false;
        var hasLaunchableMainWithArguments = false;
        for (final var declaration : declarations) {
            switch (declaration) {
                case Stmt.Var(Token name, _, _, _, _) -> {
                    final var type = context.symbols.getSymbol(context.resolution.topLevelValueSymbol(
                            (Stmt.Var) declaration)).type();
                    classBuilder.withField(
                            name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(type),
                            fieldBuilder -> fieldBuilder.withFlags(
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC));
                    classBuilder.withField(initializedFlagFieldName(name.lexeme()), ConstantDescs.CD_boolean,
                            fieldBuilder -> fieldBuilder.withFlags(
                                    ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC));
                    generateTopLevelValueMethods(context, classBuilder, (Stmt.Var) declaration, type);
                }
                case Stmt.FunctionDeclaration function -> {
                    if (function instanceof Stmt.Function source
                            && context.metadata.isClassHktImplementationHelper(source)) continue;
                    final var name = function.name();
                    final var parameters = function.parameters();
                    final var typeDescriptor = function.typeDescriptor();
                    final var sourceFunction = function instanceof Stmt.Function source ? source : null;
                    final var extensionMethod = function instanceof Stmt.ExtensionMethod extension
                            ? extension : null;
                    final var externalFunction = function instanceof Stmt.ExternalFunction external
                            ? external
                            : null;
                    final var functionType = function.typeDescriptor().returnType() instanceof InferDescriptor
                            ? context.symbols.getFunctionType(context.resolution.functionSymbolToken(name))
                            : function.typeDescriptor();
                    final var witnessSlots = BytecodeEmitter.witnessSlots(context, typeDescriptor);
                    final var methodDescriptor = BytecodeEmitter.withEvidenceParameters(
                            toJavaMethodDescriptor(functionType), witnessSlots.size());
                    if (entryHolder && name.lexeme().equals("main")
                            && functionType.parameters().isEmpty()
                            && functionType.returnType() instanceof UnitDescriptor) {
                        hasLaunchableMain = true;
                    }
                    if (entryHolder && name.lexeme().equals("main")
                            && functionType.parameters().size() == 1
                            && functionType.parameters().getFirst() instanceof ArrayDescriptor array
                            && array.elementType() instanceof StringDescriptor
                            && functionType.returnType() instanceof UnitDescriptor) {
                        hasLaunchableMainWithArguments = true;
                    }
                    classBuilder.withMethod(
                            name.lexeme(),
                            methodDescriptor,
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                            methodBuilder -> {
                                final var signature = ClassSignatureEmitter.methodSignature(typeDescriptor);
                                if (signature != null && witnessSlots.isEmpty()) {
                                    methodBuilder.with(SignatureAttribute.of(
                                            methodBuilder.constantPool().utf8Entry(signature)));
                                }
                                methodBuilder.withCode(composer -> {
                                    beginScope(context);
                                    final var previousReturnType = context.currentReturnType;
                                    context.currentReturnType = functionType.returnType();
                                    final var paramTypes = typeDescriptor.parameters();
                                    for (int i = 0; i < parameters.size(); i++) {
                                        context.symbols.declareSymbol(declaration, parameters.get(i), paramTypes.get(i),
                                                BindingMutability.IMMUTABLE);
                                        context.symbols.define(parameters.get(i));
                                    }
                                    for (final var witnessToken : witnessSlots) {
                                        context.symbols.declareSymbol(declaration, witnessToken,
                                                TypeDescriptor.ofName("java.lang.invoke.MethodHandle"),
                                                BindingMutability.IMMUTABLE);
                                        context.symbols.define(witnessToken);
                                    }
                                    //currentFunction = new FunctionModel(name.lexeme(), functionType);
                                    final var binding = externalFunction == null
                                            ? null
                                            : context.resolution.externalFunctionBinding(externalFunction);
                                    composer.transforming(
                                        (builder, element) -> {
                                            /*if (element instanceof Instruction i) {
                                                currentFunction.add(i, i.opcode().kind(), null, null, null);
                                            }*/
                                            builder.with(element);
                                        },
                                        builder -> {
                                            if (externalFunction != null) {
                                                emitExternalFunction(context, builder, externalFunction.typeDescriptor(), binding);
                                            } else if (extensionMethod != null) {
                                                StatementEmitter.emitStmts(context, builder, extensionMethod.body());
                                            } else {
                                                StatementEmitter.emitStmts(context, builder, sourceFunction.body());
                                            }
                                        });
                                    if (externalFunction == null
                                            && functionType.returnType() instanceof UnitDescriptor) {
                                        emitUnitValue(context, composer);
                                        composer.areturn();
                                    }
                                    endScope(context);
                                    context.currentReturnType = previousReturnType;
                                    //Zeron.debug(currentFunction.toString());
                                });
                            });
                    if (!witnessSlots.isEmpty()) {
                        final var bridgeType = (FunctionDescriptor) TypeSubstitution.erase(functionType);
                        classBuilder.withMethod(name.lexeme(), toJavaMethodDescriptor(bridgeType),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                                methodBuilder -> {
                                    final var signature = ClassSignatureEmitter.methodSignature(functionType);
                                    if (signature != null) {
                                        methodBuilder.with(SignatureAttribute.of(
                                                methodBuilder.constantPool().utf8Entry(signature)));
                                    }
                                    methodBuilder.withCode(BytecodeEmitter::emitEvidenceUnavailable);
                                });
                    }
                    emitDefaultArgumentWrappers(context, classBuilder, function, functionType);
                    if (sourceFunction != null) {
                        generateHktImplementationBridge(context, classBuilder,
                                ClassDesc.of(context.currentHolderName), sourceFunction,
                                context.metadata.hktBridgeSignature(sourceFunction));
                    }
                }
                default -> {}
            }
        }

        if (entryHolder && (hasLaunchableMain || hasLaunchableMainWithArguments)) {
            final var passArguments = hasLaunchableMainWithArguments;
            classBuilder.withMethodBody(
                    "main",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    composer -> {
                        if (passArguments) composer.aload(0);
                        composer.invokestatic(composer.constantPool().methodRefEntry(
                                ClassDesc.of(context.currentHolderName),
                                "main",
                                passArguments
                                        ? MethodTypeDesc.of(UNIT_CLASS, ConstantDescs.CD_Object.arrayType())
                                        : MethodTypeDesc.of(UNIT_CLASS)));
                        composer.pop();
                        composer.return_();
                    });
        }

        if (entryHolder && !context.initializationPlan.order().isEmpty()) {
            classBuilder.withMethodBody("$zeron$initialize", emptyVoidMethod(),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, composer -> composer.return_());
            classBuilder.withMethodBody(
                    "<clinit>",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        for (final var value : context.initializationPlan.order()) {
                            composer.invokestatic(ClassDesc.of(context.metadata.declarationOwner(value, context.currentHolderName)),
                                    context.initializationPlan.initializerNames().get(value), emptyVoidMethod());
                        }
                        composer.return_();
                    });
        }

                if (entryHolder) for (final var lambda : context.lambdaPlan.lambdaImplementations()) {
                    final var functionType = (FunctionDescriptor) lambda.getType();
                    final var implementationParameters = new ArrayList<TypeDescriptor>();
                    implementationParameters.addAll(context.lambdaPlan.captureTypes(lambda));
                    final var evidenceFields = BytecodeEmitter.evidenceFields(
                            context, functionType.typeParameters(), "lambda",
                            context.lambdaPlan.lambdaMethodName(lambda));
                    implementationParameters.addAll(Collections.nCopies(evidenceFields.size(),
                            TypeDescriptor.ofName("java.lang.invoke.MethodHandle")));
                    implementationParameters.addAll(functionType.parameters());
                    classBuilder.withMethodBody(
                        context.lambdaPlan.lambdaMethodName(lambda),
                        toJavaMethodDescriptor(functionType, implementationParameters),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> LambdaSupportEmitter.emitLambdaImplementation(context, composer, lambda));
                }

                if (entryHolder) for (final var lambda : context.lambdaPlan.lambdaImplementations()) {
                    final var factoryName = context.lambdaPlan.lambdaTemplateFactoryName(lambda);
                    if (factoryName == null) continue;
                    classBuilder.withMethodBody(factoryName,
                            MethodTypeDesc.of(ConstantDescs.CD_Object,
                                    ConstantDescs.CD_Object.arrayType(),
                                    ClassDesc.of("java.lang.invoke.MethodHandle").arrayType()),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                            composer -> LambdaSupportEmitter.emitLambdaTemplateFactory(
                                    context, composer, lambda));
                }

                if (entryHolder) for (final var adapter : context.lambdaPlan.adapters()) {
                    final var sourceType = adapter.source();
                    final var targetType = adapter.target();
                    final var parameters = new ArrayList<TypeDescriptor>();
                    parameters.add(sourceType);
                    parameters.addAll(targetType.parameters());
                    classBuilder.withMethodBody(
                        adapter.name(),
                        toJavaMethodDescriptor(targetType, parameters),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> LambdaSupportEmitter.emitFunctionAdapterImplementation(context, composer, sourceType, targetType));
                }

                    if (entryHolder) for (final var adapter : context.lambdaPlan.nullableAdapters()) {
                        final var sourceType = adapter.source();
                        final var targetType = adapter.target();
                        final var sourceClass = TypeDescriptor.toJavaClassDesc(sourceType);
                        final var targetClass = TypeDescriptor.toJavaClassDesc(targetType);
                        classBuilder.withMethodBody(
                            adapter.name(),
                            MethodTypeDesc.of(targetClass, sourceClass),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                            composer -> LambdaSupportEmitter.emitNullableFunctionAdapterImplementation(context, 
                                composer, sourceType, targetType));
                    }

                    if (entryHolder) for (final var reference : context.lambdaPlan.functionReferences()) {
                        classBuilder.withMethodBody(
                                reference.helperName(),
                                LambdaSupportEmitter.functionReferenceMethodType(reference),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                                composer -> LambdaSupportEmitter.emitFunctionReferenceImplementation(context, composer, reference));
                    }
    }

    static void generateClassHktImplementationHelper(final CompilationContext context,
                                                    final ClassBuilder classBuilder,
                                                    final Stmt.Function function) {
        final var typeDescriptor = function.typeDescriptor();
        final var witnessSlots = BytecodeEmitter.witnessSlots(context, typeDescriptor);
        final var methodDescriptor = BytecodeEmitter.withEvidenceParameters(
                toJavaMethodDescriptor(typeDescriptor), witnessSlots.size());
        classBuilder.withMethod(function.name().lexeme(), methodDescriptor,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                methodBuilder -> {
                    final var signature = ClassSignatureEmitter.methodSignature(typeDescriptor);
                    if (signature != null && witnessSlots.isEmpty()) {
                        methodBuilder.with(SignatureAttribute.of(
                                methodBuilder.constantPool().utf8Entry(signature)));
                    }
                    methodBuilder.withCode(composer -> {
                        beginScope(context);
                        final var previousReturnType = context.currentReturnType;
                        context.currentReturnType = typeDescriptor.returnType();
                        for (int i = 0; i < function.parameters().size(); i++) {
                            final var parameter = function.parameters().get(i);
                            context.symbols.declareSymbol(function, parameter,
                                    typeDescriptor.parameters().get(i), BindingMutability.IMMUTABLE);
                            context.symbols.define(parameter);
                        }
                        for (final var witnessToken : witnessSlots) {
                            context.symbols.declareSymbol(function, witnessToken,
                                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"),
                                    BindingMutability.IMMUTABLE);
                            context.symbols.define(witnessToken);
                        }
                        StatementEmitter.emitStmts(context, composer, function.body());
                        if (typeDescriptor.returnType() instanceof UnitDescriptor) {
                            emitUnitValue(context, composer);
                            composer.areturn();
                        }
                        endScope(context);
                        context.currentReturnType = previousReturnType;
                    });
                });
        if (!witnessSlots.isEmpty()) {
            final var bridgeType = (FunctionDescriptor) TypeSubstitution.erase(typeDescriptor);
            classBuilder.withMethod(function.name().lexeme(), toJavaMethodDescriptor(bridgeType),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                    methodBuilder -> methodBuilder.withCode(BytecodeEmitter::emitEvidenceUnavailable));
        }
        generateHktImplementationBridge(context, classBuilder,
                ClassDesc.of(context.metadata.functionOwner(function)), function,
                context.metadata.hktBridgeSignature(function));
        emitDefaultArgumentWrappers(context, classBuilder, function, typeDescriptor);
    }

    private static void generateHktImplementationBridge(final CompilationContext context,
                                                        final ClassBuilder classBuilder,
                                                        final ClassDesc owner,
                                                        final Stmt.Function implementation,
                                                        final FunctionDescriptor contractType) {
        if (contractType == null) return;
        final var implementationType = implementation.typeDescriptor();
        final var erasedContractType = (FunctionDescriptor) TypeSubstitution.erase(contractType);
        final var contractSlots = BytecodeEmitter.witnessSlots(context, contractType);
        final var implementationSlots = BytecodeEmitter.witnessSlots(context, implementationType);
        if (contractSlots.size() != implementationSlots.size()) {
            throw new IllegalStateException("HKT contract and implementation evidence arities do not match.");
        }
        final var bridgeDescriptor = BytecodeEmitter.withEvidenceParameters(
                toJavaMethodDescriptor(erasedContractType), contractSlots.size());
        final var implementationDescriptor = BytecodeEmitter.withEvidenceParameters(
                toJavaMethodDescriptor(implementationType), implementationSlots.size());
        if (bridgeDescriptor.equals(implementationDescriptor)) return;
        classBuilder.withMethodBody(implementation.name().lexeme(), bridgeDescriptor,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    var slot = 0;
                    for (int index = 0; index < contractType.arity(); index++) {
                        final var sourceType = TypeSubstitution.erase(contractType.parameters().get(index));
                        final var targetType = implementationType.parameters().get(index);
                        NominalTypeEmitter.loadLocal(context, code, slot, sourceType);
                        emitConversion(context, code, sourceType, targetType);
                        slot += sourceType.isDoubleWidth() ? 2 : 1;
                    }
                    final var handleType = TypeDescriptor.ofName("java.lang.invoke.MethodHandle");
                    for (int index = 0; index < contractSlots.size(); index++) {
                        NominalTypeEmitter.loadLocal(context, code, slot, handleType);
                        slot++;
                    }
                    code.invokestatic(owner, implementation.name().lexeme(), implementationDescriptor);
                    final var erasedReturn = erasedContractType.returnType();
                    emitConversion(context, code, implementationType.returnType(), erasedReturn);
                    code.return_(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(erasedReturn).descriptorString()));
                });
    }

    private static void emitDefaultArgumentWrappers(final CompilationContext context,
                                                    final ClassBuilder classBuilder,
                                                    final Stmt.FunctionDeclaration function,
                                                    final FunctionDescriptor functionType) {
        if (function.defaultValues().isEmpty()) return;
        final var qualifiedName = context.resolution.functionName(function.name());
        final var owner = context.metadata.functionOwner(qualifiedName);
        final var flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
                | ClassFile.ACC_SYNTHETIC;
        final var fixedArity = Stmt.fixedArity(function.parameters(), function.variadic());
        final var lastWrapperArity = function.variadic() ? fixedArity : functionType.arity() - 1;
        for (int arity = function.minimumArity(); arity <= lastWrapperArity; arity++) {
            final var suppliedArity = arity;
            final var wrapperType = TypeDescriptor.functionOf(functionType.name(),
                    functionType.returnType(), functionType.parameters().subList(0, suppliedArity)
                            .toArray(TypeDescriptor[]::new));
            final var runtimeWrapper = (FunctionDescriptor) TypeSubstitution.erase(wrapperType);
            classBuilder.withMethod(function.name().lexeme(), toJavaMethodDescriptor(runtimeWrapper),
                    flags, methodBuilder -> methodBuilder.withCode(code -> {
                        final var previousOffset = context.localSlotOffset;
                        final var previousReturnType = context.currentReturnType;
                        context.localSlotOffset = 0;
                        context.currentReturnType = functionType.returnType();
                        beginScope(context);
                        try {
                            for (int i = 0; i < suppliedArity; i++) {
                                final var parameter = function.parameters().get(i);
                                context.symbols.declareSymbol(function, parameter,
                                        functionType.parameters().get(i), BindingMutability.IMMUTABLE);
                                context.symbols.define(parameter);
                            }
                            for (int i = suppliedArity; i < fixedArity; i++) {
                                emitExpr(context, code, function.defaultValues().get(i - function.minimumArity()));
                                emitConversion(context, code, context.lastEmittedType, functionType.parameters().get(i));
                                final var parameter = function.parameters().get(i);
                                context.symbols.declareSymbol(function, parameter,
                                        functionType.parameters().get(i), BindingMutability.IMMUTABLE);
                                final var binding = context.symbols.getSymbol(parameter);
                                code.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(
                                        TypeSubstitution.erase(functionType.parameters().get(i))).descriptorString()),
                                        binding.lvt() + context.localSlotOffset);
                                context.symbols.define(parameter);
                            }
                            if (function.variadic()) {
                                final var parameter = function.parameters().get(fixedArity);
                                context.symbols.declareSymbol(function, parameter,
                                        functionType.parameters().get(fixedArity), BindingMutability.IMMUTABLE);
                                code.iconst_0();
                                code.anewarray(ClassDesc.of("java.lang.Object"));
                                final var binding = context.symbols.getSymbol(parameter);
                                code.astore(binding.lvt() + context.localSlotOffset);
                                context.symbols.define(parameter);
                            }
                            for (int i = 0; i < functionType.arity(); i++) {
                                final var parameter = function.parameters().get(i);
                                final var parameterType = TypeSubstitution.erase(functionType.parameters().get(i));
                                NominalTypeEmitter.loadLocal(context, code,
                                        context.symbols.getSymbol(parameter).lvt(), parameterType);
                                context.lastEmittedType = parameterType;
                                emitConversion(context, code, context.lastEmittedType,
                                        parameterType);
                            }
                            code.invokestatic(ClassDesc.of(owner), function.name().lexeme(),
                                    toJavaMethodDescriptor((FunctionDescriptor) TypeSubstitution.erase(functionType)));
                            code.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(
                                    TypeSubstitution.erase(functionType.returnType())).descriptorString()));
                        } finally {
                            endScope(context);
                            context.localSlotOffset = previousOffset;
                            context.currentReturnType = previousReturnType;
                        }
                    }));
        }
    }

    private static void generateTopLevelValueMethods(CompilationContext context, final ClassBuilder classBuilder,
                                              final Stmt.Var variable,
                                              final TypeDescriptor type ){
        final var owner = ClassDesc.of(context.currentHolderName);
        final var fieldName = variable.name().lexeme();
        final var initializedFlag = initializedFlagFieldName(fieldName);
        final var initializerName = context.initializationPlan.initializerNames().get(variable);
        final var initializationOwner = context.metadata.initializationOwner(variable);
        if (initializationOwner.isEmpty()) {
            throw new IllegalStateException("Missing initialization owner for top-level value " + fieldName);
        }
        classBuilder.withMethodBody(initializerName, emptyVoidMethod(),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, composer -> {
                    emitExpr(context, composer, variable.initializer());
                    emitConversion(context, composer, context.lastEmittedType, type);
                    composer.putstatic(owner, fieldName, TypeDescriptor.toJavaClassDesc(type));
                    composer.iconst_1();
                    composer.putstatic(owner, initializedFlag, ConstantDescs.CD_boolean);
                    composer.return_();
                });
        classBuilder.withMethodBody(topLevelValueAccessorName(fieldName),
                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(type)),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, composer -> {
                    composer.invokestatic(ClassDesc.of(initializationOwner),
                            "$zeron$initialize", emptyVoidMethod());
                    final var ready = composer.newLabel();
                    composer.getstatic(owner, initializedFlag, ConstantDescs.CD_boolean);
                    composer.ifne(ready);
                    final var exception = ClassDesc.of("java.lang.IllegalStateException");
                    composer.new_(exception);
                    composer.dup();
                    composer.ldc("Top-level value '" + fieldName + "' was read before initialization.");
                    composer.invokespecial(exception, "<init>",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                    composer.athrow();
                    composer.labelBinding(ready);
                    composer.getstatic(owner, fieldName, TypeDescriptor.toJavaClassDesc(type));
                    composer.return_(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(type).descriptorString()));
                });
    }

    private static String initializedFlagFieldName(final String fieldName) {
        return "$zeron$initialized$" + fieldName;
    }

    static String topLevelValueAccessorName(final String fieldName) {
        return "$zeron$get$" + fieldName;
    }

}
