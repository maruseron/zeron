package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

/// The names and JVM descriptors of compiler-provided operations and generated contract helpers.
///
/// Runtime support classes and generated contract implementations must agree on these descriptors.
/// Keeping them here prevents the bytecode emitters, witness selection, and derived-code emitters
/// from drifting apart when a contract signature changes.
public final class IntrinsicDescriptors {
    public static final String HASH_SUPPORT_OWNER = "zeron.runtime.HashSupport";
    public static final String HASH_SUPPORT_IMPL_OWNER = "com.maruseron.zeron.runtime.HashSupport";
    public static final String EQ_SUPPORT_OWNER = "zeron.runtime.EqSupport";
    public static final ClassDesc HASH_SUPPORT = ClassDesc.of(HASH_SUPPORT_OWNER);
    public static final ClassDesc HASH_SUPPORT_IMPL = ClassDesc.of(HASH_SUPPORT_IMPL_OWNER);
    public static final ClassDesc EQ_SUPPORT = ClassDesc.of(EQ_SUPPORT_OWNER);
    public static final ClassDesc METHOD_HANDLE = ClassDesc.of("java.lang.invoke.MethodHandle");

    public static final String EQ = "eq";
    public static final String HASH = "hash";
    public static final String EQ_INT = "eqInt";
    public static final String EQ_LONG = "eqLong";
    public static final String EQ_FLOAT = "eqFloat";
    public static final String EQ_BOOLEAN = "eqBoolean";
    public static final String EQ_STRING = "eqString";
    public static final String EQ_UNIT = "eqUnit";
    public static final String EQ_NULLABLE = "eqNullable";
    public static final String EQ_NULLABLE_WITH_HANDLE = "eqNullableWithHandle";
    public static final String HASH_INT = "hashInt";
    public static final String HASH_LONG = "hashLong";
    public static final String HASH_FLOAT = "hashFloat";
    public static final String HASH_BOOLEAN = "hashBoolean";
    public static final String HASH_STRING = "hashString";
    public static final String HASH_UNIT = "hashUnit";
    public static final String HASH_NULLABLE_WITH_HANDLE = "hashNullableWithHandle";
    public static final String HASH_COMBINE = "combine";
    public static final String HASH_FINALIZE = "finalizeHash";
    public static final String HASH_CASE_SEED = "caseSeed";

    public static final String DERIVED_EQ = "$zeron$eq";
    public static final String DERIVED_HASH = "$zeron$hash";
    public static final String DERIVED_ENCODE = "$zeron$encode";
    public static final String DERIVED_DECODE = "$zeron$decode";
    public static final String DERIVED_SHAPE = "$zeron$shape";
    public static final String DERIVED_NULLABLE_SHAPE = "$zeron$nullable";
    public static final String ENCODE_PATH = "$zeron$encodePath";
    public static final String DECODE_PATH = "$zeron$decodePath";

    private IntrinsicDescriptors() {}

    public static FunctionDescriptor hashSignature(final TypeDescriptor valueType) {
        return TypeDescriptor.functionOf(HASH, TypeDescriptor.ofLong(), valueType);
    }

    public static FunctionDescriptor nullableHashSignature() {
        return TypeDescriptor.functionOf(HASH, TypeDescriptor.ofLong(), TypeDescriptor.ofAny());
    }

    public static FunctionDescriptor eqSignature(final TypeDescriptor valueType) {
        return TypeDescriptor.functionOf(EQ, TypeDescriptor.ofBoolean(), valueType, valueType);
    }

    public static FunctionDescriptor nullableEqSignature() {
        return TypeDescriptor.functionOf(EQ, TypeDescriptor.ofBoolean(),
                TypeDescriptor.ofAny(), TypeDescriptor.ofAny());
    }

    public static MethodTypeDesc hashIntType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_int);
    }

    public static MethodTypeDesc hashLongType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long);
    }

    public static MethodTypeDesc hashFloatType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_double);
    }

    public static MethodTypeDesc hashBooleanType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_boolean);
    }

    public static MethodTypeDesc hashStringType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_String);
    }

    public static MethodTypeDesc hashUnitType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long,
                ClassDesc.of("zeron.lang.Unit"));
    }

    public static MethodTypeDesc hashNullableType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_Object, METHOD_HANDLE);
    }

    public static MethodTypeDesc hashCombineType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long);
    }

    public static MethodTypeDesc hashFinalizeType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long);
    }

    public static MethodTypeDesc hashCaseSeedType() {
        return MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_int);
    }

    public static MethodTypeDesc derivedHashType(final ClassDesc owner, final int evidenceCount) {
        return MethodTypeDesc.of(ConstantDescs.CD_long, valueAndEvidenceParameters(owner, evidenceCount));
    }

    public static MethodTypeDesc derivedEqType(final ClassDesc owner, final int evidenceCount) {
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        parameters.add(owner);
        for (int index = 0; index < evidenceCount; index++) parameters.add(METHOD_HANDLE);
        return MethodTypeDesc.of(ConstantDescs.CD_boolean, parameters.toArray(ClassDesc[]::new));
    }

    public static MethodTypeDesc derivedHashType(final List<ClassDesc> parameters) {
        return MethodTypeDesc.of(ConstantDescs.CD_long, parameters.toArray(ClassDesc[]::new));
    }

    private static ClassDesc[] valueAndEvidenceParameters(final ClassDesc owner, final int evidenceCount) {
        final var parameters = new ArrayList<ClassDesc>();
        parameters.add(owner);
        for (int index = 0; index < evidenceCount; index++) parameters.add(METHOD_HANDLE);
        return parameters.toArray(ClassDesc[]::new);
    }
}
