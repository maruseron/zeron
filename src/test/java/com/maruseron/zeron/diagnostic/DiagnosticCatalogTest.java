package com.maruseron.zeron.diagnostic;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DiagnosticCatalogTest {
    @Test
    public void catalogsEachExplanationResourceWithUniqueCodeAndMatchingTitle() throws IOException {
        final var seenCodes = new HashSet<DiagnosticCode>();
        for (final var entry : DiagnosticCatalog.entries()) {
            assertTrue("Duplicate diagnostic code " + entry.code(), seenCodes.add(entry.code()));
            try (var stream = getClass().getClassLoader().getResourceAsStream(entry.explanationResource())) {
                assertTrue("Missing explanation resource " + entry.explanationResource(), stream != null);
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    assertEquals("# Error code " + entry.code() + ": " + entry.title(), reader.readLine());
                }
            }
            assertEquals(entry, DiagnosticCatalog.find(entry.code()).orElseThrow());
        }
        assertEquals(34, DiagnosticCatalog.entries().size());
    }

    @Test
    public void doesNotResolveUnknownCodes() {
        assertFalse(DiagnosticCatalog.find(new DiagnosticCode("ZR9999")).isPresent());
    }
}
