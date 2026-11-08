package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.IntrinsicDescriptors;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

/// Emits the streaming `Encode` and `Decode` operations derived for a type, together with the
/// `Shape` information requested by `deriving Shape`.
///
/// The generated methods are synthetic static helpers named `$zeron$encode` and `$zeron$decode`.
/// Each takes the encoder or decoder, and one `MethodHandle` per component that needs evidence, so
/// derived code composes with derived and hand-written codecs alike. A directly recursive component
/// is handled by calling the same helper with the same evidence, exactly as derived equality does.
final class SerializationEmitter {
    private static final ClassDesc ENCODER = ClassDesc.of("zeron.serialization.Encoder");
    private static final ClassDesc DECODER = ClassDesc.of("zeron.serialization.Decoder");
    private static final ClassDesc RESULT = ClassDesc.of("zeron.lang.Result");
    private static final ClassDesc RESULT_OK = ClassDesc.of("zeron.lang.Result$$sum$Ok");
    private static final ClassDesc RESULT_ERR = ClassDesc.of("zeron.lang.Result$$sum$Err");
    private static final ClassDesc OPTION = ClassDesc.of("zeron.lang.Option");
    private static final ClassDesc OPTION_SOME = ClassDesc.of("zeron.lang.Option$$sum$Some");
    private static final ClassDesc OPTION_NONE = ClassDesc.of("zeron.lang.Option$$sum$None");
    private static final ClassDesc VALUE_KIND = ClassDesc.of("zeron.serialization.ValueKind");
    private static final ClassDesc NULL_KIND = ClassDesc.of("zeron.serialization.ValueKind$$sum$NullKind");
    private static final ClassDesc RECORD_KIND = ClassDesc.of("zeron.serialization.ValueKind$$sum$RecordKind");
    private static final ClassDesc ALTERNATIVE_KIND =
            ClassDesc.of("zeron.serialization.ValueKind$$sum$AlternativeKind");
    private static final ClassDesc ENCODE_ERROR = ClassDesc.of("zeron.serialization.EncodeError");
    private static final ClassDesc DECODE_ERROR = ClassDesc.of("zeron.serialization.DecodeError");
    private static final ClassDesc OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc STRING = ConstantDescs.CD_String;
    private static final ClassDesc METHOD_HANDLE = ClassDesc.of("java.lang.invoke.MethodHandle");
    private static final int FLAGS = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC;
    private static final MethodTypeDesc EMPTY = MethodTypeDesc.of(RESULT);
    private static final MethodTypeDesc ONE_STRING = MethodTypeDesc.of(RESULT, STRING);
    private static final MethodTypeDesc PATH_HELPER = MethodTypeDesc.of(RESULT, RESULT, STRING);
    private static final MethodTypeDesc ENCODE_ERROR_TYPE =
            MethodTypeDesc.of(ConstantDescs.CD_void, STRING,
                    ConstantDescs.CD_Object.arrayType());
    private static final MethodTypeDesc DECODE_ERROR_TYPE =
            MethodTypeDesc.of(ConstantDescs.CD_void, STRING,
                    ConstantDescs.CD_Object.arrayType(), OPTION);

    private SerializationEmitter() {}

    private record Component(String name, TypeDescriptor valueType, boolean recursive,
                             boolean nullable, ClassDesc erased, ClassDesc storage) {}

    static void emitIfRequested(final CompilationContext context, final ClassBuilder builder,
                                final Stmt declaration) {
        if (declaration == null) return;
        final var contracts = derivedContracts(declaration);
        final var encode = derives(contracts, "Encode") || derives(contracts, "Codec");
        final var decode = derives(contracts, "Decode") || derives(contracts, "Codec");
        final var shape = derives(contracts, "Shape");
        if (!encode && !decode && !shape) return;
        switch (declaration) {
            case Stmt.ProductDecl product -> {
                final var owner = ClassDesc.of(product.name().lexeme());
                final var applied = appliedType(product.name().lexeme(), product.typeParameters());
                final var components = product.components().stream()
                        .map(component -> component(component.name().lexeme(), component.type(), applied))
                        .toList();
                emitRecord(context, builder, owner, components, true, encode, decode, shape, RECORD_KIND);
            }
            case Stmt.ClassDecl dataClass when dataClass.dataClass() -> {
                final var owner = ClassDesc.of(dataClass.name().lexeme());
                final var applied = appliedType(dataClass.name().lexeme(), dataClass.typeParameters());
                final var components = dataClass.dataComponents().stream()
                        .map(component -> component(component.name().lexeme(), component.type(), applied))
                        .toList();
                emitRecord(context, builder, owner, components, false, encode, decode, shape, RECORD_KIND);
            }
            case Stmt.SumDecl sum -> {
                final var owner = ClassDesc.of(sum.name().lexeme());
                final var applied = appliedType(sum.name().lexeme(), sum.typeParameters());
                emitSum(context, builder, owner, sum, applied, encode, decode, shape);
            }
            default -> {}
        }
    }

    private static void emitRecord(final CompilationContext context, final ClassBuilder builder,
                                   final ClassDesc owner, final List<Component> components,
                                   final boolean propertyAccess, final boolean encode,
                                   final boolean decode, final boolean shape, final ClassDesc kind) {
        final var handleCount = (int) components.stream().filter(component -> !component.recursive()).count();
        if (encode) {
            emitEncodeRecord(context, builder, owner, components, propertyAccess, handleCount);
            if (!components.isEmpty()) emitPathHelper(builder, owner, true, false);
        }
        if (decode) {
            emitDecodeRecord(context, builder, owner, components, handleCount);
            if (!components.isEmpty()) emitPathHelper(builder, owner, false, false);
        }
        if (shape) emitShape(builder, kind);
    }

    private static void emitEncodeRecord(final CompilationContext context, final ClassBuilder builder,
                                         final ClassDesc owner, final List<Component> components,
                                         final boolean propertyAccess, final int handleCount) {
        final var methodType = selfType(owner, ENCODER, handleCount);
        builder.withMethodBody(IntrinsicDescriptors.DERIVED_ENCODE, methodType, FLAGS, code -> {
            final int resultSlot = 2 + handleCount;
            final int valueSlot = resultSlot + 1;
            code.aload(1);
            code.invokeinterface(ENCODER, "beginRecord", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            var handleIndex = 0;
            for (final var component : components) {
                code.aload(1);
                code.ldc(component.name());
                code.invokeinterface(ENCODER, "writeField", ONE_STRING);
                code.astore(resultSlot);
                returnOnFailure(code, resultSlot, IntrinsicDescriptors.ENCODE_PATH, owner, component.name(), false);
                code.aload(0);
                if (propertyAccess) {
                    code.invokevirtual(owner, Stmt.propertyGetterName(component.name()),
                            MethodTypeDesc.of(component.storage()));
                } else {
                    code.getfield(owner, component.name(), component.storage());
                }
                storeValue(code, component, valueSlot);
                emitEncodeValue(context, code, owner, component, 2 + handleIndex, valueSlot,
                        resultSlot, methodType, handleCount, false);
                if (!component.recursive()) handleIndex++;
            }
            code.aload(1);
            code.invokeinterface(ENCODER, "endRecord", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            emitOkUnit(context, code);
        });
    }

    private static void emitEncodeValue(final CompilationContext context, final CodeBuilder code,
                                        final ClassDesc owner, final Component component,
                                        final int handleSlot, final int valueSlot, final int resultSlot,
                                        final MethodTypeDesc selfType, final int handleCount,
                                        final boolean interfaceOwner) {
        if (component.nullable()) {
            final var nonNull = code.newLabel();
            final var done = code.newLabel();
            loadValue(code, component, valueSlot);
            code.ifnonnull(nonNull);
            code.aload(1);
            code.invokeinterface(ENCODER, "writeNull", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, IntrinsicDescriptors.ENCODE_PATH, owner, component.name(), interfaceOwner);
            code.goto_(done);
            code.labelBinding(nonNull);
            emitWriteValue(code, owner, component, handleSlot, valueSlot, resultSlot,
                    selfType, handleCount, interfaceOwner);
            code.labelBinding(done);
            return;
        }
        emitWriteValue(code, owner, component, handleSlot, valueSlot, resultSlot,
                selfType, handleCount, interfaceOwner);
    }

    private static void emitWriteValue(final CodeBuilder code, final ClassDesc owner,
                                       final Component component, final int handleSlot,
                                       final int valueSlot, final int resultSlot,
                                       final MethodTypeDesc selfType, final int handleCount,
                                       final boolean interfaceOwner) {
        if (component.recursive()) {
            loadValue(code, component, valueSlot);
            code.checkcast(owner);
            code.aload(1);
            for (int index = 0; index < handleCount; index++) code.aload(2 + index);
            code.invokestatic(owner, IntrinsicDescriptors.DERIVED_ENCODE, selfType, interfaceOwner);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, IntrinsicDescriptors.ENCODE_PATH, owner, component.name(), interfaceOwner);
            return;
        }
        code.aload(handleSlot);
        loadValue(code, component, valueSlot);
        code.aload(1);
        code.invokevirtual(METHOD_HANDLE, "invoke",
                MethodTypeDesc.of(RESULT, component.erased(), ENCODER));
        code.astore(resultSlot);
        returnOnFailure(code, resultSlot, IntrinsicDescriptors.ENCODE_PATH, owner, component.name(), interfaceOwner);
    }

    private static void emitDecodeRecord(final CompilationContext context, final ClassBuilder builder,
                                         final ClassDesc owner, final List<Component> components,
                                         final int handleCount) {
        final var methodType = selfType(null, DECODER, handleCount);
        builder.withMethodBody(IntrinsicDescriptors.DERIVED_DECODE, methodType, FLAGS, code -> {
            final int resultSlot = 1 + handleCount;
            final int arraySlot = resultSlot + 1;
            final int optionSlot = arraySlot + 1;
            final int nameSlot = optionSlot + 1;
            final int valueSlot = nameSlot + 1;
            code.aload(0);
            code.invokeinterface(DECODER, "beginRecord", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            code.loadConstant(Integer.valueOf(components.size()));
            code.anewarray(OBJECT);
            code.astore(arraySlot);
            final var loop = code.newLabel();
            final var end = code.newLabel();
            code.labelBinding(loop);
            code.aload(0);
            code.invokeinterface(DECODER, "nextField", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            code.aload(resultSlot);
            code.checkcast(RESULT_OK);
            code.invokevirtual(RESULT_OK, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
            code.astore(optionSlot);
            code.aload(optionSlot);
            code.instanceOf(OPTION_NONE);
            code.ifne(end);
            code.aload(optionSlot);
            code.checkcast(OPTION_SOME);
            code.invokevirtual(OPTION_SOME, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
            code.checkcast(STRING);
            code.astore(nameSlot);
            final var labels = new ArrayList<Label>();
            for (final var component : components) {
                final var label = code.newLabel();
                labels.add(label);
                code.aload(nameSlot);
                code.ldc(component.name());
                code.invokevirtual(STRING, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, OBJECT));
                code.ifne(label);
            }
            code.aload(0);
            code.invokeinterface(DECODER, "skipValue", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            code.goto_(loop);
            var handleIndex = 0;
            for (int index = 0; index < components.size(); index++) {
                final var component = components.get(index);
                code.labelBinding(labels.get(index));
                emitDecodeValue(context, code, owner, component, index, arraySlot, resultSlot,
                        valueSlot, 1 + handleIndex, methodType, handleCount, false);
                if (!component.recursive()) handleIndex++;
                code.goto_(loop);
            }
            code.labelBinding(end);
            code.aload(0);
            code.invokeinterface(DECODER, "endRecord", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, false);
            for (int index = 0; index < components.size(); index++) {
                final var component = components.get(index);
                if (component.nullable()) continue;
                final var present = code.newLabel();
                code.aload(arraySlot);
                code.loadConstant(Integer.valueOf(index));
                code.aaload();
                code.ifnonnull(present);
                emitDecodeFailure(code, "Missing required field '" + component.name() + "'.");
                code.labelBinding(present);
            }
            emitConstructRecord(context, code, owner, components, arraySlot);
        });
    }

    private static void emitDecodeValue(final CompilationContext context, final CodeBuilder code,
                                        final ClassDesc owner, final Component component,
                                        final int index, final int arraySlot, final int resultSlot,
                                        final int valueSlot, final int handleSlot,
                                        final MethodTypeDesc selfType, final int handleCount,
                                        final boolean interfaceOwner) {
        if (component.nullable()) {
            final var read = code.newLabel();
            final var done = code.newLabel();
            code.aload(0);
            code.invokeinterface(DECODER, "readKind", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, IntrinsicDescriptors.DECODE_PATH, owner, component.name(), interfaceOwner);
            code.aload(resultSlot);
            code.checkcast(RESULT_OK);
            code.invokevirtual(RESULT_OK, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
            code.instanceOf(NULL_KIND);
            code.ifeq(read);
            code.aload(0);
            code.invokeinterface(DECODER, "skipValue", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, IntrinsicDescriptors.DECODE_PATH, owner, component.name(), interfaceOwner);
            code.aload(arraySlot);
            code.loadConstant(Integer.valueOf(index));
            code.aconst_null();
            code.aastore();
            code.goto_(done);
            code.labelBinding(read);
            emitReadValue(code, owner, component, index, arraySlot, resultSlot, handleSlot,
                    selfType, handleCount, interfaceOwner);
            code.labelBinding(done);
            return;
        }
        emitReadValue(code, owner, component, index, arraySlot, resultSlot, handleSlot,
                selfType, handleCount, interfaceOwner);
    }

    private static void emitReadValue(final CodeBuilder code, final ClassDesc owner,
                                      final Component component, final int index,
                                      final int arraySlot, final int resultSlot,
                                      final int handleSlot, final MethodTypeDesc selfType,
                                      final int handleCount, final boolean interfaceOwner) {
        if (component.recursive()) {
            code.aload(0);
            for (int handle = 0; handle < handleCount; handle++) code.aload(1 + handle);
            code.invokestatic(owner, IntrinsicDescriptors.DERIVED_DECODE, selfType, interfaceOwner);
        } else {
            code.aload(handleSlot);
            code.aload(0);
            code.invokevirtual(METHOD_HANDLE, "invoke", MethodTypeDesc.of(RESULT, DECODER));
        }
        code.astore(resultSlot);
        returnOnFailure(code, resultSlot, IntrinsicDescriptors.DECODE_PATH, owner, component.name(), interfaceOwner);
        code.aload(arraySlot);
        code.loadConstant(Integer.valueOf(index));
        code.aload(resultSlot);
        code.checkcast(RESULT_OK);
        code.invokevirtual(RESULT_OK, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
        code.aastore();
    }

    private static void emitConstructRecord(final CompilationContext context, final CodeBuilder code,
                                            final ClassDesc owner, final List<Component> components,
                                            final int arraySlot) {
        code.new_(owner);
        code.dup();
        for (int index = 0; index < components.size(); index++) {
            code.aload(arraySlot);
            code.loadConstant(Integer.valueOf(index));
            code.aaload();
            emitUnbox(code, components.get(index).erased());
        }
        final var parameters = components.stream().map(Component::erased).toArray(ClassDesc[]::new);
        code.invokespecial(owner, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
        emitWrapOk(code);
    }

    private static void emitSum(final CompilationContext context, final ClassBuilder builder,
                                final ClassDesc owner, final Stmt.SumDecl sum,
                                final TypeDescriptor applied, final boolean encode,
                                final boolean decode, final boolean shape) {
        final var handleCount = sum.cases().stream()
                .flatMap(sumCase -> sumCase.payloads().stream())
                .map(payload -> component(payload.name().lexeme(), payload.type(), applied))
                .filter(component -> !component.recursive())
                .count();
        final var handles = (int) handleCount;
        if (encode) {
            final var methodType = selfType(owner, ENCODER, handles);
            builder.withMethodBody(IntrinsicDescriptors.DERIVED_ENCODE, methodType, FLAGS,
                    code -> emitEncodeSum(context, code, owner, sum, applied, methodType, handles));
            if (!sum.cases().isEmpty()) emitPathHelper(builder, owner, true, true);
        }
        if (decode) {
            final var methodType = selfType(null, DECODER, handles);
            builder.withMethodBody(IntrinsicDescriptors.DERIVED_DECODE, methodType, FLAGS,
                    code -> emitDecodeSum(context, code, owner, sum, applied, methodType, handles));
            if (!sum.cases().isEmpty()) emitPathHelper(builder, owner, false, true);
        }
        if (shape) emitShape(builder, ALTERNATIVE_KIND);
    }

    private static void emitEncodeSum(final CompilationContext context, final CodeBuilder code,
                                      final ClassDesc owner, final Stmt.SumDecl sum,
                                      final TypeDescriptor applied, final MethodTypeDesc methodType,
                                      final int handleCount) {
        final int resultSlot = 2 + handleCount;
        final int valueSlot = resultSlot + 1;
        var handleIndex = 0;
        for (final var sumCase : sum.cases()) {
            final var caseClass = ClassDesc.of(sum.generatedCaseName(sumCase));
            final var next = code.newLabel();
            code.aload(0);
            code.instanceOf(caseClass);
            code.ifeq(next);
            code.aload(1);
            code.ldc(sumCase.name().lexeme());
            code.invokeinterface(ENCODER, "beginAlternative", ONE_STRING);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, true);
            var payloadHandle = handleIndex;
            for (int payloadIndex = 0; payloadIndex < sumCase.payloads().size(); payloadIndex++) {
                final var payload = sumCase.payloads().get(payloadIndex);
                final var component = component(payload.name().lexeme(), payload.type(), applied);
                code.aload(0);
                code.checkcast(caseClass);
                code.invokevirtual(caseClass, "$zeron$payload$" + payloadIndex,
                        MethodTypeDesc.of(component.storage()));
                storeValue(code, component, valueSlot);
                emitEncodeValue(context, code, owner, component, 2 + payloadHandle, valueSlot,
                        resultSlot, methodType, handleCount, true);
                if (!component.recursive()) payloadHandle++;
            }
            handleIndex = payloadHandle;
            code.aload(1);
            code.invokeinterface(ENCODER, "endAlternative", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, true);
            emitOkUnit(context, code);
            code.labelBinding(next);
        }
        emitOkUnit(context, code);
    }

    private static void emitDecodeSum(final CompilationContext context, final CodeBuilder code,
                                      final ClassDesc owner, final Stmt.SumDecl sum,
                                      final TypeDescriptor applied, final MethodTypeDesc methodType,
                                      final int handleCount) {
        final int resultSlot = 1 + handleCount;
        final int tagSlot = resultSlot + 1;
        final int arraySlot = tagSlot + 1;
        final int valueSlot = arraySlot + 1;
        code.aload(0);
        code.invokeinterface(DECODER, "readAlternative", EMPTY);
        code.astore(resultSlot);
        returnOnFailure(code, resultSlot, null, owner, null, true);
        code.aload(resultSlot);
        code.checkcast(RESULT_OK);
        code.invokevirtual(RESULT_OK, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
        code.checkcast(STRING);
        code.astore(tagSlot);
        final var labels = new ArrayList<Label>();
        for (final var sumCase : sum.cases()) {
            final var label = code.newLabel();
            labels.add(label);
            code.aload(tagSlot);
            code.ldc(sumCase.name().lexeme());
            code.invokevirtual(STRING, "equals", MethodTypeDesc.of(ConstantDescs.CD_boolean, OBJECT));
            code.ifne(label);
        }
        emitFallThroughUnknownCase(code, tagSlot);
        var handleIndex = 0;
        for (int caseIndex = 0; caseIndex < sum.cases().size(); caseIndex++) {
            final var sumCase = sum.cases().get(caseIndex);
            code.labelBinding(labels.get(caseIndex));
            code.loadConstant(Integer.valueOf(sumCase.payloads().size()));
            code.anewarray(OBJECT);
            code.astore(arraySlot);
            var payloadHandle = handleIndex;
            for (int payloadIndex = 0; payloadIndex < sumCase.payloads().size(); payloadIndex++) {
                final var payload = sumCase.payloads().get(payloadIndex);
                final var component = component(payload.name().lexeme(), payload.type(), applied);
                emitDecodeValue(context, code, owner, component, payloadIndex, arraySlot, resultSlot,
                        valueSlot, 1 + payloadHandle, methodType, handleCount, true);
                if (!component.recursive()) payloadHandle++;
            }
            handleIndex = payloadHandle;
            code.aload(0);
            code.invokeinterface(DECODER, "endAlternative", EMPTY);
            code.astore(resultSlot);
            returnOnFailure(code, resultSlot, null, owner, null, true);
            final var caseClass = ClassDesc.of(sum.generatedCaseName(sumCase));
            code.new_(caseClass);
            code.dup();
            for (int payloadIndex = 0; payloadIndex < sumCase.payloads().size(); payloadIndex++) {
                final var component = component(sumCase.payloads().get(payloadIndex).name().lexeme(),
                        sumCase.payloads().get(payloadIndex).type(), applied);
                code.aload(arraySlot);
                code.loadConstant(Integer.valueOf(payloadIndex));
                code.aaload();
                emitUnbox(code, component.erased());
            }
            final var parameters = sumCase.payloads().stream()
                    .map(payload -> component(payload.name().lexeme(), payload.type(), applied))
                    .map(Component::erased).toArray(ClassDesc[]::new);
            code.invokespecial(caseClass, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
            emitWrapOk(code);
        }
    }

    private static void emitFallThroughUnknownCase(final CodeBuilder code, final int tagSlot) {
        code.new_(RESULT_ERR);
        code.dup();
        code.new_(DECODE_ERROR);
        code.dup();
        code.ldc("Unknown sum case.");
        code.iconst_1();
        code.anewarray(STRING);
        code.dup();
        code.loadConstant(Integer.valueOf(0));
        code.aload(tagSlot);
        code.aastore();
        code.getstatic(OPTION_NONE, "$zeron$singleton", OPTION_NONE);
        code.invokespecial(DECODE_ERROR, "<init>", DECODE_ERROR_TYPE);
        code.invokespecial(RESULT_ERR, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT));
        code.areturn();
    }

    private static void emitPathHelper(final ClassBuilder builder, final ClassDesc owner,
                                       final boolean encode, final boolean interfaceOwner) {
        final var errorClass = encode ? ENCODE_ERROR : DECODE_ERROR;
        final var helperName = encode ? IntrinsicDescriptors.ENCODE_PATH : IntrinsicDescriptors.DECODE_PATH;
        builder.withMethodBody(helperName, PATH_HELPER, FLAGS, code -> {
            code.aload(0);
            code.checkcast(RESULT_ERR);
            code.invokevirtual(RESULT_ERR, "$zeron$payload$0", MethodTypeDesc.of(OBJECT));
            code.checkcast(errorClass);
            code.astore(2);
            code.aload(2);
            code.invokevirtual(errorClass, Stmt.propertyGetterName("path"),
                    MethodTypeDesc.of(ConstantDescs.CD_Object.arrayType()));
            code.astore(3);
            code.aload(3);
            code.arraylength();
            code.iconst_1();
            code.iadd();
            code.anewarray(STRING);
            code.astore(4);
            code.aload(3);
            code.iconst_0();
            code.aload(4);
            code.iconst_0();
            code.aload(3);
            code.arraylength();
            code.invokestatic(ClassDesc.of("java.lang.System"), "arraycopy",
                    MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT, ConstantDescs.CD_int,
                            OBJECT, ConstantDescs.CD_int, ConstantDescs.CD_int));
            code.aload(4);
            code.aload(3);
            code.arraylength();
            code.aload(1);
            code.aastore();
            code.new_(errorClass);
            code.dup();
            code.aload(2);
            code.invokevirtual(errorClass, Stmt.propertyGetterName("message"),
                    MethodTypeDesc.of(STRING));
            code.aload(4);
            if (!encode) {
                code.aload(2);
                code.invokevirtual(errorClass, Stmt.propertyGetterName("position"),
                        MethodTypeDesc.of(OPTION));
            }
            code.invokespecial(errorClass, "<init>",
                    encode ? ENCODE_ERROR_TYPE : DECODE_ERROR_TYPE);
            code.astore(5);
            code.new_(RESULT_ERR);
            code.dup();
            code.aload(5);
            code.invokespecial(RESULT_ERR, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT));
            code.areturn();
        });
    }

    private static void emitShape(final ClassBuilder builder, final ClassDesc kind) {
        builder.withMethodBody(IntrinsicDescriptors.DERIVED_SHAPE, MethodTypeDesc.of(VALUE_KIND), FLAGS, code -> {
            code.getstatic(kind, "$zeron$singleton", kind);
            code.areturn();
        });
        builder.withMethodBody(IntrinsicDescriptors.DERIVED_NULLABLE_SHAPE, MethodTypeDesc.of(ConstantDescs.CD_boolean), FLAGS,
                code -> {
                    code.iconst_0();
                    code.ireturn();
                });
    }

    private static void returnOnFailure(final CodeBuilder code, final int resultSlot,
                                        final String pathHelper, final ClassDesc owner,
                                        final String segment, final boolean interfaceOwner) {
        final var success = code.newLabel();
        code.aload(resultSlot);
        code.instanceOf(RESULT_ERR);
        code.ifeq(success);
        if (pathHelper == null) {
            code.aload(resultSlot);
        } else {
            code.aload(resultSlot);
            code.ldc(segment);
            code.invokestatic(owner, pathHelper, PATH_HELPER, interfaceOwner);
        }
        code.areturn();
        code.labelBinding(success);
    }

    private static void emitDecodeFailure(final CodeBuilder code, final String message) {
        code.new_(RESULT_ERR);
        code.dup();
        code.new_(DECODE_ERROR);
        code.dup();
        code.ldc(message);
        code.iconst_0();
        code.anewarray(STRING);
        code.getstatic(OPTION_NONE, "$zeron$singleton", OPTION_NONE);
        code.invokespecial(DECODE_ERROR, "<init>", DECODE_ERROR_TYPE);
        code.invokespecial(RESULT_ERR, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT));
        code.areturn();
    }

    private static void emitOkUnit(final CompilationContext context, final CodeBuilder code) {
        code.new_(RESULT_OK);
        code.dup();
        BytecodeEmitter.emitUnitValue(context, code);
        code.invokespecial(RESULT_OK, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT));
        code.areturn();
    }

    private static void emitWrapOk(final CodeBuilder code) {
        code.new_(RESULT_OK);
        code.dup_x1();
        code.swap();
        code.invokespecial(RESULT_OK, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, OBJECT));
        code.areturn();
    }

    private static void storeValue(final CodeBuilder code, final Component component, final int slot) {
        switch (component.storage().descriptorString()) {
            case "I", "Z", "B", "C", "S" -> code.istore(slot);
            case "J" -> code.lstore(slot);
            case "D" -> code.dstore(slot);
            case "F" -> code.fstore(slot);
            default -> code.astore(slot);
        }
    }

    private static void loadValue(final CodeBuilder code, final Component component, final int slot) {
        switch (component.storage().descriptorString()) {
            case "I", "Z", "B", "C", "S" -> code.iload(slot);
            case "J" -> code.lload(slot);
            case "D" -> code.dload(slot);
            case "F" -> code.fload(slot);
            default -> code.aload(slot);
        }
    }

    private static void emitUnbox(final CodeBuilder code, final ClassDesc target) {
        switch (target.descriptorString()) {
            case "I" -> {
                code.checkcast(ClassDesc.of("java.lang.Integer"));
                code.invokevirtual(ClassDesc.of("java.lang.Integer"), "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case "J" -> {
                code.checkcast(ClassDesc.of("java.lang.Long"));
                code.invokevirtual(ClassDesc.of("java.lang.Long"), "longValue",
                        MethodTypeDesc.of(ConstantDescs.CD_long));
            }
            case "D" -> {
                code.checkcast(ClassDesc.of("java.lang.Double"));
                code.invokevirtual(ClassDesc.of("java.lang.Double"), "doubleValue",
                        MethodTypeDesc.of(ConstantDescs.CD_double));
            }
            case "Z" -> {
                code.checkcast(ClassDesc.of("java.lang.Boolean"));
                code.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean));
            }
            case "C" -> {
                code.checkcast(ClassDesc.of("java.lang.Character"));
                code.invokevirtual(ClassDesc.of("java.lang.Character"), "charValue",
                        MethodTypeDesc.of(ConstantDescs.CD_char));
            }
            case "B" -> {
                code.checkcast(ClassDesc.of("java.lang.Byte"));
                code.invokevirtual(ClassDesc.of("java.lang.Byte"), "byteValue",
                        MethodTypeDesc.of(ConstantDescs.CD_byte));
            }
            default -> code.checkcast(target);
        }
    }

    private static MethodTypeDesc selfType(final ClassDesc valueOrNull, final ClassDesc first,
                                           final int handleCount) {
        final var parameters = new ArrayList<ClassDesc>();
        if (valueOrNull != null) parameters.add(valueOrNull);
        parameters.add(first);
        for (int index = 0; index < handleCount; index++) parameters.add(METHOD_HANDLE);
        return MethodTypeDesc.of(RESULT, parameters.toArray(ClassDesc[]::new));
    }

    private static Component component(final String name, final TypeDescriptor type,
                                       final TypeDescriptor applied) {
        final var nullable = type instanceof NullableDescriptor;
        final var valueType = nullable ? ((NullableDescriptor) type).baseType() : type;
        return new Component(name, valueType, sameApplied(valueType, applied), nullable,
                TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(valueType)),
                TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(type)));
    }

    private static boolean sameApplied(final TypeDescriptor left, final TypeDescriptor right) {
        final var normalizedLeft = left instanceof ReferenceDescriptor reference
                ? reference.baseType() : left;
        final var normalizedRight = right instanceof ReferenceDescriptor reference
                ? reference.baseType() : right;
        return normalizedLeft.equals(normalizedRight);
    }

    private static TypeDescriptor appliedType(final String name,
                                              final List<com.maruseron.zeron.domain.TypeParameterDescriptor> parameters) {
        return parameters.isEmpty() ? TypeDescriptor.ofName(name)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(name),
                        parameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
    }

    private static List<com.maruseron.zeron.scan.Token> derivedContracts(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl value -> value.derivedContracts();
            case Stmt.ProductDecl value -> value.derivedContracts();
            case Stmt.SumDecl value -> value.derivedContracts();
            case Stmt.NewtypeDecl value -> value.derivedContracts();
            default -> List.of();
        };
    }

    private static boolean derives(final List<com.maruseron.zeron.scan.Token> contracts,
                                   final String name) {
        return contracts.stream().anyMatch(contract -> contract.lexeme().equals(name));
    }
}
