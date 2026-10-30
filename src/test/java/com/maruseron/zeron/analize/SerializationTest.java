package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public final class SerializationTest {
    @Test
    public void derivesCombinedCodecEvidenceForAggregates() {
        final var source = """
                import zeron.serialization.Codec;
                import zeron.serialization.Decode;
                import zeron.serialization.Encode;
                public type TextPayload deriving Codec = (text: String, count: Int);
                public type Status deriving Encode, Decode = Active | Inactive;
                public data class UserId(value: Int) deriving Codec {}
                public data class Box<T: nullable + mut + Encode<T> + Decode<T>>(value: T) deriving Codec {}
                fn requireCodec<T: mut + Codec<T>>(value: T): Unit = ();
                fn check(): Unit {
                    requireCodec(TextPayload.new("value", 2));
                    requireCodec(Status.Active());
                    requireCodec(UserId.new(1));
                    requireCodec(Box<Int>.new(1));
                }
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "SerializationEvidence", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void rejectsCodecDerivationForOrdinaryClasses() {
        final var source = """
                class Ordinary deriving Codec {
                    value: Int = 1;
                }
                """;
        assertThrows(ResolutionError.class,
                () -> new CompilationService(parse(source), "InvalidSerializationDerivation").resolve());
    }

    @Test
    public void requiresComponentCapabilitiesForCodecDerivation() {
        final var source = """
                import zeron.serialization.Codec;
                class Opaque {
                    value: Int = 1;
                }
                public type OpaquePayload deriving Codec = (value: Opaque, count: Int);
                fn requireCodec<T: mut + Codec<T>>(value: T): Unit = ();
                fn check(): Unit = requireCodec(OpaquePayload.new(Opaque.new(), 1));
                """;
        assertThrows(ResolutionError.class,
                () -> CompilationService.forCompilationUnits(
                        java.util.List.of(parseUnit(source)), "UnsupportedSerializationComponent", "",
                        java.util.List.of(), true, java.util.List.of()).resolve());
    }

    private static java.util.List<Stmt> parse(final String source) {
        final var result = Parser.of(Scanner.from(source, "SerializationTest.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("SerializationTest.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit().declarations();
    }

    private static CompilationUnit parseUnit(final String source) {
        final var result = Parser.of(Scanner.from(source, "SerializationTest.zn").scanTokens())
                .parseCompilationUnitWithDiagnostics("SerializationTest.zn");
        if (!result.diagnostics().isEmpty()) {
            throw new AssertionError(result.diagnostics().toString());
        }
        return result.compilationUnit();
    }
}
