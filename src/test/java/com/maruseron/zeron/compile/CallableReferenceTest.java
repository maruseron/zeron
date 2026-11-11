package com.maruseron.zeron.compile;

import com.maruseron.zeron.StandardLibrary;
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

public final class CallableReferenceTest {
    @Test
    public void resolvesMethodConstructorAndStaticReferences() throws Exception {
        final var packageName = "callableRefs" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-ref-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Counter {
                    public mut property count: Int;
                    public constructor new;
                    public constructor startingAt(count: Int) = Counter.new(count);
                    public value(): Int = this.count;
                    public mut increment(by: Int): Unit {
                        this.count = this.count + by;
                    }
                    public static fn zero(): Counter = Counter.new(0);
                }
                fn methodIdentity(): String = (Counter::value).identity;
                fn constructorIdentity(): String = (Counter::new).identity;
                fn namedConstructorIdentity(): String = (Counter::startingAt).identity;
                fn staticIdentity(): String = (Counter::zero).identity;
                fn idOwner(): String = (Counter::value).id.owner;
                fn idName(): String = (Counter::value).id.name;
                fn idText(): String = (Counter::value).id.text();
                fn isMethodKind(): Boolean =
                    match ((Counter::value).id.kind) {
                        case CallableKind.Method() -> true;
                        case CallableKind.Static() -> false;
                        case CallableKind.Constructor() -> false;
                    };
                fn sameMethod(): Boolean = Counter::value == Counter::value;
                fn differentKinds(): Boolean =
                    (Counter::value).identity != (Counter::new).identity;
                fn readThroughMethod(): Int {
                    let counter = Counter.new(4);
                    let read: Counter -> Int = Counter::value;
                    return read(counter);
                }
                fn constructThroughReference(): Int {
                    let make: (Int) -> &Counter = Counter::new;
                    return make(5).value();
                }
                fn constructNamedThroughReference(): Int {
                    let make: (Int) -> &Counter = Counter::startingAt;
                    return make(6).value();
                }
                fn callStaticThroughReference(): Int {
                    let make: () -> Counter = Counter::zero;
                    return make().value();
                }
                fn invokeMutatingMethod(): Int {
                    let counter = Counter.new(1);
                    let increment: (&Counter, Int) -> Unit = Counter::increment;
                    increment(counter, 3);
                    return counter.value();
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(packageName + ".Counter#method#value",
                        main.getMethod("methodIdentity").invoke(null));
                assertEquals(packageName + ".Counter#constructor#new",
                        main.getMethod("constructorIdentity").invoke(null));
                assertEquals(packageName + ".Counter#constructor#startingAt",
                        main.getMethod("namedConstructorIdentity").invoke(null));
                assertEquals(packageName + ".Counter#static#zero",
                        main.getMethod("staticIdentity").invoke(null));
                assertEquals(packageName + ".Counter", main.getMethod("idOwner").invoke(null));
                assertEquals("value", main.getMethod("idName").invoke(null));
                assertEquals(packageName + ".Counter#method#value",
                        main.getMethod("idText").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("isMethodKind").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("sameMethod").invoke(null));
                assertEquals(Boolean.TRUE, main.getMethod("differentKinds").invoke(null));
                assertEquals(4, main.getMethod("readThroughMethod").invoke(null));
                assertEquals(5, main.getMethod("constructThroughReference").invoke(null));
                assertEquals(6, main.getMethod("constructNamedThroughReference").invoke(null));
                assertEquals(0, main.getMethod("callStaticThroughReference").invoke(null));
                assertEquals(4, main.getMethod("invokeMutatingMethod").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void constructorReferencesAreConstantModelAssignments() throws Exception {
        final var packageName = "callableModel" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-model-");
        final var unit = parse("Main.zn", """
                package %s;
                public class User {
                    public property name: String;
                    public constructor new;
                }
                public contract FactoryModel<T: nullable + mut> requires zeron.lang.Model<T> {
                    property construction: ConstructorRef<(String) -> &T>;
                }
                public model UserFactory is FactoryModel<User> {
                    construction = User::new;
                }
                fn identity(): String = (UserFactory.construction).identity;
                fn construct(): String {
                    let make: (String) -> &User = (UserFactory.construction).callable;
                    return make("Ada").name;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(packageName + ".User#constructor#new",
                        main.getMethod("identity").invoke(null));
                assertEquals("Ada", main.getMethod("construct").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void genericMethodsAndStaticFunctionsSpecializeFromExplicitArguments() throws Exception {
        final var packageName = "callableGeneric" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-generic-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T: nullable + mut> {
                    public property value: T;
                    public constructor new;
                    public map<U: nullable + mut>(transform: (T) -> U): U = transform(this.value);
                    public static fn of<U: nullable + mut>(value: U): Box<U> = Box<U>.new(value);
                }
                fn twice(value: Int): Int = value * 2;
                fn mapped(): Int {
                    let box = Box.new(3);
                    let mapper: (Box<Int>, (Int) -> Int) -> Int = Box<Int>::map::<Int>;
                    return mapper(box, twice);
                }
                fn constructed(): Int {
                    let make: (Int) -> &Box<Int> = Box<Int>::new;
                    return make(4).value;
                }
                fn staticCall(): Int {
                    let make: (Int) -> Box<Int> = Box::of::<Int>;
                    return make(9).value;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(6, main.getMethod("mapped").invoke(null));
                assertEquals(4, main.getMethod("constructed").invoke(null));
                assertEquals(9, main.getMethod("staticCall").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void genericReferencesInferFromFullyConstrainingFunctionTypes() throws Exception {
        final var packageName = "callableInferred" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-inferred-");
        final var unit = parse("Main.zn", """
                package %s;
                public class Box<T: nullable + mut> {
                    public property value: T;
                    public constructor new;
                    public map<U: nullable + mut>(transform: (T) -> U): U =
                        transform(this.value);
                    public static fn make<U: nullable + mut>(value: U): Box<U> =
                        Box<U>.new(value);
                }
                fn twice(value: Int): Int = value * 2;
                fn inferredMethod(): Int {
                    let mapper: (Box<Int>, (Int) -> Int) -> Int = Box<Int>::map;
                    return mapper(Box.new(3), twice);
                }
                fn inferredStatic(): Int {
                    let make: (Int) -> Box<Int> = Box::make;
                    return make(7).value;
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(6, main.getMethod("inferredMethod").invoke(null));
                assertEquals(7, main.getMethod("inferredStatic").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void contractReferencesCarryDeclaredEffects() throws Exception {
        final var packageName = "callableEffects" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-effects-");
        final var unit = parse("Main.zn", """
                package %s;
                public effect Invalid {
                    public property code: Int;
                }
                public contract Loader {
                    load(value: Int): Int raises Invalid;
                }
                public class PositiveLoader is Loader {
                    public constructor new;
                    public load(value: Int): Int raises Invalid {
                        if (value < 0) {
                            raise Invalid.new(1);
                        }
                        return value;
                    }
                }
                fn loadThroughReference(value: Int): Int {
                    let load: (Loader, Int) -> Int raises Invalid = Loader::load;
                    return handle (load(PositiveLoader.new(), value)) with {
                        case Invalid.code(code) -> code;
                    };
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(5, main.getMethod("loadThroughReference", int.class).invoke(null, 5));
                assertEquals(1, main.getMethod("loadThroughReference", int.class).invoke(null, -1));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void contractReferencesUseTheContractIdentityAndDynamicDispatch() throws Exception {
        final var packageName = "callableContract" + suffix();
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-callable-contract-");
        final var unit = parse("Main.zn", """
                package %s;
                public contract Sized {
                    size(): Int;
                }
                public class Box is Sized {
                    public property width: Int;
                    public constructor new;
                    public size(): Int = this.width;
                }
                fn identity(): String = (Sized::size).identity;
                fn throughContract(): Int {
                    let box: Sized = Box.new(7);
                    let size: Sized -> Int = Sized::size;
                    return size(box);
                }
                """.formatted(packageName));
        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)),
                    packageName + ".Main", packageName, List.of(), false, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
                final var main = loader.loadClass(packageName + ".Main");
                assertEquals(packageName + ".Sized#method#size",
                        main.getMethod("identity").invoke(null));
                assertEquals(7, main.getMethod("throughContract").invoke(null));
            }
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void rejectsBoundExtensionWitnessAndStaticContractReferences() {
        assertResolutionFailsContaining("""
                public class User {
                    public property name: String;
                    public constructor new;
                }
                fn broken(): String {
                    let user = User.new("Ada");
                    return (user::name).identity;
                }
                """, "Bound references");
        assertResolutionFailsContaining("""
                public class User {
                    public property name: String;
                    public constructor new;
                }
                extension User {
                    public fn greet(): String = this.name;
                }
                fn broken(): String = (User::greet).identity;
                """, "has no property or callable");
        assertResolutionFailsContaining("""
                public contract Factory {
                    static create(): Int;
                }
                fn broken(): String = (Factory::create).identity;
                """, "has no property or callable");
        assertResolutionFailsContaining("""
                public contract Show {
                    render(): String;
                }
                public class User {
                    public property name: String;
                    public constructor new;
                }
                witness Show for User {
                    fn render(): String = this.name;
                }
                fn broken(): String = (User::render).identity;
                """, "has no property or callable");
    }

    @Test
    public void rejectsInaccessibleAndUninferableCallables() {
        assertResolutionFailsContaining("""
                public class User {
                    private hidden(): String = "secret";
                }
                fn broken(): String = (User::hidden).identity;
                """, "not accessible");
        assertResolutionFailsContaining("""
                public class Box<T: nullable + mut> {
                    public property value: T;
                    public constructor new;
                    public map<U: nullable + mut>(transform: (T) -> U): U =
                        transform(this.value);
                }
                fn broken(): String = (Box<Int>::map).identity;
                """, "needs type arguments");
        assertResolutionFailsContaining("""
                public class User {
                    public label(): String = "Ada";
                }
                fn broken(): String = (User::label::<Int>).identity;
                """, "Expected 0 callable type arguments");
    }

    @Test
    public void referencesRemainStableAcrossSeparateCompilation() throws Exception {
        final var suffix = suffix();
        final var libraryPackage = "callableLibrary" + suffix;
        final var appPackage = "callableClient" + suffix;
        final var libraryOutput = Files.createTempDirectory(Path.of("target"), "zeron-callable-library-");
        final var appOutput = Files.createTempDirectory(Path.of("target"), "zeron-callable-client-");
        final var library = parse("Library.zn", """
                package %s;
                public class User {
                    public property name: String;
                    public constructor new;
                    public label(): String = this.name;
                    public static fn create(name: String): &User = User.new(name);
                }
                """.formatted(libraryPackage));
        try {
            final var libraryCompiler = CompilationService.forCompilationUnits(List.of(library),
                    libraryPackage + ".Library", libraryPackage, List.of(), true, List.of(), libraryOutput);
            libraryCompiler.resolve();
            libraryCompiler.compile();

            final var index = com.maruseron.zeron.domain.ZeronLibraryIndex
                    .readFromDirectory(libraryOutput);
            final var app = parse("Main.zn", """
                    package %s;
                    import %s.User;
                    fn methodIdentity(): String = (User::label).identity;
                    fn staticIdentity(): String = (User::create).identity;
                    fn constructed(): String {
                        let make: (String) -> &User = User::create;
                        return make("Ada").label();
                    }
                    """.formatted(appPackage, libraryPackage));
            final var appCompiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(app)),
                    appPackage + ".Main", appPackage, List.of(index), false, List.of(), appOutput);
            appCompiler.resolve();
            appCompiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{
                    appOutput.toUri().toURL(), libraryOutput.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var main = loader.loadClass(appPackage + ".Main");
                assertEquals(libraryPackage + ".User#method#label",
                        main.getMethod("methodIdentity").invoke(null));
                assertEquals(libraryPackage + ".User#static#create",
                        main.getMethod("staticIdentity").invoke(null));
                assertEquals("Ada", main.getMethod("constructed").invoke(null));
            }
        } finally {
            deleteTree(libraryOutput);
            deleteTree(appOutput);
        }
    }

    private static void assertResolutionFailsContaining(final String body,
                                                        final String expectedMessage) {
        final var packageName = "callableInvalid" + suffix();
        final var output = Path.of("target", "zeron-callable-invalid-" + packageName);
        final var compiler = CompilationService.forCompilationUnits(
                StandardLibrary.withBundledUnits(List.of(parse("Main.zn",
                        "package " + packageName + ";\n" + body))),
                packageName + ".Main", packageName, List.of(), false, List.of(), output);
        final var error = org.junit.Assert.assertThrows(
                com.maruseron.zeron.analize.ResolutionError.class, compiler::resolve);
        assertTrue("Expected '" + expectedMessage + "' in: " + error.getMessage(),
                error.getMessage().contains(expectedMessage));
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
