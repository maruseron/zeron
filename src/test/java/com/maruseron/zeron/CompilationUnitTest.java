package com.maruseron.zeron;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class CompilationUnitTest {
    @Test
    public void parserRecoveryReportsErrorsWithoutNullDeclarations() {
        final var previousError = Zeron.hadError;
        Zeron.hadError = false;
        try {
            final var unit = Parser.of(Scanner.from("fn broken() = 1").scanTokens())
                    .parseCompilationUnit("broken.zn");
            assertTrue(Zeron.hadError);
            assertTrue(unit.declarations().isEmpty());
        } finally {
            Zeron.hadError = previousError;
        }
    }

    @Test
    public void nominalTypeIdentityIncludesPackage() {
        assertNotEquals(TypeDescriptor.of("geometry.Point"), TypeDescriptor.of("graphics.Point"));
    }

    @Test
    public void compilesMultipleFilesWithQualifiedNominalIdentity() throws Exception {
        final var mainName = "fixture.PackageMain";
        final var itemPath = Path.of("dist", "fixture", "Item.class");
        final var boxPath = Path.of("dist", "fixture", "Box.class");
        final var mainPath = Path.of("dist", "fixture", "PackageMain.class");
        final var firstUnit = parse("item.zn", """
                package fixture;
                class Item {
                    value: Int;
                    public constructor new;
                    public constructor with(value: Int) = Item.new(value);
                    public read(): Int = this.value;
                }
                fn result(): Int = Box.new(Item.with(41)).read();
                """);
        final var secondUnit = parse("box.zn", """
                package fixture;
                class Box {
                    item: Item;
                    public constructor new;
                    public read(): Int = this.item.read();
                }
                """);

        assertEquals("fixture", firstUnit.packageName());
        assertEquals(firstUnit.packageName(), secondUnit.packageName());
        final var units = List.of(firstUnit, secondUnit);

        try {
            final var compiler = CompilationService.forCompilationUnits(units, mainName, "fixture");
            compiler.resolve();
            compiler.compile();

            assertTrue(Files.exists(itemPath));
            assertTrue(Files.exists(boxPath));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(41, loader.loadClass(mainName).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(itemPath);
            Files.deleteIfExists(boxPath);
            Files.deleteIfExists(mainPath);
        }
    }

    @Test
    public void compilesAliasedPublicTypeImportAcrossPackages() throws Exception {
        final var className = "app.ImportMain";
        final var mainPath = Path.of("dist", "app", "ImportMain.class");
        final var pointPath = Path.of("dist", "geometry", "Point.class");
        final var provider = parse("Point.zn", """
                package geometry;
                public class Point {
                    value: Int;
                    public constructor new;
                    public read(): Int = this.value;
                }
                """);
        final var consumer = parse("Main.zn", """
                package app;
                import geometry.Point as Dot;
                fn result(): Int = Dot.new(42).read();
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(consumer, provider), className, "app");
            compiler.resolve();
            compiler.compile();

            assertTrue(Files.exists(pointPath));
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }
        } finally {
            Files.deleteIfExists(mainPath);
            Files.deleteIfExists(pointPath);
        }
    }

    @Test
    public void compilesStarImportsWithExplicitImportsTakingPrecedence() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var firstPackage = "starone" + suffix;
        final var secondPackage = "startwo" + suffix;
        final var appPackage = "starapp" + suffix;
        final var className = appPackage + ".ImportMain";
        final var first = parse("First.zn", """
                package %s;
                public class Item {
                    public constructor new;
                    public value(): Int = 20;
                }
                public fn answer(): Int = 22;
                """.formatted(firstPackage));
        final var second = parse("Second.zn", """
                package %s;
                public class Item {
                    public constructor new;
                    public value(): Int = 1;
                }
                public fn answer(): Int = 2;
                """.formatted(secondPackage));
        final var consumer = parse("Main.zn", """
                package %s;
                import %s.*;
                import %s.*;
                import %s.Item;
                import %s.answer;
                fn result(): Int = Item.new().value() + answer();
                """.formatted(appPackage, firstPackage, secondPackage, firstPackage, firstPackage));

        try {
            deleteTree(Path.of("dist"));
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(consumer, first, second), className, appPackage);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist"));
        }
    }

    @Test
    public void rejectsAmbiguousNamesFromStarImportsWhenUsed() {
        final var first = parse("First.zn", "package one; public fn answer(): Int = 1;");
        final var second = parse("Second.zn", "package two; public fn answer(): Int = 2;");
        final var consumer = parse("Main.zn", """
                package app;
                import one.*;
                import two.*;
                fn result(): Int = answer();
                """);

        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolveUnits(List.of(first, second, consumer)));
    }

    @Test
    public void rejectsImportOfPackagePrivateType() {
        final var provider = parse("Hidden.zn", """
                package geometry;
                class Hidden {}
                """);
        final var consumer = parse("Main.zn", """
                package app;
                import geometry.Hidden;
                fn result(): Hidden = ();
                """);

        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolveUnits(List.of(provider, consumer)));
    }

    @Test
    public void rejectsAmbiguousTypeImports() {
        final var first = parse("First.zn", "package one; public class Item {}");
        final var second = parse("Second.zn", "package two; public class Item {}");
        final var consumer = parse("Main.zn", """
                package app;
                import one.Item;
                import two.Item;
                """);

        assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolveUnits(List.of(first, second, consumer)));
    }

        @Test
        public void rejectsImportAliasesThatShadowBuiltInTypes() {
        final var provider = parse("Number.zn", "package geometry; public class Number {}");
        final var consumer = parse("Main.zn", """
            package app;
            import geometry.Number as Int;
            fn result(): Int = 1;
            """);

        assertThrows(ResolutionError.class,
            () -> new ResolutionService().resolveUnits(List.of(provider, consumer)));
        }

        @Test
        public void rejectsImportOfPackagePrivateFunction() {
        final var provider = parse("Math.zn", """
            package arithmetic;
            fn add(left: Int, right: Int): Int = left + right;
            """);
        final var consumer = parse("Main.zn", """
            package app;
            import arithmetic.add as sum;
            fn result(): Int = sum(2, 3);
            """);

        assertThrows(ResolutionError.class,
            () -> new ResolutionService().resolveUnits(List.of(consumer, provider)));
        }

        @Test
        public void acceptsInitializedTopLevelValuesOutsideEntryUnit() {
        final var entry = parse("Main.zn", "package app; fn main(): Unit = ();");
        final var library = parse("State.zn", "package app; let state = 1;");

        new ResolutionService().resolveUnits(List.of(entry, library));
        }

        @Test
        public void valueImportsEnforceVisibilityAndStarImportAmbiguity() {
            final var privateProvider = parse("Hidden.zn", "package hidden; let value = 1;");
            final var privateConsumer = parse("PrivateConsumer.zn", """
                    package app;
                    import hidden.value;
                    fn result(): Int = value;
                    """);
            assertThrows(ResolutionError.class,
                    () -> new ResolutionService().resolveUnits(List.of(privateProvider, privateConsumer)));

            final var first = parse("First.zn", "package one; public let score = 1;");
            final var second = parse("Second.zn", "package two; public let score = 2;");
            final var ambiguousConsumer = parse("Main.zn", """
                    package app;
                    import one.*;
                    import two.*;
                    fn result(): Int = score;
                    """);
            assertThrows(ResolutionError.class,
                    () -> new ResolutionService().resolveUnits(List.of(first, second, ambiguousConsumer)));
        }

        private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
