package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

public class LspWorkspaceTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void overlaysProduceDiagnosticsWithoutWritingBuildOutput() throws Exception {
        final var project = project("""
                fn main() {
                    let answer: Int = 42;
                    answer;
                }
                """);
        final var workspace = ProjectAnalysisWorkspace.open(project.manifest());
        final var overlay = """
                fn main() {
                    let answer: String = 42;
                    answer;
                }
                """;

        final var snapshot = workspace.analyze(Map.of(project.entry(),
                new ProjectAnalysisWorkspace.Overlay(3, overlay)));

        assertEquals(overlay, snapshot.sourceText().get(project.entry()));
        assertTrue(snapshot.diagnostics().get(project.entry()).stream()
                .anyMatch(diagnostic -> diagnostic.severity()
                        == com.maruseron.zeron.diagnostic.Severity.ERROR));
        assertFalse(Files.exists(project.root().resolve("dist")));
        assertTrue(Files.readString(project.entry()).contains("answer: Int"));
    }

    @Test
    public void hoverAndDefinitionUseResolverReferences() throws Exception {
        final var source = """
                fn add(value: Int): Int = value + 1;
                fn main() {
                    let result = add(1);
                    result;
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var resultUseOffset = source.lastIndexOf("result");
        final var resultPosition = DocumentCoordinates.positionAt(source, resultUseOffset);
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(),
                new HoverParams(document, resultPosition));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("Int"));
        final var definition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(document, resultPosition));

        assertTrue(definition.isLeft());
        assertEquals(1, definition.getLeft().size());
        assertEquals(project.entry().toUri().toString(), definition.getLeft().getFirst().getUri());
        assertEquals(2, definition.getLeft().getFirst().getRange().getStart().getLine());
    }

    @Test
    public void hoverFormatsFunctionTypesWithoutStrayGenericTerminator() throws Exception {
        final var source = """
                fn main() {
                    let transform: (Int) -> Int = (value) -> value;
                    transform;
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var offset = source.lastIndexOf("transform");
        final var position = DocumentCoordinates.positionAt(source, offset);
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document, position));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("(Int) -> Int"));
        assertFalse(hover.getContents().getRight().getValue().contains(">(Int)"));
    }

    @Test
    public void hoverShowsInferredLocalAndLambdaParameterTypesAtDeclarations() throws Exception {
        final var source = """
                fn main() {
                    let list = 42;
                    let transform: (Int) -> Int = (i) -> i;
                    list;
                    transform;
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var listHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("list ="))));
        final var parameterHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("(i)") + 1)));

        assertNotNull(listHover);
        assertTrue(listHover.getContents().getRight().getValue().contains("value list: Int"));
        assertNotNull(parameterHover);
        assertTrue(parameterHover.getContents().getRight().getValue().contains("value i: Int"));
    }

    @Test
    public void hoverShowsGenericSumTypeParameters() throws Exception {
        final var source = """
                type List<T> = Cons(value: T) | Empty;
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("List"))));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("sum List<T>"));
    }

    @Test
    public void hoverShowsExtensionMethodSignatureAtCall() throws Exception {
        final var source = """
                class Box {
                    public constructor new;
                }
                extension Box {
                    fn label(): String = "box";
                }
                fn main() {
                    Box.new().label();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("label"))));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("extension on Box"));
        assertTrue(hover.getContents().getRight().getValue().contains("fn label"));
    }

    @Test
    public void hoverShowsWitnessMethodSignatureAtCall() throws Exception {
        final var source = """
                contract Render<T> {
                    toString(): String;
                }
                class Box {
                    public constructor new;
                }
                witness Render<Box> for Box {
                    fn toString(): String = "box";
                }
                fn main() {
                    Box.new().toString();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("toString"))));
        final var content = hover == null ? "" : hover.getContents().getRight().getValue();

        assertNotNull(hover);
        assertTrue(content, content.contains("witness method toString"));
        assertTrue(content, content.contains("String"));
    }

    @Test
    public void genericFunctionCallHoverShowsInferredSubstitutions() throws Exception {
        final var source = """
                fn identity<T>(value: T): T = value;
                fn main() {
                    identity(42);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("identity"))));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("fn identity(Int) -> Int"));
    }

    @Test
    public void genericMemberCallHoverShowsReceiverSubstitutions() throws Exception {
        final var source = """
                public class Box<T> {
                    value: T;
                    public constructor new;
                    public readValue(): T = this.value;
                }
                fn main() {
                    Box<Int>.new(42).readValue();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var resolution = snapshot.resolution().orElseThrow();
        final var callToken = snapshot.tokens().get(project.entry()).stream()
                .filter(token -> token.span().start().offset() == source.lastIndexOf("readValue"))
                .findFirst().orElseThrow();
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("readValue"))));

        assertNotNull("target=" + resolution.referenceTarget(callToken)
                + ", type=" + resolution.referenceType(callToken)
                + ", call signature=" + resolution.callSiteFunctionType(callToken), hover);
        assertTrue(hover.getContents().getRight().getValue().contains("fn readValue() -> Int"));
    }

    @Test
    public void hoverShowsThisAndGenericConstructorTypes() throws Exception {
        final var source = """
                public class Box<T> {
                    value: T;
                    public constructor new;
                    public readValue(): T = this.value;
                }
                fn main() {
                    Box<Int>.new(42);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var thisHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("this"))));
        final var constructorHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("new"))));

        assertNotNull(thisHover);
        assertTrue(thisHover.getContents().getRight().getValue().contains("Box<T>"));
        assertNotNull(constructorHover);
        assertTrue(constructorHover.getContents().getRight().getValue()
                .contains("fn new(Int) -> &Box<Int>"));
        final var constructorCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source,
                        source.lastIndexOf("Box<Int>.new") + "Box<Int>.ne".length()));
        final var constructorItem = constructorCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("new(value: T)")).findFirst().orElseThrow();
        assertEquals("&Box<T>", constructorItem.getDetail());
    }

    @Test
    public void genericExtensionCallHoverOmitsTheImplicitReceiver() throws Exception {
        final var source = """
                class Box {
                    public constructor new;
                }
                extension Box {
                    fn echo<T>(value: T): T = value;
                }
                fn main() {
                    Box.new().echo(42);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("echo"))));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("fn echo(Int) -> Int"));
    }

    @Test
    public void completionIncludesVisibleFunctionsAndReceiverMembers() throws Exception {
        final var source = """
                public class Box {
                    public constructor new;
                    public read(): Int = 42;
                    public call(): Int = this.read();
                    public property count: Int = 42;
                    }
                    let answer: Int = 42;
                    fn add(value: Int): Int = value + 1;
                fn main() {
                    let box = Box.new();
                    box.read();
                    box.count;
                    add(1);
                    answer;
                    Box.new().read();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var addPosition = DocumentCoordinates.positionAt(source, source.indexOf("add(1)") + 2);
        final var addCompletion = LspFeatures.completion(snapshot, project.entry(), addPosition);
        final var addItem = addCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("add(value: Int)")).findFirst().orElseThrow();
        assertEquals("Int", addItem.getDetail());
        final var answerPosition = DocumentCoordinates.positionAt(source, source.indexOf("answer;") + 3);
        final var answerCompletion = LspFeatures.completion(snapshot, project.entry(), answerPosition);
        final var answerItem = answerCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("answer")).findFirst()
                .orElseThrow(() -> new AssertionError(answerCompletion.getItems().toString()));
        assertEquals("Int", answerItem.getDetail());

        final var boxReadOffset = source.indexOf("box.read()");
        final var lineStart = source.lastIndexOf('\n', boxReadOffset) + 1;
        final var globalCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, lineStart + 4));
        assertFalse(globalCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("read")));
        final var countOffset = source.indexOf("box.count") + "box.co".length();
        final var countCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, countOffset));
        final var countItem = countCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("count")).findFirst().orElseThrow();
        assertEquals("Int", countItem.getDetail());

        final var memberOffset = source.indexOf("box.read()") + "box.re".length();
        final var memberPosition = DocumentCoordinates.positionAt(source, memberOffset);
        final var memberCompletion = LspFeatures.completion(snapshot, project.entry(), memberPosition);
        final var readItem = memberCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("read()")).findFirst().orElseThrow();
        assertEquals("Int", readItem.getDetail());

        final var chainedMemberOffset = source.lastIndexOf("Box.new().read") + "Box.new().re".length();
        final var chainedMemberPosition = DocumentCoordinates.positionAt(source, chainedMemberOffset);
        final var chainedMemberCompletion = LspFeatures.completion(
                snapshot, project.entry(), chainedMemberPosition);
        assertTrue(chainedMemberCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("read()")));

        final var constructorOffset = source.lastIndexOf("Box.new().read") + "Box.ne".length();
        final var constructorPosition = DocumentCoordinates.positionAt(source, constructorOffset);
        final var constructorCompletion = LspFeatures.completion(
                snapshot, project.entry(), constructorPosition);
        final var newItem = constructorCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("new()")).findFirst().orElseThrow();
        assertEquals("&Box", newItem.getDetail());

        final var thisOffset = source.indexOf("this.read") + "this.re".length();
        final var thisPosition = DocumentCoordinates.positionAt(source, thisOffset);
        final var thisCompletion = LspFeatures.completion(snapshot, project.entry(), thisPosition);
        assertTrue(thisCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("read()")));
    }

    @Test
    public void sumTypeCompletionPrefersLocalTypeAndOffersCaseConstructors() throws Exception {
        final var source = """
                type List<T> = Nil | Cons(head: T, tail: List<T>);
                extension<T> List<T> {
                    fn custom(): Int = 1;
                }
                namespace List {
                    fn from<T>(value: T): List<T> = List.Cons(value, List.Nil());
                }
                fn main() {
                    let list: List<Int> = List.Cons(1, List.Nil());
                    list.custom();
                    List.from(1);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var offset = source.indexOf("List.Nil") + "List.".length();
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, offset));

        final var nilItem = completion.getItems().stream()
                .filter(item -> item.getLabel().equals("Nil()")).findFirst().orElseThrow();
        assertEquals("&List<T>", nilItem.getDetail());
        final var consItem = completion.getItems().stream()
                .filter(item -> item.getLabel().equals("Cons(head: T, tail: List<T>)"))
                .findFirst().orElseThrow();
        assertEquals("&List<T>", consItem.getDetail());
        final var fromItem = completion.getItems().stream()
                .filter(item -> item.getLabel().equals("from(value: T)")).findFirst().orElseThrow();
        assertEquals("List<T>", fromItem.getDetail());
        assertFalse(completion.getItems().stream()
                .anyMatch(item -> item.getDetail() != null
                        && item.getDetail().contains("zeron.collections.List")));

        final var instanceOffset = source.indexOf("list.custom", source.indexOf("fn main"))
                + "list.".length();
        final var instanceCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, instanceOffset));
        final var customItem = instanceCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("custom()")).findFirst().orElseThrow();
        assertEquals("Int", customItem.getDetail());
        assertFalse(instanceCompletion.getItems().stream()
                .anyMatch(item -> item.getDetail() != null
                        && item.getDetail().contains("zeron.collections.List")));

        final var consOffset = source.lastIndexOf("Cons");
        final var consHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(
                new TextDocumentIdentifier(project.entry().toUri().toString()),
                DocumentCoordinates.positionAt(source, consOffset + 1)));
        assertNotNull(consHover);
        assertTrue(consHover.getContents().getRight().getValue().contains("&List<Int>"));
        assertFalse(consHover.getContents().getRight().getValue().contains("$$sum$"));
    }

    @Test
    public void importedPrivateExtensionCompletesForSourceDefinedReceiver() throws Exception {
        final var project = project("""
                import List.fmap;
                fn main() {
                    let list = List.from(1, 2);
                    list.fmap(value -> value);
                }
                """);
        Files.writeString(project.entry().getParent().resolve("list.zn"), """
                type List<T> = Nil | Cons(head: T, tail: List<T>);
                namespace List {
                    fn from<T>(values: T...): List<T> = List.Nil();
                }
                extension<T> List<T> {
                    fn fmap<U>(map: (T) -> U): List<U> = List.Nil();
                }
                """);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());
        final var offset = source.indexOf("list.") + "list.".length();
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, offset));

        final var fmap = completion.getItems().stream()
                .filter(item -> item.getLabel().startsWith("fmap(")).findFirst()
                .orElseThrow(() -> new AssertionError(completion.getItems().toString()));
        assertEquals("List<U>", fmap.getDetail());
    }

    @Test
    public void importCompletionIncludesExtensionMethods() throws Exception {
        final var project = project("""
                import List.
                fn main() {}
                """);
        Files.writeString(project.entry().getParent().resolve("list.zn"), """
                type List<T> = Nil | Cons(head: T, tail: List<T>);
                extension<T> List<T> {
                    fn fmap<U>(map: (T) -> U): List<U> = List.Nil();
                    fn append(second: List<T>): List<T> = second;
                }
                """);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());
        final var offset = source.indexOf("List.") + "List.".length();
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, offset));

        final var fmap = completion.getItems().stream()
                .filter(item -> item.getLabel().equals("fmap(map: (T) -> U)"))
                .findFirst().orElseThrow(() -> new AssertionError(completion.getItems()
                        .toString()));
        assertEquals("List<U>", fmap.getDetail());
        assertTrue(completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("append(second: List<T>)")));
    }

    @Test
    public void listMonadSampleOffersImportedExtensionCompletion() throws Exception {
        final var manifest = Path.of("sample_programs", "list_monad", "zeron.toml")
                .toAbsolutePath().normalize();
        final var path = manifest.getParent().resolve("src").resolve("main.zn");
        final var source = Files.readString(path);
        final var incompleteSource = source.replace("import List.fmap;", "import List.");
        final var snapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of(
                path, new ProjectAnalysisWorkspace.Overlay(1, incompleteSource)));
        final var offset = incompleteSource.indexOf("List.") + "List.".length();
        final var completion = LspFeatures.completion(snapshot, path,
                DocumentCoordinates.positionAt(incompleteSource, offset));

        assertTrue(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().startsWith("fmap(")));
    }

    private ProjectFiles project(final String source) throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var sourceRoot = Files.createDirectories(root.resolve("src"));
        final var entry = sourceRoot.resolve("main.zn");
        Files.writeString(entry, source);
        final var manifest = root.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                entry = "src/main.zn"
                source-roots = ["src"]
                output = "dist"

                [platform]
                stdlib = "source"
                """);
        return new ProjectFiles(root, entry, manifest);
    }

    private record ProjectFiles(Path root, Path entry, Path manifest) {}
}
