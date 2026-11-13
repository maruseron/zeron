package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ZeronVersions;
import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.ContractComposition;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class BytecodeEmitter {
    static final ClassDesc UNIT_CLASS = ClassDesc.of("zeron.lang.Unit");

    static void emit(final CompilationContext context) throws IOException {
        Files.createDirectories(context.outputDirectory);
        final var signatureDeclarations = context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration).toList();
        context.lambdaPlan = new LambdaCompilationPlan(context.topLevelDeclarations, signatureDeclarations,
                context.symbols);
        if (context.lambdaPlan.hasLambdaTemplates()) {
            RuntimeSupportEmitter.emitLambdaTemplateSupportClass(context.outputDirectory);
        }
        for (final var shape : context.lambdaPlan.functionShapes().entrySet()) {
            context.registerGeneratedInterface(FunctionShapeNames.interfaceName(shape.getKey()));
            RuntimeSupportEmitter.emitFunctionShape(context.outputDirectory, shape.getKey(), shape.getValue());
        }
        final var hasNamespaceValues = context.compilationUnits.stream().filter(unit -> !unit.metadataOnly())
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .anyMatch(member -> member.namespaceName() != null
                        && member.declaration() instanceof Stmt.Var);
        for (var unitIndex = 0; unitIndex < context.compilationUnits.size(); unitIndex++) {
            final var unit = context.compilationUnits.get(unitIndex);
            if (unit.metadataOnly()) continue;
            final var isEntryHolder = unitIndex == 0;
            final var holderName = isEntryHolder ? context.mainClassName
                    : context.metadata.holderName(unit, unitIndex);
            final var unitDeclarations = NamespaceMembers.flatten(unit.declarations()).stream()
                    .filter(member -> member.namespaceName() == null)
                    .map(NamespaceMembers.Member::declaration).toList();
            final var hasTopLevelStorage = unitDeclarations.stream()
                    .anyMatch(declaration -> declaration instanceof Stmt.FunctionDeclaration
                        || declaration instanceof Stmt.Var)
                    || isEntryHolder && hasNamespaceValues
                    || isEntryHolder && !context.initializationPlan.order().isEmpty()
                    || (isEntryHolder && !context.lambdaPlan.lambdaImplementations().isEmpty());
            if ((unitIndex != 0 || context.libraryBuild) && !hasTopLevelStorage) continue;
            final var holderPath = outputPath(context, holderName);
            Files.createDirectories(holderPath.getParent());
            context.currentHolderName = holderName;
            context.classFile.buildTo(holderPath.toAbsolutePath(), ClassDesc.of(holderName), builder -> {
                builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                final var nestMembers = nestMembersForMainClass(context);
                if (holderName.equals(context.mainClassName) && !nestMembers.isEmpty()
                        && !context.lambdaPlan.lambdaImplementations().isEmpty()) {
                    builder.with(NestMembersAttribute.ofSymbols(nestMembers));
                }
                DeclarationEmitter.generateClass(context, builder, unitDeclarations, isEntryHolder);
            });
        }
        final var namespaceDeclarations = new LinkedHashMap<String, List<Stmt>>();
        for (final var unit : context.compilationUnits) {
            if (unit.metadataOnly()) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (member.namespaceName() == null) continue;
                final var owner = CompilationMetadata.namespaceOwner(unit.packageName(), member.namespaceName());
                namespaceDeclarations.computeIfAbsent(owner, _ -> new ArrayList<>()).add(member.declaration());
            }
        }
        for (final var namespace : namespaceDeclarations.entrySet()) {
            final var namespacePath = outputPath(context, namespace.getKey());
            Files.createDirectories(namespacePath.getParent());
            context.currentHolderName = namespace.getKey();
            context.classFile.buildTo(namespacePath.toAbsolutePath(), ClassDesc.of(namespace.getKey()), builder -> {
                builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                final var nestMembers = validatedNewtypeNestMembers(context, namespace.getKey());
                if (!nestMembers.isEmpty()) {
                    builder.with(NestMembersAttribute.ofSymbols(nestMembers));
                }
                DeclarationEmitter.generateClass(context, builder, namespace.getValue(), false);
            });
        }
        NominalTypeEmitter.emit(context);
        RuntimeSupportEmitter.emitReferencedRuntimeSupport(context);
        final var libraryIndex = ZeronLibraryIndex.fromCompilation(context.compilationUnits,
                context.metadata::functionOwner,
                function -> function.typeDescriptor().returnType() instanceof InferDescriptor
                        ? context.symbols.getFunctionType(
                                context.resolution.functionSymbolToken(function.name()))
                        : function.typeDescriptor(),
                variable -> context.symbols.getSymbol(
                        context.resolution.topLevelValueSymbol(variable)).type(),
                context.metadata::valueOwner,
                context.mainClassName,
                context.includeBundledSourcesInIndex);
        libraryIndex.writeTo(context.outputDirectory.resolve(ZeronVersions.API_INDEX_RELATIVE_PATH));
    }

    static Path outputPath(CompilationContext context, final String binaryName ){
        return context.outputDirectory.resolve(binaryName.replace('.', '/') + ".class");
    }

    static void emitPropertyAssignment(CompilationContext context, final CodeBuilder composer,
                                       final Expr.PropertyAssignment assignment,
                                       final boolean retainResult ){
        final var property = assignment.property;
        if (property.javaFieldTarget() != null) {
            final var target = property.javaFieldTarget();
            if (target.isStatic()) {
                throw new IllegalStateException("Static Java field writes must be rejected during resolution.");
            }
            emitExpr(context, composer, property.receiver);
            emitExpr(context, composer, assignment.value);
            emitConversion(context, composer, context.lastEmittedType, property.getType());
            if (retainResult) duplicateValueUnderReceiver(composer, property.getType());
            composer.putfield(ClassDesc.of(target.owner()), target.name(),
                    ClassDesc.ofDescriptor(target.descriptor()));
            if (retainResult) context.lastEmittedType = property.getType();
            else context.lastEmittedType = TypeDescriptor.ofUnit();
            return;
        }
        if (property.resolvedAsProperty()) {
            final var ownerName = property.resolvedOwnerName();
            final var declaredType = declarationPropertyType(context, ownerName, property.name.lexeme());
            final var erasedType = TypeSubstitution.erase(declaredType);
            emitExpr(context, composer, property.receiver);
            emitExpr(context, composer, assignment.value);
            emitConversion(context, composer, context.lastEmittedType, property.getType());
            emitConversion(context, composer, property.getType(), erasedType);
            if (retainResult) duplicateValueUnderReceiver(composer, erasedType);
            emitPropertySetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
            emitPop(context, composer, TypeDescriptor.ofUnit());
            if (retainResult) {
                emitConversion(context, composer, erasedType, property.getType());
            } else {
                context.lastEmittedType = TypeDescriptor.ofUnit();
            }
            return;
        }
        final var owner = nominalName(context, property.receiver.getType());
        final var fieldType = declarationFieldType(context, owner, property.name.lexeme());
        final var erasedFieldType = TypeSubstitution.erase(fieldType);
        emitExpr(context, composer, property.receiver);
        emitExpr(context, composer, assignment.value);
        emitConversion(context, composer, context.lastEmittedType, fieldType);
        emitConversion(context, composer, fieldType, erasedFieldType);
        if (retainResult) duplicateValueUnderReceiver(composer, erasedFieldType);
        if (context.emittingLambdaImplementation && !isSamePackage(owner, context.mainClassName)) {
            composer.invokestatic(ClassDesc.of(owner), NominalTypeEmitter.lambdaFieldWriteBridge(property.name.lexeme()),
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of(owner),
                            TypeDescriptor.toJavaClassDesc(erasedFieldType)));
        } else {
            composer.putfield(ClassDesc.of(owner), property.name.lexeme(),
                    TypeDescriptor.toJavaClassDesc(erasedFieldType));
        }
        if (retainResult) {
            emitConversion(context, composer, erasedFieldType, property.getType());
        } else {
            context.lastEmittedType = TypeDescriptor.ofUnit();
        }
    }

    private static void emitPropertyCompoundAssignment(CompilationContext context, final CodeBuilder composer,
                                               final Expr.PropertyCompoundAssignment assignment ){
        final var property = assignment.property;
        final var javaFieldTarget = property.javaFieldTarget();
        if (javaFieldTarget != null) {
            if (javaFieldTarget.isStatic()) {
                throw new IllegalStateException("Static Java field writes must be rejected during resolution.");
            }
            final var erasedType = TypeSubstitution.erase(property.getType());
            beginScope(context);
            try {
                final var receiverToken = new Token(TokenType.IDENTIFIER,
                        "$javaFieldReceiver$" + context.propertyTemporaryCount, null, property.name.span());
                context.propertyTemporaryCount++;
                final var receiverType = property.receiver.getType();
                final var receiverSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR,
                        receiverToken, receiverType, BindingMutability.IMMUTABLE);
                context.symbols.define(receiverToken);
                final var currentToken = new Token(TokenType.IDENTIFIER,
                        "$javaFieldCurrent$" + context.propertyTemporaryCount, null, property.name.span());
                context.propertyTemporaryCount++;
                final var currentType = property.getType();
                final var currentSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, currentToken,
                        currentType, BindingMutability.IMMUTABLE);
                context.symbols.define(currentToken);
                final var valueToken = new Token(TokenType.IDENTIFIER,
                        "$javaFieldValue$" + context.propertyTemporaryCount, null, property.name.span());
                context.propertyTemporaryCount++;
                final var valueType = assignment.resolvedOperation().right.getType();
                final var valueSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, valueToken,
                        valueType, BindingMutability.IMMUTABLE);
                context.symbols.define(valueToken);
                final var resultToken = new Token(TokenType.IDENTIFIER,
                        "$javaFieldResult$" + context.propertyTemporaryCount, null, property.name.span());
                context.propertyTemporaryCount++;
                final var resultSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR,
                        resultToken, erasedType, BindingMutability.IMMUTABLE);
                context.symbols.define(resultToken);

                emitExpr(context, composer, property.receiver);
                final var receiverKind = TypeKind.fromDescriptor(
                        TypeDescriptor.toJavaClassDesc(receiverType).descriptorString());
                composer.storeLocal(receiverKind, receiverSlot + context.localSlotOffset);
                composer.loadLocal(receiverKind, receiverSlot + context.localSlotOffset);
                composer.getfield(ClassDesc.of(javaFieldTarget.owner()), javaFieldTarget.name(),
                        ClassDesc.ofDescriptor(javaFieldTarget.descriptor()));
                emitConversion(context, composer, erasedType, currentType);
                composer.storeLocal(TypeKind.fromDescriptor(
                        TypeDescriptor.toJavaClassDesc(currentType).descriptorString()),
                        currentSlot + context.localSlotOffset);
                emitExpr(context, composer, assignment.value);
                emitConversion(context, composer, context.lastEmittedType, valueType);
                composer.storeLocal(TypeKind.fromDescriptor(
                        TypeDescriptor.toJavaClassDesc(valueType).descriptorString()),
                        valueSlot + context.localSlotOffset);

                final var current = new Expr.Variable(currentToken, currentType);
                final var value = new Expr.Variable(valueToken, valueType);
                emitExpr(context, composer, new Expr.Binary(current, assignment.operator, value,
                        assignment.resolvedOperation().getType()));
                emitConversion(context, composer, context.lastEmittedType, property.getType());
                emitConversion(context, composer, context.lastEmittedType, erasedType);
                final var fieldKind = TypeKind.fromDescriptor(
                        TypeDescriptor.toJavaClassDesc(erasedType).descriptorString());
                composer.storeLocal(fieldKind, resultSlot + context.localSlotOffset);
                composer.loadLocal(receiverKind, receiverSlot + context.localSlotOffset);
                composer.loadLocal(fieldKind, resultSlot + context.localSlotOffset);
                duplicateValueUnderReceiver(composer, erasedType);
                composer.putfield(ClassDesc.of(javaFieldTarget.owner()), javaFieldTarget.name(),
                        ClassDesc.ofDescriptor(javaFieldTarget.descriptor()));
                emitConversion(context, composer, erasedType, property.getType());
            } finally {
                endScope(context);
            }
            return;
        }
        final var ownerName = property.resolvedOwnerName();
        final var declaredType = declarationPropertyType(context, ownerName, property.name.lexeme());
        final var erasedType = TypeSubstitution.erase(declaredType);
        beginScope(context);
        try {
            final var currentToken = new Token(TokenType.IDENTIFIER,
                    "$propertyCurrent$" + context.propertyTemporaryCount, null, property.name.span());
            context.propertyTemporaryCount++;
            final var currentType = property.getType();
            final var currentSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, currentToken,
                    currentType, BindingMutability.IMMUTABLE);
            context.symbols.define(currentToken);
            final var valueToken = new Token(TokenType.IDENTIFIER,
                    "$propertyValue$" + context.propertyTemporaryCount, null, property.name.span());
            context.propertyTemporaryCount++;
            final var valueType = assignment.resolvedOperation().right.getType();
            final var valueSlot = context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, valueToken,
                    valueType, BindingMutability.IMMUTABLE);
            context.symbols.define(valueToken);

            emitExpr(context, composer, property.receiver);
            composer.dup();
            emitPropertyGetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
            emitConversion(context, composer, erasedType, currentType);
            composer.storeLocal(TypeKind.fromDescriptor(
                    TypeDescriptor.toJavaClassDesc(currentType).descriptorString()),
                    currentSlot + context.localSlotOffset);
            emitExpr(context, composer, assignment.value);
            emitConversion(context, composer, context.lastEmittedType, valueType);
            composer.storeLocal(TypeKind.fromDescriptor(
                    TypeDescriptor.toJavaClassDesc(valueType).descriptorString()),
                    valueSlot + context.localSlotOffset);

            final var current = new Expr.Variable(currentToken, currentType);
            final var value = new Expr.Variable(valueToken, valueType);
            emitExpr(context, composer, new Expr.Binary(current, assignment.operator, value,
                    assignment.resolvedOperation().getType()));
            emitConversion(context, composer, context.lastEmittedType, property.getType());
            emitConversion(context, composer, context.lastEmittedType, erasedType);
            duplicateValueUnderReceiver(composer, erasedType);
            emitPropertySetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
            emitPop(context, composer, TypeDescriptor.ofUnit());
            emitConversion(context, composer, erasedType, property.getType());
        } finally {
            endScope(context);
        }
    }

    private static void emitPropertyGetterCall(CompilationContext context, final CodeBuilder composer,
                                        final String ownerName,
                                        final String propertyName,
                                        final TypeDescriptor declaredType ){
        final var getterName = Stmt.propertyGetterName(propertyName);
        final var descriptor = toJavaMethodDescriptor(TypeDescriptor.functionOf(getterName, declaredType));
        if (context.resolution.contracts().containsKey(ownerName)) {
            composer.invokeinterface(ClassDesc.of(ownerName), getterName, descriptor);
        } else {
            composer.invokevirtual(ClassDesc.of(ownerName), getterName, descriptor);
        }
    }

    private static void emitPropertySetterCall(CompilationContext context, final CodeBuilder composer,
                                        final String ownerName,
                                        final String propertyName,
                                        final TypeDescriptor declaredType ){
        final var setterName = Stmt.propertySetterName(propertyName);
        final var descriptor = toJavaMethodDescriptor(TypeDescriptor.functionOf(
                setterName, TypeDescriptor.ofUnit(), declaredType));
        if (context.resolution.contracts().containsKey(ownerName)) {
            composer.invokeinterface(ClassDesc.of(ownerName), setterName, descriptor);
        } else {
            composer.invokevirtual(ClassDesc.of(ownerName), setterName, descriptor);
        }
    }

    private static TypeDescriptor declarationPropertyType(CompilationContext context, final String ownerName, final String propertyName ){
        final var classDeclaration = context.resolution.classes().get(ownerName);
        if (classDeclaration != null) {
            return classDeclaration.properties().stream()
                    .filter(property -> property.name().lexeme().equals(propertyName))
                    .findFirst().orElseThrow().type();
        }
        return context.resolution.contracts().get(ownerName).properties().stream()
                .filter(property -> property.name().lexeme().equals(propertyName))
                .findFirst().orElseThrow().type();
    }

    static void emitIntrinsicOperation(CompilationContext context, final CodeBuilder composer,
                                        final Expr expression,
                                        final boolean retainResult ){
        final var operation = switch (expression) {
            case Expr.ArrayLiteral literal -> literal.intrinsicOperation();
            case Expr.Property property -> property.intrinsicOperation();
            case Expr.Index index -> index.intrinsicOperation();
            case Expr.IndexAssignment assignment -> assignment.intrinsicOperation();
            default -> null;
        };
        if (operation == null) {
            throw new IllegalStateException("Attempted to emit an unresolved intrinsic operation.");
        }

        switch (operation.id()) {
            case ARRAY_LITERAL -> emitArrayLiteral(context, composer, (Expr.ArrayLiteral) expression, operation);
            case ARRAY_FILL -> throw new IllegalStateException("Array fill is not a syntax expression.");
            case ARRAY_ALLOC, ARRAY_CLEAR_SLOT, OPTION_UNWRAP_SOME, INT_TO_FLOAT, INT_TO_LONG,
                    FLOAT_TO_INT_OPTION, LONG_TO_INT_OPTION, INT_TO_BYTE, BYTE_TO_INT ->
                    throw new IllegalStateException("Function intrinsic is not a syntax expression.");
            case BYTE_ARRAY_ALLOC -> throw new IllegalStateException("Function intrinsic is not a syntax expression.");
            case ARRAY_LENGTH, BYTE_ARRAY_LENGTH -> {
                final var property = (Expr.Property) expression;
                emitExpr(context, composer, property.receiver);
                composer.arraylength();
                context.lastEmittedType = operation.resultType();
            }
            case ARRAY_READ, BYTE_ARRAY_READ -> emitArrayRead(context, composer, (Expr.Index) expression, operation);
            case ARRAY_WRITE, BYTE_ARRAY_WRITE -> emitArrayWrite(context, composer,
                    (Expr.IndexAssignment) expression, operation, retainResult);
        }
    }

    private static void emitArrayLiteral(CompilationContext context, final CodeBuilder composer,
                                  final Expr.ArrayLiteral literal,
                                  final ResolvedIntrinsicOperation operation ){
        final var elementType = arrayElementType(operation.resultType());
        composer.ldc(literal.elements.size());
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        for (int i = 0; i < literal.elements.size(); i++) {
            composer.dup();
            composer.ldc(i);
            final var element = literal.elements.get(i);
            emitExpr(context, composer, element);
            emitConversion(context, composer, context.lastEmittedType, elementType);
            emitBox(context, composer, context.lastEmittedType);
            composer.aastore();
        }
        context.lastEmittedType = operation.resultType();
    }

    private static TypeDescriptor arrayElementType(TypeDescriptor type) {
        if (type instanceof ReferenceDescriptor reference) type = reference.baseType();
        if (type instanceof ArrayDescriptor array) return array.elementType();
        throw new IllegalStateException("Array literal intrinsic has a non-array result type.");
    }

    private static void emitArrayRead(CompilationContext context, final CodeBuilder composer,
                               final Expr.Index index,
                               final ResolvedIntrinsicOperation operation ){
        emitExpr(context, composer, index.array);
        composer.dup();
        composer.arraylength();
        emitExpr(context, composer, index.index);
        emitArrayBoundsCheck(context, composer);
        if (operation.id() == IntrinsicId.BYTE_ARRAY_READ) {
            composer.baload();
            composer.ldc(255);
            composer.iand();
            context.lastEmittedType = operation.resultType();
        } else {
            composer.aaload();
            emitArrayReadConversion(context, composer, operation.resultType());
            if (StoredExistentialSupport.requiresHolder(operation.resultType())) {
                emitStoredUnpack(context, composer, operation.resultType());
            }
        }
    }

    private static void emitArrayWrite(CompilationContext context, final CodeBuilder composer,
                                final Expr.IndexAssignment assignment,
                                final ResolvedIntrinsicOperation operation,
                                final boolean retainResult ){
        emitExpr(context, composer, assignment.array);
        composer.dup();
        composer.arraylength();
        emitExpr(context, composer, assignment.index);
        emitArrayBoundsCheck(context, composer);
        emitExpr(context, composer, assignment.value);
        final var elementType = operation.parameterTypes().get(2);
        emitConversion(context, composer, context.lastEmittedType, elementType);
        if (retainResult) duplicateValueUnderArrayAndIndex(composer, elementType);
        if (operation.id() == IntrinsicId.BYTE_ARRAY_WRITE) {
            composer.bastore();
        } else {
            emitBox(context, composer, context.lastEmittedType);
            composer.aastore();
        }
        context.lastEmittedType = retainResult ? assignment.getType() : TypeDescriptor.ofUnit();
    }

    private static void duplicateValueUnderReceiver(final CodeBuilder composer, final TypeDescriptor type) {
        final var descriptor = TypeDescriptor.toJavaClassDesc(type).descriptorString();
        if (descriptor.equals("J") || descriptor.equals("D")) composer.dup2_x1();
        else composer.dup_x1();
    }

    private static void duplicateValueUnderArrayAndIndex(final CodeBuilder composer,
                                                         final TypeDescriptor type) {
        final var descriptor = TypeDescriptor.toJavaClassDesc(type).descriptorString();
        if (descriptor.equals("J") || descriptor.equals("D")) composer.dup2_x2();
        else composer.dup_x2();
    }

    static void emitExpr(CompilationContext context, final CodeBuilder composer, final Expr expr ){
        switch (expr) {
            case Expr.Property property -> {
                if (property.namespaceValueSymbol() != null) {
                    emitVariable(context, composer, property.namespaceValueSymbol());
                    break;
                } else if (property.javaFieldTarget() != null) {
                    final var target = property.javaFieldTarget();
                    if (target.isStatic()) {
                        composer.getstatic(ClassDesc.of(target.owner()), target.name(),
                                ClassDesc.ofDescriptor(target.descriptor()));
                    } else {
                        emitExpr(context, composer, property.receiver);
                        composer.getfield(ClassDesc.of(target.owner()), target.name(),
                                ClassDesc.ofDescriptor(target.descriptor()));
                    }
                    if (target.descriptor().equals("F")) composer.f2d();
                    context.lastEmittedType = property.getType();
                    break;
                } else if (property.safeNavigation()) {
                    emitSafeProperty(context, composer, property);
                    break;
                } else if (property.extensionCall() != null) {
                    emitExtensionCall(context, composer, property.extensionCall(), false);
                    context.lastEmittedType = property.getType();
                    break;
                }
                if (property.intrinsicOperation() != null) {
                    emitIntrinsicOperation(context, composer, property, true);
                } else if (property.resolvedAsProperty()) {
                    final var ownerName = property.resolvedOwnerName();
                    final var declaredType = declarationPropertyType(context, ownerName, property.name.lexeme());
                    final var erasedType = TypeSubstitution.erase(declaredType);
                    emitExpr(context, composer, property.receiver);
                    emitPropertyGetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
                    emitConversion(context, composer, erasedType, property.getType());
                    context.lastEmittedType = property.getType();
                } else if (property.sumPayloadIndex() >= 0) {
                    final var ownerName = property.resolvedOwnerName();
                    final var owner = context.resolution.classes().get(ownerName);
                    final var erasedType = TypeSubstitution.erase(owner.fields()
                            .get(property.sumPayloadIndex()).type());
                    emitExpr(context, composer, property.receiver);
                    composer.invokevirtual(ClassDesc.of(ownerName),
                            "$zeron$payload$" + property.sumPayloadIndex(),
                            MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedType)));
                    emitConversion(context, composer, erasedType, property.getType());
                    context.lastEmittedType = property.getType();
                } else {
                    final var owner = nominalName(context, property.receiver.getType());
                    final var fieldType = declarationFieldType(context, owner, property.name.lexeme());
                    emitExpr(context, composer, property.receiver);
                    final var erasedFieldType = TypeSubstitution.erase(fieldType);
                    if (context.emittingLambdaImplementation && !isSamePackage(owner, context.mainClassName)) {
                        composer.invokestatic(ClassDesc.of(owner), NominalTypeEmitter.lambdaFieldReadBridge(property.name.lexeme()),
                                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedFieldType), ClassDesc.of(owner)));
                    } else {
                        composer.getfield(ClassDesc.of(owner), property.name.lexeme(),
                                TypeDescriptor.toJavaClassDesc(erasedFieldType));
                    }
                    emitConversion(context, composer, erasedFieldType, property.getType());
                    context.lastEmittedType = property.getType();
                }
            }
            case Expr.PropertyAssignment assignment -> emitPropertyAssignment(context, composer, assignment, true);
            case Expr.PropertyCompoundAssignment assignment ->
                    emitPropertyCompoundAssignment(context, composer, assignment);
            case Expr.ArrayLiteral literal -> emitIntrinsicOperation(context, composer, literal, true);
            case Expr.Index index -> emitIntrinsicOperation(context, composer, index, true);
            case Expr.IndexAssignment assignment -> emitIntrinsicOperation(context, composer, assignment, true);
            case Expr.Assignment assignment -> {
                if (assignment.dataFieldOwner() != null) {
                    final var fieldType = TypeSubstitution.erase(assignment.dataFieldType());
                    emitExpr(context, composer, assignment.value);
                    emitConversion(context, composer, context.lastEmittedType, fieldType);
                    final var temporary = new Token(TokenType.IDENTIFIER,
                            "$dataFieldAssign$" + assignment.name.span().start().line()
                                    + "$" + assignment.name.span().start().column()
                                    + "$" + assignment.name.lexeme(), null, assignment.name.span());
                    final var slot = context.symbols.declareSymbol(
                            Resolver.SYNTHETIC_VAR, temporary, fieldType, BindingMutability.IMMUTABLE);
                    context.symbols.define(temporary);
                    composer.storeLocal(TypeKind.fromDescriptor(
                            TypeDescriptor.toJavaClassDesc(fieldType).descriptorString()),
                            slot + context.localSlotOffset);
                    composer.aload(0);
                    NominalTypeEmitter.loadLocal(context, composer,
                            slot + context.localSlotOffset, fieldType);
                    composer.putfield(ClassDesc.of(assignment.dataFieldOwner()),
                            assignment.name.lexeme(), TypeDescriptor.toJavaClassDesc(fieldType));
                    NominalTypeEmitter.loadLocal(context, composer,
                            slot + context.localSlotOffset, fieldType);
                    context.lastEmittedType = fieldType;
                } else {
                    final var binding = context.symbols.getSymbol(assignment.resolvedSymbolToken());
                    emitExpr(context, composer, assignment.value);
                    emitConversion(context, composer, context.lastEmittedType, binding.type());
                    duplicateValue(context, composer, binding.type());
                    if (binding.lvt() == SymbolTable.GLOBAL) {
                        composer.putstatic(ClassDesc.of(holderForDeclaration(context, binding.declaration())),
                                globalFieldName(binding, assignment.name),
                                TypeDescriptor.toJavaClassDesc(binding.type()));
                    } else {
                        composer.storeLocal(
                                TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()),
                                binding.lvt() + context.localSlotOffset);
                    }
                    context.lastEmittedType = binding.type();
                }
            }
            case Expr.CoalesceAssignment assignment -> ExpressionFlowEmitter.emitCoalesceAssignment(context, composer, assignment);
            case Expr.Binary binary -> {
                if (binary.operatorCall() != null) {
                    emitMemberCall(context, composer, binary.operatorCall());
                    context.lastEmittedType = binary.getType();
                } else if (binary.synthesizedEqualityEvidence() != null) {
                    emitEqualityEvidenceCall(context, composer, binary);
                    if (binary.operator.type() == TokenType.BANG_EQUAL) {
                        composer.iconst_1();
                        composer.ixor();
                    }
                    context.lastEmittedType = TypeDescriptor.ofBoolean();
                } else if (binary.comparisonCall() != null) {
                    emitMemberCall(context, composer, binary.comparisonCall());
                    ExpressionFlowEmitter.emitOrderingComparison(context, composer, binary.operator.type());
                } else if (binary.getType() instanceof BooleanDescriptor) {
                    ExpressionFlowEmitter.emitComparison(context, composer, binary);
                } else switch (TypeDescriptor.toJavaClassDesc(binary.getType()).descriptorString()) {
                    case "I" -> {
                        emitExpr(context, composer, binary.left);
                        emitExpr(context, composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.iadd();
                            case MINUS -> composer.isub();
                            case STAR -> composer.imul();
                            case SLASH -> composer.idiv();
                            case PERCENT -> composer.irem();
                            case AMPERSAND -> composer.iand();
                            case PIPE -> composer.ior();
                            case CARET -> composer.ixor();
                            case SHIFT_LEFT -> composer.ishl();
                            case SHIFT_RIGHT -> composer.ishr();
                            case UNSIGNED_SHIFT_RIGHT -> composer.iushr();
                            default -> throw new IllegalStateException();
                        }
                        context.lastEmittedType = TypeDescriptor.ofInt();
                    }
                    case "J" -> {
                        emitExpr(context, composer, binary.left);
                        emitExpr(context, composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.ladd();
                            case MINUS -> composer.lsub();
                            case STAR -> composer.lmul();
                            case SLASH -> composer.ldiv();
                            case PERCENT -> composer.lrem();
                            case AMPERSAND -> composer.land();
                            case PIPE -> composer.lor();
                            case CARET -> composer.lxor();
                            case SHIFT_LEFT -> composer.lshl();
                            case SHIFT_RIGHT -> composer.lshr();
                            case UNSIGNED_SHIFT_RIGHT -> composer.lushr();
                            default -> throw new IllegalStateException();
                        }
                        context.lastEmittedType = TypeDescriptor.ofLong();
                    }
                    case "D" -> {
                        emitExpr(context, composer, binary.left);
                        emitExpr(context, composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.dadd();
                            case MINUS -> composer.dsub();
                            case STAR -> composer.dmul();
                            case SLASH -> composer.ddiv();
                            case PERCENT -> composer.drem();
                            default -> throw new IllegalStateException();
                        }
                        context.lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case "Ljava/lang/String;" -> {
                        emitExpr(context, composer, binary.left);
                        emitStringConcatenationRight(context, composer, binary);
                        final var handle = MethodHandleDesc.of(
                                DirectMethodHandleDesc.Kind.STATIC,
                                ClassDesc.of("java.lang.invoke.StringConcatFactory"),
                                "makeConcatWithConstants",
                                MethodTypeDesc.of(
                                        ClassDesc.of("java.lang.invoke.CallSite"),
                                        ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                                        ConstantDescs.CD_String,
                                        ClassDesc.of("java.lang.invoke.MethodType"),
                                        ConstantDescs.CD_String,
                                        ConstantDescs.CD_Object.arrayType()).descriptorString());
                        final var dcsd = DynamicCallSiteDesc.of(
                                handle,
                                "makeConcatWithConstants",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                                "\u0001\u0001");
                        composer.invokedynamic(dcsd);
                        context.lastEmittedType = TypeDescriptor.ofString();
                    }
                }
            }
            case Expr.Grouping grouping -> emitExpr(context, composer, grouping.expression);
            case Expr.If iff -> ExpressionFlowEmitter.emitIfExpression(context, composer, iff);
            case Expr.Pipeline pipeline -> ExpressionFlowEmitter.emitForExpression(context, composer, pipeline);
            case Expr.Match match -> ExpressionFlowEmitter.emitMatchExpression(context, composer, match);
            case Expr.Raise raise -> ExpressionFlowEmitter.emitRaise(context, composer, raise);
            case Expr.Handle handle -> ExpressionFlowEmitter.emitHandle(context, composer, handle);
            case Expr.Do doExpression -> emitExpr(context, composer, doExpression.lowered());
            case Expr.HandlerLiteral handler -> emitExpr(context, composer, handler.implementation);
            case Expr.ModelLiteral model -> {
                final var declaration = model.declaration();
                final var classDesc = ClassDesc.of(model.generatedClassName());
                final var constructorTypes = declaration.canonicalConstructorTypes();
                final var constructorParameters = constructorTypes.stream()
                        .map(type -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)))
                        .toList();
                composer.new_(classDesc);
                composer.dup();
                for (int index = 0; index < model.assignments.size(); index++) {
                    emitExpr(context, composer, model.assignments.get(index).value());
                    final var parameterType = constructorTypes.get(index);
                    emitConversion(context, composer, context.lastEmittedType, parameterType);
                    emitConversion(context, composer, parameterType, TypeSubstitution.erase(parameterType));
                }
                composer.invokespecial(classDesc, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, constructorParameters));
                context.lastEmittedType = model.getType();
            }
            case Expr.HandlerDispatch dispatch ->
                    ExpressionFlowEmitter.emitHandlerDispatch(context, composer, dispatch);
            case Expr.Logical logical -> ExpressionFlowEmitter.emitLogical(context, composer, logical);
            case Expr.Coalesce coalesce -> ExpressionFlowEmitter.emitCoalesce(context, composer, coalesce);
            case Expr.TypeTest test -> ExpressionFlowEmitter.emitTypeTest(context, composer, test);
            case Expr.SumCaseTest test -> ExpressionFlowEmitter.emitSumCaseTest(context, composer, test);
            case Expr.Cast cast -> ExpressionFlowEmitter.emitCast(context, composer, cast);
            case Expr.Call call -> emitCall(context, composer, call);
            case Expr.MemberCall call -> emitMemberCall(context, composer, call);
            case Expr.AnonymousObject object -> {
                final var declaration = object.declaration();
                final var classDesc = ClassDesc.of(object.generatedClassName());
                final var constructorTypes = declaration.canonicalConstructorTypes();
                final var constructorParameters = constructorTypes.stream()
                        .map(type -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)))
                        .toList();
                composer.new_(classDesc);
                composer.dup();
                for (int index = 0; index < object.initializers().size(); index++) {
                    emitExpr(context, composer, object.initializers().get(index));
                    final var parameterType = constructorTypes.get(index);
                    emitConversion(context, composer, context.lastEmittedType, parameterType);
                    emitConversion(context, composer, parameterType, TypeSubstitution.erase(parameterType));
                }
                composer.invokespecial(classDesc, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, constructorParameters));
                context.lastEmittedType = object.getType();
            }
            case Expr.Lambda lambda -> {
                if (lambda.codeModelType() != null) {
                    emitCodeModel(context, composer, lambda);
                    break;
                }
                final var functionType = (FunctionDescriptor) lambda.getType();
                if (context.lambdaPlan.lambdaTemplateFactoryName(lambda) != null) {
                    emitLambdaTemplateValue(context, composer, lambda);
                    break;
                }
                final var captures = context.lambdaPlan.captures(lambda);
                final var captureTypes = context.lambdaPlan.captureTypes(lambda);
                final var implementationParameters = new ArrayList<>(captureTypes);
                implementationParameters.addAll(functionType.invocationParameters());
                implementationParameters.addAll(Collections.nCopies(functionType.hiddenEvidenceCount(),
                        TypeDescriptor.ofName("java.lang.invoke.MethodHandle")));
                implementationParameters.addAll(Collections.nCopies(functionType.hiddenDescriptorCount(),
                        RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE));
                for (int i = 0; i < captures.size(); i++) {
                    emitVariable(context, composer, captures.get(i));
                    emitConversion(context, composer, context.lastEmittedType, captureTypes.get(i));
                }

                final var generatedInterface = TypeDescriptor.toJavaClassDesc(functionType);
                final var samMethodType = toJavaMethodDescriptor(functionType);
                final var implementationType = toJavaMethodDescriptor(functionType, implementationParameters);
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
                    context.lambdaPlan.lambdaMethodName(lambda),
                        implementationType.descriptorString());
                composer.invokedynamic(DynamicCallSiteDesc.of(
                    metafactory,
                    "invoke",
                    MethodTypeDesc.of(generatedInterface,
                            captureTypes.stream().map(TypeDescriptor::toJavaClassDesc).toList()),
                    samMethodType,
                    implementation,
                    samMethodType));
                context.lastEmittedType = lambda.getType();
            }
            case Expr.SuffixedLiteral literal -> {
                emitMemberCall(context, composer, literal.factoryCall());
                context.lastEmittedType = literal.getType();
            }
            case Expr.PropertyReference reference -> {
                if (reference.isCallableReference()) {
                    if (reference.converted()) {
                        emitExpr(context, composer, reference.callable());
                    } else {
                        emitCallableReference(context, composer, reference);
                    }
                    context.lastEmittedType = reference.getType();
                    break;
                }
                if (reference.codeModelType() != null) {
                    emitCodeModel(context, composer, reference.reader());
                    break;
                }
                if (reference.converted()) {
                    emitExpr(context, composer, reference.reader());
                    break;
                }
                final var propertyRefClass = ClassDesc.of(SyntheticNames.PROPERTY_REF_TYPE);
                final var readerType = propertyReferenceReaderType();
                emitExpr(context, composer, reference.reader());
                emitConversion(context, composer, reference.reader().getType(), readerType);
                composer.ldc(reference.propertyName.lexeme());
                composer.ldc(reference.identity());
                emitPropertyId(composer, reference.declaringTypeName(),
                        reference.propertyName.lexeme());
                final var optionSome = ClassDesc.of("zeron.lang.Option$$sum$Some");
                final var optionNone = ClassDesc.of("zeron.lang.Option$$sum$None");
                if (reference.writer() == null) {
                    composer.getstatic(optionNone, "$zeron$singleton", optionNone);
                } else {
                    composer.new_(optionSome);
                    composer.dup();
                    emitExpr(context, composer, reference.writer());
                    composer.invokespecial(optionSome, "<init>",
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                }
                composer.invokestatic(propertyRefClass, SyntheticNames.PROPERTY_REF_FACTORY,
                        MethodTypeDesc.of(propertyRefClass,
                                TypeDescriptor.toJavaClassDesc(readerType),
                                ConstantDescs.CD_String, ConstantDescs.CD_String,
                                ClassDesc.of(SyntheticNames.PROPERTY_ID_TYPE),
                                ClassDesc.of("zeron.lang.Option")));
                context.lastEmittedType = reference.getType();
            }
            case Expr.PropertyIdReference identity -> {
                emitPropertyId(composer, identity.owner, identity.propertyName.lexeme());
                context.lastEmittedType = identity.getType();
            }
            case Expr.Literal literal -> {
                if (literal.numericConversionCall() != null) {
                    emitMemberCall(context, composer, literal.numericConversionCall());
                    context.lastEmittedType = literal.getType();
                } else if (literal.exactInteger() != null && literal.getType() instanceof IntDescriptor) {
                    composer.ldc(literal.exactInteger().intValueExact());
                    context.lastEmittedType = literal.getType();
                } else if (literal.exactInteger() != null && literal.getType() instanceof LongDescriptor) {
                    composer.ldc(literal.exactInteger().longValueExact());
                    context.lastEmittedType = literal.getType();
                } else switch (literal.value) {
                    case String s -> {
                        composer.ldc(s);
                        context.lastEmittedType = TypeDescriptor.ofString();
                    }
                    case Integer i -> {
                        composer.ldc(i);
                        context.lastEmittedType = literal.getType();
                    }
                    case Long value -> {
                        composer.ldc(value);
                        context.lastEmittedType = TypeDescriptor.ofLong();
                    }
                    case Double d -> {
                        composer.ldc(d);
                        context.lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case Boolean b -> {
                        if (b) composer.iconst_1();
                        else composer.iconst_0();
                        context.lastEmittedType = TypeDescriptor.ofBoolean();
                    }
                    case UnitLiteral _ -> {
                        emitUnitValue(context, composer);
                        context.lastEmittedType = TypeDescriptor.ofUnit();
                    }
                    case null -> {
                        composer.aconst_null();
                        context.lastEmittedType = TypeDescriptor.ofNull();
                    }
                    default -> throw new IllegalStateException("Unsupported value");
                }
            }
            case Expr.Unary unary -> {
                if (unary.operatorCall() != null) {
                    emitMemberCall(context, composer, unary.operatorCall());
                    context.lastEmittedType = unary.getType();
                } else if (unary.operator.type() == TokenType.BANG_BANG) {
                    emitExpr(context, composer, unary.right);
                    if (unary.right.getType() instanceof NullableDescriptor nullable) {
                        composer.ldc("Non-null assertion failed.");
                        composer.invokestatic(ClassDesc.of("java.util.Objects"), "requireNonNull",
                                MethodTypeDesc.of(ClassDesc.of("java.lang.Object"),
                                        ClassDesc.of("java.lang.Object"), ClassDesc.of("java.lang.String")));
                        emitConversion(context, composer, TypeDescriptor.ofAny(), nullable.baseType());
                    }
                    context.lastEmittedType = unary.getType();
                } else if (unary.operator.type() == TokenType.NOT) {
                    emitExpr(context, composer, unary.right);
                    composer.iconst_1();
                    composer.ixor();
                    context.lastEmittedType = TypeDescriptor.ofBoolean();
                } else if (unary.operator.type() == TokenType.TILDE) {
                    emitExpr(context, composer, unary.right);
                    if (unary.getType() instanceof LongDescriptor) {
                        composer.ldc(-1L);
                        composer.lxor();
                        context.lastEmittedType = TypeDescriptor.ofLong();
                    } else {
                        composer.iconst_m1();
                        composer.ixor();
                        context.lastEmittedType = TypeDescriptor.ofInt();
                    }
                } else {
                    emitExpr(context, composer, unary.right);
                    final var opcode = TypeDescriptor.toJavaClassDesc(unary.getType()).descriptorString();
                    if (unary.operator.type() == TokenType.MINUS) {
                        if (opcode.equals("I")) composer.ineg();
                        else if (opcode.equals("J")) composer.lneg();
                        else if (opcode.equals("D")) composer.dneg();
                        else throw new IllegalStateException("Unary negation requires Int or Float.");
                    } else if (unary.operator.type() != TokenType.PLUS) {
                        throw new IllegalStateException("Unsupported unary operator.");
                    }
                    context.lastEmittedType = unary.getType();
                }
            }
            case Expr.Variable variable -> {
                if (variable.resolvedFunctionName() != null) {
                    LambdaSupportEmitter.emitFunctionReference(context, composer, variable);
                } else if (variable.implicitFieldReceiver() != null) {
                    emitExpr(context, composer, variable.implicitFieldReceiver());
                    final var owner = variable.implicitFieldOwner();
                    final var erasedFieldType = TypeSubstitution.erase(variable.implicitFieldType());
                    if (context.emittingLambdaImplementation && !isSamePackage(owner, context.mainClassName)) {
                        composer.invokestatic(ClassDesc.of(owner), NominalTypeEmitter.lambdaFieldReadBridge(variable.name.lexeme()),
                                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedFieldType),
                                        ClassDesc.of(owner)));
                    } else {
                        composer.getfield(ClassDesc.of(owner), variable.name.lexeme(),
                                TypeDescriptor.toJavaClassDesc(erasedFieldType));
                    }
                    emitConversion(context, composer, erasedFieldType, variable.getType());
                    context.lastEmittedType = variable.getType();
                } else {
                    if (variable.storedFunctionType() != null
                            && !variable.storedFunctionType().hasHiddenSlots()
                            && LambdaCompilationPlan.hasInferredContractBounds(variable.storedFunctionType())) {
                        emitLambdaTemplateSpecialization(context, composer,
                                variable.resolvedSymbolToken(), variable.storedFunctionType(),
                                (FunctionDescriptor) variable.getType(), variable.evidenceArguments());
                    } else {
                        final var declaration = variable.resolvedValueDeclaration();
                        final var owner = declaration == null ? null : context.metadata.valueOwner(declaration);
                        if (owner == null) {
                            emitVariable(context, composer, variable.resolvedSymbolToken());
                        } else {
                            composer.invokestatic(ClassDesc.of(owner),
                                    DeclarationEmitter.topLevelValueAccessorName(declaration.name().lexeme()),
                                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(variable.getType())));
                            context.lastEmittedType = variable.getType();
                        }
                    }
                    if (!(variable.getType() instanceof InferDescriptor)
                            && !context.lastEmittedType.equals(variable.getType())) {
                        emitConversion(context, composer, context.lastEmittedType, variable.getType());
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private static final ClassDesc CODE_MODEL_CLASS = ClassDesc.of("zeron.compiler.CodeModel");
    private static final ClassDesc CODE_NODE_CLASS = ClassDesc.of("zeron.compiler.CodeNode");
    private static final ClassDesc CODE_TYPE_CLASS = ClassDesc.of("zeron.compiler.CodeType");
    private static final ClassDesc CODE_SHAPE_CLASS = ClassDesc.of("zeron.compiler.CodeShape");
    private static final ClassDesc CODE_LITERAL_CLASS = ClassDesc.of("zeron.compiler.CodeLiteral");
    private static final ClassDesc CODE_PARAMETER_CLASS = ClassDesc.of("zeron.compiler.CodeParameter");
    private static final ClassDesc CODE_CAPTURE_CLASS = ClassDesc.of("zeron.compiler.CodeCapture");
    private static final ClassDesc CAPTURE_VALUE_CLASS = ClassDesc.of("zeron.compiler.CaptureValue");
    private static final ClassDesc SOURCE_LOCATION_CLASS = ClassDesc.of("zeron.compiler.SourceLocation");
    private static final ClassDesc COMPARISON_OPERATOR_CLASS =
            ClassDesc.of("zeron.compiler.ComparisonOperator");
    private static final ClassDesc PROPERTY_ID_CLASS = ClassDesc.of(SyntheticNames.PROPERTY_ID_TYPE);

    private static ClassDesc compilerSumCase(final String sum, final String caseName) {
        return ClassDesc.of("zeron.compiler." + sum + "$$sum$" + caseName);
    }

    private static void emitSingleton(final CodeBuilder composer, final ClassDesc caseClass) {
        composer.getstatic(caseClass, "$zeron$singleton", caseClass);
    }

    private static void emitCodeModel(final CompilationContext context,
                                      final CodeBuilder composer,
                                      final Expr.Lambda lambda) {
        final var functionType = (FunctionDescriptor) lambda.getType();
        final var parameterTokens = lambda.parameterTokens(functionType.arity());
        final var captures = context.lambdaPlan.captures(lambda);
        final var captureTypes = context.lambdaPlan.captureTypes(lambda);
        final var body = ((Stmt.Return) lambda.body.getFirst()).value();

        composer.new_(CODE_MODEL_CLASS);
        composer.dup();
        composer.loadConstant(parameterTokens.size());
        composer.anewarray(ConstantDescs.CD_Object);
        for (int index = 0; index < parameterTokens.size(); index++) {
            composer.dup();
            composer.loadConstant(index);
            composer.new_(CODE_PARAMETER_CLASS);
            composer.dup();
            composer.loadConstant(index);
            composer.ldc(parameterTokens.get(index).lexeme());
            emitCodeType(context, composer, functionType.parameters().get(index));
            composer.invokespecial(CODE_PARAMETER_CLASS, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_String, CODE_TYPE_CLASS));
            composer.aastore();
        }
        emitCodeNode(context, composer, lambda, body, parameterTokens, captures);
        composer.loadConstant(captures.size());
        composer.anewarray(ConstantDescs.CD_Object);
        for (int index = 0; index < captures.size(); index++) {
            composer.dup();
            composer.loadConstant(index);
            composer.new_(CODE_CAPTURE_CLASS);
            composer.dup();
            composer.loadConstant(index);
            composer.ldc(captures.get(index).lexeme());
            emitCodeType(context, composer, captureTypes.get(index));
            emitCaptureValue(context, composer, captures.get(index), captureTypes.get(index));
            composer.invokespecial(CODE_CAPTURE_CLASS, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                            ConstantDescs.CD_String, CODE_TYPE_CLASS, CAPTURE_VALUE_CLASS));
            composer.aastore();
        }
        emitSourceLocation(composer, lambda.arrow);
        composer.invokespecial(CODE_MODEL_CLASS, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object.arrayType(),
                        CODE_NODE_CLASS, ConstantDescs.CD_Object.arrayType(), SOURCE_LOCATION_CLASS));
        context.lastEmittedType = lambda.codeModelType();
    }

    private static void emitCodeNode(final CompilationContext context,
                                     final CodeBuilder composer,
                                     final Expr.Lambda lambda,
                                     final Expr expression,
                                     final List<Token> parameters,
                                     final List<Token> captures) {
        if (expression instanceof Expr.Grouping grouping) {
            emitCodeNode(context, composer, lambda, grouping.expression, parameters, captures);
            return;
        }
        composer.new_(CODE_NODE_CLASS);
        composer.dup();
        emitCodeShape(context, composer, lambda, expression, parameters, captures);
        emitCodeType(context, composer, expression.getType());
        emitSourceLocation(composer, codeNodeToken(expression, lambda.arrow));
        composer.invokespecial(CODE_NODE_CLASS, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, CODE_SHAPE_CLASS,
                        CODE_TYPE_CLASS, SOURCE_LOCATION_CLASS));
    }

    private static void emitCodeShape(final CompilationContext context,
                                      final CodeBuilder composer,
                                      final Expr.Lambda lambda,
                                      final Expr expression,
                                      final List<Token> parameters,
                                      final List<Token> captures) {
        switch (expression) {
            case Expr.Literal literal -> {
                final var caseClass = compilerSumCase("CodeShape", "LiteralNode");
                composer.new_(caseClass);
                composer.dup();
                emitCodeLiteral(context, composer, literal);
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CODE_LITERAL_CLASS));
            }
            case Expr.Variable variable -> {
                final var symbol = variable.resolvedSymbolToken();
                final var parameterIndex = tokenIndex(parameters, symbol);
                final var caseClass = compilerSumCase("CodeShape",
                        parameterIndex >= 0 ? "ParameterNode" : "CaptureNode");
                final var position = parameterIndex >= 0
                        ? parameterIndex : tokenIndex(captures, symbol);
                if (position < 0) {
                    throw new IllegalStateException("Validated CodeModel capture was not planned.");
                }
                composer.new_(caseClass);
                composer.dup();
                composer.loadConstant(position);
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
            }
            case Expr.Property property -> {
                final var caseClass = compilerSumCase("CodeShape", "PropertyAccessNode");
                final var owner = property.resolvedOwnerName() == null
                        ? property.receiver.getType().name() : property.resolvedOwnerName();
                composer.new_(caseClass);
                composer.dup();
                emitCodeNode(context, composer, lambda, property.receiver, parameters, captures);
                emitPropertyId(composer, owner, property.name.lexeme());
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CODE_NODE_CLASS, PROPERTY_ID_CLASS));
            }
            case Expr.Binary binary -> {
                final var caseClass = compilerSumCase("CodeShape", "ComparisonNode");
                composer.new_(caseClass);
                composer.dup();
                emitComparisonOperator(composer, binary.operator.type());
                emitCodeNode(context, composer, lambda, binary.left, parameters, captures);
                emitCodeNode(context, composer, lambda, binary.right, parameters, captures);
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, COMPARISON_OPERATOR_CLASS,
                                CODE_NODE_CLASS, CODE_NODE_CLASS));
            }
            case Expr.Logical logical -> {
                final var caseClass = compilerSumCase("CodeShape",
                        logical.operator.type() == TokenType.AND ? "AndNode" : "OrNode");
                composer.new_(caseClass);
                composer.dup();
                emitCodeNode(context, composer, lambda, logical.left, parameters, captures);
                emitCodeNode(context, composer, lambda, logical.right, parameters, captures);
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CODE_NODE_CLASS, CODE_NODE_CLASS));
            }
            case Expr.Unary unary -> {
                final var caseClass = compilerSumCase("CodeShape", "NotNode");
                composer.new_(caseClass);
                composer.dup();
                emitCodeNode(context, composer, lambda, unary.right, parameters, captures);
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CODE_NODE_CLASS));
            }
            case Expr.TypeTest test -> {
                final var caseClass = compilerSumCase("CodeShape", "NullTestNode");
                composer.new_(caseClass);
                composer.dup();
                emitCodeNode(context, composer, lambda, test.value, parameters, captures);
                composer.iconst_1();
                composer.invokespecial(caseClass, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CODE_NODE_CLASS,
                                ConstantDescs.CD_boolean));
            }
            default -> throw new IllegalStateException("Unsupported validated CodeModel expression: "
                    + expression.getClass().getSimpleName());
        }
    }

    private static void emitComparisonOperator(final CodeBuilder composer,
                                               final TokenType operator) {
        final var caseName = switch (operator) {
            case EQUAL_EQUAL -> "Equal";
            case BANG_EQUAL -> "NotEqual";
            case LESS -> "LessThan";
            case LESS_EQUAL -> "LessThanOrEqual";
            case GREATER -> "GreaterThan";
            case GREATER_EQUAL -> "GreaterThanOrEqual";
            default -> throw new IllegalStateException("Unsupported validated CodeModel comparison.");
        };
        emitSingleton(composer, compilerSumCase("ComparisonOperator", caseName));
    }

    private static void emitCodeLiteral(final CompilationContext context,
                                        final CodeBuilder composer,
                                        final Expr.Literal literal) {
        if (literal.value == null || literal.getType() instanceof NullDescriptor) {
            emitSingleton(composer, compilerSumCase("CodeLiteral", "NullLiteral"));
            return;
        }
        if (literal.getType() instanceof StringDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "StringLiteral");
            composer.new_(caseClass);
            composer.dup();
            composer.ldc((String) literal.value);
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (literal.getType() instanceof BooleanDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "BooleanLiteral");
            composer.new_(caseClass);
            composer.dup();
            if ((Boolean) literal.value) composer.iconst_1();
            else composer.iconst_0();
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
            return;
        }
        if (literal.getType() instanceof IntDescriptor
                || literal.getType() instanceof LongDescriptor
                || literal.getType() instanceof ByteDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "IntegerLiteral");
            composer.new_(caseClass);
            composer.dup();
            composer.ldc(integerLiteralText(literal));
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (literal.getType() instanceof FloatDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "FloatLiteral");
            composer.new_(caseClass);
            composer.dup();
            composer.ldc(((Number) literal.value).doubleValue());
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_double));
            return;
        }
        if (literal.exactDecimal() != null) {
            final var caseClass = compilerSumCase("CodeLiteral", "DecimalLiteral");
            composer.new_(caseClass);
            composer.dup();
            composer.ldc(literal.exactDecimal().toString());
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        throw new IllegalStateException("Unsupported validated CodeModel literal type: "
                + literal.getType());
    }

    private static String integerLiteralText(final Expr.Literal literal) {
        if (literal.exactInteger() != null) return literal.exactInteger().toString();
        return String.valueOf(literal.value);
    }

    private static void emitCaptureValue(final CompilationContext context,
                                         final CodeBuilder composer,
                                         final Token capture,
                                         final TypeDescriptor type) {
        if (type instanceof NullDescriptor) {
            emitNullLiteralCapture(composer);
            return;
        }
        final var nullable = type instanceof NullableDescriptor;
        final var base = nullable ? ((NullableDescriptor) type).baseType() : type;
        if (isCodeLiteralType(base)) {
            if (!nullable) {
                emitLiteralCapture(context, composer, capture, base, false);
                return;
            }
            final var nullPath = composer.newLabel();
            final var done = composer.newLabel();
            emitVariable(context, composer, capture);
            composer.ifnull(nullPath);
            emitLiteralCapture(context, composer, capture, base, true);
            composer.goto_(done);
            composer.labelBinding(nullPath);
            emitNullLiteralCapture(composer);
            composer.labelBinding(done);
            return;
        }
        if (isReferenceValue(base)) {
            final var nullPath = composer.newLabel();
            final var done = composer.newLabel();
            emitVariable(context, composer, capture);
            composer.ifnull(nullPath);
            emitOpaqueCapture(context, composer, capture, base);
            composer.goto_(done);
            composer.labelBinding(nullPath);
            emitNullLiteralCapture(composer);
            composer.labelBinding(done);
            return;
        }
        emitOpaqueCapture(context, composer, capture, base);
    }

    private static boolean isCodeLiteralType(final TypeDescriptor type) {
        return type instanceof StringDescriptor || type instanceof BooleanDescriptor
                || type instanceof IntDescriptor || type instanceof LongDescriptor
                || type instanceof ByteDescriptor || type instanceof FloatDescriptor;
    }

    private static boolean isReferenceValue(final TypeDescriptor type) {
        return !(type instanceof IntDescriptor || type instanceof LongDescriptor
                || type instanceof FloatDescriptor || type instanceof BooleanDescriptor
                || type instanceof ByteDescriptor || type instanceof CharDescriptor);
    }

    private static void emitLiteralCapture(final CompilationContext context,
                                           final CodeBuilder composer,
                                           final Token capture,
                                           final TypeDescriptor type,
                                           final boolean boxed) {
        final var caseClass = compilerSumCase("CaptureValue", "LiteralCapture");
        composer.new_(caseClass);
        composer.dup();
        emitCodeLiteralFromVariable(context, composer, capture, type, boxed);
        composer.invokespecial(caseClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, CODE_LITERAL_CLASS));
    }

    private static void emitCodeLiteralFromVariable(final CompilationContext context,
                                                    final CodeBuilder composer,
                                                    final Token capture,
                                                    final TypeDescriptor type,
                                                    final boolean boxed) {
        if (type instanceof StringDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "StringLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (type instanceof BooleanDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "BooleanLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            if (boxed) {
                composer.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
            return;
        }
        if (type instanceof IntDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "IntegerLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            if (boxed) {
                composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            composer.invokestatic(ClassDesc.of("java.lang.Integer"), "toString",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int));
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (type instanceof LongDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "IntegerLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            if (boxed) {
                composer.invokevirtual(ClassDesc.of("java.lang.Long"), "longValue",
                        MethodTypeDesc.of(ConstantDescs.CD_long));
            }
            composer.invokestatic(ClassDesc.of("java.lang.Long"), "toString",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_long));
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (type instanceof ByteDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "IntegerLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            if (boxed) {
                composer.invokevirtual(ClassDesc.of("java.lang.Byte"), "byteValue",
                        MethodTypeDesc.of(ConstantDescs.CD_byte));
            }
            composer.invokestatic(ClassDesc.of("java.lang.Byte"), "toString",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_byte));
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            return;
        }
        if (type instanceof FloatDescriptor) {
            final var caseClass = compilerSumCase("CodeLiteral", "FloatLiteral");
            composer.new_(caseClass);
            composer.dup();
            emitVariable(context, composer, capture);
            if (boxed) {
                composer.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
            }
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_double));
            return;
        }
        throw new IllegalStateException("Capture is not a code-model literal kind: " + type);
    }

    private static void emitOpaqueCapture(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final Token capture,
                                          final TypeDescriptor type) {
        final var caseClass = compilerSumCase("CaptureValue", "OpaqueCapture");
        composer.new_(caseClass);
        composer.dup();
        emitVariable(context, composer, capture);
        emitConversion(context, composer, type, TypeDescriptor.ofAny());
        composer.invokespecial(caseClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
    }

    private static void emitNullLiteralCapture(final CodeBuilder composer) {
        final var caseClass = compilerSumCase("CaptureValue", "LiteralCapture");
        composer.new_(caseClass);
        composer.dup();
        emitSingleton(composer, compilerSumCase("CodeLiteral", "NullLiteral"));
        composer.invokespecial(caseClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, CODE_LITERAL_CLASS));
    }

    private static void emitPropertyId(final CodeBuilder composer,
                                       final String owner,
                                       final String name) {
        composer.ldc(owner);
        composer.ldc(name);
        composer.invokestatic(PROPERTY_ID_CLASS, SyntheticNames.PROPERTY_ID_FACTORY,
                MethodTypeDesc.of(PROPERTY_ID_CLASS, ConstantDescs.CD_String,
                        ConstantDescs.CD_String));
    }

    private static void emitSourceLocation(final CodeBuilder composer, final Token token) {
        final var span = token.span();
        final var start = span.start();
        final var end = span.end();
        composer.new_(SOURCE_LOCATION_CLASS);
        composer.dup();
        composer.ldc(span.sourcePath() == null ? "" : span.sourcePath());
        composer.loadConstant(start.line());
        composer.loadConstant(start.column() == null ? 0 : start.column());
        composer.loadConstant(end.line());
        composer.loadConstant(end.column() == null ? 0 : end.column());
        composer.invokespecial(SOURCE_LOCATION_CLASS, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                        ConstantDescs.CD_int, ConstantDescs.CD_int,
                        ConstantDescs.CD_int, ConstantDescs.CD_int));
    }

    private static Token codeNodeToken(final Expr expression, final Token fallback) {
        return switch (expression) {
            case Expr.Variable variable -> variable.name;
            case Expr.Property property -> property.name;
            case Expr.Binary binary -> binary.operator;
            case Expr.Logical logical -> logical.operator;
            case Expr.Unary unary -> unary.operator;
            case Expr.TypeTest test -> test.operator;
            case Expr.Literal literal ->
                    literal.numericToken() == null ? fallback : literal.numericToken();
            default -> fallback;
        };
    }

    private static int tokenIndex(final List<Token> tokens, final Token target) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).lexeme().equals(target.lexeme())) return index;
        }
        return -1;
    }

    private static void emitCodeType(final CompilationContext context,
                                     final CodeBuilder composer,
                                     final TypeDescriptor type) {
        if (type instanceof NullableDescriptor nullable) {
            final var caseClass = compilerSumCase("CodeType", "NullableType");
            composer.new_(caseClass);
            composer.dup();
            emitCodeType(context, composer, nullable.baseType());
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CODE_TYPE_CLASS));
            return;
        }
        if (type instanceof FunctionDescriptor function) {
            final var caseClass = compilerSumCase("CodeType", "FunctionType");
            composer.new_(caseClass);
            composer.dup();
            emitCodeTypeArray(context, composer, function.invocationParameters());
            emitCodeType(context, composer, function.returnType());
            composer.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object.arrayType(),
                            CODE_TYPE_CLASS));
            return;
        }
        final var caseClass = compilerSumCase("CodeType", "NominalType");
        composer.new_(caseClass);
        composer.dup();
        composer.ldc(codeTypeName(type));
        emitCodeTypeArray(context, composer, codeTypeArguments(type));
        composer.invokespecial(caseClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                        ConstantDescs.CD_Object.arrayType()));
    }

    private static void emitCodeTypeArray(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final List<TypeDescriptor> types) {
        composer.loadConstant(types.size());
        composer.anewarray(ConstantDescs.CD_Object);
        for (int index = 0; index < types.size(); index++) {
            composer.dup();
            composer.loadConstant(index);
            emitCodeType(context, composer, types.get(index));
            composer.aastore();
        }
    }

    private static String codeTypeName(final TypeDescriptor type) {
        if (type instanceof ArrayDescriptor) return "Array";
        if (type instanceof ByteArrayDescriptor) return "ByteArray";
        if (type instanceof GenericDescriptor generic) return generic.baseType().name();
        if (type instanceof ReferenceDescriptor) return "&";
        if (type instanceof TypeProjectionDescriptor projection) {
            return projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "out" : "in";
        }
        if (type instanceof CapturedTypeDescriptor capture) return codeTypeName(capture.upperBound());
        return type.name();
    }

    private static List<TypeDescriptor> codeTypeArguments(final TypeDescriptor type) {
        if (type instanceof ArrayDescriptor array) return List.of(array.elementType());
        if (type instanceof GenericDescriptor generic) return generic.typeParameters();
        if (type instanceof ReferenceDescriptor reference) return List.of(reference.baseType());
        if (type instanceof TypeProjectionDescriptor projection) return List.of(projection.bound());
        if (type instanceof CapturedTypeDescriptor capture) return codeTypeArguments(capture.upperBound());
        return List.of();
    }

    private static void emitCall(CompilationContext context, final CodeBuilder composer, final Expr.Call call ){
        if (call.intrinsicOperation() != null) {
            switch (call.intrinsicOperation().id()) {
                case ARRAY_FILL -> emitArrayFill(context, composer, call, call.intrinsicOperation());
                case ARRAY_ALLOC -> emitArrayAllocate(context, composer, call, call.intrinsicOperation());
                case BYTE_ARRAY_ALLOC -> emitByteArrayAllocate(context, composer, call, call.intrinsicOperation());
                case ARRAY_CLEAR_SLOT -> emitArraySlotClear(context, composer, call);
                case OPTION_UNWRAP_SOME -> emitOptionUnwrapSome(context, composer, call);
                case INT_TO_BYTE -> {
                    emitExpr(context, composer, call.arguments.getFirst());
                    composer.i2b();
                    context.lastEmittedType = call.getType();
                }
                case BYTE_TO_INT -> {
                    emitExpr(context, composer, call.arguments.getFirst());
                    composer.ldc(255);
                    composer.iand();
                    context.lastEmittedType = call.getType();
                }
                default -> throw new IllegalStateException("Unexpected intrinsic function call: "
                        + call.intrinsicOperation().id().stableName());
            }
            return;
        }
        if (call.implicitMemberCall() != null) {
            emitMemberCall(context, composer, call.implicitMemberCall());
            return;
        }
        if (call.resolvedFunctionName() != null) {
            final var functionName = call.resolvedFunctionName();
            final var functionType = call.resolvedFunctionDeclaration() == null
                    || call.resolvedFunctionDeclaration().typeDescriptor().returnType() instanceof InferDescriptor
                    ? context.symbols.getFunctionType(context.resolution.functionSymbolToken(functionName))
                    : call.resolvedFunctionDeclaration().typeDescriptor();
            if (functionType == null) throw new IllegalStateException("Resolved function has no function type.");
            final var runtimeType = functionType.isGeneric()
                    ? (FunctionDescriptor) TypeSubstitution.erase(functionType)
                    : functionType;
            final var functionOwner = call.resolvedFunctionDeclaration() == null
                    ? context.metadata.functionOwner(functionName)
                    : context.metadata.functionOwner(call.resolvedFunctionDeclaration());
            if (functionOwner == null) {
                throw new IllegalStateException("Resolved function has no JVM owner: " + functionName);
            }
            if (call.variadicElementType() != null) {
                final var fixedArity = call.variadicFixedArity();
                emitVariadicArguments(context, composer, call.arguments, runtimeType,
                        fixedArity, call.variadicElementType());
                emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
                final var invokedType = call.arguments.size() < fixedArity
                        ? prefixFunctionType(runtimeType, call.arguments.size())
                        : runtimeType;
                final var invocation = withHiddenParameters(toJavaMethodDescriptor(invokedType),
                        call.evidenceArguments().size(), call.reifiedArguments().size());
                composer.invokestatic(ClassDesc.of(functionOwner),
                        functionName.substring(functionName.lastIndexOf('.') + 1),
                        invocation);
            } else {
                for (int i = 0; i < call.arguments.size(); i++) {
                    emitExpr(context, composer, call.arguments.get(i));
                    emitConversion(context, composer, context.lastEmittedType, functionType.parameters().get(i));
                }
                emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
                final var invokedType = prefixFunctionType(runtimeType, call.arguments.size());
                final var invocation = withHiddenParameters(toJavaMethodDescriptor(invokedType),
                        call.evidenceArguments().size(), call.reifiedArguments().size());
                composer.invokestatic(ClassDesc.of(functionOwner),
                        functionName.substring(functionName.lastIndexOf('.') + 1),
                        invocation);
            }
            if (functionType.isGeneric()) {
                final var instantiated = TypeSubstitution.erase(call.getType());
                emitConversion(context, composer, runtimeType.returnType(), instantiated);
                context.lastEmittedType = instantiated;
            } else {
                context.lastEmittedType = functionType.returnType();
            }
            return;
        }

        final var binding = context.symbols.getSymbol(call.resolvedSymbolToken());
        final var bindingType = binding.type() instanceof ReferenceDescriptor reference
            ? reference.baseType()
            : binding.type();
        if (!(bindingType instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Resolved call target is not a function");
        }

        final var runtimeType = functionType.isGeneric()
                ? (FunctionDescriptor) TypeSubstitution.erase(functionType)
                : functionType;
        final var constrainedTemplate = LambdaCompilationPlan.hasInferredContractBounds(functionType)
                && isInferredConstrainedLambdaBinding(binding);
        if (constrainedTemplate) {
            final var specializedType = call.specializedFunctionType();
            if (specializedType == null) {
                throw new IllegalStateException("Constrained lambda call has no specialized function type.");
            }
            emitLambdaTemplateSpecialization(context, composer, call.resolvedSymbolToken(),
                    functionType, specializedType, call.evidenceArguments());
        } else {
            emitVariable(context, composer, call.resolvedSymbolToken());
        }
        if (call.variadicElementType() != null) {
            emitVariadicArguments(context, composer, call.arguments, runtimeType,
                    call.variadicFixedArity(), call.variadicElementType());
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType, runtimeType.parameters().get(i));
            }
        }
        if (!constrainedTemplate && runtimeType.hasHiddenSlots()) {
            emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
        }

        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(runtimeType), "invoke",
                toJavaMethodDescriptor(runtimeType));
        if (functionType.isGeneric()) {
            final var instantiatedReturn = TypeSubstitution.erase(call.getType());
            emitConversion(context, composer, runtimeType.returnType(), instantiatedReturn);
            context.lastEmittedType = call.getType();
        } else {
            context.lastEmittedType = functionType.returnType();
        }
    }

    private static boolean isInferredConstrainedLambdaBinding(final com.maruseron.zeron.analize.Bind binding) {
        return binding.declaration() instanceof Stmt.Var variable
                && variable.initializer() instanceof Expr.Lambda lambda
                && lambda.typeParameters().isEmpty();
    }

    private static void emitLambdaTemplateValue(final CompilationContext context,
                                                final CodeBuilder composer,
                                                final Expr.Lambda lambda) {
        final var templateClass = ClassDesc.of("zeron.runtime.LambdaTemplateSupport");
        final var methodHandleClass = ClassDesc.of("java.lang.invoke.MethodHandle");
        final var factoryDescriptor = MethodTypeDesc.of(ConstantDescs.CD_Object,
                ConstantDescs.CD_Object.arrayType(), methodHandleClass.arrayType());
        final var factory = MethodHandleDesc.of(DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of(context.mainClassName),
                context.lambdaPlan.lambdaTemplateFactoryName(lambda),
                factoryDescriptor.descriptorString());
        final var captures = context.lambdaPlan.captures(lambda);

        composer.new_(templateClass);
        composer.dup();
        composer.ldc(factory);
        composer.loadConstant(Integer.valueOf(captures.size()));
        composer.anewarray(ConstantDescs.CD_Object);
        for (int index = 0; index < captures.size(); index++) {
            composer.dup();
            composer.loadConstant(Integer.valueOf(index));
            emitVariable(context, composer, captures.get(index));
            emitConversion(context, composer, context.lastEmittedType, TypeDescriptor.ofAny());
            composer.aastore();
        }
        composer.invokespecial(templateClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        methodHandleClass, ConstantDescs.CD_Object.arrayType()));
        context.lastEmittedType = lambda.getType();
    }

    private static void emitLambdaTemplateSpecialization(
            final CompilationContext context, final CodeBuilder composer, final Token binding,
            final FunctionDescriptor scheme, final FunctionDescriptor specializedType,
            final List<Expr.EvidenceArgument> evidenceArguments) {
        final var templateClass = ClassDesc.of("zeron.runtime.LambdaTemplateSupport");
        final var erasedScheme = (FunctionDescriptor) TypeSubstitution.erase(scheme);
        emitVariable(context, composer, binding);
        composer.ldc(FunctionShapeKey.of(
                (FunctionDescriptor) TypeSubstitution.erase(specializedType)).canonicalEncoding());
        emitEvidenceArray(context, composer, evidenceArguments);
        composer.invokevirtual(templateClass, "specialize",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_String,
                        ClassDesc.of("java.lang.invoke.MethodHandle").arrayType()));
        composer.checkcast(TypeDescriptor.toJavaClassDesc(erasedScheme));
        context.lastEmittedType = scheme;
    }

    private static void emitEvidenceArray(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final List<Expr.EvidenceArgument> evidenceArguments) {
        final var methodHandleClass = ClassDesc.of("java.lang.invoke.MethodHandle");
        composer.loadConstant(Integer.valueOf(evidenceArguments.size()));
        composer.anewarray(methodHandleClass);
        for (int index = 0; index < evidenceArguments.size(); index++) {
            composer.dup();
            composer.loadConstant(Integer.valueOf(index));
            emitEvidenceArguments(context, composer, List.of(evidenceArguments.get(index)));
            composer.aastore();
        }
    }

    record EvidenceField(Token token, String fieldName) {}

    static List<Token> witnessSlots(final CompilationContext context, final FunctionDescriptor functionType) {
        return witnessSlots(context, functionType.typeParameters());
    }

    static List<Token> witnessSlots(final CompilationContext context,
                                    final List<TypeParameterDescriptor> typeParameters) {
        return evidenceFields(context, typeParameters, "generic", "")
                .stream().map(EvidenceField::token).toList();
    }

    static List<EvidenceField> evidenceFields(final CompilationContext context,
                                              final List<TypeParameterDescriptor> typeParameters,
                                              final String namespace,
                                              final String ownerName) {
        final var fields = new ArrayList<EvidenceField>();
        final var ownerKey = ownerName.replace('.', '$');
        for (int parameterIndex = 0; parameterIndex < typeParameters.size(); parameterIndex++) {
            final var parameter = typeParameters.get(parameterIndex);
            for (int boundIndex = 0; boundIndex < parameter.bounds().size(); boundIndex++) {
                final var bound = parameter.bounds().get(boundIndex);
                final var contractName = bound instanceof GenericDescriptor generic
                        ? generic.baseType().name()
                        : bound instanceof ReferenceDescriptor reference
                            ? reference.baseType().name() : bound.name();
                final var contract = context.resolution.contracts().get(contractName);
                if (contract == null) continue;
                final var contractUse = ContractComposition.fromType(bound);
                if (contractUse == null) continue;
                for (final var method : ContractComposition.methods(
                        context.resolution.contracts(), contractUse)) {
                    final var token = Expr.methodEvidenceToken(parameter, boundIndex, method.index());
                    fields.add(new EvidenceField(token, evidenceFieldName(namespace, ownerKey,
                            parameterIndex, boundIndex, "method", method.index())));
                }
            }
        }
        return List.copyOf(fields);
    }

    private static String evidenceFieldName(final String namespace, final String ownerKey,
                                            final int parameterIndex, final int boundIndex,
                                            final String kind, final int slotIndex) {
        return "$zeron$evidence$" + namespace + "$" + ownerKey + "$" + parameterIndex
                + "$" + boundIndex + "$" + kind + "$" + slotIndex;
    }

    static List<EvidenceField> classEvidenceFields(final CompilationContext context,
                                                  final Stmt.ClassDecl declaration) {
        final var fields = new ArrayList<EvidenceField>(evidenceFields(
                context, declaration.typeParameters(), "class", declaration.name().lexeme()));
        for (final var applied : ContractComposition.forClass(
                context.resolution.contracts(), declaration)) {
            fields.addAll(evidenceFields(context, applied.declaration().typeParameters(),
                    "contract", applied.declaration().name().lexeme()));
        }
        return List.copyOf(fields);
    }

    static MethodTypeDesc withEvidenceParameters(final MethodTypeDesc methodType, final int count) {
        if (count == 0) return methodType;
        final var parameters = new ClassDesc[count];
        java.util.Arrays.fill(parameters, ClassDesc.of("java.lang.invoke.MethodHandle"));
        return methodType.insertParameterTypes(methodType.parameterCount(), parameters);
    }

    static MethodTypeDesc withHiddenParameters(final MethodTypeDesc methodType,
                                               final int evidenceCount,
                                               final int reifiedCount) {
        return withReifiedParameters(withEvidenceParameters(methodType, evidenceCount), reifiedCount);
    }

    static MethodTypeDesc withReifiedParameters(final MethodTypeDesc methodType,
                                                final int count) {
        if (count == 0) return methodType;
        final var parameters = new ClassDesc[count];
        java.util.Arrays.fill(parameters, RuntimeTypeDescriptorEmitter.DESCRIPTOR_CLASS);
        return methodType.insertParameterTypes(methodType.parameterCount(), parameters);
    }

    /// A generic effect payload constructor carries one hidden application descriptor after every
    /// other hidden parameter. Non-generic effects keep their existing descriptor-free ABI.
    static MethodTypeDesc withEffectDescriptorParameter(final MethodTypeDesc methodType,
                                                        final boolean genericEffect) {
        if (!genericEffect) return methodType;
        return methodType.insertParameterTypes(methodType.parameterCount(),
                RuntimeTypeDescriptorEmitter.DESCRIPTOR_CLASS);
    }

    static List<Token> reifiedSlots(final FunctionDescriptor functionType) {
        return RuntimeTypeDescriptorEmitter.reifiedSlots(functionType.typeParameters());
    }

    static List<Token> reifiedSlots(final List<TypeParameterDescriptor> typeParameters) {
        return RuntimeTypeDescriptorEmitter.reifiedSlots(typeParameters);
    }

    static void declareReifiedSlots(final CompilationContext context, final Stmt declaration,
                                    final List<TypeParameterDescriptor> typeParameters) {
        for (final var token : reifiedSlots(typeParameters)) {
            context.symbols.declareSymbol(declaration, token,
                    RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE, BindingMutability.IMMUTABLE);
            context.symbols.define(token);
        }
    }

    static void emitReifiedArguments(final CompilationContext context, final CodeBuilder composer,
                                     final List<TypeDescriptor> arguments) {
        RuntimeTypeDescriptorEmitter.emitArguments(context, composer, arguments);
    }

    /// Forwards a generic effect payload's application descriptor. Inside a named constructor the
    /// hidden descriptor parameter is forwarded unchanged; at any other closed site the cached
    /// structural descriptor of the constructed application is emitted.
    private static void emitEffectDescriptorArgument(final CompilationContext context,
                                                     final CodeBuilder composer,
                                                     final Expr.MemberCall call) {
        if (context.currentEffectDescriptorToken != null
                && java.util.Objects.equals(context.currentEffectDescriptorOwner,
                        call.resolvedClassName())) {
            emitVariable(context, composer, context.currentEffectDescriptorToken);
            context.lastEmittedType = RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE;
            return;
        }
        final var application = payloadApplication(call.getType());
        if (!(application instanceof GenericDescriptor)) {
            throw new IllegalStateException("A generic effect constructor call lost its applied type.");
        }
        RuntimeTypeDescriptorEmitter.emit(context, composer, application);
        context.lastEmittedType = RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE;
    }

    private static TypeDescriptor payloadApplication(final TypeDescriptor type) {
        var current = type;
        while (current instanceof ReferenceDescriptor reference) current = reference.baseType();
        while (current instanceof NullableDescriptor nullable) current = nullable.baseType();
        return current;
    }

    static void emitHiddenArguments(final CompilationContext context, final CodeBuilder composer,
                                    final List<Expr.EvidenceArgument> evidenceArguments,
                                    final List<TypeDescriptor> reifiedArguments) {
        emitEvidenceArguments(context, composer, evidenceArguments);
        emitReifiedArguments(context, composer, reifiedArguments);
    }

    static void emitEvidenceUnavailable(final CodeBuilder composer) {
        final var exception = ClassDesc.of("java.lang.UnsupportedOperationException");
        composer.new_(exception);
        composer.dup();
        composer.ldc("This generic entry point requires compiler-provided type-class evidence.");
        composer.invokespecial(exception, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
        composer.athrow();
    }

    static void emitEvidenceArguments(final CompilationContext context,
                                      final CodeBuilder composer,
                                      final List<Expr.EvidenceArgument> evidence) {
        for (final var argument : evidence) {
            if (argument.forwardToken() != null) {
                emitVariable(context, composer, argument.forwardToken());
            } else if (argument.instanceMethod()) {
                final var ownerName = context.metadata.functionOwner(argument.ownerName()) == null
                        ? argument.ownerName()
                        : context.metadata.functionOwner(argument.ownerName());
                final var kind = switch (argument.handleKind()) {
                    case STATIC -> DirectMethodHandleDesc.Kind.STATIC;
                    case INTERFACE_STATIC -> DirectMethodHandleDesc.Kind.INTERFACE_STATIC;
                    case VIRTUAL -> DirectMethodHandleDesc.Kind.VIRTUAL;
                    case INTERFACE -> DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL;
                };
                final var erasedSignature = (FunctionDescriptor) TypeSubstitution.erase(argument.signature());
                final var evidenceCount = Math.max(witnessSlots(context, argument.signature()).size(),
                        argument.prerequisiteEvidence().size());
                final var methodType = switch (argument.handleKind()) {
                    case STATIC -> toJavaMethodDescriptor(erasedSignature);
                    case INTERFACE_STATIC -> toJavaMethodDescriptor(erasedSignature);
                    case VIRTUAL -> toJavaMethodDescriptor(TypeDescriptor.functionWithEffectsOf(
                            erasedSignature.name(), erasedSignature.returnType(),
                            erasedSignature.parameters().subList(1, erasedSignature.parameters().size()),
                            List.of(), erasedSignature.raisedEffects()));
                    case INTERFACE -> {
                        final var contract = context.resolution.contracts().get(argument.ownerName());
                        final var method = contract == null ? null : contract.methods().stream()
                                .filter(candidate -> candidate.name().lexeme()
                                        .equals(argument.constructorName()))
                                .findFirst().orElse(null);
                        if (method == null) {
                            throw new IllegalStateException("Resolved contract evidence method was not found.");
                        }
                        yield toJavaMethodDescriptor((FunctionDescriptor) TypeSubstitution.erase(
                                method.typeDescriptor()));
                    }
                };
                final var handleType = withEvidenceParameters(methodType, evidenceCount);
                composer.ldc(MethodHandleDesc.of(kind, ClassDesc.of(ownerName),
                        argument.constructorName(), handleType.descriptorString()));
                if (!argument.prerequisiteEvidence().isEmpty()) {
                    final var boundArgumentPosition = erasedSignature.parameters().size()
                            - (argument.handleKind() == Expr.EvidenceHandleKind.STATIC
                                    || argument.handleKind() == Expr.EvidenceHandleKind.INTERFACE_STATIC ? 0 : 1);
                    composer.loadConstant(Integer.valueOf(boundArgumentPosition));
                    composer.loadConstant(Integer.valueOf(argument.prerequisiteEvidence().size()));
                    composer.anewarray(ClassDesc.of("java.lang.Object"));
                    for (int index = 0; index < argument.prerequisiteEvidence().size(); index++) {
                        composer.dup();
                        composer.loadConstant(Integer.valueOf(index));
                        emitEvidenceArguments(context, composer,
                                List.of(argument.prerequisiteEvidence().get(index)));
                        composer.aastore();
                    }
                    composer.invokestatic(ClassDesc.of("java.lang.invoke.MethodHandles"), "insertArguments",
                            MethodTypeDesc.of(ClassDesc.of("java.lang.invoke.MethodHandle"),
                                    ClassDesc.of("java.lang.invoke.MethodHandle"), ConstantDescs.CD_int,
                                    ConstantDescs.CD_Object.arrayType()));
                }
            } else {
                final var targetType = (FunctionDescriptor) TypeSubstitution.erase(argument.signature());
                composer.ldc(MethodHandleDesc.of(DirectMethodHandleDesc.Kind.STATIC,
                        ClassDesc.of(argument.ownerName()), argument.constructorName(),
                        toJavaMethodDescriptor(targetType).descriptorString()));
            }
        }

    }

    private static void emitEqualityEvidenceCall(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final Expr.Binary binary) {
        final var evidence = binary.synthesizedEqualityEvidence();
        emitEvidenceArguments(context, composer, List.of(evidence));
        emitExpr(context, composer, binary.left);
        emitExpr(context, composer, binary.right);
        final var signature = (FunctionDescriptor) TypeSubstitution.erase(evidence.signature());
        final var parameterTypes = signature.parameters().stream()
                .map(TypeDescriptor::toJavaClassDesc).toList();
        composer.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(signature.returnType()), parameterTypes));
        context.lastEmittedType = signature.returnType();
    }

    private static void emitStringConcatenationRight(final CompilationContext context,
                                                     final CodeBuilder composer,
                                                     final Expr.Binary binary) {
        if (binary.right.getType() instanceof NullDescriptor) {
            emitExpr(context, composer, binary.right);
            composer.pop();
            composer.ldc("null");
            context.lastEmittedType = TypeDescriptor.ofString();
            return;
        }
        if (binary.nullableStringification()) {
            final var nullPath = composer.newLabel();
            final var done = composer.newLabel();
            emitExpr(context, composer, binary.right);
            composer.dup();
            composer.ifnull(nullPath);
            if (binary.displayCall() != null) {
                emitMemberCall(context, composer, binary.displayCall(), true);
            } else {
                final var baseType = ((NullableDescriptor) binary.right.getType()).baseType();
                emitConversion(context, composer, context.lastEmittedType, TypeSubstitution.erase(baseType));
                emitBuiltInStringValue(context, composer, baseType);
            }
            composer.goto_(done);
            composer.labelBinding(nullPath);
            composer.pop();
            composer.ldc("null");
            composer.labelBinding(done);
            context.lastEmittedType = TypeDescriptor.ofString();
            return;
        }
        if (binary.displayCall() != null) {
            emitMemberCall(context, composer, binary.displayCall(), false);
            return;
        }
        if (binary.builtInStringification()) {
            emitExpr(context, composer, binary.right);
            emitBuiltInStringValue(context, composer, binary.right.getType());
            return;
        }
        throw new IllegalStateException("String concatenation is missing resolved Display evidence.");
    }

    private static void emitBuiltInStringValue(final CompilationContext context,
                                               final CodeBuilder composer,
                                               final TypeDescriptor valueType) {
        final var type = valueType instanceof ReferenceDescriptor reference
                ? reference.baseType() : valueType;
        if (type instanceof StringDescriptor || type instanceof InferDescriptor) {
            context.lastEmittedType = TypeDescriptor.ofString();
            return;
        }
        if (type instanceof UnitDescriptor) {
            composer.pop();
            composer.ldc("Unit");
            context.lastEmittedType = TypeDescriptor.ofString();
            return;
        }
        final var descriptor = TypeSubstitution.erase(type);
        composer.invokestatic(ClassDesc.of("java.lang.String"), "valueOf",
                MethodTypeDesc.of(ConstantDescs.CD_String, TypeDescriptor.toJavaClassDesc(descriptor)));
        context.lastEmittedType = TypeDescriptor.ofString();
    }

    private static void emitArrayFill(CompilationContext context, final CodeBuilder composer,
                               final Expr.Call call,
                               final ResolvedIntrinsicOperation operation ){
        emitExpr(context, composer, call.arguments.get(0));
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        composer.dup();
        emitExpr(context, composer, call.arguments.get(1));
        emitConversion(context, composer, context.lastEmittedType, operation.parameterTypes().get(1));
        emitBox(context, composer, context.lastEmittedType);
        composer.invokestatic(ClassDesc.of("java.util.Arrays"), "fill",
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_Object));
        context.lastEmittedType = operation.resultType();
    }

    private static void emitArrayAllocate(CompilationContext context, final CodeBuilder composer,
                                   final Expr.Call call,
                                   final ResolvedIntrinsicOperation operation ){
        emitExpr(context, composer, call.arguments.getFirst());
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        context.lastEmittedType = operation.resultType();
    }

    private static void emitByteArrayAllocate(CompilationContext context, final CodeBuilder composer,
                                              final Expr.Call call,
                                              final ResolvedIntrinsicOperation operation) {
        emitExpr(context, composer, call.arguments.getFirst());
        composer.newarray(TypeKind.BYTE);
        context.lastEmittedType = operation.resultType();
    }

    private static void emitArraySlotClear(CompilationContext context, final CodeBuilder composer, final Expr.Call call ){
        emitExpr(context, composer, call.arguments.get(0));
        emitExpr(context, composer, call.arguments.get(1));
        composer.aconst_null();
        composer.aastore();
        emitUnitValue(context, composer);
        context.lastEmittedType = TypeDescriptor.ofUnit();
    }

    private static void emitOptionUnwrapSome(CompilationContext context, final CodeBuilder composer, final Expr.Call call ){
        emitExpr(context, composer, call.arguments.getFirst());
        final var someClass = ClassDesc.of("zeron.lang.Option$$sum$Some");
        composer.checkcast(someClass);
        composer.invokevirtual(someClass, "$zeron$payload$0",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        context.lastEmittedType = call.getType();
    }

    private static void emitMemberCall(CompilationContext context, final CodeBuilder composer, final Expr.MemberCall call ){
        if (call.namespaceCall() != null) {
            emitCall(context, composer, call.namespaceCall());
            return;
        }
        if (call.safeNavigation()) {
            emitSafeMemberCall(context, composer, call);
            return;
        }
        emitMemberCall(context, composer, call, false);
    }

    private static void emitSafeMemberCall(CompilationContext context, final CodeBuilder composer, final Expr.MemberCall call ){
        final var nullPath = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(context, composer, call.receiver);
        composer.dup();
        composer.ifnull(nullPath);
        emitMemberCall(context, composer, call, true);
        emitConversion(context, composer, context.lastEmittedType, call.getType());
        composer.goto_(done);
        composer.labelBinding(nullPath);
        composer.pop();
        composer.aconst_null();
        composer.labelBinding(done);
        context.lastEmittedType = call.getType();
    }

    private static void emitMemberCall(CompilationContext context, final CodeBuilder composer,
                                final Expr.MemberCall call,
                                final boolean receiverOnStack ){
        if (call.receiverFunctionSymbol() != null) {
            final var functionType = call.resolvedDescriptor();
            if (functionType == null || !functionType.isReceiverFunction()) {
                throw new IllegalStateException("Resolved receiver function call has no receiver shape.");
            }
            final var runtimeType = (FunctionDescriptor) TypeSubstitution.erase(functionType);
            if (receiverOnStack) composer.pop();
            emitVariable(context, composer, call.receiverFunctionSymbol());
            if (receiverOnStack) {
                composer.swap();
            } else {
                emitExpr(context, composer, call.receiver);
            }
            final var receiverType = call.receiver.getType() instanceof NullableDescriptor nullable
                    ? nullable.baseType() : call.receiver.getType();
            emitConversion(context, composer, receiverType, runtimeType.receiverType());
            for (int index = 0; index < call.arguments.size(); index++) {
                emitExpr(context, composer, call.arguments.get(index));
                emitConversion(context, composer, context.lastEmittedType, runtimeType.parameters().get(index));
            }
            composer.invokeinterface(TypeDescriptor.toJavaClassDesc(runtimeType), "invoke",
                    toJavaMethodDescriptor(runtimeType));
            emitConversion(context, composer, runtimeType.returnType(), TypeSubstitution.erase(call.getType()));
            context.lastEmittedType = call.getType();
            return;
        }
        if (call.functionProperty() != null) {
            emitFunctionPropertyCall(context, composer, call, receiverOnStack);
            return;
        }
        if (call.witnessEvidenceToken() != null) {
            final var factoryType = call.witnessFactoryType();
            final var receiverOffset = call.witnessEvidenceHasReceiver() ? 1 : 0;
            if (call.witnessEvidenceHasReceiver() && !receiverOnStack) {
                // A stored receiver initializes its evidence tokens while it is unpacked, so it
                // must be evaluated before the token they provide is loaded.
                emitExpr(context, composer, call.receiver);
                emitConversion(context, composer, context.lastEmittedType,
                        TypeSubstitution.erase(factoryType.parameters().getFirst()));
                emitVariable(context, composer, call.witnessEvidenceToken());
                composer.swap();
            } else {
                emitVariable(context, composer, call.witnessEvidenceToken());
                if (call.witnessEvidenceHasReceiver()) {
                    composer.swap();
                }
            }
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                        TypeSubstitution.erase(factoryType.parameters().get(i + receiverOffset)));
            }
            emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
            final var erasedParameters = factoryType.parameters().stream()
                    .map(TypeSubstitution::erase)
                    .map(TypeDescriptor::toJavaClassDesc).toList();
            composer.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                    withHiddenParameters(
                            MethodTypeDesc.of(ConstantDescs.CD_Object, erasedParameters),
                            call.evidenceArguments().size(), call.reifiedArguments().size()));
            emitConversion(context, composer, TypeDescriptor.ofName("java.lang.Object"),
                    TypeSubstitution.erase(factoryType.returnType()));
            context.lastEmittedType = factoryType.returnType();
            return;
        }
        if (call.witnessMethodName() != null) {
            final var helperType = call.witnessMethodType();
            final var owner = context.metadata.functionOwner(call.witnessMethodOwner());
            if (owner == null) {
                throw new IllegalStateException("Resolved witness helper owner not found.");
            }
            if (call.witnessMethodHasReceiver() && !receiverOnStack) {
                emitExpr(context, composer, call.receiver);
            }
            for (int index = 0; index < call.arguments.size(); index++) {
                emitExpr(context, composer, call.arguments.get(index));
                emitConversion(context, composer, context.lastEmittedType,
                        TypeSubstitution.erase(helperType.parameters().get(
                                index + (call.witnessMethodHasReceiver() ? 1 : 0))));
            }
            emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
            composer.invokestatic(ClassDesc.of(owner), call.witnessMethodName(),
                    withHiddenParameters(toJavaMethodDescriptor(helperType),
                            call.evidenceArguments().size(), call.reifiedArguments().size()));
            emitConversion(context, composer,
                    TypeSubstitution.erase(helperType.returnType()), call.getType());
            context.lastEmittedType = call.getType();
            return;
        }
        if (call.javaCallTarget() != null) {
            emitJavaMemberCall(context, composer, call, receiverOnStack);
            return;
        }
        if (call.sumCaseFactory()) {
            final var declaration = context.resolution.classes().get(call.resolvedClassName());
            if (declaration == null) throw new IllegalStateException("Resolved sum case class not found.");
            final var classDesc = ClassDesc.of(call.resolvedClassName());
            final var constructorTypes = declaration.canonicalConstructorTypes();
            final var parameters = constructorTypes.stream()
                    .map(type -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)))
                    .toList();
            if (constructorTypes.isEmpty()) {
                composer.getstatic(classDesc, "$zeron$singleton", classDesc);
            } else {
                composer.new_(classDesc);
                composer.dup();
                for (int i = 0; i < call.arguments.size(); i++) {
                    emitExpr(context, composer, call.arguments.get(i));
                    final var parameterType = constructorTypes.get(i);
                    emitConversion(context, composer, context.lastEmittedType, parameterType);
                    emitConversion(context, composer, parameterType, TypeSubstitution.erase(parameterType));
                }
                composer.invokespecial(classDesc, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
            }
            context.lastEmittedType = call.getType();
            return;
        }
        if (call.name.lexeme().equals("new") && call.resolvedClassName() != null) {
            final var declaration = context.resolution.classes().get(call.resolvedClassName());
            if (declaration == null) throw new IllegalStateException("Resolved constructor class not found.");
            final var classDesc = ClassDesc.of(call.resolvedClassName());
            final var constructorTypes = declaration.canonicalConstructorTypes();
            final var parameters = constructorTypes.stream()
                    .map(type -> TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)))
                    .toList();
            if (declaration.isEffect() && declaration.typeParameters().isEmpty()
                    && context.resolution.isStatelessEffect(call.resolvedClassName())) {
                composer.invokestatic(classDesc, "new", MethodTypeDesc.of(classDesc), true);
                context.lastEmittedType = call.getType();
                return;
            }
            final var genericEffect = NominalTypeEmitter.isGenericEffect(declaration);
            composer.new_(classDesc);
            composer.dup();
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                final var parameterType = constructorTypes.get(i);
                emitConversion(context, composer, context.lastEmittedType, parameterType);
                emitConversion(context, composer, parameterType, TypeSubstitution.erase(parameterType));
            }
            emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
            if (genericEffect) {
                emitEffectDescriptorArgument(context, composer, call);
            }
            composer.invokespecial(classDesc, "<init>",
                    withEffectDescriptorParameter(
                            withHiddenParameters(MethodTypeDesc.of(ConstantDescs.CD_void, parameters),
                                    call.evidenceArguments().size(), call.reifiedArguments().size()),
                            genericEffect));
            context.lastEmittedType = call.getType();
            return;
        }

            if (call.resolvedClassName() != null) {
                final var declaration = context.resolution.classes().get(call.resolvedClassName());
                final var namedConstructor = declaration == null ? null : declaration.namedConstructors().stream()
                    .filter(candidate -> candidate.name().lexeme().equals(call.name.lexeme()))
                    .findFirst()
                    .orElse(null);
                if (namedConstructor != null) {
                final var erasedFactory = (FunctionDescriptor) TypeSubstitution.erase(
                    namedConstructor.typeDescriptor());
                if (call.variadicElementType() != null) {
                    final var resolvedFactory = call.resolvedDescriptor() == null
                            ? namedConstructor.typeDescriptor()
                            : call.resolvedDescriptor();
                    final var resolvedElement = resolvedFactory.parameters().getLast()
                            instanceof ArrayDescriptor array
                            ? array.elementType() : call.variadicElementType();
                    emitVariadicArguments(context, composer, call.arguments,
                            (FunctionDescriptor) TypeSubstitution.erase(resolvedFactory),
                            call.variadicFixedArity(), resolvedElement);
                } else {
                    for (int i = 0; i < call.arguments.size(); i++) {
                        emitExpr(context, composer, call.arguments.get(i));
                        emitConversion(context, composer, context.lastEmittedType,
                            TypeSubstitution.erase(namedConstructor.typeDescriptor().parameters().get(i)));
                    }
                }
                final var genericEffect = declaration != null && NominalTypeEmitter.isGenericEffect(declaration);
                emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
                if (genericEffect) {
                    emitEffectDescriptorArgument(context, composer, call);
                }
                composer.invokestatic(ClassDesc.of(call.resolvedClassName()), call.name.lexeme(),
                    withEffectDescriptorParameter(
                            withHiddenParameters(toJavaMethodDescriptor(erasedFactory),
                                    call.evidenceArguments().size(), call.reifiedArguments().size()),
                            genericEffect));
                final var erasedReturnType = TypeSubstitution.erase(namedConstructor.typeDescriptor().returnType());
                emitConversion(context, composer, erasedReturnType, call.getType());
                context.lastEmittedType = call.getType();
                return;
                }
            }

        if (call.resolvedExtensionMethod() != null) {
            emitExtensionCall(context, composer, call, receiverOnStack);
            return;
        }

        final var ownerName = call.resolvedOwnerName() != null
            ? call.resolvedOwnerName()
            : nominalName(context, call.receiver.getType());
        final var classOwner = context.resolution.classes().get(ownerName);
        final var classMethod = call.resolvedSourceMethod() != null ? call.resolvedSourceMethod()
                : classOwner == null ? null : classOwner.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var contract = context.resolution.contracts().get(ownerName);
        final var contractMethod = call.resolvedContractMethod() != null ? call.resolvedContractMethod()
                : contract == null ? null : contract.methods().stream()
                .filter(method -> method.name().lexeme().equals(call.name.lexeme()))
                .findFirst()
                .orElse(null);
        final var descriptor = classMethod != null
                ? classMethod.typeDescriptor()
                : contractMethod.typeDescriptor();
        final var resolvedDescriptor = call.resolvedDescriptor() == null
            ? descriptor
            : call.resolvedDescriptor();

        if (!receiverOnStack) emitExpr(context, composer, call.receiver);
        if (call.receiverRequiresCast()) composer.checkcast(ClassDesc.of(ownerName));
        final var runtimeType = (FunctionDescriptor) TypeSubstitution.erase(descriptor);
        if (call.variadicElementType() != null) {
            emitVariadicArguments(context, composer, call.arguments, runtimeType,
                    call.variadicFixedArity(), call.variadicElementType());
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                final var parameterType = resolvedDescriptor.parameters().get(i);
                final var erasedParameterType = TypeSubstitution.erase(
                        descriptor.parameters().get(i));
                if (StoredExistentialSupport.requiresHolder(parameterType)) {
                    emitConversion(context, composer, context.lastEmittedType, parameterType);
                    emitConversion(context, composer, parameterType, erasedParameterType);
                } else {
                    emitConversion(context, composer, context.lastEmittedType,
                            erasedParameterType);
                }
            }
        }
        emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
        final var invokedType = call.variadicElementType() != null
                ? call.arguments.size() < call.variadicFixedArity()
                    ? prefixFunctionType(runtimeType, call.arguments.size())
                    : runtimeType
                : prefixFunctionType(descriptor, call.arguments.size());
        final var methodType = withHiddenParameters(toJavaMethodDescriptor(invokedType),
                call.evidenceArguments().size(), call.reifiedArguments().size());
        if (contractMethod != null) {
            composer.invokeinterface(ClassDesc.of(ownerName), call.name.lexeme(),
                    methodType);
        } else if (!classMethod.isPublic() && context.emittingLambdaImplementation
                && !isSamePackage(ownerName, context.mainClassName)) {
            final var erasedDescriptor = (FunctionDescriptor) TypeSubstitution.erase(invokedType);
            final var bridgeParameters = new ArrayList<TypeDescriptor>();
            bridgeParameters.add(TypeDescriptor.of(ownerName));
            bridgeParameters.addAll(erasedDescriptor.parameters());
            composer.invokestatic(ClassDesc.of(ownerName), NominalTypeEmitter.lambdaMethodBridge(call.name.lexeme()),
                    withHiddenParameters(toJavaMethodDescriptor(erasedDescriptor, bridgeParameters),
                            call.evidenceArguments().size(), call.reifiedArguments().size()));
        } else if (!classMethod.isPublic() && !context.emittingLambdaImplementation) {
            composer.invokespecial(ClassDesc.of(ownerName), call.name.lexeme(),
                    methodType);
        } else {
            composer.invokevirtual(ClassDesc.of(ownerName), call.name.lexeme(),
                    methodType);
        }
        final var erasedReturnType = TypeSubstitution.erase(descriptor.returnType());
        emitConversion(context, composer, erasedReturnType, resolvedDescriptor.returnType());
        context.lastEmittedType = resolvedDescriptor.returnType();
    }

    private static void emitFunctionPropertyCall(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final Expr.MemberCall call,
                                                 final boolean receiverOnStack) {
        final var property = call.functionProperty();
        final var propertyType = property.getType() instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : property.getType();
        if (!(propertyType instanceof FunctionDescriptor functionType)) {
            throw new IllegalStateException("Resolved callable property is not a function.");
        }
        if (functionType.isReceiverFunction() && !receiverOnStack) {
            emitExpr(context, composer, call.receiver);
            composer.dup();
            emitFunctionPropertyValueWithReceiverOnStack(context, composer, property);
        } else if (receiverOnStack) {
            emitFunctionPropertyValueWithReceiverOnStack(context, composer, property);
        } else {
            emitExpr(context, composer, property);
        }
        final var runtimeType = (FunctionDescriptor) TypeSubstitution.erase(functionType);
        if (runtimeType.isReceiverFunction()) {
            composer.swap();
            final var receiverType = call.receiver.getType() instanceof NullableDescriptor nullable
                    ? nullable.baseType() : call.receiver.getType();
            emitConversion(context, composer, receiverType, runtimeType.receiverType());
        }
        for (int index = 0; index < call.arguments.size(); index++) {
            emitExpr(context, composer, call.arguments.get(index));
            emitConversion(context, composer, context.lastEmittedType, runtimeType.parameters().get(index));
        }
        composer.invokeinterface(TypeDescriptor.toJavaClassDesc(runtimeType), "invoke",
                toJavaMethodDescriptor(runtimeType));
        emitConversion(context, composer, runtimeType.returnType(), TypeSubstitution.erase(call.getType()));
        context.lastEmittedType = call.getType();
    }

    private static void emitFunctionPropertyValueWithReceiverOnStack(final CompilationContext context,
                                                                     final CodeBuilder composer,
                                                                     final Expr.Property property) {
        if (property.namespaceValueSymbol() != null) {
            composer.pop();
            emitVariable(context, composer, property.namespaceValueSymbol());
            return;
        }
        if (property.javaFieldTarget() != null) {
            final var target = property.javaFieldTarget();
            if (target.isStatic()) {
                composer.pop();
                composer.getstatic(ClassDesc.of(target.owner()), target.name(),
                        ClassDesc.ofDescriptor(target.descriptor()));
            } else {
                composer.getfield(ClassDesc.of(target.owner()), target.name(),
                        ClassDesc.ofDescriptor(target.descriptor()));
            }
            if (target.descriptor().equals("F")) composer.f2d();
            context.lastEmittedType = property.getType();
            return;
        }
        if (property.extensionCall() != null) {
            emitExtensionCall(context, composer, property.extensionCall(), true);
            context.lastEmittedType = property.getType();
            return;
        }
        if (property.resolvedAsProperty()) {
            final var owner = property.resolvedOwnerName();
            final var declaredType = declarationPropertyType(context, owner, property.name.lexeme());
            final var erasedType = TypeSubstitution.erase(declaredType);
            emitPropertyGetterCall(context, composer, owner, property.name.lexeme(), declaredType);
            emitConversion(context, composer, erasedType, property.getType());
            context.lastEmittedType = property.getType();
            return;
        }
        if (property.sumPayloadIndex() >= 0) {
            final var ownerName = property.resolvedOwnerName();
            final var owner = context.resolution.classes().get(ownerName);
            final var erasedType = TypeSubstitution.erase(owner.fields()
                    .get(property.sumPayloadIndex()).type());
            composer.invokevirtual(ClassDesc.of(ownerName),
                    "$zeron$payload$" + property.sumPayloadIndex(),
                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedType)));
            emitConversion(context, composer, erasedType, property.getType());
            context.lastEmittedType = property.getType();
            return;
        }
        if (property.intrinsicOperation() != null || property.safeNavigation()) {
            throw new IllegalStateException("Unexpected callable property access form.");
        }
        final var owner = nominalName(context, nonNullType(property.receiver.getType()));
        final var fieldType = declarationFieldType(context, owner, property.name.lexeme());
        final var erasedFieldType = TypeSubstitution.erase(fieldType);
        if (context.emittingLambdaImplementation && !isSamePackage(owner, context.mainClassName)) {
            composer.invokestatic(ClassDesc.of(owner), NominalTypeEmitter.lambdaFieldReadBridge(property.name.lexeme()),
                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedFieldType), ClassDesc.of(owner)));
        } else {
            composer.getfield(ClassDesc.of(owner), property.name.lexeme(),
                    TypeDescriptor.toJavaClassDesc(erasedFieldType));
        }
        emitConversion(context, composer, erasedFieldType, property.getType());
        context.lastEmittedType = property.getType();
    }

    private static void emitExtensionCall(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final Expr.MemberCall call,
                                          final boolean receiverOnStack) {
        final var extension = call.resolvedExtensionMethod();
        final var owner = context.metadata.functionOwner(extension);
        if (owner == null) throw new IllegalStateException("Resolved extension has no JVM owner.");
        final var sourceType = extension.typeDescriptor();
        final var resolvedType = call.resolvedDescriptor();
        final var runtimeType = (FunctionDescriptor) TypeSubstitution.erase(sourceType);
        final var resolvedRuntimeType = (FunctionDescriptor) TypeSubstitution.erase(resolvedType);
        if (!receiverOnStack) emitExpr(context, composer, call.receiver);
        emitConversion(context, composer, context.lastEmittedType,
                TypeSubstitution.erase(resolvedRuntimeType.parameters().getFirst()));
        if (call.variadicElementType() != null) {
            final var callableArguments = TypeDescriptor.functionOf(sourceType.name(),
                    sourceType.returnType(),
                    sourceType.parameters().subList(1, sourceType.arity()).toArray(TypeDescriptor[]::new));
            emitVariadicArguments(context, composer, call.arguments,
                    (FunctionDescriptor) TypeSubstitution.erase(callableArguments),
                    call.variadicFixedArity(), call.variadicElementType());
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                        sourceType.parameters().get(i + 1));
            }
        }
        emitHiddenArguments(context, composer, call.evidenceArguments(), call.reifiedArguments());
        final var invokedType = call.variadicElementType() != null
                ? call.arguments.size() < call.variadicFixedArity()
                    ? prefixFunctionType(runtimeType, call.arguments.size() + 1)
                    : runtimeType
                : prefixFunctionType(runtimeType, call.arguments.size() + 1);
        composer.invokestatic(ClassDesc.of(owner), extension.name().lexeme(),
                withHiddenParameters(toJavaMethodDescriptor(invokedType),
                        call.evidenceArguments().size(), call.reifiedArguments().size()));
        emitConversion(context, composer, runtimeType.returnType(), call.getType());
        context.lastEmittedType = call.getType();
    }

    private static FunctionDescriptor prefixFunctionType(final FunctionDescriptor functionType,
                                                          final int arity) {
        return TypeDescriptor.functionOf(functionType.name(), functionType.returnType(),
                functionType.parameters().subList(0, arity).toArray(TypeDescriptor[]::new));
    }

    private static void emitVariadicArguments(final CompilationContext context,
                                              final CodeBuilder composer,
                                              final List<Expr> arguments,
                                              final FunctionDescriptor fullType,
                                              final int fixedArity,
                                              final TypeDescriptor elementType) {
        if (arguments.size() < fixedArity) {
            for (int i = 0; i < arguments.size(); i++) {
                emitExpr(context, composer, arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType, fullType.parameters().get(i));
            }
            return;
        }
        final var suppliedFixed = Math.min(arguments.size(), fixedArity);
        for (int i = 0; i < suppliedFixed; i++) {
            emitExpr(context, composer, arguments.get(i));
            emitConversion(context, composer, context.lastEmittedType, fullType.parameters().get(i));
        }
        final var count = Math.max(0, arguments.size() - fixedArity);
        composer.ldc(count);
        composer.anewarray(ClassDesc.of("java.lang.Object"));
        for (int i = 0; i < count; i++) {
            composer.dup();
            composer.ldc(i);
            emitExpr(context, composer, arguments.get(fixedArity + i));
            emitConversion(context, composer, context.lastEmittedType, elementType);
            emitBox(context, composer, context.lastEmittedType);
            composer.aastore();
        }
    }

    private static void emitJavaMemberCall(CompilationContext context, final CodeBuilder composer,
                                    final Expr.MemberCall call,
                                    final boolean receiverOnStack ){
        final var target = call.javaCallTarget();
        final var owner = ClassDesc.of(target.owner());
        final var descriptor = MethodTypeDesc.ofDescriptor(target.descriptor());
        if (target.kind() == JavaCallTarget.InvocationKind.CONSTRUCTOR) {
            composer.new_(owner);
            composer.dup();
        } else if (target.kind() != JavaCallTarget.InvocationKind.STATIC && !receiverOnStack) {
            emitExpr(context, composer, call.receiver);
        }
        final var fixedParameterCount = target.varArgs()
                ? descriptor.parameterCount() - 1
                : descriptor.parameterCount();
        for (int i = 0; i < fixedParameterCount; i++) {
            emitExpr(context, composer, call.arguments.get(i));
            final var resolvedParameter = call.resolvedDescriptor().parameters().get(i);
            emitConversion(context, composer, context.lastEmittedType, resolvedParameter);
            emitJavaErasedArgumentConversion(context, composer, resolvedParameter,
                    descriptor.parameterType(i));
            emitJavaArrayArgumentCopy(context, composer, descriptor.parameterType(i));
        }
        if (target.varArgs()) {
            emitJavaVarargsArray(context, composer, call, fixedParameterCount,
                    descriptor.parameterType(fixedParameterCount).componentType());
        }
        emitJavaExceptionTranslation(composer, target.exceptionMappings(),
                () -> emitJavaInvocation(composer, target, owner, descriptor));
        if (target.kind() != JavaCallTarget.InvocationKind.CONSTRUCTOR
                && descriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(context, composer);
        }
        if (target.kind() != JavaCallTarget.InvocationKind.CONSTRUCTOR
                && !descriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitJavaReturnAdaptation(context, composer, call, descriptor.returnType());
        }
        context.lastEmittedType = call.safeNavigation() && call.resolvedDescriptor() != null
                ? call.resolvedDescriptor().returnType()
                : call.getType();
    }

    private static void emitJavaExceptionTranslation(
            final CodeBuilder composer,
            final List<JavaCallTarget.ExceptionMapping> mappings,
            final Runnable invocation) {
        if (mappings.isEmpty()) {
            invocation.run();
            return;
        }
        final var start = composer.newLabel();
        final var end = composer.newLabel();
        final var done = composer.newLabel();
        composer.labelBinding(start);
        invocation.run();
        composer.labelBinding(end);
        composer.goto_(done);
        for (final var mapping : mappings) {
            final var handler = composer.newLabel();
            final var rethrow = composer.newLabel();
            composer.labelBinding(handler);
            composer.dup();
            composer.instanceOf(ClassDesc.of("java.lang.RuntimeException"));
            composer.ifne(rethrow);
            composer.dup();
            composer.instanceOf(ClassDesc.of("java.lang.Error"));
            composer.ifne(rethrow);
            final var effectType = ClassDesc.of(mapping.effectType());
            final var constructorDescriptor = MethodTypeDesc.ofDescriptor(mapping.constructorDescriptor());
            if (mapping.canonicalConstructor()) {
                composer.new_(effectType);
                composer.dup_x1();
                composer.swap();
                composer.invokespecial(effectType, "<init>", constructorDescriptor);
            } else {
                composer.invokestatic(effectType, mapping.constructorName(), constructorDescriptor);
            }
            final var carrier = ClassDesc.of("zeron.runtime.RaisedEffect");
            composer.new_(carrier);
            composer.dup_x1();
            composer.swap();
            composer.invokespecial(carrier, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
            composer.athrow();
            composer.labelBinding(rethrow);
            composer.athrow();
            composer.exceptionCatch(start, end, handler, ClassDesc.of(mapping.exceptionType()));
        }
        composer.labelBinding(done);
    }

    private static void emitJavaInvocation(final CodeBuilder composer,
                                           final JavaCallTarget target,
                                           final ClassDesc owner,
                                           final MethodTypeDesc descriptor) {
        switch (target.kind()) {
            case CONSTRUCTOR -> composer.invokespecial(owner, "<init>", descriptor);
            case STATIC -> composer.invokestatic(owner, target.name(), descriptor);
            case INTERFACE -> composer.invokeinterface(owner, target.name(), descriptor);
            case VIRTUAL -> composer.invokevirtual(owner, target.name(), descriptor);
        }
    }

    private static void emitJavaArrayArgumentCopy(final CompilationContext context,
                                                  final CodeBuilder composer,
                                                  final ClassDesc javaType) {
        final var descriptor = javaType.descriptorString();
        final var method = switch (descriptor) {
            case "[Ljava/lang/Object;" -> "copyObjectArray";
            case "[I" -> "toJavaIntArray";
            case "[J" -> "toJavaLongArray";
            case "[D" -> "toJavaDoubleArray";
            case "[Z" -> "toJavaBooleanArray";
            default -> null;
        };
        if (method == null) return;
        final var returnType = javaType;
        composer.invokestatic(ClassDesc.of("zeron.runtime.JavaArraySupport"), method,
                MethodTypeDesc.of(returnType, ConstantDescs.CD_Object.arrayType()));
        context.lastEmittedType = descriptor.equals("[Ljava/lang/Object;")
                ? TypeDescriptor.arrayOf(TypeDescriptor.ofAny().toNullable()).toNullable()
                : JavaTypeMapping.toZeronParameterType(javaType);
    }

    private static void emitJavaErasedArgumentConversion(final CompilationContext context,
                                                         final CodeBuilder composer,
                                                         final TypeDescriptor resolvedType,
                                                         final ClassDesc erasedJavaType) {
        final var erasedType = JavaTypeMapping.toZeronMethodType(erasedJavaType);
        if (erasedType == null || resolvedType.equals(erasedType)) return;
        final var resolvedJavaType = TypeDescriptor.toJavaClassDesc(resolvedType);
        final var targetJavaType = TypeDescriptor.toJavaClassDesc(erasedType);
        final var resolvedDescriptor = resolvedJavaType.descriptorString();
        final var targetDescriptor = targetJavaType.descriptorString();
        if ((resolvedDescriptor.equals("I") || resolvedDescriptor.equals("J")
                || resolvedDescriptor.equals("B") || resolvedDescriptor.equals("D")
                || resolvedDescriptor.equals("Z"))
                && targetDescriptor.startsWith("L")) {
            emitBox(context, composer, resolvedType);
            if (!targetDescriptor.equals("Ljava/lang/Object;")
                    && !targetDescriptor.equals(boxedDescriptor(resolvedDescriptor))) {
                composer.checkcast(targetJavaClass(context, erasedType));
            }
            context.lastEmittedType = erasedType;
            return;
        }
        emitConversion(context, composer, resolvedType, erasedType);
    }

    private static String boxedDescriptor(final String primitiveDescriptor) {
        return switch (primitiveDescriptor) {
            case "I" -> "Ljava/lang/Integer;";
            case "J" -> "Ljava/lang/Long;";
            case "B" -> "Ljava/lang/Byte;";
            case "D" -> "Ljava/lang/Double;";
            case "Z" -> "Ljava/lang/Boolean;";
            default -> throw new IllegalArgumentException(
                    "Unsupported Java generic primitive descriptor: " + primitiveDescriptor);
        };
    }

    private static void emitJavaReturnAdaptation(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final Expr.MemberCall call,
                                                 final ClassDesc javaType) {
        final var declaredType = call.resolvedDescriptor() == null
                ? call.getType() : call.resolvedDescriptor().returnType();
        emitJavaReturnAdaptation(context, composer, javaType, declaredType);
    }

    private static void emitJavaReturnAdaptation(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final ClassDesc javaType,
                                                 final TypeDescriptor declaredType) {
        final var descriptor = javaType.descriptorString();
        final var mappedType = JavaTypeMapping.toZeronReturnType(javaType);
        if (mappedType != null && mappedType.isNullable() && declaredType != null
                && !declaredType.isNullable() && descriptor.startsWith("[")) {
            composer.invokestatic(ClassDesc.of("zeron.runtime.JavaArraySupport"), "requireNonNull",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
            composer.checkcast(javaType);
        }
        final var method = switch (descriptor) {
            case "[Ljava/lang/Object;" -> "copyObjectArray";
            case "[I" -> "fromJavaIntArray";
            case "[J" -> "fromJavaLongArray";
            case "[D" -> "fromJavaDoubleArray";
            case "[Z" -> "fromJavaBooleanArray";
            default -> null;
        };
        if (method != null) {
            composer.invokestatic(ClassDesc.of("zeron.runtime.JavaArraySupport"), method,
                    MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType(), javaType));
        } else if (descriptor.equals("F")) {
            composer.f2d();
        } else if (mappedType != null && mappedType.isNullable() && declaredType != null
                && !declaredType.isNullable() && descriptor.startsWith("L")) {
            composer.invokestatic(ClassDesc.of("zeron.runtime.JavaArraySupport"), "requireNonNull",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
            composer.checkcast(javaType);
        }
        if (descriptor.startsWith("L") && declaredType != null
                && (declaredType instanceof IntDescriptor || declaredType instanceof LongDescriptor
                || declaredType instanceof ByteDescriptor || declaredType instanceof FloatDescriptor
                || declaredType instanceof BooleanDescriptor)) {
            emitUnboxOrCast(context, composer, declaredType);
            context.lastEmittedType = declaredType;
            return;
        }
        if (descriptor.startsWith("L") && declaredType != null) {
            final var targetType = TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(declaredType));
            if (targetType.descriptorString().startsWith("L") && !targetType.equals(javaType)) {
                composer.checkcast(targetJavaClass(context, declaredType));
            }
        }
    }

    private static void emitSafeProperty(CompilationContext context, final CodeBuilder composer, final Expr.Property property ){
        final var nullPath = composer.newLabel();
        final var done = composer.newLabel();
        emitExpr(context, composer, property.receiver);
        composer.dup();
        composer.ifnull(nullPath);
        if (property.intrinsicOperation() != null) {
            composer.arraylength();
            emitConversion(context, composer, property.intrinsicOperation().resultType(), property.getType());
        } else if (property.extensionCall() != null) {
            emitExtensionCall(context, composer, property.extensionCall(), true);
            emitConversion(context, composer, property.extensionCall().getType(), property.getType());
        } else if (property.resolvedAsProperty()) {
            final var owner = property.resolvedOwnerName();
            final var declaredType = declarationPropertyType(context, owner, property.name.lexeme());
            final var erasedType = TypeSubstitution.erase(declaredType);
            emitPropertyGetterCall(context, composer, owner, property.name.lexeme(), declaredType);
            emitConversion(context, composer, erasedType, property.getType());
        } else if (property.sumPayloadIndex() >= 0) {
            final var ownerName = property.resolvedOwnerName();
            final var owner = context.resolution.classes().get(ownerName);
            final var erasedType = TypeSubstitution.erase(owner.fields()
                    .get(property.sumPayloadIndex()).type());
            composer.invokevirtual(ClassDesc.of(ownerName),
                    "$zeron$payload$" + property.sumPayloadIndex(),
                    MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedType)));
            emitConversion(context, composer, erasedType, property.getType());
        } else {
            final var owner = nominalName(context, nonNullType(property.receiver.getType()));
            final var fieldType = declarationFieldType(context, owner, property.name.lexeme());
            composer.getfield(ClassDesc.of(owner), property.name.lexeme(),
                    TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(fieldType)));
            emitConversion(context, composer, TypeSubstitution.erase(fieldType), property.getType());
        }
        composer.goto_(done);
        composer.labelBinding(nullPath);
        composer.pop();
        composer.aconst_null();
        composer.labelBinding(done);
        context.lastEmittedType = property.getType();
    }

    private static TypeDescriptor nonNullType(final TypeDescriptor type) {
        return type instanceof NullableDescriptor nullable ? nullable.baseType() : type;
    }

    private static void emitJavaVarargsArray(CompilationContext context, final CodeBuilder composer,
                                      final Expr.MemberCall call,
                                      final int fixedParameterCount,
                                      final ClassDesc componentType ){
        final var componentDescriptor = componentType.descriptorString();
        final var varargCount = call.arguments.size() - fixedParameterCount;
        composer.ldc(varargCount);
        switch (componentDescriptor) {
            case "I" -> composer.newarray(TypeKind.INT);
            case "J" -> composer.newarray(TypeKind.LONG);
            case "B" -> composer.newarray(TypeKind.BYTE);
            case "D" -> composer.newarray(TypeKind.DOUBLE);
            case "Z" -> composer.newarray(TypeKind.BOOLEAN);
            default -> composer.anewarray(componentType);
        }
        for (int index = 0; index < varargCount; index++) {
            final var argumentIndex = fixedParameterCount + index;
            composer.dup();
            composer.ldc(index);
            emitExpr(context, composer, call.arguments.get(argumentIndex));
            emitConversion(context, composer, context.lastEmittedType,
                    call.resolvedDescriptor().parameters().get(argumentIndex));
            switch (componentDescriptor) {
                case "I" -> composer.iastore();
                case "J" -> composer.lastore();
                case "B" -> composer.bastore();
                case "D" -> composer.dastore();
                case "Z" -> composer.bastore();
                default -> composer.aastore();
            }
        }
    }

    static void emitExternalFunction(CompilationContext context, final CodeBuilder composer,
                                      final FunctionDescriptor signature,
                                      final FunctionBindingRegistry.Binding binding ){
        final var target = binding.target();
        if (target instanceof FunctionBindingRegistry.IntrinsicBinding intrinsicBinding) {
            emitExternalFunctionArguments(context, composer, signature);
            final var bigIntegerMethod = bigIntegerIntrinsicMethod(intrinsicBinding.id());
            if (bigIntegerMethod != null) {
                final var descriptor = MethodTypeDesc.of(
                        TypeDescriptor.toJavaClassDesc(signature.returnType()),
                        signature.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList());
                composer.invokestatic(ClassDesc.of("zeron.runtime.BigIntegerSupport"), bigIntegerMethod, descriptor);
                switch (TypeDescriptor.toJavaClassDesc(signature.returnType()).descriptorString()) {
                    case "I", "B", "Z" -> composer.ireturn();
                    case "J" -> composer.lreturn();
                    case "D" -> composer.dreturn();
                    case "V" -> composer.return_();
                    default -> composer.areturn();
                }
                context.lastEmittedType = signature.returnType();
                return;
            }
            final var unicodeMethod = stringUnicodeIntrinsicMethod(intrinsicBinding.id());
            if (unicodeMethod != null) {
                final var descriptor = MethodTypeDesc.of(
                        TypeDescriptor.toJavaClassDesc(signature.returnType()),
                        signature.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList());
                composer.invokestatic(ClassDesc.of("zeron.runtime.StringUnicode"), unicodeMethod, descriptor);
                switch (TypeDescriptor.toJavaClassDesc(signature.returnType()).descriptorString()) {
                    case "I", "B", "Z" -> composer.ireturn();
                    case "J" -> composer.lreturn();
                    case "D" -> composer.dreturn();
                    case "V" -> composer.return_();
                    default -> composer.areturn();
                }
                context.lastEmittedType = signature.returnType();
                return;
            }
            switch (intrinsicBinding.id()) {
                case INT_TO_FLOAT -> {
                    composer.i2d();
                    composer.dreturn();
                }
                case INT_TO_LONG -> {
                    composer.i2l();
                    composer.lreturn();
                }
                case FLOAT_TO_INT_OPTION -> {
                    emitFloatToIntOption(context, composer);
                    composer.areturn();
                }
                case LONG_TO_INT_OPTION -> {
                    emitLongToIntOption(context, composer);
                    composer.areturn();
                }
                case INT_TO_BYTE -> {
                    composer.i2b();
                    composer.ireturn();
                }
                case BYTE_TO_INT -> {
                    composer.ldc(255);
                    composer.iand();
                    composer.ireturn();
                }
                case CHARACTER_CODING_EXCEPTION_TO_IO_EXCEPTION -> composer.areturn();
                default -> throw new IllegalStateException("Unexpected external-function intrinsic: "
                        + intrinsicBinding.id().stableName());
            }
            context.lastEmittedType = signature.returnType();
            return;
        }

        final MethodTypeDesc methodDescriptor;
        if (target instanceof FunctionBindingRegistry.StaticMethod staticMethod) {
            methodDescriptor = staticMethod.descriptor();
            emitExternalJavaArguments(context, composer, signature, methodDescriptor, 0);
            emitJavaExceptionTranslation(composer, binding.exceptionMappings(),
                    () -> composer.invokestatic(staticMethod.owner(), staticMethod.methodName(), methodDescriptor));
        } else if (target instanceof FunctionBindingRegistry.InstanceMethod instanceMethod) {
            methodDescriptor = instanceMethod.descriptor();
            emitExternalJavaArguments(context, composer, signature, methodDescriptor, 1);
            emitJavaExceptionTranslation(composer, binding.exceptionMappings(),
                    () -> composer.invokevirtual(instanceMethod.owner(), instanceMethod.methodName(), methodDescriptor));
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod fieldMethod) {
            methodDescriptor = fieldMethod.methodDescriptor();
            composer.getstatic(fieldMethod.fieldOwner(), fieldMethod.fieldName(), fieldMethod.fieldType());
            emitExternalJavaArguments(context, composer, signature, methodDescriptor, 0);
            emitJavaExceptionTranslation(composer, binding.exceptionMappings(),
                    () -> composer.invokevirtual(fieldMethod.methodOwner(), fieldMethod.methodName(), methodDescriptor));
        } else {
            throw new IllegalStateException("Unsupported external function binding target.");
        }
        if (methodDescriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(context, composer);
            composer.areturn();
        } else {
            emitJavaReturnAdaptation(context, composer, methodDescriptor.returnType(), signature.returnType());
            composer.return_(TypeKind.fromDescriptor(
                    TypeDescriptor.toJavaClassDesc(signature.returnType()).descriptorString()));
        }
        context.lastEmittedType = signature.returnType();
    }

    private static String stringUnicodeIntrinsicMethod(final IntrinsicId id) {
        return switch (id) {
            case STRING_CODE_POINT_LENGTH -> "codePointLength";
            case STRING_CODE_POINT_AT -> "codePointAt";
            case STRING_SUBSTRING_BY_CODE_POINTS -> "substringByCodePoints";
            case STRING_INDEX_OF_CODE_POINT -> "indexOfCodePoint";
            case STRING_FIRST_MALFORMED_OFFSET -> "firstMalformedOffset";
            case STRING_CODE_POINT_AT_OFFSET -> "scalarAtOffset";
            case STRING_CODE_POINT_WIDTH_AT_OFFSET -> "scalarWidthAtOffset";
            case CHAR_TO_INT -> "charToInt";
            case INT_TO_CHAR -> "intToChar";
            case CHAR_TO_STRING -> "charToString";
            default -> null;
        };
    }

    private static String bigIntegerIntrinsicMethod(final IntrinsicId id) {
        return switch (id) {
            case BIG_INTEGER_FROM_LITERAL_TEXT -> "fromLiteralText";
            default -> null;
        };
    }

    private static void emitFloatToIntOption(final CompilationContext context,
                                             final CodeBuilder composer) {
        final var noValue = composer.newLabel();
        final var done = composer.newLabel();
        final var someClass = ClassDesc.of("zeron.lang.Option$$sum$Some");
        final var noneClass = ClassDesc.of("zeron.lang.Option$$sum$None");

        composer.dup2();
        composer.dup2();
        composer.dcmpg();
        composer.ifne(noValue);
        composer.dup2();
        composer.ldc(-2147483649.0);
        composer.dcmpg();
        composer.ifle(noValue);
        composer.dup2();
        composer.ldc(2147483648.0);
        composer.dcmpl();
        composer.ifge(noValue);
        composer.d2i();
        composer.new_(someClass);
        composer.dup_x1();
        composer.swap();
        composer.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int));
        composer.invokespecial(someClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
        composer.goto_(done);

        composer.labelBinding(noValue);
        composer.pop2();
        composer.getstatic(noneClass, "$zeron$singleton", noneClass);
        composer.labelBinding(done);
        composer.checkcast(ClassDesc.of("zeron.lang.Option"));
        context.lastEmittedType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName("zeron.lang.Option"), TypeDescriptor.ofInt());
    }

    private static void emitLongToIntOption(final CompilationContext context,
                                            final CodeBuilder composer) {
        final var noValue = composer.newLabel();
        final var done = composer.newLabel();
        final var someClass = ClassDesc.of("zeron.lang.Option$$sum$Some");
        final var noneClass = ClassDesc.of("zeron.lang.Option$$sum$None");

        composer.dup2();
        composer.ldc((long) Integer.MAX_VALUE);
        composer.lcmp();
        composer.ifgt(noValue);
        composer.dup2();
        composer.ldc((long) Integer.MIN_VALUE);
        composer.lcmp();
        composer.iflt(noValue);
        composer.l2i();
        composer.new_(someClass);
        composer.dup_x1();
        composer.swap();
        composer.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int));
        composer.invokespecial(someClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
        composer.goto_(done);

        composer.labelBinding(noValue);
        composer.pop2();
        composer.getstatic(noneClass, "$zeron$singleton", noneClass);
        composer.labelBinding(done);
        composer.checkcast(ClassDesc.of("zeron.lang.Option"));
        context.lastEmittedType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName("zeron.lang.Option"), TypeDescriptor.ofInt());
    }

    private static void emitExternalFunctionArguments(CompilationContext context, final CodeBuilder composer,
                                               final FunctionDescriptor signature ){
        var slot = 0;
        for (final var parameter : signature.parameters()) {
            NominalTypeEmitter.loadLocal(context, composer, slot, parameter);
            final var descriptor = TypeDescriptor.toJavaClassDesc(parameter).descriptorString();
            slot += descriptor.equals("D") || descriptor.equals("J") ? 2 : 1;
        }
    }

    private static void emitExternalJavaArguments(final CompilationContext context,
                                                  final CodeBuilder composer,
                                                  final FunctionDescriptor signature,
                                                  final MethodTypeDesc methodDescriptor,
                                                  final int signatureParameterOffset) {
        var slot = 0;
        if (signatureParameterOffset == 1) {
            final var receiverType = signature.parameters().getFirst();
            NominalTypeEmitter.loadLocal(context, composer, slot, receiverType);
            final var receiverDescriptor = TypeDescriptor.toJavaClassDesc(receiverType).descriptorString();
            slot += receiverDescriptor.equals("D") || receiverDescriptor.equals("J") ? 2 : 1;
        }
        for (int index = 0; index < methodDescriptor.parameterCount(); index++) {
            final var parameterType = signature.parameters().get(index + signatureParameterOffset);
            NominalTypeEmitter.loadLocal(context, composer, slot, parameterType);
            emitJavaArrayArgumentCopy(context, composer, methodDescriptor.parameterType(index));
            final var descriptor = TypeDescriptor.toJavaClassDesc(parameterType).descriptorString();
            slot += descriptor.equals("D") || descriptor.equals("J") ? 2 : 1;
        }
    }

    private static String nominalName(CompilationContext context, final TypeDescriptor type ){
        var baseType = nonNullType(type);
        if (baseType instanceof ReferenceDescriptor reference) baseType = reference.baseType();
        if (baseType instanceof GenericDescriptor generic) baseType = generic.baseType();
        if (!(baseType instanceof NominalDescriptor nominal)) {
            throw new IllegalStateException("Expected a nominal receiver, found " + type);
        }
        return nominal.name();
    }

    private static TypeDescriptor declarationFieldType(CompilationContext context, final String ownerName, final String fieldName ){
        final var declaration = context.resolution.classes().get(ownerName);
        if (declaration == null) throw new IllegalStateException("Field owner is not a class: " + ownerName);
        return declaration.fields().stream()
                .filter(field -> field.name().lexeme().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Resolved field not found: " + fieldName))
                .type();
    }

    private static List<ClassDesc> nestMembersForMainClass(CompilationContext context ){
        return context.resolution.classes().values().stream()
                .map(declaration -> declaration.name().lexeme())
                .filter(name -> !name.equals(context.mainClassName)
                        && !context.metadata.metadataTypeNames().contains(name)
                        && !isValidatedNewtype(context, name)
                        && isSamePackage(name, context.mainClassName))
                .map(ClassDesc::of)
                .toList();
    }

    private static boolean isValidatedNewtype(final CompilationContext context, final String typeName) {
        return context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration)
                .filter(Stmt.NewtypeDecl.class::isInstance)
                .map(Stmt.NewtypeDecl.class::cast)
                .anyMatch(newtype -> newtype.validator() != null
                        && newtype.name().lexeme().equals(typeName));
    }

    private static List<ClassDesc> validatedNewtypeNestMembers(final CompilationContext context,
                                                               final String namespaceOwner) {
        return context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream()
                        .map(NamespaceMembers.Member::declaration)
                        .filter(Stmt.NewtypeDecl.class::isInstance)
                        .map(Stmt.NewtypeDecl.class::cast)
                        .filter(newtype -> newtype.validator() != null)
                        .filter(newtype -> {
                            final var namespaceName = newtype.name().lexeme()
                                    .substring(newtype.name().lexeme().lastIndexOf('.') + 1);
                            return CompilationMetadata.namespaceOwner(unit.packageName(), namespaceName)
                                    .equals(namespaceOwner);
                        })
                        .map(newtype -> ClassDesc.of(newtype.name().lexeme())))
                .distinct()
                .toList();
    }

    static boolean isSamePackage(final String first, final String second) {
        final var firstSeparator = first.lastIndexOf('.');
        final var secondSeparator = second.lastIndexOf('.');
        return first.substring(0, firstSeparator < 0 ? 0 : firstSeparator)
                .equals(second.substring(0, secondSeparator < 0 ? 0 : secondSeparator));
    }

    static MethodTypeDesc toJavaMethodDescriptor(final FunctionDescriptor functionType,
                                                        final List<TypeDescriptor> parameters) {
        final var javaParameters = parameters.stream()
                .map(TypeDescriptor::toJavaClassDesc)
                .toList();
        return MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(functionType.returnType()), javaParameters);
    }

    static void emitVariable(CompilationContext context, final CodeBuilder composer, final Token name) {
        if (!context.symbols.containsSymbol(name)) {
            if (context.symbols.containsAnySymbol(name)) {
                final var binding = context.symbols.getAnySymbol(name);
                if (binding.lvt() == SymbolTable.GLOBAL) {
                    final var owner = ClassDesc.of(holderForDeclaration(context, binding.declaration()));
                    if (binding.declaration() instanceof Stmt.Var variable) {
                        composer.invokestatic(owner, DeclarationEmitter.topLevelValueAccessorName(variable.name().lexeme()),
                                MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(binding.type())));
                    } else {
                        composer.getstatic(owner, globalFieldName(binding, name),
                                TypeDescriptor.toJavaClassDesc(binding.type()));
                    }
                    if (StoredExistentialSupport.requiresHolder(binding.type())) {
                        emitStoredUnpack(context, composer, binding.type());
                    } else {
                        context.lastEmittedType = binding.type();
                    }
                    return;
                }
            }
            Zeron.debug("emitVariable missing symbol: " + name.lexeme() + " locals=" + context.symbols.getLocals());
            throw new IllegalStateException("Unknown symbol: " + name.lexeme());
        }
        final var binding = context.symbols.getSymbol(name);
        if (binding.type() instanceof FunctionDescriptor && binding.lvt() != SymbolTable.GLOBAL) {
            composer.aload(binding.lvt());
            context.lastEmittedType = binding.type();
            return;
        }
        if (binding.lvt() == SymbolTable.GLOBAL) {
            final var owner = ClassDesc.of(holderForDeclaration(context, binding.declaration()));
            if (binding.declaration() instanceof Stmt.Var variable) {
                composer.invokestatic(owner, DeclarationEmitter.topLevelValueAccessorName(variable.name().lexeme()),
                        MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(binding.type())));
            } else {
                composer.getstatic(owner, globalFieldName(binding, name),
                        TypeDescriptor.toJavaClassDesc(binding.type()));
            }
        } else {
            switch (TypeDescriptor.toJavaClassDesc(binding.type()).descriptorString()) {
                case "I", "B", "Z" -> composer.iload(binding.lvt() + context.localSlotOffset);
                case "J" -> composer.lload(binding.lvt() + context.localSlotOffset);
                case "D" -> composer.dload(binding.lvt() + context.localSlotOffset);
                default -> composer.aload(binding.lvt() + context.localSlotOffset);
            }
        }
        if (StoredExistentialSupport.requiresHolder(binding.type())) {
            emitStoredUnpack(context, composer, binding.type());
        } else {
            context.lastEmittedType = binding.type();
        }
    }

    private static String globalFieldName(final Bind binding, final Token fallback) {
        return binding.declaration() instanceof Stmt.Var variable
                ? variable.name().lexeme()
                : fallback.lexeme();
    }

    private static String holderForDeclaration(CompilationContext context, final Stmt declaration ){
        return context.metadata.declarationOwner(declaration, context.currentHolderName == null ? context.mainClassName : context.currentHolderName);
    }

    static void emitBox(CompilationContext context, final CodeBuilder composer, final TypeDescriptor type ){
        if (type == null) return;
        final var boxer = switch (type) {
            case IntDescriptor _, CharDescriptor _ -> "I";
            case LongDescriptor _ -> "J";
            case ByteDescriptor _ -> "C";
            case FloatDescriptor _ -> "D";
            case BooleanDescriptor _ -> "B";
            default -> null;
        };
        if (boxer != null) {
            composer.invokestatic(getAutoboxingFor(composer.constantPool(), boxer));
        }
    }

    static void emitUnitValue(CompilationContext context, final CodeBuilder composer ){
        composer.getstatic(UNIT_CLASS, "INSTANCE", UNIT_CLASS);
    }

    static void emitPop(CompilationContext context, final CodeBuilder composer, final TypeDescriptor type ){
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "V" -> {}
            case "D", "J" -> composer.pop2();
            default -> composer.pop();
        }
    }

    private static void duplicateValue(CompilationContext context, final CodeBuilder composer, final TypeDescriptor type ){
        switch (TypeDescriptor.toJavaClassDesc(type).descriptorString()) {
            case "D", "J" -> composer.dup2();
            default -> composer.dup();
        }
    }

    private static void emitStoredPack(final CompilationContext context,
                                      final CodeBuilder composer,
                                      final TypeDescriptor sourceType,
                                      final TypeDescriptor targetType) {
        var target = unnullable(targetType);
        while (target instanceof ReferenceDescriptor reference) target = reference.baseType();
        if (!(target instanceof GenericDescriptor targetGeneric)) {
            throw new IllegalStateException("A holder target must be an applied type: " + targetType);
        }
        var source = unnullable(sourceType);
        while (source instanceof ReferenceDescriptor reference) source = reference.baseType();
        final var sourceArguments = source instanceof GenericDescriptor sourceGeneric
                && sourceGeneric.baseType().equals(targetGeneric.baseType())
                && sourceGeneric.typeParameters().size() == targetGeneric.typeParameters().size()
                ? sourceGeneric.typeParameters() : null;
        final var payloadSlot = declareStoredTemporary(context, "packPayload", TypeDescriptor.ofAny());
        composer.storeLocal(TypeKind.REFERENCE, payloadSlot + context.localSlotOffset);
        final var slots = new ArrayList<StoredSlot>();
        for (int index = 0; index < targetGeneric.typeParameters().size(); index++) {
            final var argument = targetGeneric.typeParameters().get(index);
            if (!(argument instanceof TypeParameterDescriptor binder)
                    || !StoredExistentialSupport.needsHolder(binder)) continue;
            final var actual = sourceArguments == null ? null : sourceArguments.get(index);
            if (actual instanceof TypeParameterDescriptor actualBinder
                    && actualBinder.isStoredExistential()) {
                if (binder.isReified()) {
                    slots.add(new StoredSlot.OfToken(Expr.reifiedToken(actualBinder)));
                }
                for (int boundIndex = 0; boundIndex < binder.bounds().size(); boundIndex++) {
                    final var methods = storedBoundMethods(context, binder, boundIndex);
                    if (methods.isEmpty()) {
                        slots.add(new StoredSlot.Null());
                    } else if (methods.size() == 1) {
                        slots.add(new StoredSlot.OfToken(Expr.methodEvidenceToken(actualBinder,
                                boundIndex, 0)));
                    } else {
                        final var tokens = new ArrayList<Token>();
                        for (int methodIndex = 0; methodIndex < methods.size(); methodIndex++) {
                            tokens.add(Expr.methodEvidenceToken(actualBinder, boundIndex, methodIndex));
                        }
                        slots.add(new StoredSlot.TokenGroup(tokens));
                    }
                }
                continue;
            }
            if (actual == null) {
                throw new IllegalStateException("Cannot pack a stored existential without a source argument: "
                        + TypeFormatter.format(targetType));
            }
            if (binder.isReified()) {
                slots.add(new StoredSlot.Descriptor(actual));
            }
                            for (int boundIndex = 0; boundIndex < binder.bounds().size(); boundIndex++) {
                    if (storedBoundMethods(context, binder, boundIndex).isEmpty()) {
                        slots.add(new StoredSlot.Null());
                    } else {
                        slots.add(new StoredSlot.Evidence(context, binder, boundIndex, actual));
                    }
                }
        }
        if (slots.isEmpty()) {
            throw new IllegalStateException("A holder was requested with no slots: " + targetType);
        }
        final var holder = ClassDesc.of(StoredExistentialSupport.holderClassName(slots.size()));
        if (slots.size() <= 3) {
            composer.aload(payloadSlot + context.localSlotOffset);
            for (final var slot : slots) emitStoredSlot(context, composer, slot);
            final var parameters = new ArrayList<ClassDesc>();
            parameters.add(ConstantDescs.CD_Object);
            slots.forEach(_ -> parameters.add(ConstantDescs.CD_Object));
            composer.invokestatic(holder, "of", MethodTypeDesc.of(holder, parameters));
        } else {
            composer.ldc(slots.size());
            composer.anewarray(ConstantDescs.CD_Object);
            for (int index = 0; index < slots.size(); index++) {
                composer.dup();
                composer.ldc(index);
                emitStoredSlot(context, composer, slots.get(index));
                composer.aastore();
            }
            composer.aload(payloadSlot + context.localSlotOffset);
            composer.swap();
            composer.invokestatic(holder, "of", MethodTypeDesc.of(holder,
                    ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType()));
        }
        context.lastEmittedType = targetType;
    }

    private static void emitStoredPayload(final CompilationContext context,
                                          final CodeBuilder composer,
                                          final TypeDescriptor targetType,
                                          final TypeDescriptor holderType) {
        final var holderClass = TypeDescriptor.toJavaClassDesc(holderType);
        composer.checkcast(holderClass);
        final var holder = declareStoredTemporary(context, "holder", holderType);
        composer.storeLocal(TypeKind.REFERENCE, holder + context.localSlotOffset);
        composer.aload(holder + context.localSlotOffset);
        composer.invokevirtual(holderClass, "payload",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        emitUnboxOrCast(context, composer, targetType);
        context.lastEmittedType = targetType;
    }

    static void emitStoredUnpack(final CompilationContext context,
                                 final CodeBuilder composer,
                                 final TypeDescriptor sourceType) {
        final var source = unnullable(sourceType);
        var slotCount = 0;
        if (source instanceof GenericDescriptor generic) {
            for (final var argument : generic.typeParameters()) {
                if (argument instanceof TypeParameterDescriptor binder
                        && StoredExistentialSupport.needsHolder(binder)) {
                    slotCount += (binder.isReified() ? 1 : 0) + binder.bounds().size();
                }
            }
        }
        final var holderClass = StoredExistentialSupport.holderClassName(slotCount);
        composer.checkcast(ClassDesc.of(holderClass));
        final var holder = declareStoredTemporary(context, "holder",
                TypeDescriptor.ofName(holderClass));
        composer.storeLocal(TypeKind.REFERENCE, holder + context.localSlotOffset);
        if (source instanceof GenericDescriptor generic) {
            var slot = 0;
            for (final var argument : generic.typeParameters()) {
                if (!(argument instanceof TypeParameterDescriptor binder)
                        || !StoredExistentialSupport.needsHolder(binder)) continue;
                if (binder.isReified()) {
                    storeStoredSlot(context, composer, holder, holderClass, slot++,
                            Expr.reifiedToken(binder), RuntimeTypeDescriptorEmitter.DESCRIPTOR_TYPE);
                }
                for (int boundIndex = 0; boundIndex < binder.bounds().size(); boundIndex++) {
                    storeStoredEvidenceGroup(context, composer, holder, holderClass, slot++,
                            binder, boundIndex);
                }
            }
        }
        composer.aload(holder + context.localSlotOffset);
        composer.invokevirtual(ClassDesc.of(holderClass), "payload",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        composer.checkcast(TypeDescriptor.toJavaClassDesc(payloadBase(sourceType)));
        context.lastEmittedType = sourceType;
    }

    private static TypeDescriptor payloadBase(final TypeDescriptor type) {
        var base = unnullable(type);
        if (base instanceof ReferenceDescriptor reference) base = reference.baseType();
        if (base instanceof GenericDescriptor generic) return generic.baseType();
        return base;
    }

    private static TypeDescriptor unnullable(final TypeDescriptor type) {
        return type instanceof NullableDescriptor nullable ? nullable.baseType() : type;
    }

    private static int declareStoredTemporary(final CompilationContext context, final String hint,
                                               final TypeDescriptor type) {
        final var token = new Token(TokenType.IDENTIFIER,
                "$zeron$stored$" + hint + "$" + (context.propertyTemporaryCount++), null, -1);
        return context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, token, type,
                BindingMutability.IMMUTABLE);
    }

    private static void storeStoredEvidenceGroup(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final int holderSlot,
                                                 final String holderClass,
                                                 final int slotIndex,
                                                 final TypeParameterDescriptor binder,
                                                 final int boundIndex) {
        final var methods = storedBoundMethods(context, binder, boundIndex);
        if (methods.size() == 1) {
            storeStoredSlot(context, composer, holderSlot, holderClass, slotIndex,
                    Expr.methodEvidenceToken(binder, boundIndex, 0),
                    TypeDescriptor.ofName("java.lang.invoke.MethodHandle"));
            return;
        }
        final var groupType = TypeDescriptor.arrayOf(
                TypeDescriptor.ofName("java.lang.invoke.MethodHandle"));
        final var groupSlot = declareStoredTemporary(context, "evidenceGroup", groupType);
        emitStoredSlotLoad(context, composer, holderSlot, holderClass, slotIndex);
        composer.checkcast(ClassDesc.of("java.lang.invoke.MethodHandle").arrayType());
        composer.storeLocal(TypeKind.REFERENCE, groupSlot + context.localSlotOffset);
        for (int methodIndex = 0; methodIndex < methods.size(); methodIndex++) {
            final var token = Expr.methodEvidenceToken(binder, boundIndex, methodIndex);
            ensureStoredToken(context, token, TypeDescriptor.ofName("java.lang.invoke.MethodHandle"));
            final var binding = context.symbols.getSymbol(token);
            composer.aload(groupSlot + context.localSlotOffset);
            composer.ldc(methodIndex);
            composer.aaload();
            composer.checkcast(ClassDesc.of("java.lang.invoke.MethodHandle"));
            composer.storeLocal(TypeKind.REFERENCE, binding.lvt() + context.localSlotOffset);
        }
    }

    private static void storeStoredSlot(final CompilationContext context,
                                        final CodeBuilder composer,
                                        final int holderSlot,
                                        final String holderClass,
                                        final int slotIndex,
                                        final Token token,
                                        final TypeDescriptor type) {
        ensureStoredToken(context, token, type);
        final var binding = context.symbols.getSymbol(token);
        emitStoredSlotLoad(context, composer, holderSlot, holderClass, slotIndex);
        emitUnboxOrCast(context, composer, type);
        composer.storeLocal(TypeKind.REFERENCE, binding.lvt() + context.localSlotOffset);
    }

    private static void ensureStoredToken(final CompilationContext context, final Token token,
                                          final TypeDescriptor type) {
        if (!context.symbols.containsSymbol(token)) {
            context.symbols.declareSymbol(Resolver.SYNTHETIC_VAR, token, type,
                    BindingMutability.IMMUTABLE);
            context.symbols.define(token);
        }
    }

    private static void emitStoredSlotLoad(final CompilationContext context,
                                           final CodeBuilder composer,
                                           final int holderSlot,
                                           final String holderClass,
                                           final int slotIndex) {
        composer.aload(holderSlot + context.localSlotOffset);
        if (holderClass.equals(StoredExistentialSupport.HOLDER_CLASS)) {
            composer.ldc(slotIndex);
            composer.invokevirtual(ClassDesc.of(holderClass), "slot",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int));
        } else {
            composer.invokevirtual(ClassDesc.of(holderClass), "slot" + slotIndex,
                    MethodTypeDesc.of(ConstantDescs.CD_Object));
        }
    }

    private static void emitStoredSlot(final CompilationContext context,
                                       final CodeBuilder composer,
                                       final StoredSlot slot) {
        switch (slot) {
            case StoredSlot.Null ignored -> composer.aconst_null();
            case StoredSlot.Descriptor descriptor -> RuntimeTypeDescriptorEmitter.emit(
                    context, composer, descriptor.actualType());
            case StoredSlot.OfToken ofToken -> emitVariable(context, composer, ofToken.token());
            case StoredSlot.TokenGroup group -> {
                composer.ldc(group.tokens().size());
                composer.anewarray(ClassDesc.of("java.lang.invoke.MethodHandle"));
                for (int index = 0; index < group.tokens().size(); index++) {
                    composer.dup();
                    composer.ldc(index);
                    emitVariable(context, composer, group.tokens().get(index));
                    composer.aastore();
                }
            }
            case StoredSlot.Evidence evidence -> emitStoredEvidenceGroup(context, composer, evidence);
        }
    }

    private static void emitStoredEvidenceGroup(final CompilationContext context,
                                                final CodeBuilder composer,
                                                final StoredSlot.Evidence evidence) {
        final var methods = storedBoundMethods(context, evidence.binder(), evidence.boundIndex());
        if (methods.size() == 1) {
            emitStoredEvidenceHandle(context, composer, evidence, 0);
            return;
        }
        composer.ldc(methods.size());
        composer.anewarray(ClassDesc.of("java.lang.invoke.MethodHandle"));
        for (int index = 0; index < methods.size(); index++) {
            composer.dup();
            composer.ldc(index);
            emitStoredEvidenceHandle(context, composer, evidence, index);
            composer.aastore();
        }
    }

    private static void emitStoredEvidenceHandle(final CompilationContext context,
                                                 final CodeBuilder composer,
                                                 final StoredSlot.Evidence evidence,
                                                 final int methodIndex) {
        final var methods = storedBoundMethods(context, evidence.binder(), evidence.boundIndex());
        final var methodSlot = methods.get(methodIndex);
        final var bound = evidence.binder().bounds().get(evidence.boundIndex());
        final var use = ContractComposition.fromType(bound);
        if (use == null) {
            throw new IllegalStateException("A stored evidence slot has no contract bound: " + bound);
        }
        final var actualBase = payloadBase(evidence.actualType());
        final var actualName = actualBase instanceof NominalDescriptor nominal
                ? nominal.name() : null;
        final var classDeclaration = actualName == null ? null
                : context.resolution.classes().get(actualName);
        final Stmt.Method implementation = classDeclaration == null ? null
                : classDeclaration.methods().stream()
                        .filter(method -> method.name().lexeme().equals(
                                methodSlot.method().name().lexeme()))
                        .findFirst().orElse(null);
        final var ownerName = implementation == null ? use.name().lexeme() : actualName;
        final var signature = implementation == null
                ? (FunctionDescriptor) TypeSubstitution.substitute(methodSlot.method().typeDescriptor(),
                        methodSlot.applied().substitutions())
                : implementation.typeDescriptor();
        final var erased = (FunctionDescriptor) TypeSubstitution.erase(signature);
        final var methodType = toJavaMethodDescriptor(erased);
        final var owner = ClassDesc.of(ownerName);
        final var kind = implementation == null
                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL
                : DirectMethodHandleDesc.Kind.VIRTUAL;
        composer.ldc(MethodHandleDesc.of(kind, owner,
                methodSlot.method().name().lexeme(), methodType.descriptorString()));
    }

    private static List<ContractComposition.MethodSlot> storedBoundMethods(
            final CompilationContext context,
            final TypeParameterDescriptor binder,
            final int boundIndex) {
        final var use = ContractComposition.fromType(binder.bounds().get(boundIndex));
        return use == null ? List.of() : ContractComposition.methods(context.resolution.contracts(), use);
    }

    private sealed interface StoredSlot {
        record Descriptor(TypeDescriptor actualType) implements StoredSlot {}
        record Null() implements StoredSlot {}
        record OfToken(Token token) implements StoredSlot {}
        record TokenGroup(List<Token> tokens) implements StoredSlot {}
        record Evidence(CompilationContext context, TypeParameterDescriptor binder,
                        int boundIndex, TypeDescriptor actualType)
                implements StoredSlot {}
    }

    static void emitConversion(CompilationContext context, final CodeBuilder composer,
                                final TypeDescriptor sourceType,
                                final TypeDescriptor targetType ){
        emitConversion(context, composer, sourceType, targetType, sourceType);
    }

    /**
     * Converts a value whose declared source type may have been erased through a generic boundary.
     * {@code sourceRepresentation} records whether the value on the stack is already a holder even
     * when its JVM signature is {@code Object}.
     */
    static void emitConversion(CompilationContext context, final CodeBuilder composer,
                                final TypeDescriptor sourceType,
                                final TypeDescriptor targetType,
                                final TypeDescriptor sourceRepresentation ){
        emitConversion(context, composer, sourceType, targetType, sourceRepresentation, sourceType);
    }

    static void emitConversion(CompilationContext context, final CodeBuilder composer,
                                       final TypeDescriptor sourceType,
                                       final TypeDescriptor targetType,
                                       final TypeDescriptor sourceRepresentation,
                                       final TypeDescriptor sourceValueType ){
        if (StoredExistentialSupport.isHolderType(targetType)) {
            context.lastEmittedType = targetType;
            return;
        }
        final var targetNeedsHolder = StoredExistentialSupport.requiresHolder(targetType);
        if (StoredExistentialSupport.isHolderType(sourceRepresentation)) {
            if (targetNeedsHolder) {
                if (StoredExistentialSupport.isHolderType(sourceType)) {
                    emitStoredUnpack(context, composer, targetType);
                    return;
                }
                if (unnullable(sourceValueType).equals(unnullable(targetType))) {
                    composer.checkcast(TypeDescriptor.toJavaClassDesc(sourceRepresentation));
                    context.lastEmittedType = targetType;
                    return;
                }
                emitStoredUnpack(context, composer, sourceValueType);
                emitStoredPack(context, composer, sourceValueType, targetType);
                return;
            }
            emitStoredPayload(context, composer, targetType, sourceRepresentation);
            return;
        }
        if (StoredExistentialSupport.isHolderType(sourceType)
                && targetNeedsHolder) {
            emitStoredUnpack(context, composer, targetType);
            return;
        }
        if (targetNeedsHolder) {
            emitStoredPack(context, composer, sourceType, targetType);
            return;
        }
        if (context.resolution.isArrayIterableProjection(targetType, sourceType)) {
            composer.invokestatic(ClassDesc.of("zeron.runtime.ArrayIterable"), "wrap",
                    MethodTypeDesc.of(ClassDesc.of("zeron.collections.Iterable"),
                            TypeDescriptor.toJavaClassDesc(sourceType)));
            context.lastEmittedType = targetType;
            return;
        }
        final var sourceFunction = functionView(context, sourceType);
        final var targetFunction = functionView(context, targetType);
        if (sourceFunction != null && targetFunction != null) {
            final var erasedSource = (FunctionDescriptor) TypeSubstitution.erase(sourceFunction);
            final var erasedTarget = (FunctionDescriptor) TypeSubstitution.erase(targetFunction);
            if (!TypeDescriptor.toJavaClassDesc(erasedSource).equals(TypeDescriptor.toJavaClassDesc(erasedTarget))) {
                if (isNullableFunctionView(context, sourceType) && isNullableFunctionView(context, targetType)) {
                    final var adapterName = context.lambdaPlan.nullableAdapterName(erasedSource, erasedTarget);
                    if (adapterName == null) {
                        throw new IllegalStateException("Missing nullable function adapter for "
                                + erasedSource + " to " + erasedTarget);
                    }
                    composer.invokestatic(ClassDesc.of(context.mainClassName), adapterName,
                            MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(erasedTarget),
                                    TypeDescriptor.toJavaClassDesc(erasedSource)));
                } else {
                    LambdaSupportEmitter.emitFunctionAdapter(context, composer, erasedSource, erasedTarget);
                }
            }
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof NeverDescriptor) {
            context.lastEmittedType = targetType;
            return;
        }
        if (targetType instanceof NeverDescriptor) {
            // A value flowing into a Never-typed position means the expression cannot complete normally.
            emitPop(context, composer, sourceType);
            final var exception = ClassDesc.of("java.lang.IllegalStateException");
            composer.new_(exception);
            composer.dup();
            composer.ldc("A Never-typed expression produced a value.");
            composer.invokespecial(exception, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
            composer.athrow();
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceType.equals(targetType) || sourceType instanceof NullDescriptor && targetType.isNullable()
            || context.resolution.isContractProjection(targetType, sourceType)
            || isErasedContractProjection(context, targetType, sourceType)) {
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof NullableDescriptor nullable
                && nullable.baseType().equals(targetType)
                && (targetType instanceof IntDescriptor || targetType instanceof CharDescriptor
                || targetType instanceof LongDescriptor
                || targetType instanceof ByteDescriptor
                || targetType instanceof FloatDescriptor
                || targetType instanceof BooleanDescriptor)) {
            emitUnboxOrCast(context, composer, targetType);
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof ReferenceDescriptor reference
                && targetType.equals(reference.baseType())) {
            context.lastEmittedType = targetType;
            return;
        }
        if (targetType instanceof NullableDescriptor nullable
                && (sourceType.equals(nullable.baseType())
                || sourceType instanceof ReferenceDescriptor reference
                && nullable.baseType().equals(reference.baseType()))) {
            emitBox(context, composer, sourceType);
            context.lastEmittedType = targetType;
            return;
        }
        final var sourceJavaType = TypeDescriptor.toJavaClassDesc(sourceType);
        final var targetJavaType = TypeDescriptor.toJavaClassDesc(targetType);
        if (sourceJavaType.equals(targetJavaType)) {
            context.lastEmittedType = targetType;
            return;
        }
        if (targetJavaType.equals(ConstantDescs.CD_Object)) {
            emitBox(context, composer, sourceType);
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceJavaType.equals(ConstantDescs.CD_Object)) {
            emitUnboxOrCast(context, composer, targetType);
            context.lastEmittedType = targetType;
            return;
        }
        final var sourceDescriptor = sourceJavaType.descriptorString();
        final var targetDescriptor = TypeDescriptor.toJavaClassDesc(targetType).descriptorString();
        if ((sourceDescriptor.startsWith("L") || sourceDescriptor.startsWith("["))
                && (targetDescriptor.startsWith("L") || targetDescriptor.startsWith("["))) {
            composer.checkcast(targetJavaClass(context, targetType));
            context.lastEmittedType = targetType;
            return;
        }
        throw new IllegalStateException("Unsupported conversion from " + sourceType + " to " + targetType);
    }

    /// The erased shape of `PropertyRef.reader`, which stores the reference's reader closure.
    static TypeDescriptor propertyReferenceReaderType() {
        return TypeDescriptor.functionOf("", TypeDescriptor.ofName("java.lang.Object"),
                TypeDescriptor.ofName("java.lang.Object"));
    }

    private static void emitCallableReference(final CompilationContext context,
                                              final CodeBuilder composer,
                                              final Expr.PropertyReference reference) {
        final var declaredName = reference.callableToken().lexeme();
        final var kindText = switch (reference.callableKind()) {
            case METHOD -> "method";
            case STATIC -> "static";
            case CONSTRUCTOR -> "constructor";
        };
        final var wrapper = switch (reference.callableKind()) {
            case METHOD -> ClassDesc.of(SyntheticNames.METHOD_REF_TYPE);
            case STATIC -> ClassDesc.of(SyntheticNames.STATIC_METHOD_REF_TYPE);
            case CONSTRUCTOR -> ClassDesc.of(SyntheticNames.CONSTRUCTOR_REF_TYPE);
        };
        final var factory = switch (reference.callableKind()) {
            case METHOD -> SyntheticNames.METHOD_REF_FACTORY;
            case STATIC -> SyntheticNames.STATIC_METHOD_REF_FACTORY;
            case CONSTRUCTOR -> SyntheticNames.CONSTRUCTOR_REF_FACTORY;
        };
        emitExpr(context, composer, reference.callable());
        composer.ldc(declaredName);
        composer.ldc(reference.identity());
        composer.ldc(reference.declaringTypeName());
        composer.ldc(kindText);
        composer.ldc(declaredName);
        composer.invokestatic(ClassDesc.of(SyntheticNames.CALLABLE_ID_TYPE),
                SyntheticNames.CALLABLE_ID_FACTORY,
                MethodTypeDesc.of(ClassDesc.of(SyntheticNames.CALLABLE_ID_TYPE),
                        ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String));
        composer.invokestatic(wrapper, factory,
                MethodTypeDesc.of(wrapper, ConstantDescs.CD_Object, ConstantDescs.CD_String,
                        ConstantDescs.CD_String, ClassDesc.of(SyntheticNames.CALLABLE_ID_TYPE)));
    }

    private static boolean isErasedContractProjection(CompilationContext context, final TypeDescriptor targetType,
                                              final TypeDescriptor sourceType ){
        final var contractName = rawNominalName(context, targetType);
        final var className = rawNominalName(context, sourceType);
        if (contractName == null || className == null) return false;
        final var classDeclaration = context.resolution.classes().get(className);
        if (classDeclaration == null) return false;
        return classDeclaration.contractUses().stream()
                .anyMatch(contractUse -> contractUse.name().lexeme().equals(contractName));
    }

    private static String rawNominalName(CompilationContext context, final TypeDescriptor type ){
        return switch (type) {
            case ReferenceDescriptor reference -> rawNominalName(context, reference.baseType());
            case GenericDescriptor generic -> generic.baseType().name();
            case NominalDescriptor nominal -> nominal.name();
            default -> null;
        };
    }

    private static FunctionDescriptor functionView(CompilationContext context, final TypeDescriptor type ){
        return switch (type) {
            case FunctionDescriptor function -> function;
            case NullableDescriptor nullable -> functionView(context, nullable.baseType());
            case ReferenceDescriptor reference -> functionView(context, reference.baseType());
            default -> null;
        };
    }

    private static boolean isNullableFunctionView(CompilationContext context, final TypeDescriptor type ){
        return switch (type) {
            case NullableDescriptor nullable -> functionView(context, nullable.baseType()) != null
                    || isNullableFunctionView(context, nullable.baseType());
            case ReferenceDescriptor reference -> isNullableFunctionView(context, reference.baseType());
            default -> false;
        };
    }

    static void emitUnboxOrCast(CompilationContext context, final CodeBuilder composer, final TypeDescriptor targetType ){
        if (targetType instanceof CharDescriptor) {
            composer.invokestatic(ClassDesc.of("zeron.runtime.StringUnicode"), "unboxCharValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
            return;
        }
        final var targetDescriptor = TypeDescriptor.toJavaClassDesc(targetType).descriptorString();
        switch (targetDescriptor) {
            case "V" -> {
                // No value may be materialized for a Never-typed target.
            }
            case "I" -> {
                composer.checkcast(ConstantDescs.CD_Integer);
                composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case "J" -> {
                composer.checkcast(ConstantDescs.CD_Long);
                composer.invokevirtual(ClassDesc.of("java.lang.Long"), "longValue",
                        MethodTypeDesc.of(ConstantDescs.CD_long));
            }
            case "B" -> {
                composer.checkcast(ClassDesc.of("java.lang.Byte"));
                composer.invokevirtual(ClassDesc.of("java.lang.Byte"), "byteValue",
                        MethodTypeDesc.of(ConstantDescs.CD_byte));
                composer.ldc(255);
                composer.iand();
            }
            case "D" -> {
                composer.checkcast(ClassDesc.of("java.lang.Double"));
                composer.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
            }
            case "Z" -> {
                composer.checkcast(ClassDesc.of("java.lang.Boolean"));
                composer.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            default -> composer.checkcast(targetJavaClass(context, targetType));
        }
    }

    private static ClassDesc targetJavaClass(CompilationContext context, final TypeDescriptor targetType ){
        return TypeDescriptor.toJavaClassDesc(targetType);
    }

    private static void emitArrayBoundsCheck(CompilationContext context, final CodeBuilder composer ){
        composer.swap();
        composer.invokestatic(
            ClassDesc.of("java.util.Objects"),
                "checkIndex",
            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
    }

    static void emitArrayReadConversion(CompilationContext context, final CodeBuilder composer, final TypeDescriptor targetType ){
        final var valueType = targetType instanceof ReferenceDescriptor reference
                ? reference.baseType()
                : targetType;
        if (targetType instanceof NullableDescriptor) {
            composer.checkcast(TypeDescriptor.toJavaClassDesc(targetType));
        } else if (valueType instanceof IntDescriptor || valueType instanceof CharDescriptor) {
            composer.checkcast(ConstantDescs.CD_Integer);
            composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int));
        } else if (valueType instanceof LongDescriptor) {
            composer.checkcast(ClassDesc.of("java.lang.Long"));
            composer.invokevirtual(ClassDesc.of("java.lang.Long"), "longValue",
                    MethodTypeDesc.of(ConstantDescs.CD_long));
        } else if (valueType instanceof ByteDescriptor) {
            composer.checkcast(ClassDesc.of("java.lang.Byte"));
            composer.invokevirtual(ClassDesc.of("java.lang.Byte"), "byteValue",
                    MethodTypeDesc.of(ConstantDescs.CD_byte));
            composer.ldc(255);
            composer.iand();
        } else if (valueType instanceof FloatDescriptor) {
            composer.checkcast(ConstantDescs.CD_Double);
            composer.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                    MethodTypeDesc.of(ConstantDescs.CD_double));
        } else if (valueType instanceof BooleanDescriptor) {
            composer.checkcast(ConstantDescs.CD_Boolean);
            composer.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean));
        } else {
            composer.checkcast(TypeDescriptor.toJavaClassDesc(targetType));
        }
        context.lastEmittedType = targetType;
    }

    static void emitConstant(CompilationContext context, final CodeBuilder composer, final ConstantDesc value ){
        composer.loadConstant(value);
        context.lastEmittedType = ConstantFolder.typeForConstant(value);
    }

    private static MethodRefEntry getAutoboxingFor(final ConstantPoolBuilder cpb, final String type) {
        return switch (type) {
            case "I" -> cpb.methodRefEntry(Integer.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
            case "J" -> cpb.methodRefEntry(ConstantDescs.CD_Long, "valueOf",
                    MethodTypeDesc.ofDescriptor("(J)Ljava/lang/Long;"));
            case "D" -> cpb.methodRefEntry(Double.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
            case "B" -> cpb.methodRefEntry(Boolean.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
            case "C" -> cpb.methodRefEntry(ClassDesc.of("java.lang.Byte"), "valueOf",
                    MethodTypeDesc.ofDescriptor("(B)Ljava/lang/Byte;"));
            default -> throw new IllegalArgumentException("Autoboxing not supported.");
        };
    }

    static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }

    static MethodTypeDesc toJavaMethodDescriptor(FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var paramTypes = type.invocationParameters().stream()
                .map(TypeDescriptor::toJavaClassDesc).toList();
        return withHiddenParameters(MethodTypeDesc.of(returnType, paramTypes),
                type.hiddenEvidenceCount(), type.hiddenDescriptorCount());
    }

    static void beginScope(CompilationContext context ){
        context.symbols.beginScope();
    }

    static void endScope(CompilationContext context ){
        context.symbols.endScope();
    }

}
