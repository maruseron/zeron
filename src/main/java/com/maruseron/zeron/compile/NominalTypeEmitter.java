package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.*;
import java.nio.file.Files;
import java.util.*;

import static com.maruseron.zeron.compile.BytecodeEmitter.*;

final class NominalTypeEmitter {
    static void emit(CompilationContext context) throws IOException {
        for (final var contract : context.resolution.contracts().values()) {
            if (context.metadata.metadataTypeNames().contains(contract.name().lexeme())) continue;
            final var contractName = contract.name().lexeme();
            if (contractName.equals(context.mainClassName)) {
                throw new IllegalStateException("Contract name conflicts with generated program class: " + contractName);
            }
            final var contractPath = outputPath(context, contractName);
            Files.createDirectories(contractPath.getParent());
            context.classFile.buildTo(contractPath.toAbsolutePath(),
                    ClassDesc.of(contractName),
                    builder -> {
                        builder.withFlags((contract.isPublic() ? ClassFile.ACC_PUBLIC : 0)
                            | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
                        if (contract.isSealed()) {
                            builder.with(PermittedSubclassesAttribute.ofSymbols(contract.permittedClasses().stream()
                                    .map(permitted -> ClassDesc.of(permitted.name().lexeme())).toList()));
                        }
                        final var signature = ClassSignatureEmitter.nominalSignature(contract.typeParameters(), List.of());
                        if (signature != null) {
                            builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
                        }
                        for (final var method : contract.methods()) {
                            final var flags = ClassFile.ACC_PUBLIC
                                    | (method.isDefault() ? 0 : ClassFile.ACC_ABSTRACT);
                            final var witnessSlots = BytecodeEmitter.witnessSlots(context, method.typeDescriptor());
                            final var methodDescriptor = BytecodeEmitter.withEvidenceParameters(
                                    toJavaMethodDescriptor(method.typeDescriptor()), witnessSlots.size());
                            if (method.isDefault()) {
                                builder.withMethod(method.name().lexeme(),
                                        methodDescriptor, flags, methodBuilder -> {
                                            final var methodSignature = ClassSignatureEmitter.methodSignature(method.typeDescriptor());
                                            if (methodSignature != null && witnessSlots.isEmpty()) {
                                                methodBuilder.with(SignatureAttribute.of(
                                                        methodBuilder.constantPool().utf8Entry(methodSignature)));
                                            }
                                            methodBuilder.withCode(code -> emitContractMethod(context, code, contract, method));
                                        });
                            } else {
                                builder.withMethod(method.name().lexeme(),
                                        methodDescriptor, flags,
                                        methodBuilder -> {
                                            final var methodSignature = ClassSignatureEmitter.methodSignature(method.typeDescriptor());
                                            if (methodSignature != null && witnessSlots.isEmpty()) {
                                                methodBuilder.with(SignatureAttribute.of(
                                                        methodBuilder.constantPool().utf8Entry(methodSignature)));
                                            }
                                        });
                            }
                            emitContractDefaultArgumentWrappers(context, builder, contract, method);
                        }
                        for (final var property : contract.properties()) {
                            final var getterType = TypeDescriptor.functionOf(
                                    Stmt.propertyGetterName(property.name().lexeme()), property.type());
                            builder.withMethod(Stmt.propertyGetterName(property.name().lexeme()),
                                    toJavaMethodDescriptor(getterType),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, _ -> {});
                            if (property.isMutating()) {
                                final var setterType = TypeDescriptor.functionOf(
                                        Stmt.propertySetterName(property.name().lexeme()),
                                        TypeDescriptor.ofUnit(), property.type());
                                builder.withMethod(Stmt.propertySetterName(property.name().lexeme()),
                                        toJavaMethodDescriptor(setterType),
                                        ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, _ -> {});
                            }
                        }
                    });
        }

        for (final var declaration : context.resolution.classes().values()) {
            if (context.metadata.metadataTypeNames().contains(declaration.name().lexeme())) continue;
            final var className = declaration.name().lexeme();
            if (className.equals(context.mainClassName)) {
                throw new IllegalStateException("Class name conflicts with generated program class: " + className);
            }
            generateNominalClass(context, declaration);
        }
    }

    private static void generateNominalClass(CompilationContext context, final Stmt.ClassDecl declaration) throws IOException {
        final var className = declaration.name().lexeme();
        final var classDesc = ClassDesc.of(className);
        final var interfaces = declaration.contractUses().stream()
            .map(contractUse -> ClassDesc.of(contractUse.name().lexeme()))
            .toList();
        final var classPath = outputPath(context, className);
        Files.createDirectories(classPath.getParent());
        context.classFile.buildTo(classPath.toAbsolutePath(),
                classDesc,
                builder -> {
                    builder.withFlags((declaration.isPublic() ? ClassFile.ACC_PUBLIC : 0) | ClassFile.ACC_FINAL);
                    if (!context.lambdaPlan.lambdaImplementations().isEmpty()
                            && isSamePackage(className, context.mainClassName)) {
                        builder.with(NestHostAttribute.of(ClassDesc.of(context.mainClassName)));
                    }
                    if (!interfaces.isEmpty()) builder.withInterfaceSymbols(interfaces);
                    final var signature = ClassSignatureEmitter.nominalSignature(declaration.typeParameters(),
                            declaration.contractUses());
                    if (signature != null) {
                        builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
                    }
                    for (final var field : declaration.fields()) {
                        builder.withField(field.name().lexeme(), TypeDescriptor.toJavaClassDesc(field.type()),
                                ClassFile.ACC_PRIVATE);
                    }
                    for (final var property : declaration.properties()) {
                        if (!property.isCustom()) {
                            builder.withField(Stmt.propertyBackingFieldName(property.name().lexeme()),
                                    TypeDescriptor.toJavaClassDesc(property.type()), ClassFile.ACC_PRIVATE);
                        }
                    }
                    if (!context.lambdaPlan.lambdaImplementations().isEmpty()
                            && !isSamePackage(className, context.mainClassName)) {
                        emitCrossPackageLambdaAccessBridges(context, builder, declaration, classDesc);
                    }
                    final var constructorParameters = declaration.canonicalConstructorTypes().stream()
                            .map(type -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)))
                            .toList();
                    final var constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, constructorParameters);
                    builder.withMethodBody("<init>", constructorType,
                            declaration.constructor().isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE,
                            code -> emitCanonicalConstructor(context, code, declaration, classDesc));
                    for (final var method : declaration.methods()) {
                        final var flags = method.isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE;
                        final var witnessSlots = BytecodeEmitter.witnessSlots(context, method.typeDescriptor());
                        final var methodDescriptor = BytecodeEmitter.withEvidenceParameters(
                                toJavaMethodDescriptor(method.typeDescriptor()), witnessSlots.size());
                        builder.withMethod(method.name().lexeme(),
                                methodDescriptor, flags,
                                methodBuilder -> {
                                    final var methodSignature = ClassSignatureEmitter.methodSignature(method.typeDescriptor());
                                    if (methodSignature != null && witnessSlots.isEmpty()) {
                                        methodBuilder.with(SignatureAttribute.of(
                                                methodBuilder.constantPool().utf8Entry(methodSignature)));
                                    }
                                    methodBuilder.withCode(code -> emitClassMethod(context, code, declaration, method));
                                });
                        emitClassDefaultArgumentWrappers(context, builder, declaration, classDesc, method, flags);
                    }
                    for (final var pattern : declaration.patterns()) {
                        emitClassPattern(context, builder, declaration, pattern);
                    }
                    for (final var property : declaration.properties()) {
                        emitPropertyMethods(context, builder, declaration, property, classDesc);
                    }

                    for (final var constructor : declaration.namedConstructors()) {
                        final var factoryType = (FunctionDescriptor) TypeSubstitution.erase(
                                constructor.typeDescriptor());
                        final var flags = (constructor.isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE)
                                | ClassFile.ACC_STATIC;
                        builder.withMethodBody(constructor.name().lexeme(),
                                toJavaMethodDescriptor(factoryType), flags,
                                code -> emitNamedConstructor(context, code, constructor));
                    }
                    final var generatedBridges = new HashSet<String>();
                    for (final var contractUse : declaration.contractUses()) {
                        final var contract = context.resolution.contracts().get(contractUse.name().lexeme());
                        for (final var required : contract.methods()) {
                            final var implementation = context.resolution.implementationFor(
                                    declaration, contractUse, required);
                            final var defaultMethod = implementation == null
                                    ? context.resolution.defaultMethodFor(declaration, contractUse, required)
                                    : null;
                            if (implementation == null && defaultMethod == null) continue;
                            final var erasedContractMethod = (FunctionDescriptor) TypeSubstitution.erase(
                                    required.typeDescriptor());
                            final var bridgeDescriptor = toJavaMethodDescriptor(erasedContractMethod);
                            final var implementationDescriptor = implementation != null
                                    ? toJavaMethodDescriptor(implementation.typeDescriptor())
                                    : toJavaMethodDescriptor(defaultMethod.method().typeDescriptor());
                            final var bridgeKey = required.name().lexeme() + bridgeDescriptor.descriptorString();
                            if (bridgeDescriptor.equals(implementationDescriptor)
                                    || !generatedBridges.add(bridgeKey)) continue;
                            builder.withMethodBody(required.name().lexeme(), bridgeDescriptor,
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC,
                                    implementation != null
                                            ? code -> emitContractBridge(context, code, classDesc, required, implementation)
                                            : code -> emitContractDefaultBridge(context, code, defaultMethod, required));
                        }
                        for (final var required : contract.properties()) {
                            final var implementation = declaration.properties().stream()
                                    .filter(property -> property.name().lexeme()
                                            .equals(required.name().lexeme()))
                                    .findFirst()
                                    .orElseThrow();
                            emitPropertyContractBridge(context, builder, classDesc, required, implementation,
                                    generatedBridges);
                        }
                    }
                });
    }

    private static void emitClassPattern(final CompilationContext context,
                                         final ClassBuilder builder,
                                         final Stmt.ClassDecl owner,
                                         final Stmt.Pattern pattern) {
        final var flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC;
        final var objectArrayType = ConstantDescs.CD_Object.arrayType();
        builder.withMethodBody(Stmt.Pattern.helperName(pattern.name().lexeme()),
                MethodTypeDesc.of(objectArrayType), flags, code -> {
                    final var previousReturnType = context.currentReturnType;
                    final var previousOffset = context.localSlotOffset;
                    context.currentReturnType = TypeDescriptor.arrayOf(TypeDescriptor.ofAny());
                    context.localSlotOffset = 0;
                    beginScope(context);
                    final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
                            ? TypeDescriptor.of(owner.name().lexeme())
                            : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                                    owner.typeParameters().stream()
                                            .map(parameter -> (TypeDescriptor) parameter).toList());
                    final var thisToken = new Token(TokenType.THIS, "this", null, pattern.name().span());
                    context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken,
                            ownerType, BindingMutability.IMMUTABLE);
                    context.symbols.define(thisToken);
                    for (final var output : pattern.outputs()) {
                        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, output.name(),
                                output.type(), BindingMutability.REASSIGNABLE);
                        context.symbols.define(output.name());
                    }
                    try {
                        final var refuted = code.newLabel();
                        if (pattern.condition() != null) {
                            BytecodeEmitter.emitExpr(context, code, pattern.condition());
                            code.ifeq(refuted);
                        }
                        StatementEmitter.emitStmts(context, code, pattern.body());
                        code.ldc(pattern.outputs().size());
                        code.anewarray(ConstantDescs.CD_Object);
                        for (int i = 0; i < pattern.outputs().size(); i++) {
                            final var output = pattern.outputs().get(i);
                            final var binding = context.symbols.getSymbol(output.name());
                            code.dup();
                            code.ldc(i);
                            code.loadLocal(TypeKind.fromDescriptor(
                                    TypeDescriptor.toJavaClassDesc(output.type()).descriptorString()),
                                    binding.lvt() + context.localSlotOffset);
                            BytecodeEmitter.emitConversion(context, code, output.type(), TypeDescriptor.ofAny());
                            code.aastore();
                        }
                        code.areturn();
                        if (pattern.condition() != null) {
                            code.labelBinding(refuted);
                            code.aconst_null();
                            code.areturn();
                        }
                    } finally {
                        endScope(context);
                        context.localSlotOffset = previousOffset;
                        context.currentReturnType = previousReturnType;
                    }
                });
    }

    private static void emitContractDefaultBridge(CompilationContext context, final CodeBuilder code,
                                           final Resolver.DefaultMethodSelection defaultMethod,
                                           final Stmt.ContractMethod required ){
        code.aload(0);
        var slot = 1;
        for (int i = 0; i < required.typeDescriptor().parameters().size(); i++) {
            final var erasedParameter = TypeSubstitution.erase(
                    required.typeDescriptor().parameters().get(i));
            final var defaultParameter = defaultMethod.method().typeDescriptor().parameters().get(i);
            loadLocal(context, code, slot, erasedParameter);
            emitConversion(context, code, erasedParameter, defaultParameter);
            slot += erasedParameter.isDoubleWidth() ? 2 : 1;
        }
        code.invokeinterface(ClassDesc.of(defaultMethod.ownerName()), defaultMethod.method().name().lexeme(),
                toJavaMethodDescriptor(defaultMethod.method().typeDescriptor()));
        final var erasedReturn = TypeSubstitution.erase(required.typeDescriptor().returnType());
        emitConversion(context, code, defaultMethod.method().typeDescriptor().returnType(), erasedReturn);
        code.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(erasedReturn).descriptorString()));
    }

    private static void emitContractBridge(CompilationContext context, final CodeBuilder code,
                                    final ClassDesc classDesc,
                                    final Stmt.ContractMethod required,
                                    final Stmt.Method implementation ){
        code.aload(0);
        var slot = 1;
        for (int i = 0; i < required.typeDescriptor().parameters().size(); i++) {
            final var erasedParameter = TypeSubstitution.erase(
                    required.typeDescriptor().parameters().get(i));
            final var implementationParameter = implementation.typeDescriptor().parameters().get(i);
            loadLocal(context, code, slot, erasedParameter);
            emitConversion(context, code, erasedParameter, implementationParameter);
            slot += erasedParameter.isDoubleWidth() ? 2 : 1;
        }
        code.invokevirtual(classDesc, implementation.name().lexeme(),
                toJavaMethodDescriptor(implementation.typeDescriptor()));
        final var erasedReturn = TypeSubstitution.erase(required.typeDescriptor().returnType());
        emitConversion(context, code, implementation.typeDescriptor().returnType(), erasedReturn);
        code.return_(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(erasedReturn).descriptorString()));
    }

    private static void emitPropertyMethods(CompilationContext context, final ClassBuilder builder,
                                     final Stmt.ClassDecl owner,
                                     final Stmt.Property property,
                                     final ClassDesc classDesc ){
        final var flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC;
        final var getterName = Stmt.propertyGetterName(property.name().lexeme());
        final var getterType = TypeDescriptor.functionOf(getterName, property.type());
        if (property.getterBody() != null) {
            final var getter = new Stmt.Method(new Token(TokenType.IDENTIFIER, getterName,
                    null, property.name().span()), List.of(), getterType, property.isPublic(), false,
                    property.getterBody());
            builder.withMethod(getterName, toJavaMethodDescriptor(getterType), flags, methodBuilder -> {
                final var signature = ClassSignatureEmitter.methodSignature(getterType);
                if (signature != null) {
                    methodBuilder.with(SignatureAttribute.of(methodBuilder.constantPool().utf8Entry(signature)));
                }
                methodBuilder.withCode(code -> emitClassMethod(context, code, owner, getter));
            });
        } else {
            final var fieldType = TypeSubstitution.erase(property.type());
            builder.withMethodBody(getterName, toJavaMethodDescriptor(getterType), flags, code -> {
                code.aload(0);
                code.getfield(classDesc, Stmt.propertyBackingFieldName(property.name().lexeme()),
                        TypeDescriptor.toJavaClassDesc(fieldType));
                code.return_(TypeKind.fromDescriptor(
                        TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()));
            });
        }
        if (!property.isMutating()) return;

        final var setterName = Stmt.propertySetterName(property.name().lexeme());
        final var setterType = TypeDescriptor.functionOf(setterName,
                TypeDescriptor.ofUnit(), property.type());
        if (property.setterBody() != null) {
            final var setter = new Stmt.Method(new Token(TokenType.IDENTIFIER, setterName,
                    null, property.name().span()), List.of(property.setterParameter()), setterType,
                    property.isPublic(), true, property.setterBody());
            builder.withMethod(setterName, toJavaMethodDescriptor(setterType), flags, methodBuilder -> {
                final var signature = ClassSignatureEmitter.methodSignature(setterType);
                if (signature != null) {
                    methodBuilder.with(SignatureAttribute.of(methodBuilder.constantPool().utf8Entry(signature)));
                }
                methodBuilder.withCode(code -> emitClassMethod(context, code, owner, setter));
            });
        } else {
            final var fieldType = TypeSubstitution.erase(property.type());
            builder.withMethodBody(setterName, toJavaMethodDescriptor(setterType), flags, code -> {
                code.aload(0);
                loadLocal(context, code, 1, fieldType);
                code.putfield(classDesc, Stmt.propertyBackingFieldName(property.name().lexeme()),
                        TypeDescriptor.toJavaClassDesc(fieldType));
                emitUnitValue(context, code);
                code.areturn();
            });
        }
    }

    private static void emitPropertyContractBridge(CompilationContext context, final ClassBuilder builder,
                                            final ClassDesc classDesc,
                                            final Stmt.ContractProperty required,
                                            final Stmt.Property implementation,
                                            final Set<String> generatedBridges ){
        final var contractGetter = TypeDescriptor.functionOf(
                Stmt.propertyGetterName(required.name().lexeme()), required.type());
        final var implementationGetter = TypeDescriptor.functionOf(
                Stmt.propertyGetterName(implementation.name().lexeme()), implementation.type());
        final var contractGetterDescriptor = toJavaMethodDescriptor(
                (FunctionDescriptor) TypeSubstitution.erase(contractGetter));
        final var implementationGetterDescriptor = toJavaMethodDescriptor(
                (FunctionDescriptor) TypeSubstitution.erase(implementationGetter));
        final var getterKey = Stmt.propertyGetterName(required.name().lexeme())
                + contractGetterDescriptor.descriptorString();
        if (!contractGetterDescriptor.equals(implementationGetterDescriptor)
                && generatedBridges.add(getterKey)) {
            builder.withMethodBody(Stmt.propertyGetterName(required.name().lexeme()),
                    contractGetterDescriptor,
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC,
                    code -> {
                        code.aload(0);
                        code.invokevirtual(classDesc, Stmt.propertyGetterName(implementation.name().lexeme()),
                                implementationGetterDescriptor);
                        final var from = TypeSubstitution.erase(implementation.type());
                        final var to = TypeSubstitution.erase(required.type());
                        emitConversion(context, code, from, to);
                        code.return_(TypeKind.fromDescriptor(
                                TypeDescriptor.toJavaClassDesc(to).descriptorString()));
                    });
        }
        if (!required.isMutating()) return;
        final var contractSetter = TypeDescriptor.functionOf(
                Stmt.propertySetterName(required.name().lexeme()), TypeDescriptor.ofUnit(), required.type());
        final var implementationSetter = TypeDescriptor.functionOf(
                Stmt.propertySetterName(implementation.name().lexeme()), TypeDescriptor.ofUnit(),
                implementation.type());
        final var contractSetterDescriptor = toJavaMethodDescriptor(
                (FunctionDescriptor) TypeSubstitution.erase(contractSetter));
        final var implementationSetterDescriptor = toJavaMethodDescriptor(
                (FunctionDescriptor) TypeSubstitution.erase(implementationSetter));
        final var setterKey = Stmt.propertySetterName(required.name().lexeme())
                + contractSetterDescriptor.descriptorString();
        if (contractSetterDescriptor.equals(implementationSetterDescriptor)
                || !generatedBridges.add(setterKey)) return;
        builder.withMethodBody(Stmt.propertySetterName(required.name().lexeme()),
                contractSetterDescriptor,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC,
                code -> {
                    code.aload(0);
                    final var from = TypeSubstitution.erase(required.type());
                    final var to = TypeSubstitution.erase(implementation.type());
                    loadLocal(context, code, 1, from);
                    emitConversion(context, code, from, to);
                    code.invokevirtual(classDesc, Stmt.propertySetterName(implementation.name().lexeme()),
                            implementationSetterDescriptor);
                    code.areturn();
                });
    }

    private static void emitCanonicalConstructor(CompilationContext context, final CodeBuilder code,
                                          final Stmt.ClassDecl declaration,
                                          final ClassDesc classDesc ){
        code.aload(0);
        code.invokespecial(ConstantDescs.CD_Object, "<init>", emptyVoidMethod());
        final var previousOffset = context.localSlotOffset;
        context.localSlotOffset = 0;
        beginScope(context);
        try {
            final var thisToken = new Token(TokenType.THIS, "this", null, declaration.name().span());
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken,
                    TypeDescriptor.of(declaration.name().lexeme()), BindingMutability.IMMUTABLE);
            context.symbols.define(thisToken);
            for (final var field : declaration.fields()) {
                if (field.initializer() != null) continue;
                final var fieldType = TypeSubstitution.erase(field.type());
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, field.name(), fieldType,
                        BindingMutability.IMMUTABLE);
                context.symbols.define(field.name());
            }
            for (final var property : declaration.properties()) {
                if (property.initializer() != null || property.isCustom()) continue;
                final var fieldType = TypeSubstitution.erase(property.type());
                final var parameter = new Token(TokenType.IDENTIFIER,
                        "$propertyArg$" + property.name().lexeme(), null, property.name().span());
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter, fieldType,
                        BindingMutability.IMMUTABLE);
                context.symbols.define(parameter);
            }
            var slot = 1;
            var fieldIndex = 0;
            for (final var field : declaration.fields()) {
                final var fieldType = TypeSubstitution.erase(field.type());
                if (field.initializer() != null) {
                    emitExpr(context, code, field.initializer());
                    emitConversion(context, code, context.lastEmittedType, fieldType);
                    final var temporary = new Token(TokenType.IDENTIFIER,
                            "$fieldInit$" + fieldIndex, null, field.name().span());
                    final var temporarySlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, temporary,
                            fieldType, BindingMutability.IMMUTABLE);
                    context.symbols.define(temporary);
                    code.storeLocal(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()),
                            temporarySlot + context.localSlotOffset);
                    code.aload(0);
                    loadLocal(context, code, temporarySlot + context.localSlotOffset, fieldType);
                } else {
                    code.aload(0);
                    loadLocal(context, code, slot, fieldType);
                    slot += fieldType.isDoubleWidth() ? 2 : 1;
                }
                code.putfield(classDesc, field.name().lexeme(), TypeDescriptor.toJavaClassDesc(fieldType));
                fieldIndex++;
            }
            for (final var property : declaration.properties()) {
                if (property.isCustom()) continue;
                final var fieldType = TypeSubstitution.erase(property.type());
                if (property.initializer() != null) {
                    emitExpr(context, code, property.initializer());
                    emitConversion(context, code, context.lastEmittedType, fieldType);
                    final var temporary = new Token(TokenType.IDENTIFIER,
                            "$propertyInit$" + fieldIndex, null, property.name().span());
                    final var temporarySlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, temporary,
                            fieldType, BindingMutability.IMMUTABLE);
                    context.symbols.define(temporary);
                    code.storeLocal(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()),
                            temporarySlot + context.localSlotOffset);
                    code.aload(0);
                    loadLocal(context, code, temporarySlot + context.localSlotOffset, fieldType);
                } else {
                    code.aload(0);
                    final var parameter = new Token(TokenType.IDENTIFIER,
                            "$propertyArg$" + property.name().lexeme(), null, property.name().span());
                    loadLocal(context, code, context.symbols.getSymbol(parameter).lvt() + context.localSlotOffset, fieldType);
                    slot += fieldType.isDoubleWidth() ? 2 : 1;
                }
                code.putfield(classDesc, Stmt.propertyBackingFieldName(property.name().lexeme()),
                        TypeDescriptor.toJavaClassDesc(fieldType));
                fieldIndex++;
            }
            code.return_();
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
        }
    }

    private static void emitCrossPackageLambdaAccessBridges(CompilationContext context, final ClassBuilder builder,
                                                     final Stmt.ClassDecl declaration,
                                                     final ClassDesc classDesc ){
        for (final var field : declaration.fields()) {
            final var fieldType = TypeSubstitution.erase(field.type());
            if (context.lambdaPlan.needsLambdaFieldReadBridge(
                    declaration.name().lexeme(), field.name().lexeme())) {
                builder.withMethodBody(lambdaFieldReadBridge(field.name().lexeme()),
                        MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(fieldType), classDesc),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        code -> {
                            code.aload(0);
                            code.getfield(classDesc, field.name().lexeme(),
                                    TypeDescriptor.toJavaClassDesc(fieldType));
                            code.return_(TypeKind.fromDescriptor(
                                    TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()));
                        });
            }
            if (context.lambdaPlan.needsLambdaFieldWriteBridge(
                    declaration.name().lexeme(), field.name().lexeme())) {
                builder.withMethodBody(lambdaFieldWriteBridge(field.name().lexeme()),
                        MethodTypeDesc.of(ConstantDescs.CD_void,
                                classDesc, TypeDescriptor.toJavaClassDesc(fieldType)),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        code -> {
                            code.aload(0);
                            loadLocal(context, code, 1, fieldType);
                            code.putfield(classDesc, field.name().lexeme(),
                                    TypeDescriptor.toJavaClassDesc(fieldType));
                            code.return_();
                        });
            }
        }
        for (final var method : declaration.methods()) {
            if (method.isPublic() || !context.lambdaPlan.needsLambdaMethodBridge(
                    declaration.name().lexeme(), method.name().lexeme())) continue;
            final var erasedMethod = (FunctionDescriptor) TypeSubstitution.erase(method.typeDescriptor());
            final var bridgeParameters = new ArrayList<TypeDescriptor>();
            bridgeParameters.add(TypeDescriptor.of(declaration.name().lexeme()));
            bridgeParameters.addAll(erasedMethod.parameters());
            builder.withMethodBody(lambdaMethodBridge(method.name().lexeme()),
                    toJavaMethodDescriptor(erasedMethod, bridgeParameters),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                    code -> {
                        code.aload(0);
                        var slot = 1;
                        for (final var parameter : erasedMethod.parameters()) {
                            loadLocal(context, code, slot, parameter);
                            slot += parameter.isDoubleWidth() ? 2 : 1;
                        }
                        code.invokespecial(classDesc, method.name().lexeme(),
                                toJavaMethodDescriptor(erasedMethod));
                        code.return_(TypeKind.fromDescriptor(
                                TypeDescriptor.toJavaClassDesc(erasedMethod.returnType()).descriptorString()));
                    });
        }
    }

    static String lambdaFieldReadBridge(final String fieldName) {
        return "$zeron$lambda$read$" + fieldName;
    }

    static String lambdaFieldWriteBridge(final String fieldName) {
        return "$zeron$lambda$write$" + fieldName;
    }

    static String lambdaMethodBridge(final String methodName) {
        return "$zeron$lambda$call$" + methodName;
    }

    private static void emitClassDefaultArgumentWrappers(final CompilationContext context,
                                                         final ClassBuilder builder,
                                                         final Stmt.ClassDecl owner,
                                                         final ClassDesc classDesc,
                                                         final Stmt.Method method,
                                                         final int flags) {
        emitDefaultMethodArgumentWrappers(context, builder, owner.name().lexeme(), classDesc,
                method.name(), method.parameters(), method.typeDescriptor(),
                method.defaultValues(), method.minimumArity(), method.variadic(),
                flags | ClassFile.ACC_SYNTHETIC, false, method.isMutating());
    }

    private static void emitContractDefaultArgumentWrappers(final CompilationContext context,
                                                            final ClassBuilder builder,
                                                            final Stmt.ContractDecl owner,
                                                            final Stmt.ContractMethod method) {
        if (method.defaultValues().isEmpty()) return;
        emitDefaultMethodArgumentWrappers(context, builder, owner.name().lexeme(),
                ClassDesc.of(owner.name().lexeme()),
                method.name(), method.parameters(), method.typeDescriptor(),
                method.defaultValues(), method.minimumArity(), method.variadic(),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_SYNTHETIC, true, method.isMutating());
    }

    private static void emitDefaultMethodArgumentWrappers(final CompilationContext context,
                                                          final ClassBuilder builder,
                                                          final String ownerName,
                                                          final ClassDesc ownerDesc,
                                                          final Token methodName,
                                                          final List<Token> parameters,
                                                          final FunctionDescriptor functionType,
                                                          final List<Expr> defaultValues,
                                                          final int minimumArity,
                                                          final boolean variadic,
                                                          final int flags,
                                                          final boolean isContract,
                                                          final boolean isMutating) {
        if (defaultValues.isEmpty()) return;
        final var fixedArity = parameters.size() - (variadic ? 1 : 0);
        final var lastWrapperArity = variadic ? fixedArity : functionType.arity() - 1;
        for (int arity = minimumArity; arity <= lastWrapperArity; arity++) {
            final var suppliedArity = arity;
            final var wrapperType = TypeDescriptor.functionOf(functionType.name(),
                    functionType.returnType(), functionType.parameters().subList(0, suppliedArity)
                            .toArray(TypeDescriptor[]::new));
            final var runtimeWrapper = (FunctionDescriptor) TypeSubstitution.erase(wrapperType);
            builder.withMethod(methodName.lexeme(), toJavaMethodDescriptor(runtimeWrapper), flags,
                    methodBuilder -> methodBuilder.withCode(code -> {
                        final var previousOffset = context.localSlotOffset;
                        final var previousReturnType = context.currentReturnType;
                        context.localSlotOffset = 0;
                        context.currentReturnType = functionType.returnType();
                        beginScope(context);
                        final var thisToken = new Token(TokenType.THIS, "this", null, methodName.span());
                        final var ownerType = TypeDescriptor.of(ownerName);
                        final var thisType = isMutating
                                ? new ReferenceDescriptor(ownerType)
                                : ownerType;
                        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken,
                                thisType, BindingMutability.IMMUTABLE);
                        context.symbols.define(thisToken);
                        try {
                            for (int i = 0; i < suppliedArity; i++) {
                                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameters.get(i),
                                        functionType.parameters().get(i), BindingMutability.IMMUTABLE);
                                context.symbols.define(parameters.get(i));
                            }
                            for (int i = suppliedArity; i < fixedArity; i++) {
                                emitExpr(context, code, defaultValues.get(i - minimumArity));
                                emitConversion(context, code, context.lastEmittedType, functionType.parameters().get(i));
                                final var parameter = parameters.get(i);
                                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                                        functionType.parameters().get(i), BindingMutability.IMMUTABLE);
                                final var binding = context.symbols.getSymbol(parameter);
                                code.storeLocal(TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(
                                        TypeSubstitution.erase(functionType.parameters().get(i))).descriptorString()),
                                        binding.lvt() + context.localSlotOffset);
                                context.symbols.define(parameter);
                            }
                            if (variadic) {
                                final var parameter = parameters.get(fixedArity);
                                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                                        functionType.parameters().get(fixedArity), BindingMutability.IMMUTABLE);
                                code.iconst_0();
                                code.anewarray(ClassDesc.of("java.lang.Object"));
                                final var binding = context.symbols.getSymbol(parameter);
                                code.astore(binding.lvt() + context.localSlotOffset);
                                context.symbols.define(parameter);
                            }
                            code.aload(0);
                            for (int i = 0; i < functionType.arity(); i++) {
                                loadLocal(context, code, context.symbols.getSymbol(parameters.get(i)).lvt(),
                                        TypeSubstitution.erase(functionType.parameters().get(i)));
                            }
                            final var runtimeType = (FunctionDescriptor) TypeSubstitution.erase(functionType);
                            if (isContract) {
                                code.invokeinterface(ownerDesc, methodName.lexeme(),
                                        toJavaMethodDescriptor(runtimeType));
                            } else if ((flags & ClassFile.ACC_PRIVATE) != 0) {
                                code.invokespecial(ownerDesc, methodName.lexeme(),
                                        toJavaMethodDescriptor(runtimeType));
                            } else {
                                code.invokevirtual(ownerDesc, methodName.lexeme(),
                                        toJavaMethodDescriptor(runtimeType));
                            }
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

    private static void emitClassMethod(CompilationContext context, final CodeBuilder code,
                                 final Stmt.ClassDecl owner,
                                 final Stmt.Method method ){
        final var previousReturnType = context.currentReturnType;
        final var previousOffset = context.localSlotOffset;
        context.currentReturnType = method.typeDescriptor().returnType();
        context.localSlotOffset = 0;
        beginScope(context);
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
            ? TypeDescriptor.of(owner.name().lexeme())
            : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var thisType = method.isMutating()
            ? new ReferenceDescriptor(ownerType)
            : ownerType;
        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        context.symbols.define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            context.symbols.define(parameter);
        }
        declareEvidenceSlots(context, method.typeDescriptor());
        try {
            StatementEmitter.emitStmts(context, code, method.body());
            if (context.currentReturnType instanceof UnitDescriptor) {
                emitUnitValue(context, code);
                code.areturn();
            }
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
            context.currentReturnType = previousReturnType;
        }
    }

    private static void emitContractMethod(CompilationContext context, final CodeBuilder code,
                                    final Stmt.ContractDecl owner,
                                    final Stmt.ContractMethod method ){
        final var previousReturnType = context.currentReturnType;
        final var previousOffset = context.localSlotOffset;
        context.currentReturnType = method.typeDescriptor().returnType();
        context.localSlotOffset = 0;
        beginScope(context);
        final var thisToken = new Token(TokenType.THIS, "this", null, method.name().span());
        final TypeDescriptor ownerType = owner.typeParameters().isEmpty()
                ? TypeDescriptor.of(owner.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(owner.name().lexeme()),
                        owner.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var thisType = method.isMutating()
                ? new ReferenceDescriptor(ownerType)
                : ownerType;
        context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, thisToken, thisType, BindingMutability.IMMUTABLE);
        context.symbols.define(thisToken);
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            context.symbols.define(parameter);
        }
        declareEvidenceSlots(context, method.typeDescriptor());
        try {
            StatementEmitter.emitStmts(context, code, method.body());
            if (context.currentReturnType instanceof UnitDescriptor) {
                emitUnitValue(context, code);
                code.areturn();
            }
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
            context.currentReturnType = previousReturnType;
        }
    }

    private static void declareEvidenceSlots(final CompilationContext context,
                                            final FunctionDescriptor functionType) {
        for (final var witnessToken : BytecodeEmitter.witnessSlots(context, functionType)) {
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, witnessToken,
                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"), BindingMutability.IMMUTABLE);
            context.symbols.define(witnessToken);
        }
    }

    private static void emitNamedConstructor(CompilationContext context, final CodeBuilder code,
                                      final Stmt.NamedConstructor constructor ){
        final var previousReturnType = context.currentReturnType;
        final var previousOffset = context.localSlotOffset;
        context.currentReturnType = constructor.typeDescriptor().returnType();
        context.localSlotOffset = 0;
        beginScope(context);
        for (int i = 0; i < constructor.parameters().size(); i++) {
            final var parameter = constructor.parameters().get(i);
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    constructor.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            context.symbols.define(parameter);
        }
        try {
            StatementEmitter.emitStmts(context, code, constructor.body());
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
            context.currentReturnType = previousReturnType;
        }
    }

    static void loadLocal(CompilationContext context, final CodeBuilder code, final int slot, final TypeDescriptor type ){
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "I", "Z" -> code.iload(slot);
            case "D" -> code.dload(slot);
            default -> code.aload(slot);
        }
    }

}
