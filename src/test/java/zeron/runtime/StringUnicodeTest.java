package zeron.runtime;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class StringUnicodeTest {
    @Test
    public void splitsLiteralSeparatorsAndPreservesEmptyFields() {
        assertEquals(List.of("a", "b", ""), List.of(StringUnicode.split("a,b,", ",")));
        assertEquals(List.of("", "a"), List.of(StringUnicode.split(",a", ",")));
        assertEquals(List.of("a", "", "b"), List.of(StringUnicode.split("a,,b", ",")));
        assertEquals(List.of(""), List.of(StringUnicode.split("", ",")));
        assertEquals(List.of("a", "b"), List.of(StringUnicode.split("a::b", "::")));
    }

    @Test
    public void splitsEmptySeparatorIntoUnicodeScalars() {
        assertEquals(List.of("a", "b", "c"), List.of(StringUnicode.split("abc", "")));
        assertEquals(List.of(), List.of(StringUnicode.split("", "")));
        assertEquals(List.of("\uFFFD", "\uD83D\uDE00"),
                List.of(StringUnicode.split("\uD800\uD83D\uDE00", "")));
    }

    @Test
    public void recognizesAllLineEndingsWithoutATrailingEmptyLine() {
        assertEquals(List.of("a", "b", "c"), List.of(StringUnicode.lines("a\r\nb\rc\n")));
        assertEquals(List.of("a", "", "b"), List.of(StringUnicode.lines("a\n\nb")));
        assertEquals(List.of("a"), List.of(StringUnicode.lines("a\n")));
        assertEquals(List.of("single"), List.of(StringUnicode.lines("single")));
        assertEquals(List.of(), List.of(StringUnicode.lines("")));
    }

    @Test
    public void reportsCharactersByCodeUnitIndex() {
        assertEquals('a', StringUnicode.charCodePointAt("abc", 0));
        assertEquals('c', StringUnicode.charCodePointAt("abc", 2));
        assertEquals(-1, StringUnicode.charCodePointAt("abc", 3));
        assertEquals(-1, StringUnicode.charCodePointAt("abc", -1));
        assertEquals(0x1F600, StringUnicode.charCodePointAt("\uD83D\uDE00a", 0));
        assertEquals(-1, StringUnicode.charCodePointAt("\uD83D\uDE00a", 1));
        assertEquals('a', StringUnicode.charCodePointAt("\uD83D\uDE00a", 2));
        assertEquals(0xFFFD, StringUnicode.charCodePointAt("\uD800", 0));
    }

    @Test
    public void trimsUnicodeWhitespaceAndMapsCaseWithoutTheHostLocale() {
        assertEquals("padded", StringUnicode.trim("\u2003 padded \t"));
        assertEquals("padded \t", StringUnicode.trimStart("\u2003 padded \t"));
        assertEquals("\u2003 padded", StringUnicode.trimEnd("\u2003 padded \t"));
        assertTrue(StringUnicode.isBlank("\t \u2003"));
        assertTrue(StringUnicode.isBlank(""));
        assertFalse(StringUnicode.isBlank(" x "));
        assertEquals("a+b", StringUnicode.replace("a-b", "-", "+"));
        assertEquals("ZERON", StringUnicode.toUpperCase("zeron"));
        assertEquals("i", StringUnicode.toLowerCase("I"));
    }

    @Test
    public void reportsInvalidPatternsInsteadOfThrowing() {
        assertNull(StringUnicode.regexErrorMessage("[0-9]+"));
        assertNotNull(StringUnicode.regexErrorMessage("["));
        assertEquals(List.of("a", "b", "c"), List.of(StringUnicode.splitRegex("a1b22c", "[0-9]+")));
    }

    @Test
    public void parsesDecimalIntegerText() {
        assertTrue(StringUnicode.isDecimalInt("42"));
        assertTrue(StringUnicode.isDecimalInt("-42"));
        assertTrue(StringUnicode.isDecimalInt("+42"));
        assertFalse(StringUnicode.isDecimalInt("4x"));
        assertFalse(StringUnicode.isDecimalInt(" 42"));
        assertFalse(StringUnicode.isDecimalInt(""));
        assertFalse(StringUnicode.isDecimalInt("99999999999"));
        assertEquals(-42, StringUnicode.parseInt("-42"));
    }

    @Test
    public void parsesDecimalFloatText() {
        assertTrue(StringUnicode.isDecimalFloat("2.5e2"));
        assertTrue(StringUnicode.isDecimalFloat("-.5"));
        assertTrue(StringUnicode.isDecimalFloat("5."));
        assertFalse(StringUnicode.isDecimalFloat("1.2.3"));
        assertFalse(StringUnicode.isDecimalFloat(" 2.5"));
        assertFalse(StringUnicode.isDecimalFloat("1e400"));
        assertFalse(StringUnicode.isDecimalFloat("NaN"));
        assertEquals(250.0, StringUnicode.parseFloat("2.5e2"), 0.0);
    }
}
