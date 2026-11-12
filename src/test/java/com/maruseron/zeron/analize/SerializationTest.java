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
                public data class Box<T: nullable + mut + Encode<T, {}> + Decode<T, {}>>(value: T) deriving Codec {}
                fn requireCodec<T: mut + Codec<T, {}>>(value: T): Unit = ();
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
    public void matchesExplicitEncodeWitnessThroughRowBound() {
        final var source = """
                import zeron.serialization.Encode;
                import zeron.serialization.EncodeError;
                import zeron.serialization.Encoder;
                import zeron.lang.Result;
                public type Payload = (text: String, count: Int);
                witness Encode<Payload, {}> for Payload {
                    static fn encode(value: Payload, encoder: &Encoder): Result<Unit, EncodeError> =
                        encoder.writeString(value.text);
                }
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn check(): Unit = requireEncode(Payload.new("value", 2));
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "ExplicitEncodeWitness", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void derivesEncodeForTransparentDataClass() {
        final var source = """
                import zeron.serialization.Encode;
                public data class UserId(value: Int) deriving Encode {}
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn check(): Unit = requireEncode(UserId.new(1));
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "DataClassEncode", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void resolvesBuiltinCodecFromDirectionalEvidence() {
        final var source = """
                import zeron.serialization.Codec;
                fn requireCodec<T: mut + Codec<T, {}>>(value: T): Unit = ();
                fn check(): Unit {
                    requireCodec("value");
                    requireCodec(1);
                    requireCodec(true);
                    requireCodec(1.5);
                }
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "BuiltinCompositeCodec", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void resolvesBuiltinStringEncodeWitness() {
        final var source = """
                import zeron.serialization.Encode;
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn check(): Unit = requireEncode("value");
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "BuiltinStringEncode", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void derivesEncodeForTwoIntegerComponents() {
        final var source = """
                import zeron.serialization.Encode;
                public type Pair deriving Encode = (left: Int, right: Int);
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn check(): Unit = requireEncode(Pair.new(1, 2));
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "IntegerPairEncode", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void derivesEncodeForStringComponent() {
        final var source = """
                import zeron.serialization.Encode;
                public data class Named(text: String) deriving Encode {}
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn check(): Unit = requireEncode(Named.new("value"));
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "StringComponentEncode", "", java.util.List.of(),
                true, java.util.List.of()).resolve();
    }

    @Test
    public void derivesEachCapabilityIndependently() {
        final var source = """
                import zeron.serialization.Decode;
                import zeron.serialization.Encode;
                import zeron.serialization.Shape;
                public type TextPayload deriving Encode, Decode, Shape = (text: String, count: Int);
                fn requireEncode<T: mut + Encode<T, {}>>(value: T): Unit = ();
                fn requireDecode<T: mut + Decode<T, {}>>(value: T): Unit = ();
                fn requireShape<T: mut + Shape<T>>(value: T): Unit = ();
                fn encodeCheck(): Unit = requireEncode(TextPayload.new("value", 2));
                fn decodeCheck(): Unit = requireDecode(TextPayload.new("value", 2));
                fn shapeCheck(): Unit = requireShape(TextPayload.new("value", 2));
                """;
        CompilationService.forCompilationUnits(
                java.util.List.of(parseUnit(source)), "SerializationCapabilities", "", java.util.List.of(),
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
                fn requireCodec<T: mut + Codec<T, {}>>(value: T): Unit = ();
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
