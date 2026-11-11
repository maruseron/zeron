package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import com.maruseron.zeron.TestPaths;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class LspWorkspaceTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void interruptedAnalysisStopsAtCancellationCheckpoint() throws Exception {
        final var project = project("fn main() {}\n");
        final var workspace = ProjectAnalysisWorkspace.open(project.manifest());
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> workspace.analyze(Map.of()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void includesDeclarationDocumentationInHover() throws Exception {
        final var source = """
                /// Adds two values using the documented API.
                /// The comment is rendered as Markdown.
                public fn documented(left: Int, right: Int): Int = left + right;
                fn use(): Int = documented(1, 2);
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var position = DocumentCoordinates.positionAt(source,
                source.indexOf("public fn documented") + "public fn ".length());
        final var hover = LspFeatures.hover(snapshot, project.entry(),
                new HoverParams(new TextDocumentIdentifier(project.entry().toUri().toString()), position));

        assertNotNull(hover);
        final var contents = hover.getContents().getRight().getValue();
        assertTrue(contents.contains("fn documented"));
        assertTrue(contents.contains("Adds two values using the documented API."));
        assertTrue(contents.contains("The comment is rendered as Markdown."));
    }

    @Test
    public void builtinTypeHoverShowsActivePrimitiveEvidence() throws Exception {
        final var source = """
                fn inspect(value: Int): Int = value;
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "value: Int", "value: ".length());

        assertTrue("Unexpected hover: " + hover, hover.contains("intrinsic type Int"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Evidence available here:"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Integral<Int>"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Display"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Eq<Int>"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Hashable<Int>"));
    }

    @Test
    public void builtinTypeHoverShowsImportedArrayEvidence() throws Exception {
        final var source = """
                import witness zeron.collections.ArrayFoldable;
                fn inspect(values: &Array<Int>): Int = values.length;
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "&Array<Int>", 1);

        assertTrue("Unexpected hover: " + hover, hover.contains("intrinsic type Array<T>"));
        assertTrue("Unexpected hover: " + hover, hover.contains("Foldable<Array<_>>"));
    }

    @Test
    public void builtinTypeHoverOmitsUnimportedArrayEvidence() throws Exception {
        final var source = """
                fn inspect(values: &Array<Int>): Int = values.length;
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "&Array<Int>", 1);

        assertTrue("Unexpected hover: " + hover, hover.contains("intrinsic type Array<T>"));
        assertFalse("Unexpected hover: " + hover, hover.contains("Foldable<Array<_>>"));
    }

    @Test
    public void hoversModelsAsTheirDeclaredContract() throws Exception {
        final var source = """
                package app;

                import zeron.lang.Model;

                public class User {
                    public property name: String;
                    public mut property age: Int;
                    public constructor new;
                }

                public contract SqlModel<T: nullable + mut> requires Model<T> {
                    property tableName: String;
                }

                public model UserWire is SqlModel<User> {
                    tableName = "users";
                    name -> "user_name";
                    age -> "user_age";
                }

                fn consume(model: Model<User>): Unit {}
                fn use(): Unit { consume(UserWire); }
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var declarationHover = hoverAt(snapshot, project.entry(), source,
                "model UserWire", "model ".length());
        final var referenceHover = hoverAt(snapshot, project.entry(), source,
                "consume(UserWire)", "consume(".length());

        assertTrue("Unexpected declaration hover: " + declarationHover
                        + " diagnostics=" + snapshot.diagnostics(),
                declarationHover.contains("model UserWire: SqlModel<User>"));
        assertTrue("Missing contract assignment: " + declarationHover,
                declarationHover.contains("tableName = \"users\""));
        assertTrue("Missing mapped properties: " + declarationHover,
                declarationHover.contains("name -> \"user_name\"")
                        && declarationHover.contains("age -> \"user_age\""));
        assertFalse("Synthetic model type leaked: " + declarationHover,
                declarationHover.contains("$model"));
        assertTrue("Unexpected reference hover: " + referenceHover,
                referenceHover.contains("model UserWire: SqlModel<User>"));
        assertFalse("Synthetic model type leaked: " + referenceHover,
                referenceHover.contains("$model"));
    }

    @Test
    public void wrapsAndUnqualifiesLongHoverSignatures() throws Exception {
        final var source = """
                import zeron.lang.Option;

                fn longSignature(first: Option<Int>, second: Option<Int>, third: Option<Int>,
                                 fourth: Option<Int>, fifth: Option<Int>, sixth: Option<Int>,
                                 seventh: Option<Int>, eighth: Option<Int>): Unit {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "longSignature");

        assertTrue("Unexpected signature hover: " + hover,
                hover.contains("fn longSignature(\n    first: Option<Int>,"));
        assertFalse("Qualified names remained in signature: " + hover,
                hover.contains("zeron.lang.Option"));
    }

    @Test
    public void unqualifiesTypesInTheJsonModelSampleHover() throws Exception {
        final var manifest = TestPaths.projectFile("sample_programs", "jsonmodel", "zeron.toml");
        final var snapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of());
        final var entry = manifest.getParent().resolve(Path.of("src", "main.zn"));
        final var source = Files.readString(entry);
        final var hover = hoverAt(snapshot, entry, source, "let obj", "let ".length());

        assertTrue("Unexpected sample hover: " + hover,
                hover.contains("value obj: Result<&JsonValue, JsonError>"));
        assertFalse("Qualified names remained: " + hover,
                hover.contains("com.maruseron") || hover.contains("zeron.lang"));
    }

    @Test
    public void wrapsAndUnqualifiesLongTypeDisplaysInHover() throws Exception {
        final var source = """
                import zeron.lang.Option;
                import zeron.lang.Result;

                fn longType(obj: (Option<Int>, Option<Int>, Option<Int>, Option<Int>,
                                   Option<Int>, Option<Int>, Option<Int>) -> Unit): Unit {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "obj:");

        assertTrue("Unexpected type hover: " + hover,
                hover.contains("value obj: (\n    Option<Int>,"));
        assertFalse("Qualified names remained in a type: " + hover,
                hover.contains("zeron.lang."));
    }

    @Test
    public void formatsLongStandardLibrarySignaturesInHover() throws Exception {
        final var source = """
                import zeron.serialization.json.jsonModel;

                fn main(): Unit {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "jsonModel");

        assertTrue("Unexpected jsonModel hover: " + hover,
                hover.contains("fn jsonModel<T: nullable + mut>(\n    model: Model<T>,"));
        assertTrue("Missing owner parameter: " + hover,
                hover.contains("owner: T,"));
        assertTrue("Missing block parameter: " + hover,
                hover.contains("block: &JsonModelObjectBuilder<T>.() -> Unit"));
        assertTrue("Missing return type: " + hover,
                hover.contains(") -> Result<&JsonValue, JsonError>"));
        assertFalse("Qualified names remained: " + hover,
                hover.contains("zeron.lang.Model") || hover.contains("com.maruseron"));
    }

    @Test
    public void hoversCodeModelTypes() throws Exception {
        final var source = """
                import zeron.compiler.CodeModel;
                import zeron.compiler.CodeNode;
                fn consume(model: CodeModel<(Int) -> Boolean>): Unit {}
                fn root(value: CodeNode): CodeNode = value;
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var modelHover = hoverAt(snapshot, project.entry(), source, "CodeModel<");
        final var nodeHover = hoverAt(snapshot, project.entry(), source, "CodeNode):");

        assertTrue(modelHover, modelHover.contains("CodeModel"));
        assertTrue(nodeHover, nodeHover.contains("CodeNode"));
    }

    @Test
    public void delegatedComparableHoverShowsContractSignatureAndDocumentation() throws Exception {
        final var project = project("app/Main.zn", """
                package app;
                import model.Widget;
                fn compare(left: Widget, right: Widget): String = left.compareTo(right);
                """);
        final var contractPath = project.root().resolve(Path.of("src", "contracts", "Comparable.zn"));
        Files.createDirectories(contractPath.getParent());
        final var contractSource = """
                package contracts;
                public contract Comparable<T: mut> {
                    /// Evaluates the current instance against `other`.
                    compareTo(other: T): String;
                }
                """;
        Files.writeString(contractPath, contractSource);
        final var witnessPath = project.root().resolve(Path.of("src", "model", "Widget.zn"));
        Files.createDirectories(witnessPath.getParent());
        Files.writeString(witnessPath, """
                package model;
                import contracts.Comparable;
                public class Widget {
                    public constructor new;
                    public compareTo(other: Widget): String = "";
                }
                delegating witness Comparable<Widget> for Widget {}
                """);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, contractPath, contractSource, "compareTo(other:");

        assertNoErrors(snapshot);
        assertTrue("Unexpected hover: " + hover,
                hover.contains("fn compareTo(other: T) -> String"));
        assertTrue(hover, hover.contains("Evaluates the current instance against `other`"));
        assertFalse(hover, hover.contains("extension on zeron.numeric.Integer via"));
    }

    @Test
    public void compiledLibraryHoverUsesDeclarationParameterNames() throws Exception {
        final var packageName = "hoverparams" + java.util.UUID.randomUUID().toString().replace("-", "");
        final var project = project("""
                import %s.jsonModel;
                fn main(): String = jsonModel("name", 1, "block");
                """.formatted(packageName));
        final var libraryRoot = Files.createDirectories(project.root().resolve("lib"));
        final var libraryUnit = Parser.of(Scanner.from("""
                package %s;
                public fn jsonModel<T: nullable + mut>(model: String, owner: T, block: String): String = model;
                """.formatted(packageName)).scanTokens()).parseCompilationUnit("Library.zn");
        final var compiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                packageName + ".Library", packageName, List.of(), true, List.of(), libraryRoot);
        compiler.resolve();
        compiler.compile();
        Files.writeString(project.manifest(), Files.readString(project.manifest()) + """

                [dependencies]
                zeron-libraries = ["lib"]
                """);

        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());
        final var importHover = hoverAt(snapshot, project.entry(), source, "jsonModel");
        final var callHover = hoverAt(snapshot, project.entry(), source, "jsonModel",
                source.lastIndexOf("jsonModel") - source.indexOf("jsonModel"));

        assertTrue("Unexpected import hover: " + importHover,
                importHover.contains("fn jsonModel<T: nullable + mut>("
                        + "model: String, owner: T, block: String) -> String"));
        assertTrue("Unexpected call hover: " + callHover, callHover.contains(
                "fn jsonModel(model: String, owner: Int, block: String) -> String"));
        assertFalse(importHover, importHover.contains("arg0"));
        assertFalse(callHover, callHover.contains("arg0"));
    }

    @Test
    public void compiledLibraryNamespaceMembersAndExtensionsSupportHover() throws Exception {
        final var packageName = "nsmembers" + java.util.UUID.randomUUID().toString().replace("-", "");
        final var project = project("""
                import %s.Box;
                import %s.Box.*;
                fn main() {
                    let a = Box.answer;
                    let b = Box.make();
                    let c = Box.new().measure();
                }
                """.formatted(packageName, packageName));
        final var libraryRoot = Files.createDirectories(project.root().resolve("lib"));
        final var libraryUnit = Parser.of(Scanner.from("""
                package %s;
                public class Box {
                    public constructor new;
                }
                namespace Box {
                    public fn make(): Int = 1;
                    public let answer: Int = 42;
                }
                public extension Box {
                    public fn measure(): Int = 2;
                }
                """.formatted(packageName)).scanTokens()).parseCompilationUnit("Library.zn");
        final var compiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                packageName + ".Library", packageName, List.of(), true, List.of(), libraryRoot);
        compiler.resolve();
        compiler.compile();
        Files.writeString(project.manifest(), Files.readString(project.manifest()) + """

                [dependencies]
                zeron-libraries = ["lib"]
                """);

        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());
        final var answerHover = hoverAt(snapshot, project.entry(), source, "Box.answer",
                "Box.".length());
        final var makeHover = hoverAt(snapshot, project.entry(), source, "Box.make",
                "Box.".length());
        final var measureHover = hoverAt(snapshot, project.entry(), source, "measure");

        assertTrue("hover=" + answerHover + " diagnostics=" + snapshot.diagnostics(),
                answerHover.contains("value answer: Int"));
        assertTrue("hover=" + makeHover + " diagnostics=" + snapshot.diagnostics(),
                makeHover.contains("fn make() -> Int"));
        assertTrue("hover=" + measureHover + " diagnostics=" + snapshot.diagnostics(),
                measureHover.contains("extension on Box"));
    }

    @Test
    public void includesCompiledLibraryDocumentationInHover() throws Exception {
        final var packageName = "hoverdocs" + java.util.UUID.randomUUID().toString().replace("-", "");
        final var project = project("""
                import %s.documented;
                fn main(): Int = documented();
                """.formatted(packageName));
        final var libraryRoot = Files.createDirectories(project.root().resolve("lib"));
        final var libraryUnit = Parser.of(Scanner.from("""
                package %s;
                /// Documentation loaded from the compiled library.
                public fn documented(): Int = 42;
                """.formatted(packageName)).scanTokens()).parseCompilationUnit("Library.zn");
        final var compiler = CompilationService.forCompilationUnits(List.of(libraryUnit),
                packageName + ".Library", packageName, List.of(), true, List.of(), libraryRoot);
        compiler.resolve();
        compiler.compile();
        Files.writeString(project.manifest(), Files.readString(project.manifest()) + """

                [dependencies]
                zeron-libraries = ["lib"]
                """);

        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        assertTrue(snapshot.libraryDocumentation().toString(),
                snapshot.libraryDocumentation().containsKey(packageName + ".documented"));
        final var source = Files.readString(project.entry());
        final var hover = LspFeatures.hover(snapshot, project.entry(),
                new HoverParams(new TextDocumentIdentifier(project.entry().toUri().toString()),
                        DocumentCoordinates.positionAt(source, source.lastIndexOf("documented"))));

        assertNotNull("diagnostics=" + snapshot.diagnostics() + ", resolution="
                + snapshot.resolution().map(result -> result.errors().toString()).orElse("none"), hover);
        assertTrue(hover.getContents().getRight().getValue()
                .contains("Documentation loaded from the compiled library."));
    }

    /*
    @Test
    public void compiledStandardLibraryImportCompletionHoverAndDefinitionIncludeDeclarations()
            throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var sourceRoot = Files.createDirectories(root.resolve("src"));
        final var entry = sourceRoot.resolve("main.zn");
        final var source = """
                import zeron.serialization.DecodeError;
                fn main() {}
                """;
        Files.writeString(entry, source);
        final var manifest = root.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                entry = "src/main.zn"
                source-roots = ["src"]
                output = "dist"

                [platform]
                stdlib = "compiled"
                """);

        final var snapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of());
        assertFalse(snapshot.units().toString(), snapshot.units().stream()
                .anyMatch(unit -> unit.packageName().equals("zeron.serialization")));

        final var hover = hoverAt(snapshot, entry, source, "DecodeError");
        assertTrue(hover, hover.contains("type DecodeError = (message: String, path: Array<String>, position: Option<Int>)"));

        final var definition = LspFeatures.definition(snapshot, entry, new DefinitionParams(
                new TextDocumentIdentifier(entry.toUri().toString()),
                DocumentCoordinates.positionAt(source, source.indexOf("DecodeError"))));
        assertTrue(definition.toString(), definition.isLeft());
        final var locations = definition.getLeft();
        assertEquals(1, locations.size());
        final var location = locations.getFirst();
        assertTrue(location.getUri(), location.getUri().endsWith("serialization.zn"));
        final var definitionText = Files.readString(Path.of(java.net.URI.create(location.getUri())));
        final var definitionOffset = DocumentCoordinates.offsetAt(
                definitionText, location.getRange().getStart());
        assertTrue(definitionText, definitionText.startsWith("DecodeError", definitionOffset));

        final var incompleteImport = source.replace(
                "import zeron.serialization.DecodeError;", "import zeron.serialization.");
        final var importSnapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of(
                entry, new ProjectAnalysisWorkspace.Overlay(1, incompleteImport)));
        final var importOffset = incompleteImport.indexOf("zeron.serialization.")
                + "zeron.serialization.".length();
        final var completion = LspFeatures.completion(importSnapshot, entry,
                DocumentCoordinates.positionAt(incompleteImport, importOffset));
        assertTrue(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("*")));
        final var decodeError = completion.getItems().stream()
                .filter(item -> item.getLabel().equals("DecodeError"))
                .findFirst().orElseThrow(() -> new AssertionError(completion.getItems().toString()));
        assertEquals("type DecodeError = (message: String, path: Array<String>, position: Option<Int>)",
                decodeError.getDetail());
    }
    */

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
    public void codeActionAddsAllMissingContractMethodStubs() throws Exception {
        final var source = """
                contract BaseOperation {
                    inherited(): Unit;
                }
                contract RequiredOperations requires BaseOperation {
                    first(): Unit;
                    second(value: Int): Unit;
                }
                class Implementer is RequiredOperations {
                }
                fn main() {}
                """;
        final var project = project(source);
        final var workspace = ProjectAnalysisWorkspace.open(project.manifest());
        final var initial = workspace.analyze(Map.of(
                project.entry(), new ProjectAnalysisWorkspace.Overlay(1, source)));
        final var conformance = initial.diagnostics().get(project.entry()).stream()
                .filter(diagnostic -> diagnostic.message().contains("Contract 'RequiredOperations'"))
                .findFirst().orElseThrow(() -> new AssertionError(initial.diagnostics().toString()));
        final var diagnosticSpan = conformance.primarySpan();
        final var diagnostic = new org.eclipse.lsp4j.Diagnostic(
                DocumentCoordinates.rangeAt(source, diagnosticSpan.start().offset(), diagnosticSpan.end().offset()),
                conformance.message());
        diagnostic.setCode(conformance.code().value());

        try (final var service = new ZeronTextDocumentService(workspace)) {
            final var uri = project.entry().toUri().toString();
            service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
                    new org.eclipse.lsp4j.TextDocumentItem(uri, "zeron", 1, source)));
            final var params = new CodeActionParams();
            params.setTextDocument(new TextDocumentIdentifier(uri));
            params.setRange(new Range());
            params.setContext(new CodeActionContext(List.of(diagnostic), List.of()));

            final var actions = service.codeAction(params).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, actions.size());
            assertTrue(actions.getFirst().isRight());
            final var action = actions.getFirst().getRight();
            assertEquals("Add missing contract method stubs", action.getTitle());
            assertEquals(org.eclipse.lsp4j.CodeActionKind.QuickFix, action.getKind());

            final var documentEdit = action.getEdit().getDocumentChanges().getFirst().getLeft();
            assertEquals(Integer.valueOf(1), documentEdit.getTextDocument().getVersion());
            final var edit = documentEdit.getEdits().getFirst().getLeft();
            final var start = DocumentCoordinates.offsetAt(source, edit.getRange().getStart());
            final var end = DocumentCoordinates.offsetAt(source, edit.getRange().getEnd());
            final var updated = source.substring(0, start) + edit.getNewText() + source.substring(end);
            assertTrue(updated, updated.contains("public first(): Unit"));
            assertTrue(updated, updated.contains("public second(value: Int): Unit"));
            assertTrue(updated, updated.contains("public inherited(): Unit"));

            final var afterEdit = workspace.analyze(Map.of(
                    project.entry(), new ProjectAnalysisWorkspace.Overlay(2, updated)));
            assertNoErrors(afterEdit);
        }
    }

    @Test
    public void codeActionAddsMissingWitnessMethodStubsIncludingStaticMethods() throws Exception {
        final var source = """
                contract Operations {
                    accept(value: Int): Unit;
                    static reset(value: Int): Unit;
                }
                class Target {
                    public constructor new;
                }
                witness Operations for Target {
                }
                fn main() {}
                """;
        final var project = project(source);
        final var workspace = ProjectAnalysisWorkspace.open(project.manifest());
        final var initial = workspace.analyze(Map.of(
                project.entry(), new ProjectAnalysisWorkspace.Overlay(1, source)));
        final var missingMethod = initial.diagnostics().get(project.entry()).stream()
                .filter(diagnostic -> diagnostic.message().contains("Missing witness implementation"))
                .findFirst().orElseThrow(() -> new AssertionError(initial.diagnostics().toString()));
        final var diagnosticSpan = missingMethod.primarySpan();
        final var diagnostic = new org.eclipse.lsp4j.Diagnostic(
                DocumentCoordinates.rangeAt(source, diagnosticSpan.start().offset(), diagnosticSpan.end().offset()),
                missingMethod.message());
        diagnostic.setCode(missingMethod.code().value());

        try (final var service = new ZeronTextDocumentService(workspace)) {
            final var uri = project.entry().toUri().toString();
            service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
                    new org.eclipse.lsp4j.TextDocumentItem(uri, "zeron", 1, source)));
            final var params = new CodeActionParams();
            params.setTextDocument(new TextDocumentIdentifier(uri));
            params.setRange(new Range());
            params.setContext(new CodeActionContext(List.of(diagnostic), List.of()));

            final var actions = service.codeAction(params).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, actions.size());
            final var action = actions.getFirst().getRight();
            assertEquals("Add missing witness method stubs", action.getTitle());
            final var documentEdit = action.getEdit().getDocumentChanges().getFirst().getLeft();
            final var edit = documentEdit.getEdits().getFirst().getLeft();
            final var start = DocumentCoordinates.offsetAt(source, edit.getRange().getStart());
            final var end = DocumentCoordinates.offsetAt(source, edit.getRange().getEnd());
            final var updated = source.substring(0, start) + edit.getNewText() + source.substring(end);
            assertTrue(updated, updated.contains("fn accept(value: Int): Unit"));
            assertTrue(updated, updated.contains("static fn reset(value: Int): Unit"));

            final var afterEdit = workspace.analyze(Map.of(
                    project.entry(), new ProjectAnalysisWorkspace.Overlay(2, updated)));
            assertNoErrors(afterEdit);
        }
    }

    @Test
    public void manifestlessWorkspaceAnalyzesOpenDocumentsWithoutAnEntryPoint() throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var workspace = ProjectAnalysisWorkspace.openSourceWorkspace(root);
        final var source = root.resolve("Main.zn");
        final var text = """
                import zeron.lang.Option;
                fn answer(value: Option<Int>): Int = 42;
                """;

        final var snapshot = workspace.analyze(Map.of(source,
                new ProjectAnalysisWorkspace.Overlay(1, text)));

        assertEquals(1, snapshot.units().size());
        assertTrue(snapshot.resolution().isPresent());
        assertTrue(snapshot.resolution().orElseThrow().errors().isEmpty());
        assertEquals(text, snapshot.sourceText().get(source));
    }

    @Test
    public void directlyOpenedStandardLibraryRootIsNotBundledAgain() throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var source = standardLibraryOptionSource(root);

        final var workspace = ProjectAnalysisWorkspace.openSourceWorkspace(root);
        final var snapshot = workspace.analyze(Map.of());

        assertEquals(List.of(root.toAbsolutePath().normalize()), workspace.sourceRoots());
        assertEquals(1, snapshot.units().size());
        assertTrue(snapshot.resolution().isPresent());
        assertTrue(snapshot.resolution().orElseThrow().errors().isEmpty());
        assertTrue(snapshot.unassociatedDiagnostics().isEmpty());
        assertTrue(snapshot.sourceText().containsKey(source));
    }

    @Test
    public void repositoryRootSelectsNestedStandardLibrarySources() throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var standardLibraryRoot = root.resolve(Path.of("src", "main", "resources", "stdlib"));
        final var source = standardLibraryOptionSource(standardLibraryRoot);

        final var workspace = ProjectAnalysisWorkspace.openSourceWorkspace(root);
        final var snapshot = workspace.analyze(Map.of());

        assertEquals(List.of(standardLibraryRoot.toAbsolutePath().normalize()), workspace.sourceRoots());
        assertEquals(1, snapshot.units().size());
        assertTrue(snapshot.resolution().isPresent());
        assertTrue(snapshot.resolution().orElseThrow().errors().isEmpty());
        assertTrue(snapshot.sourceText().containsKey(source));
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
    public void definitionNavigatesToBundledStandardLibrarySource() throws Exception {
        final var source = """
                import zeron.lang.Option;
                fn inspect(value: Option<Int>): Option<Int> = value.map((item) -> item);
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var offset = source.indexOf("map(");
        final var definition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(new TextDocumentIdentifier(project.entry().toUri().toString()),
                        DocumentCoordinates.positionAt(source, offset)));

        assertTrue(definition.isLeft());
        assertEquals(1, definition.getLeft().size());
        final var target = Path.of(java.net.URI.create(definition.getLeft().getFirst().getUri()));
        assertTrue(target.toString(), target.endsWith(Path.of("stdlib", "zeron", "lang", "option.zn")));
        final var targetSource = Files.readString(target);
        assertTrue(targetSource.contains("public type Option<T: nullable + mut>"));
        final var mapDeclaration = targetSource.indexOf("public fn map<B: mut>");
        assertTrue(mapDeclaration >= 0);
        final var expectedPosition = DocumentCoordinates.positionAt(targetSource, mapDeclaration);
        assertEquals(expectedPosition.getLine(),
                definition.getLeft().getFirst().getRange().getStart().getLine());
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
        assertTrue(hover.getContents().getRight().getValue().contains("Int -> Int"));
        assertFalse(hover.getContents().getRight().getValue().contains("((Int)"));
    }

    @Test
    public void hoverFormatsReceiverFunctionTypes() throws Exception {
        final var source = """
                contract Scope {}
                fn invoke(scope: Scope, callback: Scope.() -> Int): Int = scope.callback();
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("callback:"))));

        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("Scope.() -> Int"));
    }

    @Test
    public void completionFormatsHandlerTypesInMemberSignatures() throws Exception {
        final var source = """
                effect Failure {}
                handler catchFailure {
                    case Failure -> "";
                }
                public class HandlerApi {
                    public constructor new;
                    public apply(policy: String ~> {Failure} raises {}): Unit {}
                }
                fn main() {
                    let api = HandlerApi.new();
                    api.apply(catchFailure);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        assertTrue(snapshot.diagnostics().toString(),
                snapshot.diagnostics().get(project.entry()).isEmpty());
        final var offset = source.indexOf("api.ap") + "api.ap".length();
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, offset));
        final var apply = completion.getItems().stream()
                .filter(item -> item.getLabel().startsWith("apply("))
                .findFirst().orElseThrow(() -> new AssertionError(completion.getItems().toString()));

        assertEquals("apply(policy: String ~> {Failure})", apply.getLabel());
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
        assertTrue(hover.getContents().getRight().getValue().contains("type List<T>"));
    }

    @Test
    public void hoverShowsTypeParameterConstraintsAtDeclarationsAndOnValues() throws Exception {
        final var source = """
                contract Tagged<T> {}
                public class Box<T: Tagged<T>> {
                    public constructor new;
                }
                extension<T: Tagged<T>> Box<T> {
                    fn marker(): Int = 1;
                }
                fn keep<T: Tagged<T>>(value: T, box: Box<T>): T = value;
                fn borrow<T: nullable + mut>(value: T): T = value;
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var classParameterHover = hoverAt(snapshot, project.entry(), source,
                "class Box<T", "class Box<".length());
        final var extensionParameterHover = hoverAt(snapshot, project.entry(), source,
                "extension<T", "extension<".length());
        final var functionParameterHover = hoverAt(snapshot, project.entry(), source,
                "fn keep<T", "fn keep<".length());
        final var parameterUseHover = hoverAt(snapshot, project.entry(), source,
                "value: T", "value: ".length());
        final var markerParameterHover = hoverAt(snapshot, project.entry(), source,
                "fn borrow<T", "fn borrow<".length());
        final var valueHover = hoverAt(snapshot, project.entry(), source, "value: T");
        final var genericValueHover = hoverAt(snapshot, project.entry(), source, "box: Box");

        assertTrue("class=" + classParameterHover + " diagnostics=" + snapshot.diagnostics(),
                classParameterHover.contains("type parameter T: Tagged<T>"));
        assertTrue(extensionParameterHover,
                extensionParameterHover.contains("type parameter T: Tagged<T>"));
        assertTrue(functionParameterHover,
                functionParameterHover.contains("type parameter T: Tagged<T>"));
        assertTrue(markerParameterHover,
                markerParameterHover.contains("type parameter T: nullable + mut"));
        assertTrue(parameterUseHover,
                parameterUseHover.contains("type parameter T: Tagged<T>"));
        assertTrue(valueHover, valueHover.contains("value value: T"));
        assertTrue(valueHover, valueHover.contains("type parameter T: Tagged<T>"));
        assertTrue(genericValueHover, genericValueHover.contains("value box: Box<T>"));
        assertTrue(genericValueHover,
                genericValueHover.contains("type parameter T: Tagged<T>"));
    }

    @Test
    public void hoverDescribesContractsClassesEffectsAndNamedHandlers() throws Exception {
        final var source = """
                contract Marker<T> {}
                contract Named<T> requires Marker<T> {}
                public class Widget<T> is Marker<T> {}
                public effect Failure {}
                public handler Recover {
                    case Failure -> "";
                }
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var contractHover = hoverAt(snapshot, project.entry(), source, "Named<T>");
        final var classHover = hoverAt(snapshot, project.entry(), source, "Widget<T>");
        final var effectHover = hoverAt(snapshot, project.entry(), source, "Failure");
        final var handlerHover = hoverAt(snapshot, project.entry(), source, "Recover");

        assertTrue(contractHover, contractHover.contains("contract Named<T> : Marker<T>"));
        assertTrue(classHover, classHover.contains("class Widget<T> is Marker<T>"));
        assertTrue(effectHover, effectHover.contains("effect Failure"));
        assertTrue(handlerHover, handlerHover.contains("handler Recover"));
    }

    @Test
    public void dataClassHoverShowsComponentsAndContracts() throws Exception {
        final var source = """
                contract PairLike<T> {}
                public data class PairBox<T>(left: T, right: Int) is PairLike<T> {}
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "PairBox<T>");

        assertTrue(hover, hover.contains("data class PairBox<T>(left: T, right: Int) is PairLike<T>"));
    }

    @Test
    public void classHoverSeparatesDeclaredConformanceFromInScopeWitnesses() throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var sourceRoot = Files.createDirectories(root.resolve("src"));
        final var entry = sourceRoot.resolve(Path.of("app", "Main.zn"));
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, """
                package app;
                import model.Widget;
                import instances.*;
                fn inspect(value: Widget): Unit {}
                """);
        final var outOfScope = sourceRoot.resolve(Path.of("client", "Main.zn"));
        Files.createDirectories(outOfScope.getParent());
        Files.writeString(outOfScope, """
                package client;
                import model.Widget;
                fn inspect(value: Widget): Unit {}
                """);
        final var model = sourceRoot.resolve(Path.of("model", "Widget.zn"));
        Files.createDirectories(model.getParent());
        Files.writeString(model, """
                package model;
                public contract Display {}
                public class Widget is Display deriving Eq {}
                """);
        final var instance = sourceRoot.resolve(Path.of("instances", "WidgetTag.zn"));
        Files.createDirectories(instance.getParent());
        Files.writeString(instance, """
                package instances;
                import model.Widget;
                public contract Tag<T> {
                    label(): String;
                }
                witness Tag<Widget> for Widget {
                    fn label(): String = "widget";
                }
                """);
        final var manifest = root.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                entry = "src/app/Main.zn"
                source-roots = ["src"]
                output = "dist"

                [platform]
                stdlib = "source"
                """);

        final var snapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of());
        final var appSource = Files.readString(entry);
        final var appHover = hoverAt(snapshot, entry, appSource, "Widget):");
        final var clientSource = Files.readString(outOfScope);
        final var clientHover = hoverAt(snapshot, outOfScope, clientSource, "Widget):");

        assertTrue(appHover, appHover.contains("class Widget is Display"));
        assertTrue(appHover, appHover.contains("Eq<Widget>"));
        assertTrue(appHover, appHover.contains("Tag<Widget>"));
        assertTrue(clientHover, clientHover.contains("Eq<Widget>"));
        assertFalse(clientHover, clientHover.contains("Tag<Widget>"));
    }

    @Test
    public void definitionNavigatesFromImportedTypeReferencesToTheirDeclaration() throws Exception {
        final var project = project("import model.Widget;\n"
                + "fn echo(value: Widget): Widget = value;\n");
        final var modelDirectory = Files.createDirectories(project.entry().getParent().resolve("model"));
        final var model = modelDirectory.resolve("Widget.zn");
        Files.writeString(model, """
                package model;
                public class Widget {}
                """);
        final var source = Files.readString(project.entry());
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        assertTrue(snapshot.sourceText().keySet().toString(),
                snapshot.sourceText().containsKey(model.toAbsolutePath().normalize()));
        assertTrue(snapshot.units().toString(),
                snapshot.units().stream().anyMatch(unit -> unit.packageName().equals("model")));
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var typeUseOffset = source.indexOf("Widget):");
        final var typeHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, typeUseOffset)));
        assertNotNull(typeHover);
        assertTrue(typeHover.getContents().getRight().getValue(),
                typeHover.getContents().getRight().getValue().contains("class Widget"));
        assertTrue(snapshot.filesByCompilerPath().toString(),
                snapshot.filesByCompilerPath().containsValue(model.toAbsolutePath().normalize()));
        final var definition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(document, DocumentCoordinates.positionAt(source, typeUseOffset)));

        assertTrue(definition.isLeft());
        assertEquals(1, definition.getLeft().size());
        assertEquals(model.toUri().toString(), definition.getLeft().getFirst().getUri());
        assertEquals(1, definition.getLeft().getFirst().getRange().getStart().getLine());
    }

    @Test
    public void truncatesLongSumTypeHoverAtTheConfiguredCharacterLimit() throws Exception {
        final var cases = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "Case" + index)
                .collect(java.util.stream.Collectors.joining(" | "));
        final var source = "type Huge = " + cases + ";\nfn main() {}\n";
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("Huge"))));

        assertNotNull(hover);
        final var line = hover.getContents().getRight().getValue().lines()
                .filter(value -> value.startsWith("type Huge = ")).findFirst().orElseThrow();
        assertEquals(180, line.length());
        assertTrue(line.endsWith("..."));
    }

    @Test
    public void typeHoversShowSumCasesAndProductMembers() throws Exception {
        final var source = """
                type Outcome<T> = Success(value: T) | Failure(message: String);
                type Position = (x: Int, y: Int);
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());

        final var sumHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("Outcome"))));
        final var productHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("Position"))));

        assertNotNull(sumHover);
        assertTrue(sumHover.getContents().getRight().getValue()
                .contains("type Outcome<T> = Success(T) | Failure(String)"));
        assertNotNull(productHover);
        assertTrue(productHover.getContents().getRight().getValue()
                .contains("type Position = (x: Int, y: Int)"));
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
        assertTrue(content, content.contains("extension on Box via Render<Box>"));
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
        assertTrue("Unexpected identity hover: " + hover.getContents().getRight().getValue(),
                hover.getContents().getRight().getValue().contains("fn identity(value: Int) -> Int"));
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
    public void completionUsesReturnTypeOfChainedMemberCalls() throws Exception {
        final var source = """
                public class Box {
                    public constructor new;
                    public next(step: Int): Box = this;
                    public read(): Int = 42;
                }
                fn main() {
                    Box.new().next(1).read();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var resolution = snapshot.resolution().orElseThrow();
        final var nextToken = snapshot.tokens().get(project.entry()).stream()
                .filter(token -> token.span().start().offset() == source.indexOf("next(1)"))
                .findFirst().orElseThrow();
        final var nextCallType = resolution.callSiteFunctionType(nextToken);

        assertNotNull(resolution.referenceTarget(nextToken));
        assertNotNull(nextCallType);
        assertEquals("Box", com.maruseron.zeron.domain.TypeFormatter.format(nextCallType.returnType()));

        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, source.lastIndexOf("read")));
        assertTrue(completion.getItems().stream().anyMatch(item -> item.getLabel().startsWith("read(")));
    }

    @Test
    public void immutableReceiverMutationHasMutationDiagnostic() throws Exception {
        final var source = """
                class Counter {
                    value: Int;
                    public constructor new;
                    public mut increment(): Unit {
                        this.value = this.value + 1;
                    }
                }
                fn main(counter: Counter) {
                    counter.increment();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        assertTrue(snapshot.diagnostics().get(project.entry()).toString(),
                snapshot.diagnostics().get(project.entry()).stream()
                .anyMatch(diagnostic -> diagnostic.code().value().equals("ZR2012")
                        && diagnostic.message().contains("Mutating method requires a mutable reference.")));
    }

    @Test
    public void hoverShowsThisAndGenericConstructorTypes() throws Exception {
        final var source = """
                public class Box<T> {
                    value: T;
                    public constructor new;
                    public readValue(): T = this.value;
                    public readImplicitValue(): T = value;
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
        final var valueHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("this.value") + "this.".length())));
        final var implicitValueHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("= value;") + "= ".length())));
        final var constructorHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("new"))));

        assertNotNull(thisHover);
        assertTrue(thisHover.getContents().getRight().getValue().contains("Box<T>"));
        assertNotNull(valueHover);
        assertTrue(valueHover.getContents().getRight().getValue().contains("T"));
        assertNotNull(implicitValueHover);
        assertTrue(implicitValueHover.getContents().getRight().getValue().contains("T"));
        assertNotNull(constructorHover);
        assertTrue(constructorHover.getContents().getRight().getValue(),
                constructorHover.getContents().getRight().getValue()
                        .contains("constructor new(Int) -> &Box<Int>"));
        final var constructorCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source,
                        source.lastIndexOf("Box<Int>.new") + "Box<Int>.ne".length()));
        final var constructorItem = constructorCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("new(value: T)")).findFirst().orElseThrow();
        assertEquals("&Box<T>", constructorItem.getDetail());
    }

    @Test
    public void hoverShowsSumCasesGenericParametersRaisesAndExternalModifiers() throws Exception {
        final var source = """
                public effect Failure {
                    public property message: String;
                }
                public type Outcome<T, E> = Good(value: T) | Bad(error: E);
                public class Box<T> {
                    value: T;
                    public constructor new;
                    public constructor start() raises Failure = raise Failure.new("failed");
                    public map<R>(transform: (T) -> R): Box<R> raises Failure =
                        Box<R>.new(transform(this.value));
                }
                external fn native(value: Int): Int;
                public external class JavaThing = "java.lang.String" {
                    public constructor new();
                }
                fn useJavaThing(value: JavaThing): Unit {}
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());

        final var sumHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("Outcome"))));
        final var sumCaseHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("Good("))));
        final var constructorHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("start()"))));
        final var methodHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("map<R>"))));
        final var functionHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.indexOf("native"))));
        final var externalClassHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("JavaThing"))));
        final var externalClassDefinition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(document, DocumentCoordinates.positionAt(source,
                        source.indexOf("JavaThing):"))));

        assertNotNull(sumHover);
        assertTrue(sumHover.getContents().getRight().getValue()
                .contains("type Outcome<T, E> = Good(T) | Bad(E)"));
        assertNotNull(sumCaseHover);
        assertTrue(sumCaseHover.getContents().getRight().getValue()
                .contains("constructor Good<T, E>(T) -> Outcome<T, E>"));
        assertNotNull(constructorHover);
        assertTrue(constructorHover.getContents().getRight().getValue(),
                constructorHover.getContents().getRight().getValue()
                        .contains("constructor start() -> &Box<T> raises {Failure}"));
        assertNotNull(methodHover);
        assertTrue(methodHover.getContents().getRight().getValue()
                .contains("fn map<R>"));
        assertTrue(methodHover.getContents().getRight().getValue()
                .contains("raises {Failure}"));
        assertNotNull(functionHover);
        assertTrue(functionHover.getContents().getRight().getValue().contains("external fn native"));
        assertNotNull(externalClassHover);
        assertTrue(externalClassHover.getContents().getRight().getValue()
                .contains("external class JavaThing = String"));
        assertTrue(externalClassDefinition.isLeft());
        assertEquals(DocumentCoordinates.positionAt(source, source.lastIndexOf("JavaThing ="))
                        .getLine(),
                externalClassDefinition.getLeft().getFirst().getRange().getStart().getLine());
    }

    @Test
    public void hoverShowsSourceNameForRefinedSumCaseTypes() throws Exception {
        final var source = """
                public type Option<T: nullable> = Some(value: T) | None;
                fn inspect<T: nullable>(option: Option<T>): Option<T> {
                    let new = option;
                    if (new matches Option.None()) { return new; }
                    return new;
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "new;\n}");

        assertTrue("Unexpected hover: " + hover, hover.contains("Option.Some<T>"));
        assertFalse(hover, hover.contains("$$sum$"));
    }

    @Test
    public void boundedGenericAndHigherKindedMemberCallsShowHover() throws Exception {
        final var source = """
                import zeron.lang.Hashable;
                public contract Foldable<F<_>> {
                    foldLeft<A: mut, B: mut>(
                        this: F<A>, initial: B, combine: (B, A) -> B
                    ): B;
                    default count<A: mut>(this: F<A>): Int =
                        this.foldLeft(0, (count, _) -> count + 1);
                }
                fn hashKey<T: Hashable<T>>(key: T): Long = key.hash();
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hashHover = hoverAt(snapshot, project.entry(), source, "key.hash", "key.".length());
        final var foldableHover = hoverAt(snapshot, project.entry(), source,
                "this.foldLeft", "this.".length());

        assertTrue("Unexpected hash hover: " + hashHover, hashHover.contains("fn hash() -> Long"));
        assertTrue("Unexpected foldLeft hover: " + foldableHover, foldableHover.contains("fn foldLeft"));
    }

    @Test
    public void witnessMethodHoverUsesSourceNamesInListMonadSample() throws Exception {
        final var manifest = TestPaths.projectFile("sample_programs", "list_monad", "zeron.toml")
                .toAbsolutePath().normalize();
        final var path = manifest.getParent().resolve("src").resolve("main.zn");
        final var source = Files.readString(path);
        final var snapshot = ProjectAnalysisWorkspace.open(manifest).analyze(Map.of());
        final var mapHover = hoverAt(snapshot, path, source, "map(");
        final var listPath = manifest.getParent().resolve("src").resolve("list.zn");
        final var listSource = Files.readString(listPath);
        final var witnessDeclarationHovers = List.of(
                hoverAt(snapshot, listPath, listSource, "toString():"),
                hoverAt(snapshot, listPath, listSource, "map<A:"),
                hoverAt(snapshot, listPath, listSource, "flatMap<A:"),
                hoverAt(snapshot, listPath, listSource, "pure<A:"),
                hoverAt(snapshot, listPath, listSource, "ap<A:"));
        final var witnessCallHovers = List.of(
                hoverAt(snapshot, listPath, listSource, "tail.map", "tail.".length()),
                hoverAt(snapshot, listPath, listSource, "head.combine", "head.".length()),
                hoverAt(snapshot, listPath, listSource, "transform.flatMap", "transform.".length()),
                hoverAt(snapshot, listPath, listSource, "head.toString", "head.".length()));

        assertTrue("Unexpected map hover: " + mapHover, mapHover.contains("extension on List"));
        assertFalse("Unexpected map hover: " + mapHover, mapHover.contains("$$sum$"));
        assertTrue("Unexpected witness declarations: " + witnessDeclarationHovers,
                witnessDeclarationHovers.stream().allMatch(hover -> hover.contains("fn ")));
        assertFalse("Unexpected witness declarations: " + witnessDeclarationHovers,
                witnessDeclarationHovers.stream()
                        .anyMatch(hover -> hover.contains("$$sum$") || hover.contains("$zeron$witness$")));
        assertTrue("Missing witness call hovers: " + witnessCallHovers,
                witnessCallHovers.stream().allMatch(hover -> !hover.isBlank()));
        assertFalse("Unexpected witness calls: " + witnessCallHovers,
                witnessCallHovers.stream().anyMatch(hover ->
                        hover.contains("$$sum$") || hover.contains("$zeron$witness$")));
        assertTrue("Expected a source-level map signature: " + witnessCallHovers.getFirst(),
                witnessCallHovers.getFirst().contains("fn map<"));
    }

    @Test
    public void hoversUnaryFunctionArgumentWithoutParentheses() throws Exception {
        final var source = """
                import zeron.collections.ArrayList;
                fn main() {
                    let list = ArrayList<Int>.of();
                    list.stream().map(i -> i * 2);
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "map");

        assertTrue(hover, hover.contains("fn map(Int -> Int) -> Stream<Int>"));
        assertFalse(hover, hover.contains("((Int)"));
    }

    @Test
    public void hoversInferredGenericReceiverCall() throws Exception {
        final var source = """
                import zeron.collections.List;
                fn listToString<T: Display>(list: List<T>): String =
                    list.fold("[", (acc, i) -> acc + i.toString());
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var hover = hoverAt(snapshot, project.entry(), source, "fold");

        assertTrue(hover, hover.contains("fn fold(String, (String, T) -> String) -> String"));
        assertFalse(hover, hover.contains("<R"));
    }

    @Test
    public void receiverFunctionMembersSupportCompletionHoverAndNavigation() throws Exception {
        final var source = """
                public class Box {
                    public constructor new;
                    public read(): Int = 42;
                }
                fn apply(box: Box, body: Box.() -> Int): Int = box.body();
                fn result(box: Box): Int = apply(box, () -> this.read());
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var memberOffset = source.indexOf("this.read") + "this.".length();
        final var memberPosition = DocumentCoordinates.positionAt(source, memberOffset);
        final var completion = LspFeatures.completion(snapshot, project.entry(), memberPosition);
        final var hover = LspFeatures.hover(snapshot, project.entry(),
                new HoverParams(document, memberPosition));
        final var definition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(document, memberPosition));

        assertTrue(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("read()")));
        assertNotNull(hover);
        assertTrue(hover.getContents().getRight().getValue().contains("fn read() -> Int"));
        assertTrue(definition.isLeft());
        assertEquals(DocumentCoordinates.positionAt(source, source.indexOf("public read"))
                        .getLine(),
                definition.getLeft().getFirst().getRange().getStart().getLine());
    }

    @Test
    public void qualifiedJavaTypeCompletionDoesNotSuggestSameNamedZeronType() throws Exception {
        final var source = """
                package zeron.math;
                public class BigInteger {}
                fn use(value: java.math.Big): Unit {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, source.indexOf("Big):") + "Big".length()));

        assertFalse(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("BigInteger")));
    }

    @Test
    public void hoverAndDefinitionSupportHigherKindedImplementationMethods() throws Exception {
        final var source = """
                contract Mapper<F<_>> {
                    map<A, B>(this: F<A>, transform: (A) -> B): F<B>;
                }
                class Box<T> is Mapper<Box<_>> {
                    value: T;
                    public constructor new;
                    implementation Mapper<Box<_>> {
                        fn map<A, B>(this: Box<A>, transform: (A) -> B): Box<B> =
                            Box<B>.new(transform(this.value));
                    }
                }
                fn main() {}
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var methodOffset = source.indexOf("fn map") + "fn ".length();
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, methodOffset)));
        final var definition = LspFeatures.definition(snapshot, project.entry(),
                new DefinitionParams(document, DocumentCoordinates.positionAt(source, methodOffset)));

        assertNotNull(hover);
        final var content = hover.getContents().getRight().getValue();
        assertTrue(content, content.contains("extension on Box<_> via Mapper<Box<_>>"));
        assertTrue(content, content.contains("fn map"));
        assertTrue(definition.isLeft());
        assertEquals(1, definition.getLeft().size());
        assertEquals(DocumentCoordinates.positionAt(source, methodOffset).getLine(),
                definition.getLeft().getFirst().getRange().getStart().getLine());
    }

    /*@Test
    public void hoverPrefersAbstractContractMethodOverDelegatingWitnessSignature() throws Exception {
        final var source = """
                public contract Contract1<T: nullable + mut> {
                    eq(other: T): Boolean;
                }

                public contract Contract2<T: mut> requires Eq<T> {}

                public class Implementer {
                    public eq(other: Implementer): Boolean = true;
                }

                delegating witness Contract2<Implementer> for Implementer {}
                """;
    }*/

    @Test
    public void hoverPrefersLocalBindingOverSameNamedTopLevelFunction() throws Exception {
        final var source = """
                fn row(): String = "global";
                class Row {
                    public constructor new;
                    public render(): String = "local";
                }
                fn inspect(rows: Array<Row>) {
                    for (let row in rows) row.render();
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var document = new TextDocumentIdentifier(project.entry().toUri().toString());
        final var hover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(document,
                DocumentCoordinates.positionAt(source, source.lastIndexOf("row.render"))));
        final var content = hover == null ? "" : hover.getContents().getRight().getValue();

        assertNotNull(hover);
        assertTrue(content, content.contains("Row"));
        assertFalse(content, content.contains("fn row"));
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
        assertTrue("Unexpected echo hover: " + hover.getContents().getRight().getValue(),
                hover.getContents().getRight().getValue().contains("fn echo<T>(value: Int) -> Int"));
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
                        && item.getDetail().contains("zeron.collections.ArrayList")));

        final var instanceOffset = source.indexOf("list.custom", source.indexOf("fn main"))
                + "list.".length();
        final var instanceCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, instanceOffset));
        final var customItem = instanceCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("custom()")).findFirst().orElseThrow();
        assertEquals("Int", customItem.getDetail());
        assertFalse(instanceCompletion.getItems().stream()
                .anyMatch(item -> item.getDetail() != null
                        && item.getDetail().contains("zeron.collections.ArrayList")));

        final var consOffset = source.lastIndexOf("Cons");
        final var consHover = LspFeatures.hover(snapshot, project.entry(), new HoverParams(
                new TextDocumentIdentifier(project.entry().toUri().toString()),
                DocumentCoordinates.positionAt(source, consOffset + 1)));
        assertNotNull(consHover);
        assertTrue(consHover.getContents().getRight().getValue().contains("&List<Int>"));
        assertFalse(consHover.getContents().getRight().getValue().contains("$$sum$"));
    }

    @Test
    public void importedNamespaceMembersAndExtensionsSupportHoverAndDefinition() throws Exception {
        final var project = project("app/Main.zn", """
                package app;
                import lib.Box;
                import lib.Box.*;
                fn main() {
                    let a = Box.answer;
                    let b = Box.make();
                    let c = Box.new().measure();
                }
                """);
        final var sourceRoot = project.entry().getParent().getParent();
        final var library = sourceRoot.resolve("lib/Box.zn");
        Files.createDirectories(library.getParent());
        Files.writeString(library, """
                package lib;
                public class Box {
                    public constructor new;
                }
                namespace Box {
                    public fn make(): Int = 1;
                    public let answer: Int = 42;
                }
                public extension Box {
                    public fn measure(): Int = 2;
                }
                """);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());

        assertNoErrors(snapshot);
        final var answerHover = hoverAt(snapshot, project.entry(), source, "Box.answer",
                "Box.".length());
        final var makeHover = hoverAt(snapshot, project.entry(), source, "Box.make",
                "Box.".length());
        final var measureHover = hoverAt(snapshot, project.entry(), source, "measure");
        assertTrue("Unexpected answer hover: " + answerHover, answerHover.contains("value answer: Int"));
        assertTrue("Unexpected make hover: " + makeHover, makeHover.contains("fn make() -> Int"));
        assertTrue("Unexpected measure hover: " + measureHover,
                measureHover.contains("extension on Box"));

        final var answerDefinition = definitionAt(snapshot, project.entry(), source,
                "Box.answer", "Box.".length());
        final var makeDefinition = definitionAt(snapshot, project.entry(), source,
                "Box.make", "Box.".length());
        final var measureDefinition = definitionAt(snapshot, project.entry(), source, "measure", 0);
        assertDefinition(library, answerDefinition, "answer");
        assertDefinition(library, makeDefinition, "make");
        assertDefinition(library, measureDefinition, "measure");
    }

    @Test
    public void explicitlyImportedNamespaceMembersAndExtensionsSupportHoverAndDefinition()
            throws Exception {
        final var project = project("app/Main.zn", """
                package app;
                import lib.Box;
                import lib.Box.answer;
                import lib.Box.make;
                import lib.Box.measure;
                fn main() {
                    let a = answer;
                    let b = make();
                    let c = Box.new().measure();
                }
                """);
        final var sourceRoot = project.entry().getParent().getParent();
        final var library = sourceRoot.resolve("lib/Box.zn");
        Files.createDirectories(library.getParent());
        Files.writeString(library, """
                package lib;
                public class Box {
                    public constructor new;
                }
                namespace Box {
                    public fn make(): Int = 1;
                    public let answer: Int = 42;
                }
                public extension Box {
                    public fn measure(): Int = 2;
                }
                """);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var source = Files.readString(project.entry());

        assertNoErrors(snapshot);
        final var answerHover = hoverAt(snapshot, project.entry(), source,
                "= answer", "= ".length());
        final var makeHover = hoverAt(snapshot, project.entry(), source, "= make", "= ".length());
        final var measureHover = hoverAt(snapshot, project.entry(), source, "measure");
        assertTrue("Unexpected answer hover: " + answerHover, answerHover.contains("value answer: Int"));
        assertTrue("Unexpected make hover: " + makeHover, makeHover.contains("fn make() -> Int"));
        assertTrue("Unexpected measure hover: " + measureHover,
                measureHover.contains("extension on Box"));

        final var answerDefinition = definitionAt(snapshot, project.entry(), source,
                "= answer", "= ".length());
        final var makeDefinition = definitionAt(snapshot, project.entry(), source,
                "= make", "= ".length());
        final var measureDefinition = definitionAt(snapshot, project.entry(), source, "measure", 0);
        assertDefinition(library, answerDefinition, "answer");
        assertDefinition(library, makeDefinition, "make");
        assertDefinition(library, measureDefinition, "measure");
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
                .filter(item -> item.getLabel().equals("fmap(map: T -> U)"))
                .findFirst().orElseThrow(() -> new AssertionError(completion.getItems()
                        .toString()));
        assertEquals("List<U>", fmap.getDetail());
        assertTrue(completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("append(second: List<T>)")));
    }

    @Test
    public void namespaceWildcardImportCompletionIncludesMethodsAndProperties() throws Exception {
        final var project = project("""
                import Box.*;
                fn main() {
                    let box = Box.new();
                    box.imported();
                }
                """);
        Files.writeString(project.entry().getParent().resolve("Box.zn"), """
                public class Box {
                    public constructor new;
                }
                """);
        Files.writeString(project.entry().getParent().resolve("Extensions.zn"), """
                public extension Box {
                    public fn imported(): Int = 1;
                    public property enabled: Boolean {
                        get = true;
                    }
                }
                """);

        final var source = Files.readString(project.entry());
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var receiverOffset = source.indexOf("box.imported") + "box.".length();
        final var receiverCompletion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, receiverOffset));
        assertTrue("items=" + receiverCompletion.getItems()
                        + ", imports=Box.*",
                receiverCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().startsWith("imported(")));
        assertTrue(receiverCompletion.getItems().toString(), receiverCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("enabled")));

        final var incompleteImport = source.replace("import Box.*;", "import Box.");
        final var importSnapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of(
                project.entry(), new ProjectAnalysisWorkspace.Overlay(1, incompleteImport)));
        final var importOffset = incompleteImport.indexOf("import Box.") + "import Box.".length();
        final var importCompletion = LspFeatures.completion(importSnapshot, project.entry(),
                DocumentCoordinates.positionAt(incompleteImport, importOffset));
        assertTrue(importCompletion.getItems().toString(), importCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("*")));
        assertTrue(importCompletion.getItems().toString(), importCompletion.getItems().stream()
                .anyMatch(item -> item.getLabel().startsWith("imported(")));
    }

    /*
    // TODO: decide whether this test should stay. i lean no, since it's incredibly coupled
    // and the sample program is subject to change
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
        */

    @Test
    public void completionIsSuppressedInsideComments() throws Exception {
        final var source = """
                fn main() {
                    // ad
                    /// do
                    /* re */
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var lineComment = source.indexOf("// ad") + "// ad".length();
        final var docComment = source.indexOf("/// do") + "/// do".length();
        final var blockComment = source.indexOf("/* re") + "/* re".length();

        assertTrue(LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, lineComment)).getItems().isEmpty());
        assertTrue(LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, docComment)).getItems().isEmpty());
        assertTrue(LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, blockComment)).getItems().isEmpty());
    }

    @Test
    public void completionExcludesGeneratedWitnessDefinitions() throws Exception {
        final var source = """
                package zeron.lang;

                fn local(): Int = 1;
                fn main() {
                }
                """;
        final var project = project("zeron/lang/profile.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, source.lastIndexOf('}')));

        assertTrue(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().equals("local()")));
        assertFalse(completion.getItems().toString(), completion.getItems().stream()
                .anyMatch(item -> item.getLabel().startsWith("$zeron$")));
    }

    @Test
    public void completionContinuesForIncompleteMemberAccess() throws Exception {
        final var source = """
                public class Predicate {
                    public constructor new;
                    public test(): Boolean = true;
                }
                fn main(predicate: Predicate) {
                    predicate.
                }
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());
        final var receiverOffset = source.indexOf("predicate.");
        final var completion = LspFeatures.completion(snapshot, project.entry(),
                DocumentCoordinates.positionAt(source, receiverOffset + "predicate.".length()));
        final var receiver = snapshot.tokens().get(project.entry()).stream()
                .filter(token -> token.span().start().offset() == receiverOffset)
                .findFirst().orElseThrow();
        final var receiverType = snapshot.resolution().map(resolution ->
                resolution.referenceType(receiver)).orElse(null);

        assertTrue(snapshot.diagnostics().toString(), snapshot.diagnostics().values().stream()
                .flatMap(List::stream)
                .anyMatch(diagnostic -> diagnostic.message().contains("Expect member name after '.'")));
        assertTrue("completion=" + completion.getItems() + ", receiverType=" + receiverType
                + ", diagnostics=" + snapshot.diagnostics(),
                completion.getItems().stream()
                .anyMatch(item -> item.getLabel().startsWith("test(")));
    }

    @Test
    public void hoversQualifiedJavaTypesAndExternalMembers() throws Exception {
        final var source = """
                package app;

                import java.io.File;
                import java.io.PrintStream;
                import java.lang.System;

                fn pathOf(file: File): String? = file.getPath();
                fn stream(): &PrintStream? = System.out;
                fn elapsed(): Long = System.nanoTime();
                fn sizeOf(values: &java.util.List<String>): Int = values.size();
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var listHover = hoverAt(snapshot, project.entry(), source,
                "java.util.List<String>", "java.util.".length());
        assertTrue("Unexpected Java type hover: " + listHover,
                listHover.contains("external interface java.util.List"));
        final var memberHover = hoverAt(snapshot, project.entry(), source,
                "file.getPath", "file.".length());
        assertTrue("Unexpected Java member hover: " + memberHover,
                memberHover.contains("external fn java.io.File.getPath() -> java.lang.String"));
        assertTrue("Missing facade binding in hover: " + memberHover,
                memberHover.contains("external fn getPath() -> String?"));
        final var fieldHover = hoverAt(snapshot, project.entry(), source,
                "System.out", "System.".length());
        assertTrue("Unexpected Java field hover: " + fieldHover,
                fieldHover.contains("java static field java.lang.System.out: java.io.PrintStream"));
        assertTrue("Missing facade property in hover: " + fieldHover,
                fieldHover.contains("external static property out"));
        final var methodHover = hoverAt(snapshot, project.entry(), source,
                "System.nanoTime", "System.".length());
        assertTrue("Unexpected Java static method hover: " + methodHover,
                methodHover.contains("external static fn java.lang.System.nanoTime() -> long"));
        assertTrue("Missing facade method in hover: " + methodHover,
                methodHover.contains("external fn nanoTime() -> Long"));
        assertTrue("Missing definition link in hover: " + memberHover,
                memberHover.contains("[Go to definition]("));
    }

    @Test
    public void includesDefinitionLinkInHover() throws Exception {
        final var source = """
                fn add(left: Int, right: Int): Int = left + right;
                fn use(): Int = add(1, 2);
                """;
        final var project = project(source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var referenceHover = hoverAt(snapshot, project.entry(), source, "add(1");
        assertTrue(referenceHover, referenceHover.contains("[Go to definition]("
                + project.entry().toUri() + "#L1)"));

        final var declarationHover = hoverAt(snapshot, project.entry(), source,
                "fn add", "fn ".length());
        assertFalse(declarationHover, declarationHover.contains("[Go to definition]("));
    }

    @Test
    public void completesFacadeAndJavaMembers() throws Exception {
        final var source = """
                package app;

                import java.io.File;
                import java.lang.System;

                fn open(path: String): Boolean = File.new(path).exists();
                fn nameOf(file: File): String? = file.getName();
                fn stream(): &java.io.PrintStream? = System.out;
                fn sizeOf(values: &java.util.List<String>): Int = values.size();
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var constructorCompletion = completionAt(snapshot, project.entry(), source,
                "File.new", "File.".length());
        assertTrue("file=" + constructorCompletion.getItems(), constructorCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().equals("new(path: String)")));
        assertFalse("file=" + constructorCompletion.getItems(), constructorCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().startsWith("exists(")));

        final var instanceCompletion = completionAt(snapshot, project.entry(), source,
                "file.getName", "file.".length());
        assertTrue("file=" + instanceCompletion.getItems(), instanceCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().equals("getName()")));
        assertTrue("file=" + instanceCompletion.getItems(), instanceCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().equals("exists()")));

        final var staticCompletion = completionAt(snapshot, project.entry(), source,
                "System.out", "System.".length());
        assertTrue("system=" + staticCompletion.getItems(), staticCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().equals("out")));
        assertTrue("system=" + staticCompletion.getItems(), staticCompletion.getItems()
                .stream().anyMatch(item -> item.getLabel().equals("getenv(name: String?)")));

        final var javaCompletion = completionAt(snapshot, project.entry(), source,
                "values.size", "values.".length());
        final var sizeItem = javaCompletion.getItems().stream()
                .filter(item -> item.getLabel().equals("size()")).findFirst()
                .orElseThrow(() -> new AssertionError(javaCompletion.getItems().toString()));
        assertEquals("Int", sizeItem.getDetail());
    }

    @Test
    public void definesFacadeMembersAndUsesJdkSourcesWhenInstalled() throws Exception {
        final var source = """
                package app;

                import java.io.File;

                fn pathOf(file: File): String? = file.getPath();
                fn sizeOf(values: &java.util.List<String>): Int = values.size();
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var facadeDefinition = definitionAt(snapshot, project.entry(), source,
                "file.getPath", "file.".length());
        assertEquals(1, facadeDefinition.size());
        assertTrue(facadeDefinition.getFirst().getUri(),
                facadeDefinition.getFirst().getUri().endsWith("File.zn"));
        final var jdkSources = Files.isRegularFile(
                Path.of(System.getProperty("java.home"), "lib", "src.zip"));
        assertEquals(jdkSources ? 1 : 0, definitionAt(snapshot, project.entry(), source,
                "java.util.List<String>", "java.util.".length()).size());
        assertEquals(jdkSources ? 1 : 0, definitionAt(snapshot, project.entry(), source,
                "values.size", "values.".length()).size());
    }

    @Test
    public void definesJavaTypesAndMembersInAttachedSources() throws Exception {
        final var source = """
                package app;

                import java.io.File;

                fn pathOf(file: File): String? = file.getPath();
                fn sizeOf(values: &java.util.List<String>): Int = values.size();
                """;
        final var project = projectWithJavaSources(source, Map.of(
                "java/io/File.java", """
                        package java.io;

                        public class File {
                            public String getPath() { return null; }
                        }
                        """,
                "java/util/List.java", """
                        package java.util;

                        public interface List<E> {
                            int size();
                        }
                        """));
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var typeDefinition = definitionAt(snapshot, project.entry(), source,
                "java.util.List<String>", "java.util.".length());
        assertEquals(1, typeDefinition.size());
        assertTrue(typeDefinition.getFirst().getUri(),
                typeDefinition.getFirst().getUri().endsWith("List.java"));
        assertEquals(2, typeDefinition.getFirst().getRange().getStart().getLine());

        final var memberDefinition = definitionAt(snapshot, project.entry(), source,
                "values.size", "values.".length());
        assertEquals(1, memberDefinition.size());
        assertTrue(memberDefinition.getFirst().getUri(),
                memberDefinition.getFirst().getUri().endsWith("List.java"));
        assertEquals(3, memberDefinition.getFirst().getRange().getStart().getLine());

        final var facadeDefinition = definitionAt(snapshot, project.entry(), source,
                "file.getPath", "file.".length());
        assertEquals(1, facadeDefinition.size());
        assertTrue(facadeDefinition.getFirst().getUri(),
                facadeDefinition.getFirst().getUri().endsWith("File.zn"));
    }

    @Test
    public void definesJdkTypesAndMembersInInstalledSources() throws Exception {
        final var srcZip = Path.of(System.getProperty("java.home"), "lib", "src.zip");
        assumeTrue("JDK sources are not installed: " + srcZip, Files.isRegularFile(srcZip));
        final var source = """
                package app;

                fn sizeOf(values: &java.util.List<String>): Int = values.size();
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var typeDefinition = definitionAt(snapshot, project.entry(), source,
                "java.util.List<String>", "java.util.".length());
        assertEquals(1, typeDefinition.size());
        assertTrue(typeDefinition.getFirst().getUri(),
                typeDefinition.getFirst().getUri().endsWith("/List.java"));
        assertEquals("List", textAtRange(typeDefinition.getFirst(),
                Files.readString(Path.of(URI.create(typeDefinition.getFirst().getUri())))));

        final var memberDefinition = definitionAt(snapshot, project.entry(), source,
                "values.size", "values.".length());
        assertEquals(1, memberDefinition.size());
        assertTrue(memberDefinition.getFirst().getUri(),
                memberDefinition.getFirst().getUri().endsWith("/List.java"));
    }

    @Test
    public void definesJavaMembersInSidecarSourceArchives() throws Exception {
        final var source = """
                package app;

                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;

                fn result(): Int = Fixture.add(19, 23);
                """;
        final var project = project("app/Main.zn", source);
        final var library = Files.createDirectories(project.root().resolve("lib"));
        writeFixtureJar(library.resolve("fixture.jar"));
        final var sources = library.resolve("fixture-sources.jar");
        try (final var out = new JarOutputStream(Files.newOutputStream(sources))) {
            out.putNextEntry(new JarEntry(
                    "com/maruseron/zeron/fixtures/JavaInteropFixture.java"));
            out.write(Files.readAllBytes(TestPaths.projectFile(
                    "src", "test", "java", "com", "maruseron", "zeron", "fixtures",
                    "JavaInteropFixture.java")));
            out.closeEntry();
        }
        Files.writeString(project.manifest(), Files.readString(project.manifest())
                + "\n[dependencies]\njava-classpath = [\"lib/fixture.jar\"]\n");
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var definition = definitionAt(snapshot, project.entry(), source,
                "Fixture.add", "Fixture.".length());
        assertEquals(1, definition.size());
        assertTrue(definition.getFirst().getUri(),
                definition.getFirst().getUri().endsWith("/JavaInteropFixture.java"));
        assertEquals("add", textAtRange(definition.getFirst(),
                Files.readString(Path.of(URI.create(definition.getFirst().getUri())))));
    }

    @Test
    public void leavesJavaReferencesWithoutSourceOrFacadeUndefined() throws Exception {
        final var source = """
                package app;

                import com.maruseron.zeron.fixtures.JavaInteropFixture as Fixture;

                fn result(): Int = Fixture.add(19, 23);
                """;
        final var project = project("app/Main.zn", source);
        final var library = Files.createDirectories(project.root().resolve("lib"));
        writeFixtureJar(library.resolve("fixture.jar"));
        Files.writeString(project.manifest(), Files.readString(project.manifest())
                + "\n[dependencies]\njava-classpath = [\"lib/fixture.jar\"]\n");
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        assertTrue(definitionAt(snapshot, project.entry(), source,
                "Fixture.add", "Fixture.".length()).isEmpty());
    }

    @Test
    public void hoversAndDefinesSumCaseNamesInMatches() throws Exception {
        final var source = """
                package app;

                public type JsonError = Encode(message: String) | Parse(message: String);

                public contract Encode {}

                fn getErrorMessage(error: JsonError): String = match (error) {
                    case Encode(message) -> message;
                    case JsonError.Parse(message) -> message;
                };
                """;
        final var project = project("app/Main.zn", source);
        final var snapshot = ProjectAnalysisWorkspace.open(project.manifest()).analyze(Map.of());

        final var bareHover = hoverAt(snapshot, project.entry(), source,
                "case Encode", "case ".length());
        assertTrue(bareHover, bareHover.contains("constructor Encode"));
        assertFalse("hover=" + bareHover + " diagnostics=" + snapshot.diagnostics()
                        + " resolution=" + snapshot.resolution()
                        .map(result -> result.errors().toString()).orElse("none"),
                bareHover.contains("contract Encode"));

        final var qualifiedHover = hoverAt(snapshot, project.entry(), source,
                "JsonError.Parse", "JsonError.".length());
        assertTrue(qualifiedHover, qualifiedHover.contains("constructor Parse"));

        final var definition = definitionAt(snapshot, project.entry(), source,
                "case Encode", "case ".length());
        assertEquals(1, definition.size());
        assertEquals(project.entry().toUri().toString(), definition.getFirst().getUri());
        assertEquals("Encode", textAtRange(definition.getFirst(), source));
    }

    @Test
    public void hoversStdlibJsonErrorCaseWithoutMatchingTheEncodeContract() throws Exception {
        final var stdlib = TestPaths.projectFile("src", "main", "resources", "stdlib");
        final var path = stdlib.resolve(Path.of("zeron", "serialization", "json", "typed.zn"));
        final var source = Files.readString(path);
        final var snapshot = ProjectAnalysisWorkspace.openSourceWorkspace(stdlib).analyze(Map.of());
        final var offset = source.indexOf("case Encode(message)") + "case ".length();

        final var hover = LspFeatures.hover(snapshot, path, new HoverParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                DocumentCoordinates.positionAt(source, offset)));
        assertNotNull(hover);
        final var contents = hover.getContents().getRight().getValue();
        assertTrue(contents, contents.contains("constructor Encode"));
        assertFalse(contents, contents.contains("contract Encode"));

        final var definition = LspFeatures.definition(snapshot, path, new DefinitionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                DocumentCoordinates.positionAt(source, offset)));
        assertEquals(1, definition.getLeft().size());
        final var location = definition.getLeft().getFirst();
        assertTrue(location.getUri(), location.getUri().endsWith("/json.zn"));
        assertEquals("Encode", textAtRange(location,
                Files.readString(Path.of(URI.create(location.getUri())))));
    }

    private static String textAtRange(final Location location, final String text) {
        final var range = location.getRange();
        final var line = text.lines().toList().get(range.getStart().getLine());
        return line.substring(range.getStart().getCharacter(), range.getEnd().getCharacter());
    }

    private static void assertDefinition(final Path expectedFile,
                                         final List<?> locations,
                                         final String expectedText) throws Exception {
        assertEquals(locations.toString(), 1, locations.size());
        final var location = (Location) locations.getFirst();
        assertEquals(expectedFile.toUri().toString(), location.getUri());
        assertEquals(expectedText, textAtRange(location, Files.readString(expectedFile)));
    }

    private static void assertNoErrors(final ProjectAnalysisWorkspace.Snapshot snapshot) {
        final var errors = snapshot.diagnostics().values().stream()
                .flatMap(List::stream)
                .filter(diagnostic -> diagnostic.severity()
                        == com.maruseron.zeron.diagnostic.Severity.ERROR)
                .toList();
        assertTrue("Unexpected errors: " + errors + " diagnostics=" + snapshot.diagnostics(),
                errors.isEmpty());
    }

    private static void writeFixtureJar(final Path jar) throws Exception {
        final var resource = "/com/maruseron/zeron/fixtures/JavaInteropFixture.class";
        try (final var input = LspWorkspaceTest.class.getResourceAsStream(resource);
             final var out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) {
                throw new IllegalStateException("Java interop fixture class is missing.");
            }
            out.putNextEntry(new JarEntry(resource.substring(1)));
            out.write(input.readAllBytes());
            out.closeEntry();
        }
    }

    private ProjectFiles projectWithJavaSources(final String source,
                                                final Map<String, String> javaSources) throws Exception {
        final var project = project("app/Main.zn", source);
        final var sourceRoot = Files.createDirectories(project.root().resolve("java-src"));
        for (final var entry : javaSources.entrySet()) {
            final var file = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        Files.writeString(project.manifest(), Files.readString(project.manifest())
                + "\n[dependencies]\njava-sources = [\"java-src\"]\n");
        return project;
    }

    private static CompletionList completionAt(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                               final Path path, final String source,
                                               final String searchedText, final int relativeOffset) {
        final var offset = source.indexOf(searchedText);
        return LspFeatures.completion(snapshot, path,
                DocumentCoordinates.positionAt(source, offset + relativeOffset));
    }

    private static List<? extends Location> definitionAt(
            final ProjectAnalysisWorkspace.Snapshot snapshot, final Path path,
            final String source, final String searchedText, final int relativeOffset) {
        final var offset = source.indexOf(searchedText);
        final var definition = LspFeatures.definition(snapshot, path, new DefinitionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                DocumentCoordinates.positionAt(source, offset + relativeOffset)));
        return definition.isLeft() ? definition.getLeft() : List.of();
    }

    private ProjectFiles project(final String source) throws Exception {
        return project("main.zn", source);
    }

    private ProjectFiles project(final String relativePath, final String source) throws Exception {
        final var root = temporaryFolder.newFolder().toPath();
        final var sourceRoot = Files.createDirectories(root.resolve("src"));
        final var entry = sourceRoot.resolve(relativePath);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, source);
        final var manifest = root.resolve("zeron.toml");
        Files.writeString(manifest, """
                [project]
                entry = "src/%s"
                source-roots = ["src"]
                output = "dist"

                [platform]
                stdlib = "source"
                """.formatted(relativePath.replace('\\', '/')));
        return new ProjectFiles(root, entry, manifest);
    }

    private Path standardLibraryOptionSource(final Path standardLibraryRoot) throws Exception {
        final var source = standardLibraryRoot.resolve(Path.of("zeron", "lang", "option.zn"));
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package zeron.lang;
                public type Option<T: nullable> = Some(value: T) | None;
                """);
        return source.toAbsolutePath().normalize();
    }

    private static String hoverAt(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                  final Path path, final String source, final String searchedText) {
        return hoverAt(snapshot, path, source, searchedText, 0);
    }

    private static String hoverAt(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                  final Path path, final String source,
                                  final String searchedText, final int relativeOffset) {
        final var offset = source.indexOf(searchedText);
        final var hover = LspFeatures.hover(snapshot, path, new HoverParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                DocumentCoordinates.positionAt(source, offset + relativeOffset)));
        return hover == null ? "" : hover.getContents().getRight().getValue();
    }

    private record ProjectFiles(Path root, Path entry, Path manifest) {}
}
