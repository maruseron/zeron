package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.analize.ContractComposition;
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
            final var sumDeclaration = context.compilationUnits.stream()
                    .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                    .map(NamespaceMembers.Member::declaration)
                    .filter(Stmt.SumDecl.class::isInstance)
                    .map(Stmt.SumDecl.class::cast)
                    .filter(sum -> sum.name().lexeme().equals(contractName))
                    .findFirst().orElse(null);
            context.classFile.buildTo(contractPath.toAbsolutePath(),
                    ClassDesc.of(contractName),
                    builder -> {
                        builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                        builder.withFlags((contract.isPublic() ? ClassFile.ACC_PUBLIC : 0)
                            | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
                        if (!contract.requiredContracts().isEmpty()) {
                            builder.withInterfaceSymbols(contract.requiredContracts().stream()
                                    .map(required -> ClassDesc.of(required.name().lexeme())).toList());
                        }
                        if (contract.isSealed()) {
                            builder.with(PermittedSubclassesAttribute.ofSymbols(contract.permittedClasses().stream()
                                    .map(permitted -> ClassDesc.of(permitted.name().lexeme())).toList()));
                        }
                        final var signature = ClassSignatureEmitter.nominalSignature(
                                contract.typeParameters(), contract.requiredContracts());
                        if (signature != null) {
                            builder.with(SignatureAttribute.of(builder.constantPool().utf8Entry(signature)));
                        }
                        for (final var evidenceSlot : BytecodeEmitter.evidenceFields(
                                context, contract.typeParameters(), "contract", contract.name().lexeme())) {
                            builder.withMethod(evidenceSlot.fieldName(),
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.MethodHandle")),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT | ClassFile.ACC_SYNTHETIC,
                                    _ -> {});
                        }
                        if (sumDeclaration != null) emitSumEqualityMethod(builder, sumDeclaration);
                        if (sumDeclaration != null && derives(sumDeclaration.derivedContracts(), "Hashable")) {
                            emitSumHashMethod(builder, sumDeclaration);
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
                            if (!witnessSlots.isEmpty()) {
                                final var bridgeType = (FunctionDescriptor) TypeSubstitution.erase(
                                        method.typeDescriptor());
                                final var bridgeFlags = ClassFile.ACC_PUBLIC
                                        | (method.isDefault() ? 0 : ClassFile.ACC_ABSTRACT)
                                        | ClassFile.ACC_SYNTHETIC;
                                builder.withMethod(method.name().lexeme(),
                                        toJavaMethodDescriptor(bridgeType), bridgeFlags, methodBuilder -> {
                                            final var methodSignature =
                                                    ClassSignatureEmitter.methodSignature(method.typeDescriptor());
                                            if (methodSignature != null) {
                                                methodBuilder.with(SignatureAttribute.of(
                                                        methodBuilder.constantPool().utf8Entry(methodSignature)));
                                            }

                                            if (method.isDefault()) {
                                                methodBuilder.withCode(BytecodeEmitter::emitEvidenceUnavailable);
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

    private static void emitSumEqualityMethod(final ClassBuilder builder, final Stmt.SumDecl sum) {
        final var root = ClassDesc.of(sum.name().lexeme());
        final var appliedRoot = sum.typeParameters().isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(sum.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(sum.name().lexeme()),
                        sum.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var evidenceCount = sum.cases().stream().flatMap(sumCase -> sumCase.payloads().stream())
                .filter(payload -> !isRecursiveSumPayload(payload.type(), appliedRoot)).count();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(root);
        parameters.add(root);
        for (long index = 0; index < evidenceCount; index++) {
            parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_boolean, parameters);
        builder.withMethodBody("$zeron$eq", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var unequal = code.newLabel();
                    var evidenceIndex = 0;
                    for (int caseIndex = 0; caseIndex < sum.cases().size(); caseIndex++) {
                        final var sumCase = sum.cases().get(caseIndex);
                        final var caseClass = ClassDesc.of(sum.generatedCaseName(sumCase));
                        final var nextCase = code.newLabel();
                        code.aload(0);
                        code.instanceOf(caseClass);
                        code.ifeq(nextCase);
                        code.aload(1);
                        code.instanceOf(caseClass);
                        code.ifeq(unequal);
                        for (int payloadIndex = 0; payloadIndex < sumCase.payloads().size(); payloadIndex++) {
                            final var payloadType = sumCase.payloads().get(payloadIndex).type();
                            final var payloadClass = TypeDescriptor.toJavaClassDesc(
                                    TypeSubstitution.erase(payloadType));
                            final var recursive = isRecursiveSumPayload(payloadType, appliedRoot);
                            if (recursive) {
                                final var leftSlot = 2 + (int) evidenceCount;
                                final var rightSlot = leftSlot + 1;
                                final var payloadDone = payloadType instanceof NullableDescriptor
                                        ? code.newLabel() : null;
                                if (payloadType instanceof NullableDescriptor) {
                                    final var checkRight = code.newLabel();
                                    code.aload(0);
                                    code.checkcast(caseClass);
                                    code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                            MethodTypeDesc.of(payloadClass));
                                    code.astore(leftSlot);
                                    code.aload(1);
                                    code.checkcast(caseClass);
                                    code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                            MethodTypeDesc.of(payloadClass));
                                    code.astore(rightSlot);
                                    code.aload(leftSlot);
                                    code.ifnonnull(checkRight);
                                    code.aload(rightSlot);
                                    code.ifnonnull(unequal);
                                    code.goto_(payloadDone);
                                    code.labelBinding(checkRight);
                                    code.aload(rightSlot);
                                    code.ifnull(unequal);
                                    code.aload(leftSlot);
                                    code.checkcast(root);
                                    code.aload(rightSlot);
                                    code.checkcast(root);
                                } else {
                                    code.aload(0);
                                    code.checkcast(caseClass);
                                    code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                            MethodTypeDesc.of(payloadClass));
                                    code.aload(1);
                                    code.checkcast(caseClass);
                                    code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                            MethodTypeDesc.of(payloadClass));
                                }
                                for (int handleIndex = 0; handleIndex < evidenceCount; handleIndex++) {
                                    code.aload(2 + handleIndex);
                                }
                                code.invokestatic(root, "$zeron$eq", methodType, true);
                                if (payloadType instanceof NullableDescriptor) {
                                    code.ifeq(unequal);
                                    code.labelBinding(payloadDone);
                                }
                            } else {
                                code.aload(2 + evidenceIndex);
                                code.aload(0);
                                code.checkcast(caseClass);
                                code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                        MethodTypeDesc.of(payloadClass));
                                code.aload(1);
                                code.checkcast(caseClass);
                                code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                        MethodTypeDesc.of(payloadClass));
                                code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                                        MethodTypeDesc.of(ConstantDescs.CD_boolean, payloadClass, payloadClass));
                                evidenceIndex++;
                            }
                            if (!recursive || !(payloadType instanceof NullableDescriptor)) code.ifeq(unequal);
                        }
                        code.iconst_1();
                        code.ireturn();
                        code.labelBinding(nextCase);
                    }
                    code.labelBinding(unequal);
                    code.iconst_0();
                    code.ireturn();
                });
    }

    private static void emitSumHashMethod(final ClassBuilder builder, final Stmt.SumDecl sum) {
        final var root = ClassDesc.of(sum.name().lexeme());
        final var appliedRoot = appliedType(sum.name().lexeme(), sum.typeParameters());
        final var evidenceCount = sum.cases().stream().flatMap(sumCase -> sumCase.payloads().stream())
                .filter(payload -> !isRecursiveSumPayload(payload.type(), appliedRoot)).count();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(root);
        for (long index = 0; index < evidenceCount; index++) {
            parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_int, parameters);
        builder.withMethodBody("$zeron$hash", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var accumulator = 1 + (int) evidenceCount;
                    var evidenceIndex = 0;
                    for (int caseIndex = 0; caseIndex < sum.cases().size(); caseIndex++) {
                        final var sumCase = sum.cases().get(caseIndex);
                        final var caseClass = ClassDesc.of(sum.generatedCaseName(sumCase));
                        final var nextCase = code.newLabel();
                        code.aload(0);
                        code.instanceOf(caseClass);
                        code.ifeq(nextCase);
                        code.ldc(caseIndex + 1);
                        code.istore(accumulator);
                        for (int payloadIndex = 0; payloadIndex < sumCase.payloads().size(); payloadIndex++) {
                            final var payloadType = sumCase.payloads().get(payloadIndex).type();
                            final var payloadClass = TypeDescriptor.toJavaClassDesc(
                                    TypeSubstitution.erase(payloadType));
                            final var recursive = isRecursiveSumPayload(payloadType, appliedRoot);
                            code.iload(accumulator);
                            code.ldc(31);
                            code.imul();
                            if (recursive) {
                                final var payloadValue = accumulator + 1;
                                code.aload(0);
                                code.checkcast(caseClass);
                                code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                        MethodTypeDesc.of(payloadClass));
                                if (payloadType instanceof NullableDescriptor) {
                                    final var nonNull = code.newLabel();
                                    final var done = code.newLabel();
                                    code.astore(payloadValue);
                                    code.aload(payloadValue);
                                    code.ifnonnull(nonNull);
                                    code.iconst_0();
                                    code.goto_(done);
                                    code.labelBinding(nonNull);
                                    code.aload(payloadValue);
                                    code.checkcast(root);
                                    for (int handleIndex = 0; handleIndex < evidenceCount; handleIndex++) {
                                        code.aload(1 + handleIndex);
                                    }
                                    code.invokestatic(root, "$zeron$hash", methodType, true);
                                    code.labelBinding(done);
                                } else {
                                    code.checkcast(root);
                                    for (int handleIndex = 0; handleIndex < evidenceCount; handleIndex++) {
                                        code.aload(1 + handleIndex);
                                    }
                                    code.invokestatic(root, "$zeron$hash", methodType, true);
                                }
                            } else {
                                code.aload(1 + evidenceIndex++);
                                code.aload(0);
                                code.checkcast(caseClass);
                                code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                                        MethodTypeDesc.of(payloadClass));
                                code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                                        MethodTypeDesc.of(ConstantDescs.CD_int, payloadClass));
                            }
                            code.iadd();
                            code.istore(accumulator);
                        }
                        code.iload(accumulator);
                        code.ireturn();
                        code.labelBinding(nextCase);
                    }
                    code.iconst_0();
                    code.ireturn();
                });
    }

    private static boolean sameAppliedType(final TypeDescriptor left, final TypeDescriptor right) {
        final var normalizedLeft = left instanceof ReferenceDescriptor reference ? reference.baseType() : left;
        final var normalizedRight = right instanceof ReferenceDescriptor reference ? reference.baseType() : right;
        return normalizedLeft.equals(normalizedRight);
    }

    private static boolean isRecursiveSumPayload(final TypeDescriptor payloadType,
                                                 final TypeDescriptor appliedRoot) {
        final var normalizedPayload = payloadType instanceof NullableDescriptor nullable
                ? nullable.baseType() : payloadType;
        return sameAppliedType(normalizedPayload, appliedRoot);
    }

    private static void generateNominalClass(CompilationContext context, final Stmt.ClassDecl declaration) throws IOException {
        final var className = declaration.name().lexeme();
        final var generatedSumCase = className.contains("$$sum$");
        final var product = productDeclaration(context, className);
        final var newtype = newtypeDeclaration(context, className);
        final var classDesc = ClassDesc.of(className);
        final var interfaces = declaration.contractUses().stream()
            .map(contractUse -> ClassDesc.of(contractUse.name().lexeme()))
            .toList();
        final var classPath = outputPath(context, className);
        Files.createDirectories(classPath.getParent());
        context.classFile.buildTo(classPath.toAbsolutePath(),
                classDesc,
                builder -> {
                    builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                    builder.withFlags((declaration.isPublic() ? ClassFile.ACC_PUBLIC : 0) | ClassFile.ACC_FINAL);
                    final var nestHost = newtype != null && newtype.validator() != null
                            ? newtypeNestHost(context, newtype)
                            : !context.lambdaPlan.lambdaImplementations().isEmpty()
                                && isSamePackage(className, context.mainClassName)
                                    ? context.mainClassName : null;
                    if (nestHost != null) {
                        builder.with(NestHostAttribute.of(ClassDesc.of(nestHost)));
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
                    final var classEvidenceSlots = BytecodeEmitter.classEvidenceFields(context, declaration);
                    for (final var evidenceSlot : classEvidenceSlots) {
                        builder.withField(evidenceSlot.fieldName(),
                                ClassDesc.of("java.lang.invoke.MethodHandle"),
                                ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
                    }
                    if (generatedSumCase && declaration.fields().isEmpty()) {
                        builder.withField("$zeron$singleton", classDesc,
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                    }
                    for (final var property : declaration.properties()) {
                        if (!property.isCustom()) {
                            builder.withField(Stmt.propertyBackingFieldName(property.name().lexeme()),
                                    TypeDescriptor.toJavaClassDesc(property.type()),
                                    ClassFile.ACC_PRIVATE
                                            | (product == null && newtype == null ? 0 : ClassFile.ACC_FINAL));
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
                    final var constructorFlags = declaration.constructor().isPublic()
                            ? ClassFile.ACC_PUBLIC
                            : ClassFile.ACC_PRIVATE;
                    builder.withMethodBody("<init>",
                            BytecodeEmitter.withEvidenceParameters(constructorType, classEvidenceSlots.size()),
                            constructorFlags,
                            code -> emitCanonicalConstructor(context, code, declaration, classDesc,
                                    classEvidenceSlots));
                    if (!classEvidenceSlots.isEmpty()) {
                        builder.withMethodBody("<init>", constructorType, constructorFlags,
                                BytecodeEmitter::emitEvidenceUnavailable);
                    }
                    if (generatedSumCase && declaration.fields().isEmpty()) {
                        builder.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void),
                                ClassFile.ACC_STATIC, code -> {
                                    code.new_(classDesc);
                                    code.dup();
                                    code.invokespecial(classDesc, "<init>", constructorType);
                                    code.putstatic(classDesc, "$zeron$singleton", classDesc);
                                    code.return_();
                                });
                    }
                    if (generatedSumCase) {
                        for (int index = 0; index < declaration.fields().size(); index++) {
                            final var field = declaration.fields().get(index);
                            final var fieldType = TypeSubstitution.erase(field.type());
                            final var getterName = "$zeron$payload$" + index;
                            builder.withMethodBody(getterName,
                                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(fieldType)),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, code -> {
                                        code.aload(0);
                                        code.getfield(classDesc, field.name().lexeme(),
                                                TypeDescriptor.toJavaClassDesc(fieldType));
                                        code.return_(TypeKind.fromDescriptor(
                                                TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()));
                                    });
                        }
                    }
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
                        if (!witnessSlots.isEmpty()) {
                            final var bridgeType = (FunctionDescriptor) TypeSubstitution.erase(
                                    method.typeDescriptor());
                            builder.withMethod(method.name().lexeme(),
                                    toJavaMethodDescriptor(bridgeType), flags | ClassFile.ACC_SYNTHETIC,
                                    methodBuilder -> {
                                        final var methodSignature =
                                                ClassSignatureEmitter.methodSignature(method.typeDescriptor());
                                        if (methodSignature != null) {
                                            methodBuilder.with(SignatureAttribute.of(
                                                    methodBuilder.constantPool().utf8Entry(methodSignature)));
                                        }
                                        methodBuilder.withCode(BytecodeEmitter::emitEvidenceUnavailable);
                                    });
                        }
                        emitClassDefaultArgumentWrappers(context, builder, declaration, classDesc, method, flags);
                    }
                    for (final var pattern : declaration.patterns()) {
                        emitClassPattern(context, builder, declaration, pattern);
                    }
                    for (final var property : declaration.properties()) {
                        emitPropertyMethods(context, builder, declaration, property, classDesc);
                    }
                    if (product != null) {
                        emitProductEqualityMethod(builder, product);
                        if (derives(product.derivedContracts(), "Hashable")) {
                            emitProductHashMethod(builder, product);
                        }
                    } else {
                        final var sourceDeclaration = sourceTypeDeclaration(context, className);
                        if (sourceDeclaration != null
                                && derives(derivedContracts(sourceDeclaration), "Eq")) {
                            emitClassEqualityMethod(builder, declaration);
                        }
                        if (sourceDeclaration != null
                                && derives(derivedContracts(sourceDeclaration), "Hashable")) {
                            emitClassHashMethod(builder, declaration);
                        }
                    }

                    for (final var constructor : declaration.namedConstructors()) {
                        final var factoryType = (FunctionDescriptor) TypeSubstitution.erase(
                                constructor.typeDescriptor());
                        final var evidenceType = BytecodeEmitter.withEvidenceParameters(
                                toJavaMethodDescriptor(factoryType), classEvidenceSlots.size());
                        final var flags = (constructor.isPublic() ? ClassFile.ACC_PUBLIC : ClassFile.ACC_PRIVATE)
                                | ClassFile.ACC_STATIC;
                        builder.withMethodBody(constructor.name().lexeme(),
                                evidenceType, flags,
                                code -> emitNamedConstructor(context, code, constructor, classEvidenceSlots));
                        if (!classEvidenceSlots.isEmpty()) {
                            builder.withMethodBody(constructor.name().lexeme(),
                                    toJavaMethodDescriptor(factoryType), flags,
                                    BytecodeEmitter::emitEvidenceUnavailable);
                        }
                    }

                    final var generatedBridges = new HashSet<String>();
                    for (final var applied : ContractComposition.forClass(
                            context.resolution.contracts(), declaration)) {
                        final var contractUse = applied.use();
                        final var contract = applied.declaration();
                        for (final var evidenceSlot : BytecodeEmitter.evidenceFields(
                                context, contract.typeParameters(), "contract", contract.name().lexeme())) {
                            builder.withMethodBody(evidenceSlot.fieldName(),
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.MethodHandle")),
                                    ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC,
                                    code -> {
                                        code.aload(0);
                                        code.getfield(classDesc, evidenceSlot.fieldName(),
                                                ClassDesc.of("java.lang.invoke.MethodHandle"));
                                        code.areturn();
                                    });
                        }
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

    private static Stmt.ProductDecl productDeclaration(final CompilationContext context,
                                                       final String className) {
        return context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration)
                .filter(Stmt.ProductDecl.class::isInstance)
                .map(Stmt.ProductDecl.class::cast)
                .filter(product -> product.name().lexeme().equals(className))
                .findFirst().orElse(null);
    }

    private static Stmt.NewtypeDecl newtypeDeclaration(final CompilationContext context,
                                                       final String className) {
        return context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration)
                .filter(Stmt.NewtypeDecl.class::isInstance)
                .map(Stmt.NewtypeDecl.class::cast)
                .filter(newtype -> newtype.name().lexeme().equals(className))
                .findFirst().orElse(null);
    }

    private static String newtypeNestHost(final CompilationContext context,
                                          final Stmt.NewtypeDecl newtype) {
        for (final var unit : context.compilationUnits) {
            if (NamespaceMembers.flatten(unit.declarations()).stream()
                    .map(NamespaceMembers.Member::declaration).anyMatch(declaration -> declaration == newtype)) {
                final var namespaceName = newtype.name().lexeme()
                        .substring(newtype.name().lexeme().lastIndexOf('.') + 1);
                return CompilationMetadata.namespaceOwner(unit.packageName(), namespaceName);
            }
        }
        throw new IllegalStateException("Newtype declaration has no compilation unit.");
    }

    private static void emitProductEqualityMethod(final ClassBuilder builder,
                                                  final Stmt.ProductDecl product) {
        final var owner = ClassDesc.of(product.name().lexeme());
        final var appliedProduct = product.typeParameters().isEmpty()
                ? (TypeDescriptor) TypeDescriptor.ofName(product.name().lexeme())
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(product.name().lexeme()),
                        product.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
        final var evidenceCount = product.components().stream()
                .filter(component -> !isRecursiveProductComponent(component.type(), appliedProduct))
                .count();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        parameters.add(owner);
        for (long index = 0; index < evidenceCount; index++) {
            parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_boolean, parameters);
        builder.withMethodBody("$zeron$eq", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var unequal = code.newLabel();
                    var evidenceIndex = 0;
                    for (final var component : product.components()) {
                        final var componentType = component.type();
                        final var componentClass = TypeDescriptor.toJavaClassDesc(
                                TypeSubstitution.erase(componentType));
                        final var recursive = isRecursiveProductComponent(componentType, appliedProduct);
                        if (recursive) {
                            final var leftSlot = 2 + (int) evidenceCount;
                            final var rightSlot = leftSlot + 1;
                            final var getterName = Stmt.propertyGetterName(component.name().lexeme());
                            final var getterType = MethodTypeDesc.of(componentClass);
                            final var compared = componentType instanceof NullableDescriptor
                                    ? code.newLabel() : null;
                            if (componentType instanceof NullableDescriptor) {
                                final var checkRight = code.newLabel();
                                code.aload(0);
                                code.invokevirtual(owner, getterName, getterType);
                                code.astore(leftSlot);
                                code.aload(1);
                                code.invokevirtual(owner, getterName, getterType);
                                code.astore(rightSlot);
                                code.aload(leftSlot);
                                code.ifnonnull(checkRight);
                                code.aload(rightSlot);
                                code.ifnonnull(unequal);
                                code.goto_(compared);
                                code.labelBinding(checkRight);
                                code.aload(rightSlot);
                                code.ifnull(unequal);
                                code.aload(leftSlot);
                                code.checkcast(owner);
                                code.aload(rightSlot);
                                code.checkcast(owner);
                            } else {
                                code.aload(0);
                                code.invokevirtual(owner, getterName, getterType);
                                code.checkcast(owner);
                                code.aload(1);
                                code.invokevirtual(owner, getterName, getterType);
                                code.checkcast(owner);
                            }
                            for (int handleIndex = 0; handleIndex < evidenceCount; handleIndex++) {
                                code.aload(2 + handleIndex);
                            }
                            code.invokestatic(owner, "$zeron$eq", methodType);
                            if (compared != null) {
                                code.ifeq(unequal);
                                code.labelBinding(compared);
                            } else {
                                code.ifeq(unequal);
                            }
                        } else {
                            final var handleType = MethodTypeDesc.of(
                                    ConstantDescs.CD_boolean, componentClass, componentClass);
                            code.aload(2 + evidenceIndex++);
                            code.aload(0);
                            code.invokevirtual(owner, Stmt.propertyGetterName(component.name().lexeme()),
                                    MethodTypeDesc.of(componentClass));
                            code.aload(1);
                            code.invokevirtual(owner, Stmt.propertyGetterName(component.name().lexeme()),
                                    MethodTypeDesc.of(componentClass));
                            code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke", handleType);
                            code.ifeq(unequal);
                        }
                    }
                    code.iconst_1();
                    code.ireturn();
                    code.labelBinding(unequal);
                    code.iconst_0();
                    code.ireturn();
                });
    }

    private static void emitProductHashMethod(final ClassBuilder builder,
                                              final Stmt.ProductDecl product) {
        final var owner = ClassDesc.of(product.name().lexeme());
        final var appliedProduct = appliedType(product.name().lexeme(), product.typeParameters());
        final var components = product.components().stream()
                .filter(component -> !isRecursiveProductComponent(component.type(), appliedProduct))
                .toList();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        components.forEach(_ -> parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle")));
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_int, parameters);
        builder.withMethodBody("$zeron$hash", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var accumulator = 1 + components.size();
                    code.iconst_1();
                    code.istore(accumulator);
                    var handleIndex = 0;
                    for (final var component : product.components()) {
                        final var componentType = component.type();
                        code.iload(accumulator);
                        code.ldc(31);
                        code.imul();
                        if (isRecursiveProductComponent(componentType, appliedProduct)) {
                            emitRecursiveHash(code, owner, appliedProduct, componentType,
                                    Stmt.propertyGetterName(component.name().lexeme()),
                                    true,
                                    componentType instanceof NullableDescriptor,
                                    components.size(), 0, accumulator);
                        } else {
                            final var componentClass = TypeDescriptor.toJavaClassDesc(
                                    TypeSubstitution.erase(componentType));
                            code.aload(1 + handleIndex++);
                            code.aload(0);
                            code.invokevirtual(owner, Stmt.propertyGetterName(component.name().lexeme()),
                                    MethodTypeDesc.of(componentClass));
                            code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                                    MethodTypeDesc.of(ConstantDescs.CD_int, componentClass));
                        }
                        code.iadd();
                        code.istore(accumulator);
                    }
                    code.iload(accumulator);
                    code.ireturn();
                });
    }

    private record StoredComponent(String name, TypeDescriptor type, boolean property) {}

    private static void emitClassEqualityMethod(final ClassBuilder builder,
                                               final Stmt.ClassDecl declaration) {
        final var owner = ClassDesc.of(declaration.name().lexeme());
        final var appliedType = appliedType(declaration.name().lexeme(), declaration.typeParameters());
        final var components = storedComponents(declaration);
        final var evidenceCount = components.stream()
                .filter(component -> !isRecursiveProductComponent(component.type(), appliedType)).count();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        parameters.add(owner);
        for (long index = 0; index < evidenceCount; index++) {
            parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_boolean, parameters);
        builder.withMethodBody("$zeron$eq", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var unequal = code.newLabel();
                    var evidenceIndex = 0;
                    for (final var component : components) {
                        final var componentType = component.type();
                        final var componentClass = TypeDescriptor.toJavaClassDesc(
                                TypeSubstitution.erase(componentType));
                        if (isRecursiveProductComponent(componentType, appliedType)) {
                            emitRecursiveEquality(code, owner, componentType, component.name(),
                                    component.property(), componentType instanceof NullableDescriptor,
                                    (int) evidenceCount, unequal);
                        } else {
                            final var handleType = MethodTypeDesc.of(
                                    ConstantDescs.CD_boolean, componentClass, componentClass);
                            code.aload(2 + evidenceIndex++);
                            code.aload(0);
                            emitLoadStoredComponent(code, owner, component, componentClass);
                            code.aload(1);
                            emitLoadStoredComponent(code, owner, component, componentClass);
                            code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke", handleType);
                            code.ifeq(unequal);
                        }
                    }
                    code.iconst_1();
                    code.ireturn();
                    code.labelBinding(unequal);
                    code.iconst_0();
                    code.ireturn();
                });
    }

    private static void emitClassHashMethod(final ClassBuilder builder,
                                            final Stmt.ClassDecl declaration) {
        final var owner = ClassDesc.of(declaration.name().lexeme());
        final var appliedType = appliedType(declaration.name().lexeme(), declaration.typeParameters());
        final var components = storedComponents(declaration);
        final var evidenceCount = (int) components.stream()
                .filter(component -> !isRecursiveProductComponent(component.type(), appliedType)).count();
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        for (int index = 0; index < evidenceCount; index++) {
            parameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var methodType = MethodTypeDesc.of(ConstantDescs.CD_int, parameters);
        builder.withMethodBody("$zeron$hash", methodType,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                code -> {
                    final var accumulator = 1 + evidenceCount;
                    code.iconst_1();
                    code.istore(accumulator);
                    var handleIndex = 0;
                    for (final var component : components) {
                        final var componentType = component.type();
                        code.iload(accumulator);
                        code.ldc(31);
                        code.imul();
                        if (isRecursiveProductComponent(componentType, appliedType)) {
                            emitRecursiveHash(code, owner, appliedType, componentType,
                                    component.name(), component.property(),
                                    componentType instanceof NullableDescriptor,
                                    evidenceCount, 0, accumulator);
                        } else {
                            final var componentClass = TypeDescriptor.toJavaClassDesc(
                                    TypeSubstitution.erase(componentType));
                            code.aload(1 + handleIndex++);
                            code.aload(0);
                            emitLoadStoredComponent(code, owner, component, componentClass);
                            code.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                                    MethodTypeDesc.of(ConstantDescs.CD_int, componentClass));
                        }
                        code.iadd();
                        code.istore(accumulator);
                    }
                    code.iload(accumulator);
                    code.ireturn();
                });
    }

    private static void emitRecursiveEquality(
            final CodeBuilder code, final ClassDesc owner, final TypeDescriptor componentType,
            final String componentName, final boolean property, final boolean nullable,
            final int evidenceCount, final Label unequal) {
        final var componentClass = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(componentType));
        final var leftSlot = 2 + evidenceCount;
        final var rightSlot = leftSlot + 1;
        final var compared = nullable ? code.newLabel() : null;
        if (nullable) {
            final var checkRight = code.newLabel();
            code.aload(0);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.astore(leftSlot);
            code.aload(1);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.astore(rightSlot);
            code.aload(leftSlot);
            code.ifnonnull(checkRight);
            code.aload(rightSlot);
            code.ifnonnull(unequal);
            code.goto_(compared);
            code.labelBinding(checkRight);
            code.aload(rightSlot);
            code.ifnull(unequal);
            code.aload(leftSlot);
            code.checkcast(owner);
            code.aload(rightSlot);
            code.checkcast(owner);
        } else {
            code.aload(0);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.checkcast(owner);
            code.aload(1);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.checkcast(owner);
        }
        final var recursiveParameters = new ArrayList<ClassDesc>(List.of(owner, owner));
        for (int index = 0; index < evidenceCount; index++) {
            recursiveParameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var recursiveType = MethodTypeDesc.of(
                ConstantDescs.CD_boolean, recursiveParameters.toArray(ClassDesc[]::new));
        for (int index = 0; index < evidenceCount; index++) code.aload(2 + index);
        code.invokestatic(owner, "$zeron$eq", recursiveType);
        if (compared != null) {
            code.ifeq(unequal);
            code.labelBinding(compared);
        } else {
            code.ifeq(unequal);
        }
    }

    private static void emitRecursiveHash(
            final CodeBuilder code, final ClassDesc owner, final TypeDescriptor appliedType,
            final TypeDescriptor componentType, final String componentName, final boolean property,
            final boolean nullable, final int evidenceCount, final int receiverSlot,
            final int accumulator) {
        final var componentClass = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(componentType));
        final var hashParameters = new ArrayList<ClassDesc>();
        hashParameters.add(owner);
        for (int index = 0; index < evidenceCount; index++) {
            hashParameters.add(ClassDesc.of("java.lang.invoke.MethodHandle"));
        }
        final var hashType = MethodTypeDesc.of(
                ConstantDescs.CD_int, hashParameters.toArray(ClassDesc[]::new));
        if (nullable) {
            final var nonNull = code.newLabel();
            final var done = code.newLabel();
            code.aload(receiverSlot);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.astore(accumulator + 1);
            code.aload(accumulator + 1);
            code.ifnonnull(nonNull);
            code.iconst_0();
            code.goto_(done);
            code.labelBinding(nonNull);
            code.aload(accumulator + 1);
            code.checkcast(owner);
            for (int index = 0; index < evidenceCount; index++) code.aload(1 + index);
            code.invokestatic(owner, "$zeron$hash", hashType);
            code.labelBinding(done);
        } else {
            code.aload(receiverSlot);
            emitGetStoredComponent(code, owner, componentName, property, componentClass);
            code.checkcast(owner);
            for (int index = 0; index < evidenceCount; index++) code.aload(1 + index);
            code.invokestatic(owner, "$zeron$hash", hashType);
        }
    }

    private static void emitLoadStoredComponent(final CodeBuilder code, final ClassDesc owner,
                                               final StoredComponent component,
                                               final ClassDesc componentClass) {
        emitGetStoredComponent(code, owner, component.name(), component.property(), componentClass);
    }

    private static void emitGetStoredComponent(final CodeBuilder code, final ClassDesc owner,
                                              final String componentName, final boolean property,
                                              final ClassDesc componentClass) {
        final var fieldName = property ? Stmt.propertyBackingFieldName(componentName) : componentName;
        code.getfield(owner, fieldName, componentClass);
    }

    private static List<StoredComponent> storedComponents(final Stmt.ClassDecl declaration) {
        final var components = new LinkedHashMap<String, StoredComponent>();
        declaration.fields().forEach(field -> components.put(field.name().lexeme(),
                new StoredComponent(field.name().lexeme(), field.type(), false)));
        declaration.properties().stream().filter(property -> !property.isCustom())
                .forEach(property -> components.put(property.name().lexeme(),
                        new StoredComponent(property.name().lexeme(), property.type(), true)));
        if (declaration.storedStateOrder().isEmpty()) return List.copyOf(components.values());
        return declaration.storedStateOrder().stream().map(Token::lexeme)
                .filter(components::containsKey).map(components::get).toList();
    }

    private static TypeDescriptor appliedType(final String name,
                                              final List<TypeParameterDescriptor> typeParameters) {
        return typeParameters.isEmpty() ? TypeDescriptor.ofName(name)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(name),
                        typeParameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private static Stmt sourceTypeDeclaration(final CompilationContext context, final String name) {
        return context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration)
                .filter(declaration -> switch (declaration) {
                    case Stmt.ClassDecl value -> value.name().lexeme().equals(name);
                    case Stmt.ProductDecl value -> value.name().lexeme().equals(name);
                    case Stmt.SumDecl value -> value.name().lexeme().equals(name);
                    case Stmt.NewtypeDecl value -> value.name().lexeme().equals(name);
                    default -> false;
                })
                .findFirst().orElse(null);
    }

    private static List<Token> derivedContracts(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl value -> value.derivedContracts();
            case Stmt.ProductDecl value -> value.derivedContracts();
            case Stmt.SumDecl value -> value.derivedContracts();
            case Stmt.NewtypeDecl value -> value.derivedContracts();
            default -> List.of();
        };
    }

    private static boolean derives(final List<Token> contracts, final String name) {
        return contracts.stream().anyMatch(contract -> contract.lexeme().equals(name));
    }

    private static boolean isRecursiveProductComponent(final TypeDescriptor componentType,
                                                       final TypeDescriptor appliedProduct) {
        final var normalizedComponent = componentType instanceof NullableDescriptor nullable
                ? nullable.baseType() : componentType;
        return sameAppliedType(normalizedComponent, appliedProduct);
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
                                          final ClassDesc classDesc,
                                          final List<BytecodeEmitter.EvidenceField> classEvidenceSlots){
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
            for (final var evidenceSlot : classEvidenceSlots) {
                context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, evidenceSlot.token(),
                        TypeDescriptor.ofName("java.lang.invoke.MethodHandle"), BindingMutability.IMMUTABLE);
                context.symbols.define(evidenceSlot.token());
            }
            for (final var evidenceSlot : classEvidenceSlots) {
                code.aload(0);
                BytecodeEmitter.emitVariable(context, code, evidenceSlot.token());
                code.putfield(classDesc, evidenceSlot.fieldName(),
                        ClassDesc.of("java.lang.invoke.MethodHandle"));
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
                            if (!isContract) {
                                final var classDeclaration = context.resolution.classes().get(ownerName);
                                if (classDeclaration != null) {
                                    declareClassEvidenceSlots(context, code, classDeclaration, ownerDesc);
                                }
                            } else {
                                final var contractDeclaration = context.resolution.contracts().get(ownerName);
                                if (contractDeclaration != null) {
                                    declareContractEvidenceSlots(context, code, contractDeclaration, ownerDesc);
                                }
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
        final var ownerClass = ClassDesc.of(owner.name().lexeme());
        for (final var field : owner.fields()) {
            if (!Stmt.isAnonymousCaptureField(field)) continue;
            final var captureName = new Token(TokenType.IDENTIFIER,
                    Stmt.anonymousCaptureSourceName(field), null, field.name().span());
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, captureName,
                    field.type(), BindingMutability.IMMUTABLE);
            context.symbols.define(captureName);
            final var binding = context.symbols.getSymbol(captureName);
            final var fieldType = TypeSubstitution.erase(field.type());
            code.aload(0);
            code.getfield(ownerClass, field.name().lexeme(),
                    TypeDescriptor.toJavaClassDesc(fieldType));
            code.storeLocal(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()),
                    binding.lvt() + context.localSlotOffset);
        }
        for (int i = 0; i < method.parameters().size(); i++) {
            final var parameter = method.parameters().get(i);
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, parameter,
                    method.typeDescriptor().parameters().get(i), BindingMutability.IMMUTABLE);
            context.symbols.define(parameter);
        }
        declareEvidenceSlots(context, method.typeDescriptor());
        declareClassEvidenceSlots(context, code, owner, ClassDesc.of(owner.name().lexeme()));
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
        declareContractEvidenceSlots(context, code, owner, ClassDesc.of(owner.name().lexeme()));
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
                                      final Stmt.NamedConstructor constructor,
                                      final List<BytecodeEmitter.EvidenceField> classEvidenceSlots){
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
        for (final var evidenceSlot : classEvidenceSlots) {
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, evidenceSlot.token(),
                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"), BindingMutability.IMMUTABLE);
            context.symbols.define(evidenceSlot.token());
        }
        try {
            StatementEmitter.emitStmts(context, code, constructor.body());
        } finally {
            endScope(context);
            context.localSlotOffset = previousOffset;
            context.currentReturnType = previousReturnType;
        }
    }

    private static void declareClassEvidenceSlots(final CompilationContext context,
                                                  final CodeBuilder code,
                                                  final Stmt.ClassDecl owner,
                                                  final ClassDesc classDesc) {
        for (final var evidenceSlot : BytecodeEmitter.classEvidenceFields(context, owner)) {
            final var localSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, evidenceSlot.token(),
                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"), BindingMutability.IMMUTABLE);
            context.symbols.define(evidenceSlot.token());
            code.aload(0);
            code.getfield(classDesc, evidenceSlot.fieldName(),
                    ClassDesc.of("java.lang.invoke.MethodHandle"));
            code.astore(localSlot + context.localSlotOffset);
        }
    }

    private static void declareContractEvidenceSlots(final CompilationContext context,
                                                     final CodeBuilder code,
                                                     final Stmt.ContractDecl owner,
                                                     final ClassDesc contractDesc) {
        for (final var evidenceSlot : BytecodeEmitter.evidenceFields(
                context, owner.typeParameters(), "contract", owner.name().lexeme())) {
            final var localSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, evidenceSlot.token(),
                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"), BindingMutability.IMMUTABLE);
            context.symbols.define(evidenceSlot.token());
            code.aload(0);
            code.invokeinterface(contractDesc, evidenceSlot.fieldName(),
                    MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.MethodHandle")));
            code.astore(localSlot + context.localSlotOffset);
        }
    }

    static void loadLocal(CompilationContext context, final CodeBuilder code, final int slot, final TypeDescriptor type ){
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "I", "B", "Z" -> code.iload(slot);
            case "J" -> code.lload(slot);
            case "D" -> code.dload(slot);
            default -> code.aload(slot);
        }
    }

}
