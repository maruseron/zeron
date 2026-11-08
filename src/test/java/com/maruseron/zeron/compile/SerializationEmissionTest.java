package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/// Verifies the bytecode emitted for derived serialization operations by running it against
/// recording encoder and decoder implementations.
public final class SerializationEmissionTest {
    @Test
    public void derivedEncodeAndDecodeRunThroughStreamingEncoderAndDecoder() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "serialization" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-serialization-");
        final var unit = parse("Serialization.zn", """
                package %s;
                import zeron.lang.Result;
                import zeron.serialization.Decode;
                import zeron.serialization.DecodeError;
                import zeron.serialization.Decoder;
                import zeron.serialization.Encode;
                import zeron.serialization.EncodeError;
                import zeron.serialization.Encoder;
                import zeron.serialization.Shape;
                import zeron.serialization.ValueKind;
                import zeron.serialization.decode;
                import zeron.serialization.encode;

                public type Point deriving Encode, Decode, Shape = (x: Int, y: Int);
                public type Tag deriving Encode, Decode = Empty | Number(value: Int);

                public fn writePoint(value: Point, encoder: &Encoder): Result<Unit, EncodeError> =
                    encode(value, encoder);

                public fn readPoint(decoder: &Decoder): Result<Point, DecodeError> =
                    decode<{}, Point>(decoder);

                public fn writeTag(value: Tag, encoder: &Encoder): Result<Unit, EncodeError> =
                    encode(value, encoder);

                public fn readTag(decoder: &Decoder): Result<Tag, DecodeError> =
                    decode<{}, Tag>(decoder);

                fn requireShape<T: mut + Shape<T>>(value: T): ValueKind = T.shape();

                public fn pointShape(): ValueKind = requireShape(Point.new(1, 2));
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                final var pointClass = loader.loadClass(packageName + ".Point");
                final var tagClass = loader.loadClass(packageName + ".Tag");
                final var encoderClass = loader.loadClass("zeron.serialization.Encoder");
                final var decoderClass = loader.loadClass("zeron.serialization.Decoder");
                final var resultOk = loader.loadClass("zeron.lang.Result$$sum$Ok");
                final var optionSome = loader.loadClass("zeron.lang.Option$$sum$Some");
                final var optionNone = loader.loadClass("zeron.lang.Option$$sum$None");

                final var transcript = new ArrayList<String>();
                final var encoder = encoder(encoderClass, resultOk, transcript);

                final var point = pointClass.getConstructor(int.class, int.class).newInstance(3, 4);
                main.getMethod("writePoint", pointClass, encoderClass).invoke(null, point, encoder);
                assertEquals(List.of("beginRecord", "writeField:x", "writeInteger:3",
                        "writeField:y", "writeInteger:4", "endRecord"), transcript);

                final var numberClass = loader.loadClass(packageName + ".Tag$$sum$Number");
                final var number = numberClass.getConstructor(int.class).newInstance(5);
                transcript.clear();
                main.getMethod("writeTag", tagClass, encoderClass).invoke(null, number, encoder);
                assertEquals(List.of("beginAlternative:Number", "writeInteger:5", "endAlternative"),
                        transcript);

                final var decoder = decoder(decoderClass, resultOk, optionSome, optionNone,
                        List.of("x", "y"), List.of("7", "9"));
                final var decoded = main.getMethod("readPoint", decoderClass).invoke(null, decoder);
                final var decodedPoint = payload(resultOk, decoded);
                assertEquals(7, pointClass.getMethod("$zeron$get$x").invoke(decodedPoint));
                assertEquals(9, pointClass.getMethod("$zeron$get$y").invoke(decodedPoint));

                final var tagDecoder = decoder(decoderClass, resultOk, optionSome, optionNone,
                        List.of("Number"), List.of("5"));
                final var decodedTag = main.getMethod("readTag", decoderClass).invoke(null, tagDecoder);
                final var decodedNumber = payload(resultOk, decodedTag);
                assertEquals(numberClass, decodedNumber.getClass());
                assertEquals(5, decodedNumber.getClass().getMethod("$zeron$payload$0")
                        .invoke(decodedNumber));

                assertEquals("zeron.serialization.ValueKind$$sum$RecordKind",
                        main.getMethod("pointShape").invoke(null).getClass().getName());
            }
        } finally {
            deleteTree(output);
        }
    }

    private static Object encoder(final Class<?> encoderClass, final Class<?> resultOk,
                                  final List<String> transcript) throws Exception {
        final var okConstructor = resultOk.getConstructor(Object.class);
        return Proxy.newProxyInstance(encoderClass.getClassLoader(), new Class<?>[]{encoderClass},
                (proxy, method, arguments) -> {
                    final var name = method.getName();
                    if (name.equals("writeField")) {
                        transcript.add("writeField:" + arguments[0]);
                    } else if (name.equals("writeInteger")) {
                        transcript.add("writeInteger:" + arguments[0]);
                    } else if (name.equals("beginAlternative")) {
                        transcript.add("beginAlternative:" + arguments[0]);
                    } else {
                        transcript.add(name);
                    }
                    return okConstructor.newInstance((Object) null);
                });
    }

    private static Object decoder(final Class<?> decoderClass, final Class<?> resultOk,
                                  final Class<?> optionSome, final Class<?> optionNone,
                                  final List<String> fieldNames, final List<String> integers)
            throws Exception {
        final var okConstructor = resultOk.getConstructor(Object.class);
        final var someConstructor = optionSome.getConstructor(Object.class);
        final var none = optionNone.getField("$zeron$singleton").get(null);
        final var fields = new ArrayList<>(fieldNames);
        final var numbers = new ArrayList<>(integers);
        final var alternative = fields.isEmpty() ? null : fields.getFirst();
        return Proxy.newProxyInstance(decoderClass.getClassLoader(), new Class<?>[]{decoderClass},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "nextField" -> okConstructor.newInstance(fields.isEmpty()
                            ? none
                            : someConstructor.newInstance(fields.removeFirst()));
                    case "readAlternative" -> okConstructor.newInstance(alternative);
                    case "readInteger" -> okConstructor.newInstance(numbers.removeFirst());
                    default -> okConstructor.newInstance((Object) null);
                });
    }

    private static Object payload(final Class<?> resultOk, final Object result) throws Exception {
        final var payload = result.getClass().getMethod("$zeron$payload$0").invoke(result);
        if (payload == null) throw new AssertionError("Expected a decoded value, found a failure.");
        return payload;
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens())
                .parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
