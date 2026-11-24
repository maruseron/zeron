package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;
import zeron.runtime.ContinuationProviderLoader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/// Level-3 cases receive a first-class `Cont`: a case that is not the exact tail form may do
/// work around `k.resume`, and the resume drives the protected body through the provider runtime.
public final class Level3ContinuationTest {
    @Test
    public void generalResumeCasesCompileToTheContinuationRuntime() throws Exception {
        final var packageName = "level3" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-level3-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;
                public effect Ask<T> responds T {}

                class FailingResource is Disposable {
                    state: &Array<Int>;
                    public constructor new;
                    public mut dispose(): Unit performs DisposeFailure {
                        this.state[0] = this.state[0] + 1;
                        perform DisposeFailure.new("cleanup");
                    }
                }

                fn compute(): Int performs Ask<Int> = perform Ask<Int>() + 22;
                public fn general(): Int = handle (compute()) with {
                    case Ask<Int>, k -> {
                        let resumed = k.resume(8);
                        yield resumed + 1;
                    };
                };
                public fn stored(): Int = handle (compute()) with {
                    case Ask<Int>, k -> {
                        let saved = k;
                        yield saved.resume(8);
                    };
                };
                public fn deep(): Int = handle (perform Ask<Int>() + perform Ask<Int>()) with {
                    case Ask<Int>, k -> {
                        let resumed = k.resume(5);
                        yield resumed;
                    };
                };
                public fn aborts(): Int = handle (perform Ask<Int>()) with {
                    case Ask<Int>, k -> 7;
                };
                public effect Failure {
                    public property message: String;
                }
                fn caseFailure(): Int performs Failure = handle (perform Ask<Int>()) with {
                    case Ask<Int>, k -> {
                        perform Failure.new("boom");
                        yield k.resume(8);
                    };
                };
                public fn escapedCaseEffect(): Int = handle (caseFailure()) with {
                    case Failure -> 99;
                };
                public fn cancelled(): Int performs DisposeFailure =
                    handle (compute()) with {
                        case Ask<Int>, k -> {
                            let before = k.isDone();
                            k.cancel();
                            let after = k.isDone();
                            yield if (before or not after) then 0 else 42;
                        };
                    };

                fn explicitCancelBody(): Int performs Ask<Int>, DisposeFailure {
                    let state: &Array<Int> = [0];
                    using (let fails = FailingResource.new(state)) {
                        perform Ask<Int>();
                    }
                    return 0;
                }

                public fn explicitCancelCleanup(): Int performs DisposeFailure {
                    return handle (explicitCancelBody()) with {
                        case Ask<Int>, k -> {
                            k.cancel();
                            yield 0;
                        };
                    };
                }

                public fn explicitCancelObserved(): Int =
                    handle (explicitCancelCleanup()) with {
                        case DisposeFailure -> 99;
                    };

                fn implicitAbortBody(): Int performs Ask<Int>, DisposeFailure {
                    let state: &Array<Int> = [0];
                    using (let fails = FailingResource.new(state)) {
                        perform Ask<Int>();
                    }
                    return 0;
                }

                public fn implicitAbortCleanup(): Int performs DisposeFailure {
                    return handle (implicitAbortBody()) with {
                        case Ask<Int>, k -> 7;
                    };
                }

                public effect Tick responds Unit {}
                fn ticks(count: Int): Int performs Tick, Ask<Int> {
                    let mut index = 0;
                    while (index < count) {
                        perform Tick.new();
                        index = index + 1;
                    }
                    return perform Ask<Int>();
                }
                public fn tailStack(): Int = handle (ticks(100000)) with {
                    case Tick, k -> k.resume(());
                    case Ask<Int>, k -> {
                        let saved = k;
                        yield saved.resume(7);
                    };
                };
                public fn tailBlock(): Int = handle (ticks(1000)) with {
                    case Tick, k -> {
                        let before = 1;
                        yield k.resume(());
                    };
                    case Ask<Int>, k -> k.resume(3);
                };
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
            final var compilation = compiler.compileWithDiagnostics();
            assertTrue(compilation.diagnostics().toString(), compilation.successful());

            final var result = runProgram(output, packageName + ".Main");
            assertEquals(result.output, 0, result.exitCode);
            assertTrue(result.output, result.output.contains("general=31"));
            assertTrue(result.output, result.output.contains("stored=30"));
            assertTrue(result.output, result.output.contains("deep=10"));
            assertTrue(result.output, result.output.contains("aborts=7"));
            assertTrue(result.output, result.output.contains("escapedCaseEffect=99"));
            assertTrue(result.output, result.output.contains("cancelled=42"));
            assertTrue(result.output, result.output.contains("explicitCancelObserved=99"));
            assertTrue(result.output, result.output.contains("implicitAbortCleanup=7"));
            assertTrue(result.output, result.output.contains("tailStack=7"));
            assertTrue(result.output, result.output.contains("tailBlock=3"));
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void disposeRejectsAwaitAndContinuationCapturingHandlers() throws Exception {
        final var packageName = "level3dispose" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-level3-dispose-");
        final var unit = parse("Main.zn", """
                package %s;
                import zeron.concurrent.Await;
                import zeron.resource.Disposable;
                import zeron.resource.DisposeFailure;
                public effect Ask<T> responds T {}
                public class Waits is Disposable {
                    public constructor new;
                    public mut dispose(): Unit performs DisposeFailure, Await {
                    }
                }
                public class Captures is Disposable {
                    public constructor new;
                    public mut dispose(): Unit performs DisposeFailure {
                        handle (perform Ask<Int>()) with {
                            case Ask<Int>, k -> {
                                let resumed = k.resume(1);
                                yield resumed;
                            };
                        };
                    }
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), true, List.of(), output);
            final var resolution = compiler.resolveWithDiagnostics();
            final var errors = resolution.errors().toString();
            assertTrue(errors, errors.contains("cannot declare Await"));
            assertTrue(errors, errors.contains("cannot install a handler that captures a continuation"));
        } finally {
            deleteTree(output);
        }
    }

    private record ProgramResult(int exitCode, String output) {}

    private static ProgramResult runProgram(final Path output, final String mainClass)
            throws Exception {
        assertTrue("level-3 requires the continuation provider export",
                ContinuationProviderLoader.loomPackagePresent());
        final var java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        final var command = new ArrayList<String>();
        command.add(java.toString());
        command.addAll(ContinuationProviderLoader.launcherArguments());
        command.add("-D" + ContinuationProviderLoader.BACKEND_PROPERTY + "=a");
        command.add("-cp");
        command.add(System.getProperty("java.class.path") + File.pathSeparator + output);
        command.add(Program.class.getName());
        command.add(mainClass);

        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final var completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Level-3 program timed out");
        }
        final var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProgramResult(process.exitValue(), text);
    }

    /// Runs in a fresh JVM so the continuation provider can see the module export.
    public static final class Program {
        public static void main(final String[] arguments) throws Exception {
            final var main = Class.forName(arguments[0]);
            for (final var name : List.of("general", "stored", "deep", "aborts", "escapedCaseEffect",
                    "cancelled", "explicitCancelObserved", "implicitAbortCleanup", "tailStack", "tailBlock")) {
                System.out.println(name + "=" + main.getMethod(name).invoke(null));
            }
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
