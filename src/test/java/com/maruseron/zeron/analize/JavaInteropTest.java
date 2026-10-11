package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.domain.FunctionBindingRegistry;
import com.maruseron.zeron.domain.JavaTypeMapping;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class JavaInteropTest {
    private static final Path JAVA_FIXTURE_ROOT = Path.of("target", "test-classes");

    @Test
    public void rejectsExternalClassesOutsideTheCuratedJdkSet() {
        final var source = parse("UncuratedExternalClass.zn", """
                package externalFacade;
                public external class Runtime = "java.lang.Runtime" {}
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source),
                "externalFacade.UncuratedExternalClass", "externalFacade");
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    @Test
    public void rejectsAssignmentToSystemOut() {
        final var source = parse("WriteSystemOut.zn", """
                package externalClient;
                import java.lang.System;
                fn replaceOutput(): Unit {
                    System.out = null;
                }
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source),
                "externalClient.WriteSystemOut", "externalClient");
        assertThrows(ResolutionError.class, compiler::resolve);
    }

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
        final var compiler = CompilationService.forCompilationUnits(List.of(clientSource, facadeSource),
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
        final var compiler = CompilationService.forCompilationUnits(List.of(source),
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
                fn objectArrayLength(): Int {
                    let values: Array<Int> = [2, 3, 5];
                    return Fixture.objectArrayLength(values);
                }
                fn objectArrayRoundTrip(): Array<Any?>? {
                    let values: Array<Int> = [2, 3, 5];
                    return Fixture.echoObjects(values);
                }
                fn nullObjectArray(): Array<Any?>? = Fixture.echoObjects(null);
                fn objectArrayCopyIsolated(): Int {
                    let values: &Array<Int> = [2];
                    Fixture.overwriteFirstObject(values);
                    return values[0];
                }
                fn voidReturn(): Unit {
                    let fixture: &Fixture = Fixture.new(12);
                    fixture.reset();
                    return ();
                }
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, "interop",
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
                assertEquals(3, client.getMethod("objectArrayLength").invoke(null));
                assertTrue(java.util.Arrays.equals(new Object[]{2, 3, 5},
                        (Object[]) client.getMethod("objectArrayRoundTrip").invoke(null)));
                assertNull(client.getMethod("nullObjectArray").invoke(null));
                assertEquals(2, client.getMethod("objectArrayCopyIsolated").invoke(null));
                client.getMethod("voidReturn").invoke(null);
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsTypedAndPrimitiveJavaArrayMethodSignatures() {
        final var objectArrayDescriptor = ClassDesc.ofDescriptor("[Ljava/lang/Object;");
        assertNull(JavaTypeMapping.toZeronType(objectArrayDescriptor));
        assertEquals(TypeDescriptor.arrayOf(TypeDescriptor.ofAny().toNullable()).toNullable(),
                JavaTypeMapping.toZeronMethodType(objectArrayDescriptor));
        for (final var source : List.of(
                """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(): String? {
                    let values: Array<String> = ["a"];
                    return Fixture.echoStrings(values);
                }
                """,
                """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(): Int {
                    let values: Array<Int> = [1];
                    return Fixture.echoInts(values).length;
                }
                """)) {
            final var unit = parse("UnsupportedJavaArray.zn", source);
            final var compiler = CompilationService.forCompilationUnits(List.of(unit),
                    "interop.UnsupportedJavaArray", "interop", List.of(), true, List.of(JAVA_FIXTURE_ROOT));
            assertThrows(ResolutionError.class, compiler::resolve);
        }
    }

    @Test
    public void readsStaticAndInstanceJavaFieldsAndWritesMutableInstanceFields() throws Exception {
        final var className = "interop.JavaFieldClient" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", "interop", className.substring("interop.".length()) + ".class");
        final var source = parse("JavaFieldClient.zn", """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn staticRead(): Int = Fixture.publicStaticValue;
                fn finalStaticRead(): Int = Fixture.finalStaticValue;
                fn instanceRead(): Int {
                    let fixture: &Fixture = Fixture.new(1);
                    return fixture.finalValue;
                }
                fn instanceWrite(): Int {
                    let fixture: &Fixture = Fixture.new(1);
                    fixture.publicValue = 31;
                    return fixture.publicValue;
                }
                fn instanceCompoundWrite(): Int {
                    let fixture: &Fixture = Fixture.new(1);
                    fixture.publicValue += 5;
                    return fixture.publicValue;
                }
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), className, "interop",
                List.of(), true, List.of(JAVA_FIXTURE_ROOT));
        try {
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    Path.of("dist").toUri().toURL(), JAVA_FIXTURE_ROOT.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var client = loader.loadClass(className);
                assertEquals(23, client.getMethod("staticRead").invoke(null));
                assertEquals(29, client.getMethod("finalStaticRead").invoke(null));
                assertEquals(17, client.getMethod("instanceRead").invoke(null));
                assertEquals(31, client.getMethod("instanceWrite").invoke(null));
                assertEquals(5, client.getMethod("instanceCompoundWrite").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsWritesToFinalAndStaticJavaFields() {
        for (final var assignment : List.of(
                "fixture.finalValue = 1;",
                "Fixture.publicStaticValue = 1;",
                "Fixture.finalStaticValue = 1;")) {
            final var source = parse("JavaFieldWrite.zn", """
                    package interop;
                    import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                    fn invalid(): Unit {
                        let fixture: &Fixture = Fixture.new(1);
                        %s
                    }
                    """.formatted(assignment));
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    "interop.JavaFieldWrite", "interop",
                    List.of(), true, List.of(JAVA_FIXTURE_ROOT));
            assertThrows(ResolutionError.class, compiler::resolve);
        }
    }

    @Test
    public void rejectsJavaFieldWritesThroughReadonlyViewsAndUnsupportedDescriptors() {
        for (final var sourceText : List.of(
                """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(fixture: Fixture): Unit {
                    fixture.publicValue = 1;
                }
                """,
                """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(fixture: &Fixture): Int {
                    return fixture.publicArray.length;
                }
                """,
                """
                package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(fixture: &Fixture): Int {
                    return fixture.value;
                }
                """)) {
            final var source = parse("InvalidJavaField.zn", sourceText);
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    "interop.InvalidJavaField", "interop",
                    List.of(), true, List.of(JAVA_FIXTURE_ROOT));
            assertThrows(ResolutionError.class, compiler::resolve);
        }
    }

    @Test
    public void resolvesJavaClassesFromOrderedDirectoryAndJarRoots() throws Exception {
        final var packageName = "interopJar" + UUID.randomUUID().toString().replace("-", "");
        final var className = packageName + ".JarInteropClient";
        final var jar = Files.createTempFile(Path.of("target"), "zeron-java-interop-", ".jar");
        final var emptyJar = Files.createTempFile(Path.of("target"), "zeron-java-empty-", ".jar");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-java-interop-output-");
        final var source = parse("JarInteropClient.zn", """
                package %s;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn result(): Int = Fixture.add(19, 23);
                """.formatted(packageName));
        try {
            writeFixtureJar(jar);
            try (final var out = new JarOutputStream(Files.newOutputStream(emptyJar))) {
                out.putNextEntry(new JarEntry("empty"));
                out.write(new byte[0]);
                out.closeEntry();
            }
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    className, packageName, List.of(), true, List.of(emptyJar, jar), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    output.toUri().toURL(), jar.toUri().toURL()}, getClass().getClassLoader())) {
                assertEquals(42, loader.loadClass(className).getMethod("result").invoke(null));
            }
        } finally {
            deleteTree(output);
            Files.deleteIfExists(jar);
            Files.deleteIfExists(emptyJar);
        }
    }

    @Test
    public void runClassAddsJavaClasspathJarToChildRuntime() throws Exception {
        final var packageName = "runJavaJar" + UUID.randomUUID().toString().replace("-", "");
        final var sourceDirectory = Files.createTempDirectory(Path.of("target"), "zeron-java-runtime-source-");
        final var sourcePath = sourceDirectory.resolve("Main.zn");
        final var jar = Files.createTempFile(Path.of("target"), "zeron-java-runtime-", ".jar");
        final var standardLibraryJar = Path.of("target", "zeron-stdlib-runtime-"
                + UUID.randomUUID().toString().replace("-", "") + ".jar");
        final var classFile = Path.of("dist", packageName, "Main.class");
        try {
            writeFixtureJar(jar);
            Files.writeString(sourcePath, """
                    package %s;
                    import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                    fn main(): Unit {
                        let value: Int = Fixture.add(19, 23);
                    }
                    """.formatted(packageName));
            assertEquals(0, Zeron.runCli(sourcePath.toString(), "--java-classpath", jar.toString()));
            assertTrue(Files.isRegularFile(classFile));
            assertEquals(0, Zeron.runCli("--run-class", classFile.toString(),
                    "--stdlib-jar", standardLibraryJar.toString(),
                    "--java-classpath", jar.toString()));
            assertEquals(0, Zeron.runCli("--run-class", classFile.toString(),
                    "--stdlib-jar", standardLibraryJar.toString(),
                    "--java-classpath", JAVA_FIXTURE_ROOT.toString()));
        } finally {
            deleteTree(Path.of("dist"));
            deleteTree(sourceDirectory);
            Files.deleteIfExists(jar);
            Files.deleteIfExists(standardLibraryJar);
        }
    }

    @Test
    public void resolvesExportedJdkTypesByImportAndQualifiedName() throws Exception {
        final var packageName = "jdkInterop" + UUID.randomUUID().toString().replace("-", "");
        final var className = packageName + ".JdkInteropClient";
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-jdk-interop-output-");
        final var source = parse("JdkInteropClient.zn", """
                package %s;
                import java.io.ByteArrayOutputStream as Buffer;
                fn importedType(): String? {
                    let value: &Buffer = Buffer.new();
                    return value.toString();
                }
                fn qualifiedType(): String? {
                    let value: &java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream.new();
                    return value.toString();
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(List.of(source),
                    className, packageName, List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();
            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var client = loader.loadClass(className);
                assertTrue(client.getMethod("importedType").invoke(null) instanceof String);
                assertTrue(client.getMethod("qualifiedType").invoke(null) instanceof String);
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsJdkClassesFromPackagesNotExportedToUnnamedModules() {
        final var source = parse("InaccessibleJdkType.zn", """
                package inaccessibleJdk;
                import jdk.internal.misc.Unsafe as Unsafe;
                fn invalid(value: Unsafe): Unit {}
                """);
        final var result = new ResolutionService("inaccessibleJdk", List.of())
                .resolveUnitsWithDiagnostics(List.of(source));
        assertTrue(result.errors().stream().anyMatch(error ->
                error.message().contains("Unknown import target")
                        || error.message().contains("not exported")));
    }

    @Test
    public void doesNotImplicitlyImportJdkTypesIntoTheDefaultScope() {
        final var source = parse("ImplicitJdkType.zn", """
                package implicitJdk;
                fn invalid(value: ByteArrayOutputStream): Unit {}
                """);
        final var result = new ResolutionService("implicitJdk", List.of())
                .resolveUnitsWithDiagnostics(List.of(source));
        assertTrue(result.errors().toString(), result.errors().stream().anyMatch(error ->
                error.message().contains("Unknown type 'implicitJdk.ByteArrayOutputStream'")));
    }

    @Test
    public void malformedJarClassEntryProducesResolutionDiagnosticInsteadOfFallback() throws Exception {
        final var malformedJar = Files.createTempFile(Path.of("target"), "zeron-java-malformed-", ".jar");
        final var source = parse("MalformedJavaInterop.zn", """
                package malformedInterop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn result(): Int = Fixture.add(19, 23);
                """);
        try {
            try (final var out = new JarOutputStream(Files.newOutputStream(malformedJar))) {
                out.putNextEntry(new JarEntry("com/maruseron/zeron/fixtures/JavaInteropFixture.class"));
                out.write(new byte[]{1, 2, 3, 4});
                out.closeEntry();
            }
            final var result = new ResolutionService("malformedInterop",
                    List.of(malformedJar, JAVA_FIXTURE_ROOT)).resolveUnitsWithDiagnostics(List.of(source));
            assertTrue(result.errors().stream().anyMatch(diagnostic ->
                    diagnostic.message().contains("Java metadata for")
                            && diagnostic.message().contains("JavaInteropFixture.class")));
        } finally {
            Files.deleteIfExists(malformedJar);
        }
    }

    private static void writeFixtureJar(final Path jar) throws Exception {
        final var resource = "/com/maruseron/zeron/fixtures/JavaInteropFixture.class";
        try (final InputStream input = JavaInteropTest.class.getResourceAsStream(resource);
             final var out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) throw new IllegalStateException("Java interop fixture class is missing.");
            out.putNextEntry(new JarEntry(resource.substring(1)));
            out.write(input.readAllBytes());
            out.closeEntry();
        }
    }

    @Test
    public void rejectsJavaInstanceCallsThroughReadOnlyViews() {
        final var source = parse("JavaReadonlyClient.zn", """
            package interop;
                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;
                fn invalid(value: Fixture): Int = value.current();
                """);
        final var compiler = CompilationService.forCompilationUnits(List.of(source), "interop.JavaReadonlyClient", "interop",
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
            final var compiler = CompilationService.forCompilationUnits(List.of(unit), "interop.JavaInvalidClient", "interop",
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
