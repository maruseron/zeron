package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class Rank2PolymorphismTest {
    @Test
    public void parsesPolymorphicFunctionTypesAndResolvesCallsAtEachApplication() {
        final var statements = parse("""
                fn describeBoth(f: <T> (T) -> String, a: Int, b: String): String =
                    f(a) + f(b);
                """);
        new ResolutionService().resolve(statements);

        final var declaration = (Stmt.Function) statements.getFirst();
        final var parameter = (FunctionDescriptor) declaration.typeDescriptor().parameters().getFirst();
        assertEquals(1, parameter.typeParameters().size());
        assertEquals("T", parameter.typeParameters().getFirst().name());
    }

    @Test
    public void resolvesExplicitGenericLambdaAndSpecializesEachCall() {
        final var statements = parse("""
                let selfIdentity: <T> (T) -> T = <T> (value: T) -> value;
                let number = selfIdentity(1);
                let text = selfIdentity("text");
                """);
        final var result = new ResolutionService().resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "number"));
        assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "text"));
    }

    @Test
    public void contextualGenericLambdaMatchesAlphaRenamedExpectedBinders() {
        final var statements = parse("""
                fn apply(operation: <T> (T) -> T): Int = operation(1);
                fn result(): Int = apply(<U> (value: U) -> value);
                """);

        new ResolutionService().resolve(statements);
    }

    @Test
    public void infersPolymorphicTypeFromAnnotatedGenericLambdaParameters() {
        final var statements = parse("""
                let selfIdentity = <T> (value: T) -> value;
                let number = selfIdentity(1);
                let text = selfIdentity("text");
                """);
        final var result = new ResolutionService().resolve(statements);

        assertEquals(TypeDescriptor.ofInt(), bindingType(result, statements, "number"));
        assertEquals(TypeDescriptor.ofString(), bindingType(result, statements, "text"));
    }

    @Test
    public void polymorphicFunctionValuesAreAlphaEquivalent() {
        final var statements = parse("""
                let first: <T> (T) -> T = <T> (value: T) -> value;
                let second: <U> (U) -> U = first;
                """);

        new ResolutionService().resolve(statements);
    }

    @Test
    public void rejectsPolymorphicFunctionValuesWithDifferentResults() {
        final var statements = parse("""
                let first: <T> (T) -> T = <T> (value: T) -> value;
                let second: <T> (T) -> Int = first;
                """);

        org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(statements));
    }

    @Test
    public void namedGenericFunctionEtaExpandsToPolymorphicFunctionType() {
        final var statements = parse("""
                fn identityNamed<T>(value: T): T = value;
                let polymorphic: <T> (T) -> T = identityNamed;
                fn applied(): String = polymorphic("text");
                """);

        new ResolutionService().resolve(statements);
    }

    @Test
    public void compilesAndRunsUnboundedGenericLambdaAtSeveralApplications() throws Exception {
        final var className = "Rank2Lambda" + UUID.randomUUID().toString().replace("-", "");
        final var statements = parse("""
                fn identityNamed<T>(value: T): T = value;
                let selfIdentity: <T> (T) -> T = <T> (value: T) -> value;
                let inferredIdentity = <T> (value: T) -> value;
                let etaIdentity: <T> (T) -> T = identityNamed;
                fn appliedNumber(): Int = selfIdentity(7);
                fn appliedText(): String = selfIdentity("text");
                fn inferredNumber(): Int = inferredIdentity(11);
                fn etaNumber(): Long = etaIdentity(9L);
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(7, generated.getMethod("appliedNumber").invoke(null));
                assertEquals("text", generated.getMethod("appliedText").invoke(null));
                assertEquals(11, generated.getMethod("inferredNumber").invoke(null));
                assertEquals(9L, generated.getMethod("etaNumber").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void rejectsMutableGenericLambdaBindings() {
        final var statements = parse("""
                let mut identity = <T> (value: T) -> value;
                """);

        final var error = org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(statements));
        org.junit.Assert.assertTrue(error.getMessage().contains("immutable binding"));
    }

    @Test
    public void compilesAndRunsReifiedGenericLambdaApplication() throws Exception {
        final var className = "Rank2Reified" + UUID.randomUUID().toString().replace("-", "");
        final var statements = parse("""
                let describe: <T: reified> (T) -> String =
                    <T: reified> (value: T) -> "typed";
                fn reifiedValue(): String = describe(1);
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals("typed", generated.getMethod("reifiedValue").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void compilesAndRunsBoundedGenericLambdaApplication() throws Exception {
        final var className = "Rank2Bounded" + UUID.randomUUID().toString().replace("-", "");
        final var statements = parse("""
                contract Render { render(): String; }
                class Item is Render {
                    public constructor new;
                    public render(): String = "item";
                }
                fn renderNamed<T: Render>(value: T): String = value.render();
                let renderer: <T: Render> (T) -> String =
                    <T: Render> (value: T) -> renderNamed(value);
                fn bounded(): String = renderer(Item.new());
                """);
        final var compiler = new CompilationService(statements, className);
        compiler.resolve();

        try {
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals("item", generated.getMethod("bounded").invoke(null));
            }
        } finally {
            Files.deleteIfExists(Path.of("dist", className + ".class"));
        }
    }

    @Test
    public void supportsPolymorphicCallbacksOverNestedGenericApplications() {
        final var statements = parse("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                let describe: <T> (Box<T>) -> Int = <T> (box: Box<T>) -> 1;
                fn result(): Int = describe(Box.new("text"));
                """);

        new ResolutionService().resolve(statements);
    }

    @Test
    public void supportsPolymorphicUseEncoding() {
        final var statements = parse("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn useBox(box: Box<Int>, f: <U> (Box<U>) -> Int): Int = f(box);
                fn result(): Int = useBox(Box.new(1), <U> (box: Box<U>) -> 2);
                """);

        new ResolutionService().resolve(statements);
    }

    @Test
    public void preservesPolymorphicFunctionTypesAcrossSeparateCompilation() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var libraryPackage = "rank2lib" + suffix;
        final var clientPackage = "rank2client" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "rank2-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"), "rank2-client-");
        final var library = parseUnit("Library.zn", """
                package %s;
                public contract Render { render(): String; }
                public class Item is Render {
                    public constructor new;
                    public render(): String = "item";
                }
                public fn applyRenderer(f: <T: Render> (T) -> String, item: Item): String = f(item);
                public fn applyReified(f: <T: reified> (T) -> String, value: Int): String = f(value);
                """.formatted(libraryPackage));
        final var client = parseUnit("Main.zn", """
                package %s;
                import %s.Render;
                import %s.Item;
                import %s.applyRenderer;
                import %s.applyReified;
                fn result(): String =
                    applyRenderer(<T: Render> (value: T) -> value.render(), Item.new());
                fn reifiedResult(): String = applyReified(<T: reified> (value: T) -> "typed", 1);
                """.formatted(clientPackage, libraryPackage, libraryPackage, libraryPackage,
                        libraryPackage));

        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(library), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);
            final var applyRenderer = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".applyRenderer"))
                    .findFirst().orElseThrow();
            final var callback = (FunctionDescriptor) applyRenderer.signature().parameters().getFirst();
            assertEquals(1, callback.typeParameters().size());
            assertEquals(1, callback.typeParameters().getFirst().bounds().size());
            final var reifiedCallback = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .filter(function -> function.qualifiedName().equals(libraryPackage + ".applyReified"))
                    .map(function -> (FunctionDescriptor) function.signature().parameters().getFirst())
                    .findFirst().orElseThrow();
            org.junit.Assert.assertTrue(reifiedCallback.typeParameters().getFirst().isReified());

            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(client), clientPackage + ".Main", clientPackage,
                    List.of(index), true, List.of(), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(clientPackage + ".Main");
                assertEquals("item", generated.getMethod("result").invoke(null));
                assertEquals("typed", generated.getMethod("reifiedResult").invoke(null));
            }
        } finally {
            deleteTree(clientOutput);
            deleteTree(libraryOutput);
        }
    }

    @Test
    public void genericLambdaCannotBeCheckedAgainstMonomorphicFunctionType() {
        final var statements = parse("""
                fn apply(operation: (Int) -> Int): Int = operation(1);
                fn invalid(): Int = apply(<T> (value: T) -> value);
                """);

        org.junit.Assert.assertThrows(ResolutionError.class,
                () -> new ResolutionService().resolve(statements));
    }

    private static TypeDescriptor bindingType(final ResolutionResult result,
                                              final List<Stmt> statements,
                                              final String name) {
        final var declaration = statements.stream()
                .filter(Stmt.Var.class::isInstance)
                .map(Stmt.Var.class::cast)
                .filter(variable -> variable.name().lexeme().equals(name))
                .findFirst()
                .orElseThrow();
        return result.globalSymbolTable().getSymbol(declaration.name()).type();
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }

    private static CompilationUnit parseUnit(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (final var paths = Files.walk(root)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
