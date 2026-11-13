package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ExistentialGenericsTest {
    @Test
    public void starProjectionReadsAnUnknownArgument() throws Exception {
        assertCompiledResult("star", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                fn read(box: Box<*>): Any? = box.value;
                fn use(): Any? {
                    let box: Box<Int> = Box.new(7);
                    return read(box);
                }
                """, "use", 7);
    }

    @Test
    public void starProjectionIsRejectedForArrays() {
        assertRejected("""
                fn bad(values: Array<*>): Unit = ();
                """);
    }

    @Test
    public void anonymousExistentialPacksAndUsesInheritedBoundEvidence() throws Exception {
        assertCompiledResult("anonymous", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn describe(box: Box<: Render>): String = box.value.render();
                fn use(): String = describe(Box.new(Text.new()));
                """, "use", "text");
    }

    @Test
    public void storedLocalCarriesEvidenceForBoundMembers() throws Exception {
        assertCompiledResult("storedLocal", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let concrete: Box<Text> = Box.new(Text.new());
                    let box: Box<: Render> = concrete;
                    return box.value.render();
                }
                """, "use", "text");
    }

    @Test
    public void storedLocalInfersFromAConcreteInitializer() throws Exception {
        assertCompiledResult("storedLocalInference", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let box: Box<: Render> = Box.new(Text.new());
                    return box.value.render();
                }
                """, "use", "text");
    }

    @Test
    public void storedFieldCarriesEvidenceForBoundMembers() throws Exception {
        assertCompiledResult("storedField", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: Render>;
                    public constructor new;
                }
                fn use(): String {
                    let concrete: Box<Text> = Box.new(Text.new());
                    let holder = Holder.new(concrete);
                    return holder.box.value.render();
                }
                """, "use", "text");
    }

    @Test
    public void storedArrayElementsAreIndependent() throws Exception {
        assertCompiledResult("storedArray", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let values: Array<Box<: Render>> = [Box.new(Text.new())];
                    return values[0].value.render();
                }
                """, "use", "text");
    }

    @Test
    public void storedReifiedFieldKeepsItsDescriptor() throws Exception {
        assertCompiledResult("storedReified", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: reified>;
                    public constructor new;
                }
                fn use(): Boolean {
                    let concrete: Box<Int> = Box.new(7);
                    let holder = Holder.new(concrete);
                    return holder.box is Box<Int>;
                }
                """, "use", true);
    }

    @Test
    public void markerOnlyStoredExistentialIsErased() throws Exception {
        assertCompiledResult("storedErased", """
                package %s;
                public class Box<T: nullable> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): Any? {
                    let concrete: Box<String> = Box.new("value");
                    let box: Box<: nullable> = concrete;
                    return box.value;
                }
                """, "use", "value");
    }

    @Test
    public void anonymousExistentialSignaturesRoundTripThroughLibraryIndex() throws Exception {
        final var packageName = "zep0104Index" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0104-index-");
        final var unit = parse("Library.zn", """
                package %s;
                public contract Marker {}
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public fn describe(box: Box<: Marker>): Any? = box.value;
                public fn isIntBox(box: Box<: reified>): Boolean = box is Box<Int>;
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Library", packageName, List.of(), true,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(output);
            final var functions = index.declarations().stream()
                    .filter(ZeronLibraryIndex.FunctionExport.class::isInstance)
                    .map(ZeronLibraryIndex.FunctionExport.class::cast)
                    .toList();
            final var describe = functions.stream()
                    .filter(function -> function.qualifiedName().equals(packageName + ".describe"))
                    .findFirst().orElseThrow().signature();
            final var reified = functions.stream()
                    .filter(function -> function.qualifiedName().equals(packageName + ".isIntBox"))
                    .findFirst().orElseThrow().signature();
            assertEquals(1, describe.typeParameters().size());
            assertTrue(describe.typeParameters().getFirst().isAnonymousExistential());
            assertTrue(describe.explicitTypeParameters().isEmpty());
            assertEquals(1, reified.typeParameters().size());
            assertTrue(reified.typeParameters().getFirst().isAnonymousExistential());
            assertTrue(reified.typeParameters().getFirst().isReified());
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void storedHolderCarriesADescriptorAndEvidenceTogether() throws Exception {
        assertCompiledResult("storedTwoSlots", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: reified + Render>;
                    public constructor new;
                }
                fn use(): String {
                    let concrete: Box<Text> = Box.new(Text.new());
                    let holder = Holder.new(concrete);
                    if (holder.box is Box<Text>) {
                        return holder.box.value.render();
                    }
                    return "wrong";
                }
                """, "use", "text");
    }

    @Test
    public void storedHolderUsesTheErasedFamilyBeyondThreeSlots() throws Exception {
        assertCompiledResult("storedFourSlots", """
                package %s;
                public contract A {
                    a(): String;
                }
                public contract B {
                    b(): String;
                }
                public contract C {
                    c(): String;
                }
                public class Text is A, B, C {
                    public constructor new;
                    public a(): String = "a";
                    public b(): String = "b";
                    public c(): String = "c";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: reified + A + B + C>;
                    public constructor new;
                }
                fn use(): String {
                    let concrete: Box<Text> = Box.new(Text.new());
                    let holder = Holder.new(concrete);
                    if (holder.box is Box<Text>) {
                        return holder.box.value.a() + holder.box.value.b() + holder.box.value.c();
                    }
                    return "wrong";
                }
                """, "use", "abc");
    }

    @Test
    public void storedBoundEvidenceGroupKeepsEveryMethod() throws Exception {
        assertCompiledResult("storedEvidenceGroup", """
                package %s;
                public contract Both {
                    first(): String;
                    second(): String;
                }
                public class Text is Both {
                    public constructor new;
                    public first(): String = "first";
                    public second(): String = "second";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let box: Box<: Both> = Box.new(Text.new());
                    return box.value.first() + box.value.second();
                }
                """, "use", "firstsecond");
    }

    @Test
    public void nestedStoredArrayElementsOwnTheirHolders() throws Exception {
        assertCompiledResult("storedNestedArray", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let values: Array<Array<Box<: Render>>> = [[Box.new(Text.new())]];
                    return values[0][0].value.render();
                }
                """, "use", "text");
    }

    @Test
    public void storedValueCanBePassedToAnExistentialParameter() throws Exception {
        assertCompiledResult("storedForward", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn describe(box: Box<: Render>): String = box.value.render();
                fn use(): String {
                    let box: Box<: Render> = Box.new(Text.new());
                    return describe(box);
                }
                """, "use", "text");
    }

    @Test
    public void storedExistentialFieldWorksAcrossSeparateCompilation() throws Exception {
        final var libraryPackage = "zep0106Separate" + suffix();
        final var libraryOutput = Files.createTempDirectory(Path.of("target"),
                "zeron-zep0106-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"),
                "zeron-zep0106-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: Render>;
                    public constructor new;
                }
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);

            final var clientPackage = "zep0106Client" + suffix();
            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.*;
                    fn use(): String {
                        let concrete: Box<Text> = Box.new(Text.new());
                        let holder = Holder.new(concrete);
                        return holder.box.value.render();
                    }
                    """.formatted(clientPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(clientUnit), clientPackage + ".Client", clientPackage,
                    List.of(index), false, List.of(libraryOutput), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    ExistentialGenericsTest.class.getClassLoader())) {
                assertEquals("text", loader.loadClass(clientPackage + ".Client")
                        .getMethod("use").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void storedCollectionElementsOwnTheirHolders() throws Exception {
        assertCompiledResult("storedCollection", """
                package %s;
                import zeron.collections.*;
                import zeron.lang.*;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let values: ArrayList<Box<: Render>> = ArrayList.of(Box.new(Text.new()));
                    return match (values.at(0)) {
                        case Some(box) -> box.value.render();
                        case None() -> "none";
                    };
                }
                """, "use", "text");
    }

    @Test
    public void storedCollectionElementsSurviveMutationAndIteration() throws Exception {
        assertCompiledResult("storedCollectionMutation", """
                package %s;
                import zeron.collections.*;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let mut values: &ArrayList<Box<: Render>> = ArrayList.of(Box.new(Text.new()));
                    values.add(Box.new(Text.new()));
                    let mut result = "";
                    for (let box in values) {
                        result = result + box.value.render();
                    }
                    return result;
                }
                """, "use", "texttext");
    }

    @Test
    public void storedModelFieldArrayCarriesPerElementEvidence() throws Exception {
        assertCompiledResult("storedModelFields", """
                package %s;
                public contract Show {
                    show(): String;
                }
                public class Text is Show {
                    public constructor new;
                    public show(): String = "text";
                }
                public class User {
                    public constructor new;
                }
                public class Column<T, V: Show> {
                    public property value: V;
                    public constructor new;
                    public show(): String = this.value.show();
                }
                fn use(): String {
                    let fields: Array<Column<User, : Show>> =
                            [Column<User, Text>.new(Text.new())];
                    return fields[0].show();
                }
                """, "use", "text");
    }

    @Test
    public void nestedStoredCollectionElementsKeepIndependentHolders() throws Exception {
        assertCompiledResult("storedNestedCollection", """
                package %s;
                import zeron.collections.*;
                import zeron.lang.*;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): String {
                    let inner: ArrayList<Box<: Render>> =
                            ArrayList.of(Box.new(Text.new()));
                    let outer = ArrayList.of(inner);
                    return match (outer.at(0)) {
                        case Some(items) -> match (items.at(0)) {
                            case Some(box) -> box.value.render();
                            case None() -> "none";
                        };
                        case None() -> "none";
                    };
                }
                """, "use", "text");
    }

    @Test
    public void storedCollectionFieldWorksAcrossSeparateCompilation() throws Exception {
        final var libraryPackage = "zep0106CollectionLibrary" + suffix();
        final var libraryOutput = Files.createTempDirectory(Path.of("target"),
                "zeron-zep0106-collection-library-");
        final var clientOutput = Files.createTempDirectory(Path.of("target"),
                "zeron-zep0106-collection-client-");
        final var libraryUnit = parse("Library.zn", """
                package %s;
                import zeron.collections.*;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Store {
                    public property values: ArrayList<Box<: Render>>;
                    public constructor new;
                    public first(): String = match (this.values.at(0)) {
                        case Option.Some(box) -> box.value.render();
                        case Option.None() -> "none";
                    };
                }
                public fn make(): Store = Store.new(ArrayList.of(Box.new(Text.new())));
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(
                    List.of(libraryUnit), libraryPackage + ".Library", libraryPackage,
                    List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();
            final var index = ZeronLibraryIndex.readFromDirectory(libraryOutput);

            final var clientPackage = "zep0106CollectionClient" + suffix();
            final var clientUnit = parse("Client.zn", """
                    package %s;
                    import %s.*;
                    fn use(): String = make().first();
                    """.formatted(clientPackage, libraryPackage));
            final var clientCompiler = CompilationService.forCompilationUnits(
                    List.of(clientUnit), clientPackage + ".Client", clientPackage,
                    List.of(index), false, List.of(libraryOutput), clientOutput);
            clientCompiler.resolve();
            clientCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    clientOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    ExistentialGenericsTest.class.getClassLoader())) {
                assertEquals("text", loader.loadClass(clientPackage + ".Client")
                        .getMethod("use").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(clientOutput);
        }
    }

    @Test
    public void storedHolderIdentityUsesThePayload() throws Exception {
        assertCompiledResult("storedIdentity", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn use(): Boolean {
                    let first: Box<: Render> = Box.new(Text.new());
                    let second: Box<: Render> = first;
                    return first === second;
                }
                """, "use", true);
    }

    @Test
    public void storedExistentialFieldMetadataRoundTripsThroughLibraryIndex() throws Exception {
        final var packageName = "zep0106Index" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0106-index-");
        final var unit = parse("Library.zn", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public class Holder {
                    public property box: Box<: reified>;
                    public constructor new;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    List.of(unit), packageName + ".Library", packageName, List.of(), true,
                    List.of(), output);
            compiler.resolve();
            compiler.compile();

            final var index = ZeronLibraryIndex.readFromDirectory(output);
            final var holder = index.declarations().stream()
                    .filter(ZeronLibraryIndex.ClassExport.class::isInstance)
                    .map(ZeronLibraryIndex.ClassExport.class::cast)
                    .filter(export -> export.qualifiedName().equals(packageName + ".Holder"))
                    .findFirst().orElseThrow();
            final var property = holder.properties().stream()
                    .filter(candidate -> candidate.name().equals("box"))
                    .findFirst().orElseThrow();
            assertTrue(property.type() instanceof com.maruseron.zeron.domain.GenericDescriptor);
            final var argument = ((com.maruseron.zeron.domain.GenericDescriptor) property.type())
                    .typeParameters().getFirst();
            assertTrue(argument instanceof com.maruseron.zeron.domain.TypeParameterDescriptor);
            final var binder = (com.maruseron.zeron.domain.TypeParameterDescriptor) argument;
            assertTrue(binder.isStoredExistential());
            assertTrue(binder.isReified());
            assertEquals(ZeronLibraryIndex.VERSION, 14);
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void contractMethodsCanUseAnonymousExistentials() throws Exception {
        assertCompiledResult("contractAnonymous", """
                package %s;
                public contract Render {
                    render(): String;
                }
                public class Text is Render {
                    public constructor new;
                    public render(): String = "text";
                }
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                public contract Renderer {
                    render(box: Box<: Render>): String;
                }
                public class RendererImpl is Renderer {
                    public constructor new;
                    public render(box: Box<: Render>): String = box.value.render();
                }
                fn use(): String = RendererImpl.new().render(Box.new(Text.new()));
                """, "use", "text");
    }

    @Test
    public void reifiedAnonymousExistentialSupportsExactAppliedTests() throws Exception {
        assertCompiledResult("anonymousReified", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox(box: Box<: reified>): Boolean = box is Box<Int>;
                fn useInt(): Boolean = isIntBox(Box.new(7));
                fn useString(): Boolean = isIntBox(Box.new("value"));
                """, "useInt", true);
    }

    @Test
    public void reifiedTypeVariableSupportsExactAppliedTests() throws Exception {
        assertCompiledResult("reifiedExact", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox<T: reified>(value: T): Boolean = value is Box<Int>;
                fn useInt(): Boolean = isIntBox(Box.new(7));
                fn useString(): Boolean = isIntBox("value");
                """, "useInt", true);
    }

    @Test
    public void exactAppliedTestRequiresAReifiedSource() {
        assertRejected("""
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn invalid<T>(value: T): Boolean = value is Box<Int>;
                """);
    }

    @Test
    public void exactAppliedTestsDistinguishArguments() throws Exception {
        assertCompiledResult("reifiedFalse", """
                package %s;
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                fn isIntBox<T: reified>(value: T): Boolean = value is Box<Int>;
                fn useString(): Boolean = isIntBox(Box.new("value"));
                """, "useString", false);
    }

    @Test
    public void exactArrayTestsUseTheElementDescriptor() throws Exception {
        assertCompiledResult("reifiedArray", """
                package %s;
                fn isIntRange<T: reified>(value: T): Boolean = value is Array<Int>;
                fn useRange(): Boolean {
                    let values: Array<Int> = [1, 2, 3];
                    return isIntRange(values);
                }
                """, "useRange", true);
    }

    @Test
    public void anonymousExistentialPositionsAreRejected() {
        final var prefix = """
                public class Box<T> {
                    public property value: T;
                    public constructor new;
                }
                """;
        final var sources = List.of(
                prefix + "fn bad(): Box<: Any> = Box.new(1);",
                prefix + "fn bad(this: Box<: Any>): Unit = ();",
                prefix + "fn bad(callback: (Box<: Any>) -> Unit): Unit = ();",
                prefix + "fn bad(values: Array<: Any>): Unit = ();",
                prefix + "fn bad(): Unit { let callback: (Box<: Any>) -> Unit = (box) -> (); }");
        for (int index = 0; index < sources.size(); index++) {
            assertInvalidSource(sources.get(index), "position " + index);
        }
    }

    private static void assertCompiledResult(final String testName,
                                             final String source,
                                             final String method,
                                             final Object expected) throws Exception {
        final var packageName = "zep0104" + testName + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-zep0104-");
        final var unit = parse("Main.zn", source.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, ExistentialGenericsTest.class.getClassLoader())) {
                assertEquals(expected, loader.loadClass(packageName + ".Main")
                        .getMethod(method).invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static void assertRejected(final String source) {
        final var unit = parse("Main.zn", "package zep0104Rejected%s;\n".formatted(suffix())
                + source);
        final var packageName = unit.packageName();
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(unit)),
                packageName + ".Main", packageName, List.of(), false, List.of(),
                Path.of("target", "zeron-zep0104-rejected-" + suffix()));
        assertThrows(ResolutionError.class, compiler::resolve);
    }

    private static void assertInvalidSource(final String source, final String description) {
        final var packageName = "zep0104Invalid" + suffix();
        final var result = Parser.of(Scanner.from("package " + packageName + ";\n" + source,
                "Main.zn").scanTokens()).parseCompilationUnitWithDiagnostics("Main.zn");
        if (!result.diagnostics().isEmpty()) return;
        try {
            CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(result.compilationUnit())),
                    packageName + ".Main", packageName, List.of(), false, List.of(),
                    Path.of("target", "zeron-zep0104-invalid-" + suffix())).resolve();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(description + ": expected the source to be rejected");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
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
