package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ExplanationFormatterTest {
    @Test
    public void formatsExplanationHeadingsInlineCodeAndFencedExamples() {
        final var markdown = """
                # Error code ZR9000: Example title

                A message with `inline` text.

                ## Typical example

                ```zeron
                fn run() {}
                ```

                ## Suggested fix

                Use the replacement.
                """;

        final var formatted = ExplanationFormatter.format(markdown);

        assertEquals(String.join(System.lineSeparator(),
                "ZR9000: Example title",
                "",
                "A message with inline text.",
                "",
                "Typical example:",
                "",
                "    fn run() {}",
                "",
                "Suggested fix:",
                "",
                "Use the replacement.",
                ""), formatted);
    }

    @Test
    public void wrapsLongParagraphsToTerminalWidth() {
        final var markdown = """
                # Error code ZR9001: Long paragraph

                This paragraph has enough words to wrap onto a second line at the formatter's
                fixed terminal width, while preserving a normal space between source lines.
                """;

        final var formatted = ExplanationFormatter.format(markdown);

        assertEquals(String.join(System.lineSeparator(),
                "ZR9001: Long paragraph",
                "",
                "This paragraph has enough words to wrap onto a second line at the formatter's",
                "fixed terminal width, while preserving a normal space between source lines.",
                ""), formatted);
    }

    @Test
    public void colorsOnlyZeronCodeBlocksWhenEnabled() {
        final var markdown = """
                A plain paragraph.

                ```zeron
                let answer = 42; // example
                ```

                ```text
                let this stay plain
                ```
                """;

        final var plain = ExplanationFormatter.format(markdown);
        final var colored = ExplanationFormatter.format(markdown, true);

        assertFalse(plain.contains("\u001b["));
        assertTrue(colored.contains("\u001b[35mlet\u001b[0m"));
        assertTrue(colored.contains("\u001b[36m42\u001b[0m"));
        assertTrue(colored.contains("\u001b[90m// example\u001b[0m"));
        assertTrue(colored.contains("A plain paragraph."));
        assertTrue(colored.contains("    let this stay plain"));
    }

    @Test
    public void leavesUnlabelledAndUnknownLanguageFencesUncolored() {
        final var markdown = """
                ```text
                let value = 1;
                ```

                ```
                let another = 2;
                ```
                """;

        assertFalse(ExplanationFormatter.format(markdown, true).contains("\u001b["));
    }
}
