package com.maruseron.zeron;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GeneratedDocumentationTest {
    @Test
    public void checkedInReferencePagesMatchTheSharedMetadata() throws Exception {
        assertEquals(Files.readString(TestPaths.projectFile("guide", "39-command-reference.md")),
                DocumentationGenerator.renderCommandReference());
        assertEquals(Files.readString(TestPaths.projectFile("guide", "40-manifest-reference.md")),
                DocumentationGenerator.renderManifestReference());
    }

    @Test
    public void everyCommandOptionAndManifestKeyIsDocumentedAndHasHelp() {
        final var commandReference = DocumentationGenerator.renderCommandReference();
        for (final var command : CliMetadata.COMMANDS) {
            assertTrue(command.name(), CliHelp.hasTopic(command.name()));
            assertTrue(command.name(), commandReference.contains("### `" + command.name() + "`"));
        }
        for (final var option : CliMetadata.OPTIONS) {
            assertTrue(option.name(), CliHelp.hasTopic(option.name()));
            assertTrue(option.name(), commandReference.contains("### `" + option.name() + "`"));
            if (!option.shortName().isEmpty()) assertTrue(option.shortName(), CliHelp.hasTopic(option.shortName()));
        }
        final var manifestReference = DocumentationGenerator.renderManifestReference();
        for (final var table : CliMetadata.MANIFEST_TABLES) {
            assertTrue(table.name(), manifestReference.contains("## `[" + table.name() + "]`"));
            for (final var key : table.keys()) {
                assertTrue(key.name(), manifestReference.contains("| `" + key.name() + "` |"));
            }
        }
        assertTrue(CliHelp.hasTopic("manifest"));
    }
}
