package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FunctionBindingRegistry {
    public sealed interface JvmTarget permits StaticMethod, InstanceMethod,
            StaticFieldInstanceMethod, IntrinsicBinding {}

    public record IntrinsicBinding(IntrinsicId id) implements JvmTarget {
        public IntrinsicBinding {
            Objects.requireNonNull(id);
        }
    }

    public record StaticMethod(ClassDesc owner,
                               String methodName,
                               MethodTypeDesc descriptor) implements JvmTarget {
        public StaticMethod {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(descriptor);
        }
    }

    public record InstanceMethod(ClassDesc owner,
                                 String methodName,
                                 MethodTypeDesc descriptor) implements JvmTarget {
        public InstanceMethod {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(descriptor);
        }
    }

    public record StaticFieldInstanceMethod(ClassDesc fieldOwner,
                                             String fieldName,
                                             ClassDesc fieldType,
                                             ClassDesc methodOwner,
                                             String methodName,
                                             MethodTypeDesc methodDescriptor) implements JvmTarget {
        public StaticFieldInstanceMethod {
            Objects.requireNonNull(fieldOwner);
            Objects.requireNonNull(fieldName);
            Objects.requireNonNull(fieldType);
            Objects.requireNonNull(methodOwner);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(methodDescriptor);
            if (!fieldType.equals(methodOwner)) {
                throw new IllegalArgumentException("Static field type must match the instance method owner.");
            }
        }
    }

    public record Binding(FunctionDescriptor signature, JvmTarget target) {
        public Binding {
            Objects.requireNonNull(signature);
            Objects.requireNonNull(target);
        }
    }

    private static final FunctionBindingRegistry STANDARD = createStandard();

    private final Map<String, Binding> bindings;

    private FunctionBindingRegistry(final Map<String, Binding> bindings) {
        this.bindings = Map.copyOf(new LinkedHashMap<>(bindings));
    }

    public static FunctionBindingRegistry standard() {
        return STANDARD;
    }

    public static FunctionBindingRegistry of(final Map<String, Binding> bindings) {
        return new FunctionBindingRegistry(bindings);
    }

    public FunctionBindingRegistry withBinding(final String qualifiedName, final Binding binding) {
        final var updated = new LinkedHashMap<>(bindings);
        if (updated.putIfAbsent(Objects.requireNonNull(qualifiedName), Objects.requireNonNull(binding)) != null) {
            throw new IllegalArgumentException("Duplicate function binding: " + qualifiedName);
        }
        return new FunctionBindingRegistry(updated);
    }

    public FunctionBindingRegistry withBindings(final FunctionBindingRegistry additionalBindings) {
        final var updated = new LinkedHashMap<>(bindings);
        additionalBindings.bindings.forEach((name, binding) -> {
            final var existing = updated.putIfAbsent(name, binding);
            if (existing != null && !existing.equals(binding)) {
                throw new IllegalArgumentException("Conflicting function binding: " + name);
            }
        });
        return new FunctionBindingRegistry(updated);
    }

    public Binding find(final String qualifiedName) {
        return bindings.get(qualifiedName);
    }

    private static FunctionBindingRegistry createStandard() {
        final var numericConversion = TypeDescriptor.functionOf("intToFloat",
                TypeDescriptor.ofFloat(), TypeDescriptor.ofInt());
        final var intToLongConversion = TypeDescriptor.functionOf("intToLong",
                TypeDescriptor.ofLong(), TypeDescriptor.ofInt());
        final var longToIntConversion = TypeDescriptor.functionOf("longToInt",
                TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"), TypeDescriptor.ofInt()),
                TypeDescriptor.ofLong());
        final var intToByteConversion = TypeDescriptor.functionOf("intToByte",
                TypeDescriptor.ofByte(), TypeDescriptor.ofInt());
        final var byteToIntConversion = TypeDescriptor.functionOf("byteToInt",
                TypeDescriptor.ofInt(), TypeDescriptor.ofByte());
        final var stringGetBytes = TypeDescriptor.functionOf("stringGetBytes",
                TypeDescriptor.ofByteArray(), TypeDescriptor.ofString().toNullable(),
                new NullableDescriptor(new ReferenceDescriptor(
                        TypeDescriptor.ofName("java.nio.charset.Charset"))));
        final var codingExceptionConversion = TypeDescriptor.functionOf(
                "characterCodingExceptionAsIOException",
                TypeDescriptor.ofName("java.io.IOException"),
                TypeDescriptor.ofName("java.nio.charset.CharacterCodingException"));
        final var utf8Decoder = TypeDescriptor.functionOf("utf8Decoder",
                new NullableDescriptor(new ReferenceDescriptor(
                        TypeDescriptor.ofName("java.nio.charset.CharsetDecoder"))));
        final var checkedNumericConversion = TypeDescriptor.functionOf("floatToInt",
                TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.lang.Option"), TypeDescriptor.ofInt()),
                TypeDescriptor.ofFloat());
        final var string = TypeDescriptor.ofString().toNullable();
        final var stringSearch = TypeDescriptor.functionOf("stringIndexOf",
                TypeDescriptor.ofInt(), string, string);
        final var stringLastSearch = TypeDescriptor.functionOf("stringLastIndexOf",
                TypeDescriptor.ofInt(), string, string);
        final var stringStartsWith = TypeDescriptor.functionOf("stringStartsWith",
                TypeDescriptor.ofBoolean(), string, string);
        final var stringEndsWith = TypeDescriptor.functionOf("stringEndsWith",
                TypeDescriptor.ofBoolean(), string, string);
        final var floatPredicate = TypeDescriptor.functionOf("floatPredicate",
                TypeDescriptor.ofBoolean(), TypeDescriptor.ofFloat());
        final var stringLength = TypeDescriptor.functionOf("stringLength", TypeDescriptor.ofInt(), string);
        final var stringIsEmpty = TypeDescriptor.functionOf("stringIsEmpty", TypeDescriptor.ofBoolean(), string);
        final var stringSubstring = TypeDescriptor.functionOf("stringSubstring",
                string, string, TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        final var stringClass = ClassDesc.of("java.lang.String");
        final var stringCodePointLength = TypeDescriptor.functionOf("stringCodePointLength",
                TypeDescriptor.ofInt(), string);
        final var stringCodePointAt = TypeDescriptor.functionOf("stringCodePointAt",
                TypeDescriptor.ofInt(), string, TypeDescriptor.ofInt());
        final var stringSubstringByCodePoints = TypeDescriptor.functionOf("stringSubstringByCodePoints",
                string, string, TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        final var stringIndexOfCodePoint = TypeDescriptor.functionOf("stringIndexOfCodePoint",
                TypeDescriptor.ofInt(), string, TypeDescriptor.ofChar());
        final var stringFirstMalformedOffset = TypeDescriptor.functionOf("stringFirstMalformedOffset",
                TypeDescriptor.ofInt(), string);
        final var stringCodePointAtOffset = TypeDescriptor.functionOf("stringCodePointAtOffset",
                TypeDescriptor.ofChar(), string, TypeDescriptor.ofInt());
        final var stringCodePointWidthAtOffset = TypeDescriptor.functionOf("stringCodePointWidthAtOffset",
                TypeDescriptor.ofInt(), string, TypeDescriptor.ofInt());
        final var charToInt = TypeDescriptor.functionOf("charToInt",
                TypeDescriptor.ofInt(), TypeDescriptor.ofChar());
        final var intToChar = TypeDescriptor.functionOf("intToChar",
                TypeDescriptor.ofChar(), TypeDescriptor.ofInt());
        final var charToString = TypeDescriptor.functionOf("charToString",
                TypeDescriptor.ofString(), TypeDescriptor.ofChar());
        final var doubleClass = ClassDesc.of("java.lang.Double");
        return new FunctionBindingRegistry(Map.ofEntries(
                Map.entry("zeron.lang.intToFloat",
                        new Binding(numericConversion, new IntrinsicBinding(IntrinsicId.INT_TO_FLOAT))),
                Map.entry("zeron.lang.intToLong",
                        new Binding(intToLongConversion, new IntrinsicBinding(IntrinsicId.INT_TO_LONG))),
                Map.entry("zeron.lang.longToInt",
                        new Binding(longToIntConversion, new IntrinsicBinding(IntrinsicId.LONG_TO_INT_OPTION))),
                Map.entry("zeron.lang.intToByteUnchecked",
                        new Binding(intToByteConversion, new IntrinsicBinding(IntrinsicId.INT_TO_BYTE))),
                Map.entry("zeron.lang.byteToInt",
                        new Binding(byteToIntConversion, new IntrinsicBinding(IntrinsicId.BYTE_TO_INT))),
                Map.entry("zeron.io.text.stringGetBytes",
                        new Binding(stringGetBytes, new InstanceMethod(ClassDesc.of("java.lang.String"),
                                "getBytes", MethodTypeDesc.of(ClassDesc.ofDescriptor("[B"),
                                        ClassDesc.of("java.nio.charset.Charset"))))),
                Map.entry("zeron.io.text.utf8Decoder",
                        new Binding(utf8Decoder, new StaticFieldInstanceMethod(
                                ClassDesc.of("java.nio.charset.StandardCharsets"), "UTF_8",
                                ClassDesc.of("java.nio.charset.Charset"),
                                ClassDesc.of("java.nio.charset.Charset"), "newDecoder",
                                MethodTypeDesc.of(ClassDesc.of("java.nio.charset.CharsetDecoder"))))),
                Map.entry("zeron.io.characterCodingExceptionAsIOException",
                        new Binding(codingExceptionConversion, new IntrinsicBinding(
                                IntrinsicId.CHARACTER_CODING_EXCEPTION_TO_IO_EXCEPTION))),
                Map.entry("zeron.lang.floatToInt",
                        new Binding(checkedNumericConversion,
                                new IntrinsicBinding(IntrinsicId.FLOAT_TO_INT_OPTION))),
                Map.entry("zeron.lang.floatIsNaN",
                        new Binding(floatPredicate, new StaticMethod(doubleClass, "isNaN",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_double)))),
                Map.entry("zeron.lang.floatIsFinite",
                        new Binding(floatPredicate, new StaticMethod(doubleClass, "isFinite",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_double)))),
                Map.entry("zeron.math.mathAbsInt",
                        mathBinding("mathAbsInt", "abs", TypeDescriptor.ofInt(), TypeDescriptor.ofInt())),
                Map.entry("zeron.math.mathAbsLong",
                        mathBinding("mathAbsLong", "abs", TypeDescriptor.ofLong(), TypeDescriptor.ofLong())),
                Map.entry("zeron.math.mathAbsFloat",
                        mathBinding("mathAbsFloat", "abs", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathMinInt",
                        mathBinding("mathMinInt", "min", TypeDescriptor.ofInt(),
                                TypeDescriptor.ofInt(), TypeDescriptor.ofInt())),
                Map.entry("zeron.math.mathMinLong",
                        mathBinding("mathMinLong", "min", TypeDescriptor.ofLong(),
                                TypeDescriptor.ofLong(), TypeDescriptor.ofLong())),
                Map.entry("zeron.math.mathMinFloat",
                        mathBinding("mathMinFloat", "min", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathMaxInt",
                        mathBinding("mathMaxInt", "max", TypeDescriptor.ofInt(),
                                TypeDescriptor.ofInt(), TypeDescriptor.ofInt())),
                Map.entry("zeron.math.mathMaxLong",
                        mathBinding("mathMaxLong", "max", TypeDescriptor.ofLong(),
                                TypeDescriptor.ofLong(), TypeDescriptor.ofLong())),
                Map.entry("zeron.math.mathMaxFloat",
                        mathBinding("mathMaxFloat", "max", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathClampInt",
                        mathBinding("mathClampInt", "clamp", TypeDescriptor.ofInt(),
                                TypeDescriptor.ofLong(), TypeDescriptor.ofInt(), TypeDescriptor.ofInt())),
                Map.entry("zeron.math.mathClampLong",
                        mathBinding("mathClampLong", "clamp", TypeDescriptor.ofLong(),
                                TypeDescriptor.ofLong(), TypeDescriptor.ofLong(), TypeDescriptor.ofLong())),
                Map.entry("zeron.math.mathClampFloat",
                        mathBinding("mathClampFloat", "clamp", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatSqrt",
                        mathBinding("mathFloatSqrt", "sqrt", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatPow",
                        mathBinding("mathFloatPow", "pow", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatExp",
                        mathBinding("mathFloatExp", "exp", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatLn",
                        mathBinding("mathFloatLn", "log", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatLog10",
                        mathBinding("mathFloatLog10", "log10", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatSin",
                        mathBinding("mathFloatSin", "sin", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatCos",
                        mathBinding("mathFloatCos", "cos", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatTan",
                        mathBinding("mathFloatTan", "tan", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatAsin",
                        mathBinding("mathFloatAsin", "asin", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatAcos",
                        mathBinding("mathFloatAcos", "acos", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatAtan",
                        mathBinding("mathFloatAtan", "atan", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatAtan2",
                        mathBinding("mathFloatAtan2", "atan2", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatFloor",
                        mathBinding("mathFloatFloor", "floor", TypeDescriptor.ofFloat(),
                                TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatCeil",
                        mathBinding("mathFloatCeil", "ceil", TypeDescriptor.ofFloat(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.math.mathFloatRound",
                        mathBinding("mathFloatRound", "round", TypeDescriptor.ofLong(), TypeDescriptor.ofFloat())),
                Map.entry("zeron.lang.stringLength",
                        new Binding(stringLength, new InstanceMethod(stringClass, "length",
                                MethodTypeDesc.of(ConstantDescs.CD_int)))),
                Map.entry("zeron.lang.stringIsEmpty",
                        new Binding(stringIsEmpty, new InstanceMethod(stringClass, "isEmpty",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean)))),
                Map.entry("zeron.lang.stringIndexOf",
                        new Binding(stringSearch, new InstanceMethod(stringClass, "indexOf",
                                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_String)))),
                Map.entry("zeron.lang.stringLastIndexOf",
                        new Binding(stringLastSearch, new InstanceMethod(stringClass, "lastIndexOf",
                                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_String)))),
                Map.entry("zeron.lang.stringStartsWith",
                        new Binding(stringStartsWith, new InstanceMethod(stringClass, "startsWith",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String)))),
                Map.entry("zeron.lang.stringEndsWith",
                        new Binding(stringEndsWith, new InstanceMethod(stringClass, "endsWith",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String)))),
                Map.entry("zeron.lang.stringSubstring",
                        new Binding(stringSubstring, new InstanceMethod(stringClass, "substring",
                                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int,
                                        ConstantDescs.CD_int)))),
                Map.entry("zeron.lang.stringCodePointLength",
                        intrinsicBinding(stringCodePointLength, IntrinsicId.STRING_CODE_POINT_LENGTH)),
                Map.entry("zeron.lang.stringCodePointAt",
                        intrinsicBinding(stringCodePointAt, IntrinsicId.STRING_CODE_POINT_AT)),
                Map.entry("zeron.lang.stringSubstringByCodePoints",
                        intrinsicBinding(stringSubstringByCodePoints,
                                IntrinsicId.STRING_SUBSTRING_BY_CODE_POINTS)),
                Map.entry("zeron.lang.stringIndexOfCodePoint",
                        intrinsicBinding(stringIndexOfCodePoint, IntrinsicId.STRING_INDEX_OF_CODE_POINT)),
                Map.entry("zeron.lang.stringFirstMalformedOffset",
                        intrinsicBinding(stringFirstMalformedOffset, IntrinsicId.STRING_FIRST_MALFORMED_OFFSET)),
                Map.entry("zeron.lang.stringCodePointAtOffset",
                        intrinsicBinding(stringCodePointAtOffset, IntrinsicId.STRING_CODE_POINT_AT_OFFSET)),
                Map.entry("zeron.lang.stringCodePointWidthAtOffset",
                        intrinsicBinding(stringCodePointWidthAtOffset,
                                IntrinsicId.STRING_CODE_POINT_WIDTH_AT_OFFSET)),
                Map.entry("zeron.lang.charToInt",
                        intrinsicBinding(charToInt, IntrinsicId.CHAR_TO_INT)),
                Map.entry("zeron.lang.intToChar",
                        intrinsicBinding(intToChar, IntrinsicId.INT_TO_CHAR)),
                Map.entry("zeron.lang.charToString",
                        intrinsicBinding(charToString, IntrinsicId.CHAR_TO_STRING))));
    }

    private static Binding intrinsicBinding(final FunctionDescriptor signature, final IntrinsicId id) {
        return new Binding(signature, new IntrinsicBinding(id));
    }

    private static Binding mathBinding(final String functionName,
                                       final String methodName,
                                       final TypeDescriptor returnType,
                                       final TypeDescriptor... parameterTypes) {
        final var parameterClasses = Arrays.stream(parameterTypes)
                .map(TypeDescriptor::toJavaClassDesc)
                .toArray(ClassDesc[]::new);
        return new Binding(TypeDescriptor.functionOf(functionName, returnType, parameterTypes),
                new StaticMethod(ClassDesc.of("java.lang.Math"), methodName,
                        MethodTypeDesc.of(TypeDescriptor.toJavaClassDesc(returnType), parameterClasses)));
    }
}