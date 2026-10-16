package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class WitnessOverlapTest {
    @Test
    public void rejectsIntersectingFullyAppliedContractPatterns() {
        final var source = """
                contract Marker<T> {}
                class Box<T, U> { public constructor new; }
                witness<T, U> Marker<U> for Box<T, U> {}
                witness<A, B> Marker<Array<A>> for Box<A, B> {}
                """;

        final var error = assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(source)));

        assertTrue(error.getMessage().contains("Witness patterns overlap"));
    }

    @Test
    public void permitsDisjointAppliedContractPatternsForSameTarget() {
        final var source = """
                contract Marker<T> {}
                class Box<T> { public constructor new; }
                class Left<T> { public constructor new; }
                class Right<T> { public constructor new; }
                witness<T> Marker<Left<T>> for Box<T> {}
                witness<U> Marker<Right<U>> for Box<U> {}
                """;

        new ResolutionService().resolve(parse(source));
    }

    @Test
    public void unifiesRepeatedVariablesAndIgnoresPrerequisiteBounds() {
        final var source = """
                contract Marker<T> {}
                contract Named {}
                class Box<T> { public constructor new; }
                class Pair<A, B> { public constructor new; }
                witness<T: Named> Marker<Pair<T, T>> for Box<T> {}
                witness<U> Marker<Pair<U, Int>> for Box<U> {}
                """;

        final var error = assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(parse(source)));

        assertTrue(error.getMessage().contains("Witness patterns overlap"));
    }

    @Test
    public void repeatedVariablesCanProveAppliedPatternsDisjoint() {
        final var source = """
                contract Marker<T> {}
                class Box<T> { public constructor new; }
                class Pair<A, B> { public constructor new; }
                witness<T> Marker<Pair<T, T>> for Box<T> {}
                witness<U> Marker<Pair<Int, String>> for Box<U> {}
                """;

        new ResolutionService().resolve(parse(source));
    }

    @Test
    public void checksAllWitnessesActiveInConsumerIncludingCompiledLibraryMetadata() throws Exception {
        final var firstLibrary = parseUnit("WitnessA.zn", """
                package overlap.a;
                public contract Marker<T> {}
                witness<T, U> Marker<U> for overlap.b.Box<T, U> {}
                """);
        final var secondLibrary = parseUnit("WitnessB.zn", """
                package overlap.b;
                public class Box<T, U> { public constructor new; }
                witness<A, B> overlap.a.Marker<Array<A>> for Box<A, B> {}
                """);
        final var libraries = List.of(firstLibrary, secondLibrary);

        new ResolutionService().resolveUnits(libraries);

        final var clientWithoutImports = parseUnit("ClientWithoutImports.zn", """
                package client;
                """);
        new ResolutionService().resolveUnits(List.of(firstLibrary, secondLibrary, clientWithoutImports));

        final var index = ZeronLibraryIndex.fromCompilation(libraries,
                _ -> null, function -> function.typeDescriptor(), _ -> null, _ -> null, "WitnessLibrary", false);
        final var indexPath = Files.createTempFile("zeron-witness-overlap", ".bin");
        try {
            index.writeTo(indexPath);
            final var restoredLibrary = ZeronLibraryIndex.readFrom(indexPath);
            final var consumer = parseUnit("WitnessConsumer.zn", """
                    package client;
                    import overlap.a.*;
                    import overlap.b.*;
                    """);
            final var compilation = new ArrayList<CompilationUnit>(restoredLibrary
                    .toCompilationUnits("witness-library"));
            compilation.add(consumer);

            final var error = assertThrows(ResolutionError.class,
                    () -> new ResolutionService().resolveUnits(compilation));

            assertTrue(error.getMessage().contains("Witness patterns overlap"));
        } finally {
            Files.deleteIfExists(indexPath);
        }
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static CompilationUnit parseUnit(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }
}
