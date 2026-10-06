package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
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
                    final var name = function.name();
                    final var parameters = function.parameters();
                    final var typeDescriptor = function.typeDescriptor();
                    final var sourceFunction = function instanceof Stmt.Function source ? source : null;
                    final var externalFunction = function instanceof Stmt.ExternalFunction external
                            ? external
                            : null;
                    final var functionToken = context.resolution.functionSymbolToken(name);
                    final var functionType = context.symbols.getFunctionType(functionToken);
                    if (entryHolder && name.lexeme().equals("main")
                            && functionType.parameters().isEmpty()
                            && functionType.returnType() instanceof UnitDescriptor) {
                        hasLaunchableMain = true;
                    }
                    classBuilder.withMethod(
                            name.lexeme(),
                            toJavaMethodDescriptor(functionType),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                            methodBuilder -> {
                                final var signature = ClassSignatureEmitter.methodSignature(typeDescriptor);
                                if (signature != null) {
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
                }
                default -> {}
            }
        }

        if (entryHolder && hasLaunchableMain) {
            classBuilder.withMethodBody(
                    "main",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    composer -> {
                        composer.invokestatic(composer.constantPool().methodRefEntry(
                                ClassDesc.of(context.currentHolderName),
                                "main",
                                MethodTypeDesc.of(UNIT_CLASS)));
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
                    implementationParameters.addAll(functionType.parameters());
                    classBuilder.withMethodBody(
                        context.lambdaPlan.lambdaMethodName(lambda),
                        toJavaMethodDescriptor(functionType, implementationParameters),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        composer -> LambdaSupportEmitter.emitLambdaImplementation(context, composer, lambda));
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
                                toJavaMethodDescriptor(reference.targetType()),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                                composer -> LambdaSupportEmitter.emitFunctionReferenceImplementation(context, composer, reference));
                    }
    }

    private static void generateTopLevelValueMethods(CompilationContext context, final ClassBuilder classBuilder,
                                              final Stmt.Var variable,
                                              final TypeDescriptor type ){
        final var owner = ClassDesc.of(context.currentHolderName);
        final var fieldName = variable.name().lexeme();
        final var initializedFlag = initializedFlagFieldName(fieldName);
        final var initializerName = context.initializationPlan.initializerNames().get(variable);
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
                    if (context.currentHolderName.contains("$zeron$Namespace$")) {
                        composer.invokestatic(ClassDesc.of(context.mainClassName),
                                "$zeron$initialize", emptyVoidMethod());
                    }
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
