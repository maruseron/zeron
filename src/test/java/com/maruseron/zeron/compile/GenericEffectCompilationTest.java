package com.maruseron.zeron.compile;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GenericEffectCompilationTest {
    @Test
    public void exactApplicationsDispatchToMatchingHandlerCases() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var packageName = "genericEffects" + suffix;
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-generic-effect-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Failure<T: nullable> {
                    public property value: T;
                }
                fn handledInt(): Int = handle (raise Failure<Int>(42)) with {
                    case Failure<String> as failure -> 0;
                    case Failure<Int> as failure -> failure.value;
                };
                fn handledString(): Int = handle (raise Failure<String>("boom")) with {
                    case Failure<Int>(_) -> -1;
                    case Failure<String> as failure -> failure.value.length();
                };
                fn caughtByEffect(): Int = handle (raise Failure<Int>(9)) with {
                    case Effect -> 7;
                };
                fn mismatchedDescriptorFallsThrough(): Int = handle (raise Failure<Int>(9)) with {
                    case Failure<String> -> -1;
                    case Effect -> 7;
                };
                fn nullableIdentity(): Int = handle (raise Failure<Int?>(null)) with {
                    case Failure<Int> as failure -> -1;
                    case Failure<Int?> as failure -> failure.value ?? 5;
                };
                fn nestedIdentity(): Int = handle (raise Failure<Array<Int>>([1, 2, 3])) with {
                    case Failure<Array<Int>> as failure -> failure.value.length;
                };
                fn orAlternative(): Int = handle (raise Failure<Int>(11)) with {
                    case Failure<Int> | Failure<Int> as failure -> failure.value;
                };
                fn positionalPayload(): Int = handle (raise Failure<Int>(19)) with {
                    case Failure<Int>(value) -> value;
                };
                fn raiseInto<E: effect>(raised: E): Int raises E = raise raised;
                fn throughEffectVariable(): Int = handle (raiseInto(Failure<Int>(13))) with {
                    case Failure<Int> as failure -> failure.value;
                };
                public effect Boxed<T: nullable> {
                    public property value: T;
                    public constructor of(value: T) = Boxed<T>.new(value);
                }
                fn namedConstructorForwarding(): Int = handle (raise Boxed<Int>.of(17)) with {
                    case Boxed<Int> as boxed -> boxed.value;
                };
                public effect Ping<T> {}
                fn statelessGeneric(): Int = handle (raise Ping<Int>()) with {
                    case Ping<Int> -> 23;
                };
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(42, main.getMethod("handledInt").invoke(null));
                assertEquals(4, main.getMethod("handledString").invoke(null));
                assertEquals(7, main.getMethod("caughtByEffect").invoke(null));
                assertEquals(7, main.getMethod("mismatchedDescriptorFallsThrough").invoke(null));
                assertEquals(5, main.getMethod("nullableIdentity").invoke(null));
                assertEquals(3, main.getMethod("nestedIdentity").invoke(null));
                assertEquals(11, main.getMethod("orAlternative").invoke(null));
                assertEquals(19, main.getMethod("positionalPayload").invoke(null));
                assertEquals(13, main.getMethod("throughEffectVariable").invoke(null));
                assertEquals(17, main.getMethod("namedConstructorForwarding").invoke(null));
                assertEquals(23, main.getMethod("statelessGeneric").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void openApplicationsAreRejected() {
        final var unit = parse("Bad.zn", """
                effect Failure<T> {
                    public property value: T;
                }
                fn bad<T>(): Unit raises Failure<T> = raise Failure<T>(value);
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                "bad.Main", "bad", List.of(), true, List.of(),
                Path.of("target", "zeron-generic-effect-invalid"));
        final var resolution = compiler.resolveWithDiagnostics();
        assertTrue(resolution.errors().toString(), resolution.errors().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("must be closed")));
    }

    @Test
    public void appliedEffectIdentitySurvivesSeparateCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "genericEffectLibrary" + suffix;
        final var clientPackage = "genericEffectClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-generic-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "zeron-generic-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public effect Failure<T: nullable> {
                    public property value: T;
                }
                public fn fail(): Int raises Failure<Int> = raise Failure<Int>(21);
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(),
                    libraryOutput);
            final var libraryResolution = libraryCompiler.resolveWithDiagnostics();
            assertTrue(libraryResolution.errors().toString(), libraryResolution.errors().isEmpty());
            libraryCompiler.compile();

            final var index = com.maruseron.zeron.domain.ZeronLibraryIndex
                    .readFromDirectory(libraryOutput);
            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.Failure;
                    import %s.fail;
                    fn handled(): Int = handle (fail()) with {
                        case Failure<Int> as failure -> failure.value;
                    };
                    """.formatted(clientPackage, libraryPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(List.of(clientUnit),
                    clientPackage + ".Client", clientPackage, List.of(index), true, List.of(),
                    clientOutput);
            final var clientResolution = clientCompiler.resolveWithDiagnostics();
            assertTrue(clientResolution.errors().toString(), clientResolution.errors().isEmpty());
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(21, loader.loadClass(clientPackage + ".Client")
                        .getMethod("handled").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
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
