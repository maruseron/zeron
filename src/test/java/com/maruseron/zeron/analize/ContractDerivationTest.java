package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Comparator;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ContractDerivationTest {
    @Test
    public void derivesEqAndHashableForProductsClassesSumsAndNewtypes() throws Exception {
        final var programName = "DerivationProgram" + UUID.randomUUID().toString().replace("-", "");
        final var source = """
                import zeron.lang.Eq;
                import zeron.lang.Hashable;
                public type Pair deriving Hashable = (left: Int, right: String);
                public class Record deriving Eq, Hashable {
                    first: Int;
                    second: String;
                }
                public class Node deriving Eq, Hashable {
                    value: Int;
                    next: Node?;
                }
                public type Choice deriving Hashable = Empty | Value(value: Int);
                public newtype UserId deriving Eq, Hashable = Int;
                fn same<T: mut + Eq<T>>(left: T, right: T): Boolean = left.eq(right);
                fn hash<T: mut + Hashable<T>>(value: T): Int = value.hash();
                fn equalRecords(): Boolean =
                    same(Record.new(7, "seven"), Record.new(7, "seven"));
                fn unequalRecords(): Boolean =
                    same(Record.new(7, "seven"), Record.new(8, "seven"));
                fn recordHash(): Int = hash(Record.new(7, "seven"));
                fn sameRecordHash(): Boolean =
                    hash(Record.new(7, "seven")) == hash(Record.new(7, "seven"));
                fn sameNodes(): Boolean =
                    same(Node.new(7, Node.new(8, null)), Node.new(7, Node.new(8, null)));
                fn nodeHash(): Int = hash(Node.new(7, Node.new(8, null)));
                fn pairHash(): Int = hash(Pair.new(7, "seven"));
                fn emptyHash(): Int = hash(Choice.Empty());
                fn valueHash(): Int = hash(Choice.Value(7));
                fn idHash(): Int = hash(UserId.new(7));
                """;
        final var compiler = new CompilationService(parse(source), programName);
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var program = loader.loadClass(programName);
                assertTrue((Boolean) program.getMethod("equalRecords").invoke(null));
                assertFalse((Boolean) program.getMethod("unequalRecords").invoke(null));
                assertTrue((Integer) program.getMethod("recordHash").invoke(null) != 0);
                assertTrue((Boolean) program.getMethod("sameRecordHash").invoke(null));
                assertTrue((Boolean) program.getMethod("sameNodes").invoke(null));
                assertTrue((Integer) program.getMethod("nodeHash").invoke(null) != 0);
                assertTrue((Integer) program.getMethod("pairHash").invoke(null) != 0);
                assertTrue((Integer) program.getMethod("emptyHash").invoke(null) != 0);
                assertTrue((Integer) program.getMethod("valueHash").invoke(null) != 0);
                assertTrue((Integer) program.getMethod("idHash").invoke(null) != 0);
            }
        } finally {
            for (final var generated : List.of(programName + ".class", "Pair.class", "Record.class", "Node.class",
                    "Choice.class", "Choice$$sum$Empty.class", "Choice$$sum$Value.class", "UserId.class")) {
                Files.deleteIfExists(Path.of("dist", generated));
            }
        }
    }

    @Test
    public void rejectsRedundantOrInconsistentDerivationRequests() {
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                type Pair deriving Eq = (left: Int, right: Int);
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                class Record deriving Hashable {
                    value: Int;
                }
                """)));
        assertThrows(ResolutionError.class, () -> new ResolutionService().resolve(parse("""
                import zeron.lang.Eq;
                class Record is Eq deriving Eq {
                    public eq(other: Record): Boolean = true;
                }
                """)));
    }

    @Test
    public void exportsOpaqueGenericRequirementsForSeparateCompilation() throws Exception {
            final var suffix = UUID.randomUUID().toString().replace("-", "");
            final var libraryPackage = "derivationlib" + suffix;
            final var libraryOutput = Files.createTempDirectory(Path.of("target"), "derived-library-");
            final var clientOutput = Files.createTempDirectory(Path.of("target"), "derived-client-");
            try {
                final var library = parseUnit("library/Box.zn", """
                        package %s;
                        import zeron.lang.Eq;
                        import zeron.lang.Hashable;
                        public class Box<T> deriving Eq, Hashable {
                            value: T;
                        }
                        """.formatted(libraryPackage));
                final var libraryCompiler = CompilationService.forCompilationUnits(
                        List.of(library), libraryPackage + ".Library", libraryPackage,
                        List.of(), true, List.of(), libraryOutput);
                libraryCompiler.resolve();
                libraryCompiler.compile();
                final var index = ZeronLibraryIndex.readFrom(libraryOutput.resolve(
                        com.maruseron.zeron.ZeronVersions.API_INDEX_RELATIVE_PATH));
                final var derivedHash = index.declarations().stream()
                        .filter(ZeronLibraryIndex.WitnessExport.class::isInstance)
                        .map(ZeronLibraryIndex.WitnessExport.class::cast)
                        .filter(witness -> witness.derived()
                                && witness.contractType().name().equals("zeron.lang.Hashable"))
                        .findFirst().orElseThrow();
                assertTrue(derivedHash.requirements().stream()
                        .anyMatch(requirement -> requirement.contractType().name().equals("zeron.lang.Hashable")));

                final var client = parseUnit("client/Main.zn", """
                        package client;
                        import %s.Box;
                        import zeron.lang.Hashable;
                        fn hashBox<T: mut + Hashable<T>>(value: T): Int = value.hash();
                        fn result(): Int = hashBox(Box<Int>.new(17));
                        """.formatted(libraryPackage));
                final var clientCompiler = CompilationService.forCompilationUnits(
                        List.of(client), "client.Main", "client",
                        List.of(index), true, List.of(), clientOutput);
                clientCompiler.resolve();
                clientCompiler.compile();
                try (final var loader = new URLClassLoader(new java.net.URL[]{
                        clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                        getClass().getClassLoader())) {
                    assertTrue((Integer) loader.loadClass("client.Main").getMethod("result").invoke(null) != 0);
                }
            } finally {
                deleteTree(clientOutput);
                deleteTree(libraryOutput);
            }
    }

    private static List<com.maruseron.zeron.ast.Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source, "ContractDerivationTest.zn").scanTokens()).parse();
    }

    private static CompilationUnit parseUnit(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source, sourcePath).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
