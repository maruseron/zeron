package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.domain.FunctionBindingRegistry;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public final class JavaInteropTest {
    private static final Path JAVA_FIXTURE_ROOT = Path.of("target", "test-classes");

    @Test
    public void bindsImportedExternalFunctionToJvmStaticMethod() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var facadePackage = "externalFacade" + suffix;
        final var clientPackage = "externalClient" + suffix;
        final var className = clientPackage + ".ExternalBindingClient";
        final var facadeSource = parse("ExternalFacade.zn", """
                package %s;
                public external fn add(left: Int, right: Int): Int;
                """.formatted(facadePackage));
        final var clientSource = parse("ExternalBindingClient.zn", """
                package %s;
                import %s.add as sum;
                fn result(): Int = sum(19, 23);
                """.formatted(clientPackage, facadePackage));
        final var compiler = Compiler.forCompilationUnits(List.of(clientSource, facadeSource),
            className, clientPackage, List.of(), true, List.of(JAVA_FIXTURE_ROOT),
            fixtureAddBinding(facadePackage + ".add"));

        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), JAVA_FIXTURE_ROOT.toUri().toURL()},
                    getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(Path.of("dist", clientPackage));
            deleteTree(Path.of("dist", facadePackage));
        }
    }

    @Test
    public void rejectsExternalFunctionWhenJvmSignatureDoesNotMatch() {
        final var source = parse("InvalidExternalFacade.zn", """
                package externalFacade;
            external fn add(left: Int, right: Int): String;
                """);
        final var compiler = Compiler.forCompilationUnits(List.of(source),
                "externalFacade.InvalidExternalFacade", "externalFacade",
            List.of(), true, List.of(JAVA_FIXTURE_ROOT), fixtureAddBinding("externalFacade.add"));
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void resolvesAndInvokesJavaConstructorsMethodsAndOverloads() throws Exception {
        final var className = "interop.JavaInteropClient" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", "interop", className.substring("interop.".length()) + ".class");
        final var source = parse("JavaInteropClient.zn", """
            package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn staticCall(): Int = Fixture.add(2, 3);
                fn overloadInt(): Int = Fixture.choose(7);
                fn overloadFloat(): Float = Fixture.choose(2.5);
                fn formatted(): String? = Fixture.format("%s:%d", "item", 7);
                fn summed(): Int = Fixture.sum(3, 4, 5);
                fn summedFloat(): Float = Fixture.sum(1.5, 2.5);
                fn joined(): String? = Fixture.join("|", "a", "b");
                fn emptyJoin(): String? = Fixture.join("|");
                fn fixedOverVarargs(): String? = Fixture.choose("fixed");
                fn emptyVarargs(): String? = Fixture.choose();
                fn passMutableReference(): Int {
                    let fixture: &Fixture = Fixture.new(10);
                    return Fixture.readValue(fixture) + fixture.increment(5);
                }
                fn stringReturn(): String? = Fixture.echo("java");
                fn nullStringArgument(): String? = Fixture.echo(null);
                fn voidReturn(): Unit {
                    let fixture: &Fixture = Fixture.new(12);
                    fixture.reset();
                    return ();
                }
                """);
        final var compiler = Compiler.forCompilationUnits(List.of(source), className, "interop",
                List.of(), true, List.of(JAVA_FIXTURE_ROOT));

        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL(),
                            JAVA_FIXTURE_ROOT.toUri().toURL()}, getClass().getClassLoader())) {
                final var client = loader.loadClass(className);
                assertEquals(5, client.getMethod("staticCall").invoke(null));
                assertEquals(7, client.getMethod("overloadInt").invoke(null));
                assertEquals(2.5, (Double) client.getMethod("overloadFloat").invoke(null), 0.0);
                assertEquals("item:7", client.getMethod("formatted").invoke(null));
                assertEquals(12, client.getMethod("summed").invoke(null));
                assertEquals(4.0, (Double) client.getMethod("summedFloat").invoke(null), 0.0);
                assertEquals("a|b", client.getMethod("joined").invoke(null));
                assertEquals("", client.getMethod("emptyJoin").invoke(null));
                assertEquals("fixed", client.getMethod("fixedOverVarargs").invoke(null));
                assertEquals("", client.getMethod("emptyVarargs").invoke(null));
                assertEquals(25, client.getMethod("passMutableReference").invoke(null));
                assertEquals("java", client.getMethod("stringReturn").invoke(null));
                assertNull(client.getMethod("nullStringArgument").invoke(null));
                client.getMethod("voidReturn").invoke(null);
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsJavaInstanceCallsThroughReadOnlyViews() {
        final var source = parse("JavaReadonlyClient.zn", """
            package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(value: Fixture): Int = value.current();
                """);
        final var compiler = Compiler.forCompilationUnits(List.of(source), "interop.JavaReadonlyClient", "interop",
                List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void rejectsAmbiguousNullOverloadsAndGenericJavaMethods() {
        for (final var source : List.of(
                "import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture; "
                        + "fn invalid(): String? = Fixture.choose(null);",
                "import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture; "
                    + "fn invalid(): String? = Fixture.ambiguous(null);",
                "import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture; "
                        + "fn invalid(): Int = Fixture.generic(1);")) {
            final var unit = parse("JavaInvalidClient.zn", "package interop; " + source);
            final var compiler = Compiler.forCompilationUnits(List.of(unit), "interop.JavaInvalidClient", "interop",
                    List.of(), true, List.of(JAVA_FIXTURE_ROOT));
            assertThrows(ResolutionError.class, compiler::resolve);
        }
    }

    private static CompilationUnit parse(final String sourcePath, final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parseCompilationUnit(sourcePath);
    }

        private static FunctionBindingRegistry fixtureAddBinding(final String qualifiedName) {
        final var signature = TypeDescriptor.functionOf("add", TypeDescriptor.ofInt(),
            TypeDescriptor.ofInt(), TypeDescriptor.ofInt());
        final var target = new FunctionBindingRegistry.StaticMethod(
            ClassDesc.of("com.maruseron.zeron.fixtures.JavaInteropFixture"), "add",
            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
        return FunctionBindingRegistry.standard().withBinding(qualifiedName,
            new FunctionBindingRegistry.Binding(signature, target));
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
