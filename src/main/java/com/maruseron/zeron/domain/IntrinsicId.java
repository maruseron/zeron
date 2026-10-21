package com.maruseron.zeron.domain;

public enum IntrinsicId {
    ARRAY_LITERAL("zeron.array.literal.v1"),
    ARRAY_FILL("zeron.array.fill.v1"),
    ARRAY_ALLOC("zeron.array.allocate.v1"),
    ARRAY_CLEAR_SLOT("zeron.array.clear-slot.v1"),
    OPTION_UNWRAP_SOME("zeron.option.unwrap-some.v1"),
    INT_TO_FLOAT("zeron.numeric.int-to-float.v1"),
    INT_TO_LONG("zeron.numeric.int-to-long.v1"),
    FLOAT_TO_INT_OPTION("zeron.numeric.float-to-int-option.v1"),
    LONG_TO_INT_OPTION("zeron.numeric.long-to-int-option.v1"),
    ARRAY_LENGTH("zeron.array.length.v1"),
    ARRAY_READ("zeron.array.read.v1"),
    ARRAY_WRITE("zeron.array.write.v1"),
    BYTE_ARRAY_ALLOC("zeron.byte-array.allocate.v1"),
    BYTE_ARRAY_LENGTH("zeron.byte-array.length.v1"),
    BYTE_ARRAY_READ("zeron.byte-array.read.v1"),
    BYTE_ARRAY_WRITE("zeron.byte-array.write.v1"),
    INT_TO_BYTE("zeron.numeric.int-to-byte.v1"),
    BYTE_TO_INT("zeron.numeric.byte-to-int.v1"),
    CHARACTER_CODING_EXCEPTION_TO_IO_EXCEPTION("zeron.io.character-coding-exception-to-io-exception.v1"),
    STRING_CODE_POINT_LENGTH("zeron.string.code-point-length.v1"),
    STRING_CODE_POINT_AT("zeron.string.code-point-at.v1"),
    STRING_SUBSTRING_BY_CODE_POINTS("zeron.string.substring-by-code-points.v1"),
    STRING_INDEX_OF_CODE_POINT("zeron.string.index-of-code-point.v1"),
    STRING_FIRST_MALFORMED_OFFSET("zeron.string.first-malformed-offset.v1"),
    STRING_CODE_POINT_AT_OFFSET("zeron.string.code-point-at-offset.v1"),
    STRING_CODE_POINT_WIDTH_AT_OFFSET("zeron.string.code-point-width-at-offset.v1"),
    CHAR_TO_INT("zeron.char.to-int.v1"),
    INT_TO_CHAR("zeron.char.from-int.v1"),
    CHAR_TO_STRING("zeron.char.to-string.v1"),
    BIG_INTEGER_FROM_LITERAL_TEXT("zeron.numeric.big-integer-from-literal-text.v1");

    private final String stableName;

    IntrinsicId(final String stableName) {
        this.stableName = stableName;
    }

    public String stableName() {
        return stableName;
    }
}