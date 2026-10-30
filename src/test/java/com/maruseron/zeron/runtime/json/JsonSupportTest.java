package com.maruseron.zeron.runtime.json;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class JsonSupportTest {
    @Test
    public void parsesAndSerializesValuesWithoutChangingNumberLexemesOrObjectOrder() {
        final var input = """
                {"z":-0.00e+12,"text":"line\\n\\uD83D\\uDE03","items":[null,true,2]}
                """.strip();
        final var parsed = JsonSupport.parse(input);

        assertTrue(parsed.isSuccess());
        assertEquals("{\"z\":-0.00e+12,\"text\":\"line\\n"
                        + new String(Character.toChars(0x1f603))
                        + "\",\"items\":[null,true,2]}",
                JsonSupport.stringify(parsed.value()).text());
        assertEquals("z", parsed.value().objectValue().keyAt(0));
        assertEquals("-0.00e+12",
                parsed.value().objectValue().get("z").numberLexeme());
    }

    @Test
    public void rejectsMalformedSyntaxDuplicateKeysAndUnpairedSurrogatesAtTheirLocations() {
        assertError("01", "Leading zero in JSON number", 1, 1, 2);
        assertError("{\"x\":1,\"x\":2}", "Duplicate object key 'x'", 7, 1, 8);
        assertError("true\n?", "Unexpected trailing input", 5, 2, 1);
        assertError("\"\\q\"", "Invalid escape sequence", 2, 1, 3);

        final var escapedUnpaired = new String(new char[]{'"', '\\', 'u', 'D', '8', '0', '0', '"'});
        assertError(escapedUnpaired, "Unpaired high surrogate escape", 1, 1, 2);
        final var rawUnpaired = new String(new char[]{'"', '\ud800', '"'});
        assertError(rawUnpaired, "Unpaired high surrogate in string", 1, 1, 2);
    }

    @Test
    public void enforcesTextAndNestingLimits() {
        final var tooDeep = "[".repeat(JsonSupport.MAX_NESTING_DEPTH + 1)
                + "0" + "]".repeat(JsonSupport.MAX_NESTING_DEPTH + 1);
        final var depthResult = JsonSupport.parse(tooDeep);
        assertFalse(depthResult.isSuccess());
        assertTrue(depthResult.error().message().contains("maximum depth"));

        final var tooLarge = "\"" + "x".repeat(JsonSupport.MAX_TEXT_LENGTH) + "\"";
        final var lengthResult = JsonSupport.parse(tooLarge);
        assertFalse(lengthResult.isSuccess());
        assertTrue(lengthResult.error().message().contains("maximum length"));
    }

    @Test
    public void checksIntegerConversionsAndRejectsInvalidNumbers() {
        assertNull(JsonSupport.number("01").value());
        assertEquals(Integer.valueOf(100), JsonValue.numberValue("1e2").intValue());
        assertEquals(Long.valueOf(-9L), JsonValue.numberValue("-9.0").longValue());
        assertNull(JsonValue.numberValue("1.01").intValue());
        assertNull(JsonValue.numberValue("2147483648").intValue());
        assertNull(JsonValue.numberValue("1e1000000").intValue());
    }

    @Test
    public void preservesObjectInsertionOrderAndRejectsDuplicateBuilderKeys() {
        final var object = new JsonObject();
        assertTrue(object.add("first", JsonValue.numberValue("1")));
        assertTrue(object.add("second", JsonValue.numberValue("2")));
        assertFalse(object.add("first", JsonValue.numberValue("3")));
        assertEquals("{\"first\":1,\"second\":2}", JsonSupport.stringify(
                JsonValue.objectValue(object)).text());
    }

    @Test
    public void rejectsCyclicValuesAndInvalidSurrogatesDuringSerialization() {
        final var array = new JsonArray();
        array.add(JsonValue.arrayValue(array));
        final var cycle = JsonSupport.stringify(JsonValue.arrayValue(array));
        assertFalse(cycle.isSuccess());
        assertTrue(cycle.error().message().contains("Cyclic"));

        final var invalidString = JsonSupport.stringify(JsonValue.stringValue(
                new String(new char[]{'\udfff'})));
        assertFalse(invalidString.isSuccess());
        assertTrue(invalidString.error().message().contains("Unpaired low surrogate"));
    }

    private static void assertError(
            final String input, final String message, final int offset,
            final int line, final int column) {
        final var result = JsonSupport.parse(input);
        assertFalse(result.isSuccess());
        assertEquals(message, result.error().message());
        assertEquals(offset, result.error().offset());
        assertEquals(line, result.error().line());
        assertEquals(column, result.error().column());
    }
}
