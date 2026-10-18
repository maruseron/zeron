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
                class Box<T> {
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
                }
                fn add(value: Int): Int = value + 1;
                fn main() {
                    let box = Box.new();
                    box.read();
                    add(1);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var addPosition = DocumentCoordinates.positionAt(source, source.indexOf("add(1)") + 2);
        final var addCompletion = LspFeatures.completion(snapshot, project.entry(), addPosition);
        assertTrue(addCompletion.getItems().stream().anyMatch(item -> item.getLabel().equals("add")));

        final var memberOffset = source.indexOf("box.read()") + "box.re".length();
        final var memberPosition = DocumentCoordinates.positionAt(source, memberOffset);
        final var memberCompletion = LspFeatures.completion(snapshot, project.entry(), memberPosition);
        assertTrue(memberCompletion.getItems().stream().anyMatch(item -> item.getLabel().equals("read")));
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
