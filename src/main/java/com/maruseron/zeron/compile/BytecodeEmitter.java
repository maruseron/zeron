package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.FloatDescriptor;
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
        RuntimeSupportEmitter.emitUnitClass(context.outputDirectory);
        RuntimeSupportEmitter.emitRaisedEffectClass(context.outputDirectory);
        final var signatureDeclarations = context.compilationUnits.stream()
                .flatMap(unit -> NamespaceMembers.flatten(unit.declarations()).stream())
                .map(NamespaceMembers.Member::declaration).toList();
        context.lambdaPlan = new LambdaCompilationPlan(context.topLevelDeclarations, signatureDeclarations,
                context.symbols);
        for (final var shape : context.lambdaPlan.functionShapes().entrySet()) {
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
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                DeclarationEmitter.generateClass(context, builder, namespace.getValue(), false);
            });
        }
        NominalTypeEmitter.emit(context);
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
        libraryIndex.writeTo(context.outputDirectory.resolve(Path.of("META-INF", "zeron", "api-v19.bin")));
    }

    static Path outputPath(CompilationContext context, final String binaryName ){
        return context.outputDirectory.resolve(binaryName.replace('.', '/') + ".class");
    }

    static void emitPropertyAssignment(CompilationContext context, final CodeBuilder composer,
                                       final Expr.PropertyAssignment assignment,
                                       final boolean retainResult ){
        final var property = assignment.property;
        if (property.resolvedAsProperty()) {
            final var ownerName = property.resolvedOwnerName();
            final var declaredType = declarationPropertyType(context, ownerName, property.name.lexeme());
            final var erasedType = TypeSubstitution.erase(declaredType);
            emitExpr(context, composer, property.receiver);
            emitExpr(context, composer, assignment.value);
            emitConversion(context, composer, context.lastEmittedType, property.getType());
            emitConversion(context, composer, property.getType(), erasedType);
            emitPropertySetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
            if (retainResult) emitUnitValue(context, composer);
            context.lastEmittedType = TypeDescriptor.ofUnit();
            return;
        }
        final var owner = nominalName(context, property.receiver.getType());
        final var erasedFieldType = TypeSubstitution.erase(
                declarationFieldType(context, owner, property.name.lexeme()));
        emitExpr(context, composer, property.receiver);
        emitExpr(context, composer, assignment.value);
        emitConversion(context, composer, context.lastEmittedType, erasedFieldType);
        if (context.emittingLambdaImplementation && !isSamePackage(owner, context.mainClassName)) {
            composer.invokestatic(ClassDesc.of(owner), NominalTypeEmitter.lambdaFieldWriteBridge(property.name.lexeme()),
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of(owner),
                            TypeDescriptor.toJavaClassDesc(erasedFieldType)));
        } else {
            composer.putfield(ClassDesc.of(owner), property.name.lexeme(),
                    TypeDescriptor.toJavaClassDesc(erasedFieldType));
        }
        if (retainResult) emitUnitValue(context, composer);
        context.lastEmittedType = TypeDescriptor.ofUnit();
    }

    private static void emitPropertyCompoundAssignment(CompilationContext context, final CodeBuilder composer,
                                               final Expr.PropertyCompoundAssignment assignment ){
        final var property = assignment.property;
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
            emitConversion(context, composer, context.lastEmittedType, erasedType);
            emitPropertySetterCall(context, composer, ownerName, property.name.lexeme(), declaredType);
            context.lastEmittedType = TypeDescriptor.ofUnit();
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
            case ARRAY_ALLOC, ARRAY_CLEAR_SLOT, OPTION_UNWRAP_SOME, INT_TO_FLOAT, FLOAT_TO_INT_OPTION ->
                    throw new IllegalStateException("Function intrinsic is not a syntax expression.");
            case ARRAY_LENGTH -> {
                final var property = (Expr.Property) expression;
                emitExpr(context, composer, property.receiver);
                composer.arraylength();
                context.lastEmittedType = operation.resultType();
            }
            case ARRAY_READ -> emitArrayRead(context, composer, (Expr.Index) expression, operation);
            case ARRAY_WRITE -> emitArrayWrite(context, composer,
                    (Expr.IndexAssignment) expression, operation, retainResult);
        }
    }

    private static void emitArrayLiteral(CompilationContext context, final CodeBuilder composer,
                                  final Expr.ArrayLiteral literal,
                                  final ResolvedIntrinsicOperation operation ){
        final var elementType = operation.parameterTypes().isEmpty()
                ? arrayElementType(operation.resultType())
                : operation.parameterTypes().getFirst();
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
        composer.aaload();
        emitArrayReadConversion(context, composer, operation.resultType());
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
        emitBox(context, composer, context.lastEmittedType);
        composer.aastore();
        if (retainResult) emitUnitValue(context, composer);
        context.lastEmittedType = operation.resultType();
    }

    static void emitExpr(CompilationContext context, final CodeBuilder composer, final Expr expr ){
        switch (expr) {
            case Expr.Property property -> {
                if (property.namespaceValueSymbol() != null) {
                    emitVariable(context, composer, property.namespaceValueSymbol());
                    break;
                } else if (property.javaFieldTarget() != null) {
                    final var target = property.javaFieldTarget();
                    composer.getstatic(ClassDesc.of(target.owner()), target.name(),
                            ClassDesc.ofDescriptor(target.descriptor()));
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
            case Expr.CoalesceAssignment assignment -> ExpressionFlowEmitter.emitCoalesceAssignment(context, composer, assignment);
            case Expr.Binary binary -> {
                if (binary.getType() instanceof BooleanDescriptor) {
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
                        emitExpr(context, composer, binary.right);
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
            case Expr.Logical logical -> ExpressionFlowEmitter.emitLogical(context, composer, logical);
            case Expr.Coalesce coalesce -> ExpressionFlowEmitter.emitCoalesce(context, composer, coalesce);
            case Expr.TypeTest test -> ExpressionFlowEmitter.emitTypeTest(context, composer, test);
            case Expr.Cast cast -> ExpressionFlowEmitter.emitCast(context, composer, cast);
            case Expr.Call call -> emitCall(context, composer, call);
            case Expr.MemberCall call -> emitMemberCall(context, composer, call);
            case Expr.Lambda lambda -> {
                final var functionType = (FunctionDescriptor) lambda.getType();
                final var captures = context.lambdaPlan.captures(lambda);
                final var captureTypes = context.lambdaPlan.captureTypes(lambda);
                final var implementationParameters = new ArrayList<>(captureTypes);
                implementationParameters.addAll(functionType.parameters());
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
            case Expr.Literal literal -> {
                switch (literal.value) {
                    case String s -> {
                        composer.ldc(s);
                        context.lastEmittedType = TypeDescriptor.ofString();
                    }
                    case Integer i -> {
                        composer.ldc(i);
                        context.lastEmittedType = TypeDescriptor.ofInt();
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
                if (unary.operator.type() == TokenType.NOT) {
                    emitExpr(context, composer, unary.right);
                    composer.iconst_1();
                    composer.ixor();
                    context.lastEmittedType = TypeDescriptor.ofBoolean();
                } else if (unary.operator.type() == TokenType.TILDE) {
                    emitExpr(context, composer, unary.right);
                    composer.iconst_m1();
                    composer.ixor();
                    context.lastEmittedType = TypeDescriptor.ofInt();
                } else {
                    emitExpr(context, composer, unary.right);
                    final var opcode = TypeDescriptor.toJavaClassDesc(unary.getType()).descriptorString();
                    if (unary.operator.type() == TokenType.MINUS) {
                        if (opcode.equals("I")) composer.ineg();
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
                    emitVariable(context, composer, variable.resolvedSymbolToken());
                    if (!(variable.getType() instanceof InferDescriptor)
                            && !context.lastEmittedType.equals(variable.getType())) {
                        emitConversion(context, composer, context.lastEmittedType, variable.getType());
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private static void emitCall(CompilationContext context, final CodeBuilder composer, final Expr.Call call ){
        if (call.intrinsicOperation() != null) {
            switch (call.intrinsicOperation().id()) {
                case ARRAY_FILL -> emitArrayFill(context, composer, call, call.intrinsicOperation());
                case ARRAY_ALLOC -> emitArrayAllocate(context, composer, call, call.intrinsicOperation());
                case ARRAY_CLEAR_SLOT -> emitArraySlotClear(context, composer, call);
                case OPTION_UNWRAP_SOME -> emitOptionUnwrapSome(context, composer, call);
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
                emitEvidenceArguments(context, composer, call.evidenceArguments());
                final var invokedType = call.arguments.size() < fixedArity
                        ? prefixFunctionType(runtimeType, call.arguments.size())
                        : runtimeType;
                final var invocation = withEvidenceParameters(toJavaMethodDescriptor(invokedType),
                        call.evidenceArguments().size());
                composer.invokestatic(ClassDesc.of(functionOwner),
                        functionName.substring(functionName.lastIndexOf('.') + 1),
                        invocation);
            } else {
                for (int i = 0; i < call.arguments.size(); i++) {
                    emitExpr(context, composer, call.arguments.get(i));
                    emitConversion(context, composer, context.lastEmittedType, functionType.parameters().get(i));
                }
                emitEvidenceArguments(context, composer, call.evidenceArguments());
                final var invokedType = prefixFunctionType(runtimeType, call.arguments.size());
                final var invocation = withEvidenceParameters(toJavaMethodDescriptor(invokedType),
                        call.evidenceArguments().size());
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
        emitVariable(context, composer, call.resolvedSymbolToken());
        if (call.variadicElementType() != null) {
            emitVariadicArguments(context, composer, call.arguments, runtimeType,
                    call.variadicFixedArity(), call.variadicElementType());
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType, runtimeType.parameters().get(i));
            }
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

    static List<Token> witnessSlots(final CompilationContext context, final FunctionDescriptor functionType) {
        final var slots = new ArrayList<Token>();
        for (final var parameter : functionType.typeParameters()) {
            for (int boundIndex = 0; boundIndex < parameter.bounds().size(); boundIndex++) {
                final var bound = parameter.bounds().get(boundIndex);
                final var contractName = bound instanceof GenericDescriptor generic
                        ? generic.baseType().name()
                        : bound instanceof ReferenceDescriptor reference
                            ? reference.baseType().name() : bound.name();
                final var contract = context.resolution.contracts().get(contractName);
                if (contract == null) continue;
                for (int constructorIndex = 0; constructorIndex < contract.namedConstructors().size();
                     constructorIndex++) {
                    slots.add(Expr.evidenceToken(parameter, boundIndex, constructorIndex));
                }
            }
        }
        return List.copyOf(slots);
    }

    static MethodTypeDesc withEvidenceParameters(final MethodTypeDesc methodType, final int count) {
        if (count == 0) return methodType;
        final var parameters = new ClassDesc[count];
        java.util.Arrays.fill(parameters, ClassDesc.of("java.lang.invoke.MethodHandle"));
        return methodType.insertParameterTypes(methodType.parameterCount(), parameters);
    }

    private static void emitEvidenceArguments(final CompilationContext context,
                                              final CodeBuilder composer,
                                              final List<Expr.EvidenceArgument> evidence) {
        for (final var argument : evidence) {
            if (argument.forwardToken() != null) {
                emitVariable(context, composer, argument.forwardToken());
            } else {
                final var targetType = (FunctionDescriptor) TypeSubstitution.erase(argument.signature());
                composer.ldc(MethodHandleDesc.of(DirectMethodHandleDesc.Kind.STATIC,
                        ClassDesc.of(argument.ownerName()), argument.constructorName(),
                        toJavaMethodDescriptor(targetType).descriptorString()));
            }
        }
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
        composer.aconst_null();
        composer.invokeinterface(ClassDesc.of("zeron.lang.Option"), "getOrElse",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
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
        if (call.witnessEvidenceToken() != null) {
            emitVariable(context, composer, call.witnessEvidenceToken());
            final var factoryType = call.witnessFactoryType();
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                        TypeSubstitution.erase(factoryType.parameters().get(i)));
            }
            final var erasedParameters = factoryType.parameters().stream()
                    .map(TypeSubstitution::erase)
                    .map(TypeDescriptor::toJavaClassDesc).toList();
            composer.invokevirtual(ClassDesc.of("java.lang.invoke.MethodHandle"), "invoke",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, erasedParameters));
            context.lastEmittedType = factoryType.returnType();
            return;
        }
        if (call.javaCallTarget() != null) {
            emitJavaMemberCall(context, composer, call, receiverOnStack);
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
            composer.new_(classDesc);
            composer.dup();
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                        TypeSubstitution.erase(constructorTypes.get(i)));
            }
            composer.invokespecial(classDesc, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
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
                    emitVariadicArguments(context, composer, call.arguments,
                            (FunctionDescriptor) TypeSubstitution.erase(resolvedFactory),
                            call.variadicFixedArity(),
                            TypeSubstitution.erase(call.variadicElementType()));
                } else {
                    for (int i = 0; i < call.arguments.size(); i++) {
                        emitExpr(context, composer, call.arguments.get(i));
                        emitConversion(context, composer, context.lastEmittedType,
                            TypeSubstitution.erase(namedConstructor.typeDescriptor().parameters().get(i)));
                    }
                }
                composer.invokestatic(ClassDesc.of(call.resolvedClassName()), call.name.lexeme(),
                    toJavaMethodDescriptor(erasedFactory));
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
                    call.variadicFixedArity(), TypeSubstitution.erase(call.variadicElementType()));
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                    TypeSubstitution.erase(descriptor.parameters().get(i)));
            }
        }
        emitEvidenceArguments(context, composer, call.evidenceArguments());
        final var invokedType = call.variadicElementType() != null
                ? call.arguments.size() < call.variadicFixedArity()
                    ? prefixFunctionType(runtimeType, call.arguments.size())
                    : runtimeType
                : prefixFunctionType(descriptor, call.arguments.size());
        final var methodType = withEvidenceParameters(toJavaMethodDescriptor(invokedType),
                call.evidenceArguments().size());
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
                    withEvidenceParameters(toJavaMethodDescriptor(erasedDescriptor, bridgeParameters),
                            call.evidenceArguments().size()));
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
                    call.variadicFixedArity(),
                    TypeSubstitution.erase(call.variadicElementType()));
        } else {
            for (int i = 0; i < call.arguments.size(); i++) {
                emitExpr(context, composer, call.arguments.get(i));
                emitConversion(context, composer, context.lastEmittedType,
                        sourceType.parameters().get(i + 1));
            }
        }
        final var invokedType = call.variadicElementType() != null
                ? call.arguments.size() < call.variadicFixedArity()
                    ? prefixFunctionType(runtimeType, call.arguments.size() + 1)
                    : runtimeType
                : prefixFunctionType(runtimeType, call.arguments.size() + 1);
        composer.invokestatic(ClassDesc.of(owner), extension.name().lexeme(),
                toJavaMethodDescriptor(invokedType));
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
            emitConversion(context, composer, context.lastEmittedType, call.resolvedDescriptor().parameters().get(i));
        }
        if (target.varArgs()) {
            emitJavaVarargsArray(context, composer, call, fixedParameterCount,
                    descriptor.parameterType(fixedParameterCount).componentType());
        }
        switch (target.kind()) {
            case CONSTRUCTOR -> composer.invokespecial(owner, "<init>", descriptor);
            case STATIC -> composer.invokestatic(owner, target.name(), descriptor);
            case INTERFACE -> composer.invokeinterface(owner, target.name(), descriptor);
            case VIRTUAL -> composer.invokevirtual(owner, target.name(), descriptor);
        }
        if (target.kind() != JavaCallTarget.InvocationKind.CONSTRUCTOR
            && descriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(context, composer);
        }
        context.lastEmittedType = call.safeNavigation() && call.resolvedDescriptor() != null
                ? call.resolvedDescriptor().returnType()
                : call.getType();
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
            switch (intrinsicBinding.id()) {
                case INT_TO_FLOAT -> {
                    composer.i2d();
                    composer.dreturn();
                }
                case FLOAT_TO_INT_OPTION -> {
                    emitFloatToIntOption(context, composer);
                    composer.areturn();
                }
                default -> throw new IllegalStateException("Unexpected external-function intrinsic: "
                        + intrinsicBinding.id().stableName());
            }
            context.lastEmittedType = signature.returnType();
            return;
        }
        final MethodTypeDesc methodDescriptor;
        if (target instanceof FunctionBindingRegistry.StaticMethod staticMethod) {
            methodDescriptor = staticMethod.descriptor();
            emitExternalFunctionArguments(context, composer, signature);
            composer.invokestatic(staticMethod.owner(), staticMethod.methodName(), methodDescriptor);
        } else if (target instanceof FunctionBindingRegistry.StaticFieldInstanceMethod fieldMethod) {
            methodDescriptor = fieldMethod.methodDescriptor();
            composer.getstatic(fieldMethod.fieldOwner(), fieldMethod.fieldName(), fieldMethod.fieldType());
            emitExternalFunctionArguments(context, composer, signature);
            composer.invokevirtual(fieldMethod.methodOwner(), fieldMethod.methodName(), methodDescriptor);
        } else {
            throw new IllegalStateException("Unsupported external function binding target.");
        }
        if (methodDescriptor.returnType().equals(ConstantDescs.CD_void)) {
            emitUnitValue(context, composer);
            composer.areturn();
        } else {
            composer.return_(TypeKind.fromDescriptor(methodDescriptor.returnType().descriptorString()));
        }
        context.lastEmittedType = signature.returnType();
    }

    private static void emitFloatToIntOption(final CompilationContext context,
                                             final CodeBuilder composer) {
        final var noValue = composer.newLabel();
        final var done = composer.newLabel();
        final var someClass = ClassDesc.of("zeron.lang.Some");
        final var noneClass = ClassDesc.of("zeron.lang.None");

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
        composer.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int));
        composer.invokestatic(someClass, "from",
                MethodTypeDesc.of(someClass, ConstantDescs.CD_Object));
        composer.goto_(done);

        composer.labelBinding(noValue);
        composer.pop2();
        composer.invokestatic(noneClass, "none", MethodTypeDesc.of(noneClass));
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
            slot += TypeDescriptor.toJavaClassDesc(parameter).descriptorString().equals("D") ? 2 : 1;
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
                        && isSamePackage(name, context.mainClassName))
                .map(ClassDesc::of)
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
                    context.lastEmittedType = binding.type();
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
                case "I", "Z" -> composer.iload(binding.lvt() + context.localSlotOffset);
                case "D" -> composer.dload(binding.lvt() + context.localSlotOffset);
                default -> composer.aload(binding.lvt() + context.localSlotOffset);
            }
        }
        context.lastEmittedType = binding.type();
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
            case IntDescriptor _ -> "I";
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

    static void emitConversion(CompilationContext context, final CodeBuilder composer,
                                final TypeDescriptor sourceType,
                                final TypeDescriptor targetType ){
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
        if (sourceType.equals(targetType) || sourceType instanceof NullDescriptor && targetType.isNullable()
            || context.resolution.isContractProjection(targetType, sourceType)
            || isErasedContractProjection(context, targetType, sourceType)) {
            context.lastEmittedType = targetType;
            return;
        }
        if (sourceType instanceof NullableDescriptor nullable
                && nullable.baseType().equals(targetType)
                && (targetType instanceof IntDescriptor
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
        throw new IllegalStateException("Unsupported conversion from " + sourceType + " to " + targetType);
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
        final var targetDescriptor = TypeDescriptor.toJavaClassDesc(targetType).descriptorString();
        switch (targetDescriptor) {
            case "I" -> {
                composer.checkcast(ConstantDescs.CD_Integer);
                composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
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
        } else if (valueType instanceof IntDescriptor) {
            composer.checkcast(ConstantDescs.CD_Integer);
            composer.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int));
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
            case "D" -> cpb.methodRefEntry(Double.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
            case "B" -> cpb.methodRefEntry(Boolean.class.describeConstable().orElseThrow(), "valueOf", MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
            default -> throw new IllegalArgumentException("Autoboxing not supported.");
        };
    }

    static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }

    static MethodTypeDesc toJavaMethodDescriptor(FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var paramTypes = type.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList();
        return MethodTypeDesc.of(returnType, paramTypes);
    }

    static void beginScope(CompilationContext context ){
        context.symbols.beginScope();
    }

    static void endScope(CompilationContext context ){
        context.symbols.endScope();
    }

}
