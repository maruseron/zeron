package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnsiSyntaxHighlighterTest {
    @Test
    public void highlightsCurrentLiteralAndKeywordTokens() {
        final var highlighted = AnsiSyntaxHighlighter.highlight(
                "'x' 42L object throws using");

        assertTrue(highlighted.contains(AnsiColor.apply("'x'", "32")));
        assertTrue(highlighted.contains(AnsiColor.apply("42L", "36")));
        assertTrue(highlighted.contains(AnsiColor.apply("object", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("throws", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("using", "35")));
    }

    @Test
    public void highlightsContextualKeywordsInTheirSyntaxPositions() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                class Record deriving Eq, Hashable {
                    public property value: Int {
                        get = 1;
                    }
                }
                fn handled(): Int = handle (identity(1)) with {
                    case Failure(error) with suppressed as failures -> 0;
                }
                """);

        assertTrue(highlighted.contains(AnsiColor.apply("deriving", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("get", "35")));
        assertEquals(2, occurrences(highlighted, AnsiColor.apply("with", "35")));
    }

    @Test
    public void highlightsUseSiteProjectionKeywords() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                let producers: Box<out Animal> = source;
                let consumers: Box<in Dog> = sink;
                """);

        assertTrue(highlighted.contains(AnsiColor.apply("out", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("in", "35")));
    }

    @Test
    public void highlightsEffectAndNullableAdmissibilityMarkersContextually() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                public effect IoFailure {}
                fn resolveEffects<E: effect, T: nullable + Display<T>>(value: T): String =
                    value.toString();
                let nullable = 1;
                """);

        assertEquals(2, occurrences(highlighted, AnsiColor.apply("effect", "35")));
        assertEquals(1, occurrences(highlighted, AnsiColor.apply("nullable", "35")));
    }

    @Test
    public void highlightsYieldAndCollectOnlyAsForExpressionResults() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                let lazy = for item in items yield item;
                let eager = for item in items collect item into sink;
                let yield = 1;
                let collect = 2;
                """);

        assertEquals(1, occurrences(highlighted, AnsiColor.apply("yield", "35")));
        assertEquals(1, occurrences(highlighted, AnsiColor.apply("collect", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("yield", "35") + " ="));
        assertFalse(highlighted.contains(AnsiColor.apply("collect", "35") + " ="));
    }

    @Test
    public void highlightsNamedAndAnonymousHandlerSyntaxContextually() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                public handler Named { case Failure(_) -> ""; }
                let inline = handler { case Failure(_) -> ""; };
                fn apply(value: String ~> {Failure} raises {}): String =
                    handle (raise Failure.new()) with value;
                """);

        assertEquals(2, occurrences(highlighted, AnsiColor.apply("handler", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("with", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("raises", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("~", "33") + AnsiColor.apply(">", "33")));
    }

    @Test
    public void highlightsHigherKindedImplementationBlocksContextually() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                class Box<A> {
                    implementation Functor<Box<_>> {
                        fn map<A, B>(this: Box<A>, transform: (A) -> B): Box<B> = this;
                    }
                }
                let implementation = 1;
                """);

        assertEquals(1, occurrences(highlighted, AnsiColor.apply("implementation", "35")));
    }

    @Test
    public void leavesOutAsAnIdentifierOutsideTypeProjections() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                let out = 1;
                fn read(out: Int): Int = out;
                """);

        assertFalse(highlighted.contains(AnsiColor.apply("out", "35")));
    }

    @Test
    public void highlightsNumericSuffixSyntaxContextually() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                import suffix sample.Meter.m;
                class Meter {
                    suffix m {
                        integer: string fromText;
                    }
                }
                fn value(): Int = 5m;
                """);

        assertEquals(2, occurrences(highlighted, AnsiColor.apply("suffix", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("integer", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("string", "35")));
        assertTrue(highlighted.contains(AnsiColor.apply("5", "36")));
        assertTrue(highlighted.contains(AnsiColor.apply("m", "36")));
    }

    @Test
    public void leavesContextualWordsAsIdentifiersOutsideTheirSyntaxPositions() {
        final var highlighted = AnsiSyntaxHighlighter.highlight("""
                let deriving = 1;
                let handler = 0;
                let with = 2;
                let get = 3;
                let suffix = 4;
                let integer: Int = 5;
                let decimal: Float = 6.0;
                let string: String = "text";
                fn notAHandler(): Unit = call() with { };
                """);

        assertFalse(highlighted.contains(AnsiColor.apply("deriving", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("handler", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("with", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("get", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("suffix", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("integer", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("decimal", "35")));
        assertFalse(highlighted.contains(AnsiColor.apply("string", "35")));
    }

    private static int occurrences(final String source, final String value) {
        var count = 0;
        var index = source.indexOf(value);
        while (index >= 0) {
            count++;
            index = source.indexOf(value, index + value.length());
        }
        return count;
    }
}
