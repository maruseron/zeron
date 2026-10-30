package com.maruseron.zeron;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.StandardLibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CliParserTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesExistingProjectCommandsAndOptions() throws Exception {
        final var manifest = createProjectManifest("project.toml", "project-src");
        final var build = (CliParser.ProjectCommand) parse(
                "build", "--manifest", manifest.toString(), "-d");
        assertFalse(build.launch());
        assertEquals(manifest, build.manifest());
        assertTrue(build.debug());

        final var run = (CliParser.ProjectCommand) parse("run", "--manifest", manifest.toString());
        assertTrue(run.launch());
        assertEquals(manifest, run.manifest());
        assertFalse(run.debug());
    }

    @Test
    public void parsesLspStdioTransportOption() throws Exception {
        final var manifest = createProjectManifest("lsp-project.toml", "lsp-src");
        final var command = (CliParser.LspCommand) parse(
                "lsp", "--stdio", "--manifest", manifest.toString());

        assertEquals(Optional.of(manifest), command.manifest());
        assertTrue(invalid("lsp", "--stdio", "--stdio").message().contains("only be specified once"));
        assertTrue(invalid("lsp", "--unknown").message().contains("Unknown build option"));
    }

    @Test
    public void parsesManifestlessLspCommandWhenNoDefaultManifestExists() {
        assertFalse(Files.exists(Path.of("zeron.toml")));

        final var command = (CliParser.LspCommand) parse("lsp", "--stdio");
        assertEquals(Optional.empty(), command.manifest());
        assertEquals(Optional.empty(), command.manifest());
        assertTrue(invalid("lsp", "--manifest").message().contains("requires a TOML file"));
    }

    @Test
    public void parsesStandaloneFileAndProjectCompilations() throws Exception {
        final var source = createFile("Main.zn");
        final var library = createFile("library.jar");
        final var host = createDirectory("host");
        final var output = temporaryFolder.getRoot().toPath().resolve("program.jar");
        final var files = (CliParser.Compile) parse(
                source.toString(), "--library", library.toString(), "--java-classpath", host.toString(),
                "--stdlib", "compiled", "--jar-output", output.toString(), "--debug");
        assertEquals(List.of(source.toString()), files.sourceFiles());
        assertEquals(List.of(library), files.libraries());
        assertEquals(List.of(host), files.javaClassPathRoots());
        assertFalse(files.bundleStandardLibrarySources());
        assertEquals(output, files.jarOutput());
        assertTrue(files.debug());

        final var sourceRoot = createDirectory("src");
        final var entry = Path.of("app", "Main.zn");
        createFile(sourceRoot.resolve(entry));
        final var project = (CliParser.Compile) parse(
                "--root", sourceRoot.toString(), "--entry", entry.toString());
        assertEquals(List.of(sourceRoot), project.sourceRoots());
        assertEquals(entry, project.entry());
        assertTrue(project.sourceFiles().isEmpty());
    }

    @Test
    public void parsesReplAndStandardLibraryBuildCommands() throws Exception {
        assertFalse(((CliParser.Repl) parse()).debug());
        final var outputDirectory = temporaryFolder.getRoot().toPath().resolve("stdlib");
        final var jarOutput = temporaryFolder.getRoot().toPath().resolve("stdlib.jar");
        final var build = (CliParser.BuildStandardLibrary) parse(
                "--build-stdlib", outputDirectory.toString(), "--jar-output", jarOutput.toString());
        assertEquals(outputDirectory, build.outputDirectory());
        assertEquals(jarOutput, build.jarOutput());

        assertTrue(parse("init") instanceof CliParser.Initialize);
        assertEquals("ZR2014", ((CliParser.Explain) parse("explain", "ZR2014")).code());
        assertEquals(Optional.empty(), ((CliParser.Help) parse("help")).topic());
        assertEquals(Optional.of("--manifest"), ((CliParser.Help) parse("help", "--manifest")).topic());
        final var index = createFile(ZeronVersions.API_INDEX_FILE_NAME);
        assertEquals(index,
                ((CliParser.Dump) parse("dump", index.toString())).source());
        assertEquals(Optional.empty(),
                ((CliParser.DumpStandardLibrary) parse("dump", "--stdlib")).packageName());
        assertEquals(Optional.of("zeron.collections"),
                ((CliParser.DumpStandardLibrary) parse("dump", "--stdlib", "zeron.collections"))
                        .packageName());

        final var format = (CliParser.Format) parse("fmt", "--check", "src");
        assertTrue(format.check());
        assertEquals(List.of(Path.of("src")), format.paths());
        assertTrue(CliHelp.hasTopic("fmt"));
    }

    @Test
    public void parsesVersionCommandsAndReservesOnlyTheBareVersionPath() throws Exception {
        assertTrue(parse("version") instanceof CliParser.Version);
        assertTrue(parse("--version") instanceof CliParser.Version);
        assertTrue(invalid("version", "extra").message().contains("Usage: zeron version"));
        assertTrue(invalid("--version", "extra").message().contains("Usage: zeron --version"));
        assertTrue(CliHelp.hasTopic("version"));
        assertTrue(CliHelp.hasTopic("--version"));

        final var source = createFile("version.zn");
        assertTrue(parse(source.toString()) instanceof CliParser.Compile);
    }

    @Test
    public void printsTheEmbeddedProjectVersionForBothCommands() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("version"));
            assertEquals(0, Zeron.runCli("--version"));
        } finally {
            System.setOut(originalOut);
        }

        final var projectPom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        final var versionMatcher = Pattern.compile(
                "<artifactId>zeron</artifactId>\\s*<version>([^<]+)</version>").matcher(projectPom);
        assertTrue("Could not find the Maven project version in pom.xml.", versionMatcher.find());
        final var projectVersion = versionMatcher.group(1);
        assertEquals(projectVersion, ZeronVersion.current());
        assertEquals("zeron " + projectVersion + System.lineSeparator()
                        + "zeron " + projectVersion + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
        assertEquals("development", ZeronVersion.read(null));
    }

    @Test
    public void preservesLastSpecifiedValueForRepeatedScalarOptions() throws Exception {
        final var firstManifest = createProjectManifest("first.toml", "first-src");
        final var secondManifest = createProjectManifest("second.toml", "second-src");
        final var project = (CliParser.ProjectCommand) parse(
                "build", "--manifest", firstManifest.toString(), "--manifest", secondManifest.toString());
        assertEquals(secondManifest, project.manifest());

        final var sourceRoot = createDirectory("src");
        createFile(sourceRoot.resolve("first.zn"));
        createFile(sourceRoot.resolve("second.zn"));
        final var compilation = (CliParser.Compile) parse(
                "--root", sourceRoot.toString(), "--entry", "first.zn", "--entry", "second.zn",
                "--stdlib", "compiled", "--stdlib", "source");
        assertEquals(Path.of("second.zn"), compilation.entry());
        assertTrue(compilation.bundleStandardLibrarySources());
    }

    @Test
    public void rejectsUnknownOptionsAndMissingOptionValues() {
        assertTrue(invalid("Main.zn", "--debgu").message().contains("Unknown option"));
        invalid("--root");
        invalid("build", "--manifest");
        invalid("explain");
        invalid("init", "--debug");
        assertTrue(invalid("explain", "ZR9999").message().contains("Unknown Zeron diagnostic code"));
        assertTrue(invalid("explain", "not-a-code").message().contains("ZR0000"));
        assertTrue(invalid("help", "unknown").message().contains("Unknown help topic"));
        invalid("help", "build", "run");
        invalid("dump");
        invalid("dump", "index.bin", "extra");
        invalid("dump", "--stdlib", "not-a-package");
        invalid("dump", "--stdlib", "zeron.collections", "extra");
        invalid("fmt", "--unknown");
        invalid("fmt", "--check", "--check");
    }

    @Test
    public void rejectsIncompleteOrMixedProjectInputsAndUnknownOptions() {
        invalid("--root", "src");
        invalid("--entry", "Main.zn");
        invalid("Main.zn", "--root", "src", "--entry", "Main.zn");

        invalid("-starts-with-dash.zn");
    }

    @Test
    public void rejectsMissingAndWrongKindPathArguments() throws Exception {
        final var missing = temporaryFolder.getRoot().toPath().resolve("missing.zn");
        assertTrue(invalid("build", "--manifest", missing.toString()).message()
                .contains("Project manifest must be an existing file"));
        assertTrue(invalid("dump", missing.toString()).message().contains("Dump source"));
        assertTrue(invalid(missing.toString()).message().contains("Source file must be an existing file"));

        final var directory = createDirectory("not-a-file");
        assertTrue(invalid("build", "--manifest", directory.toString()).message()
                .contains("Project manifest must be an existing file"));
        final var invalidProject = createFile("invalid-project.toml");
        Files.writeString(invalidProject,
                "[project]\nentry = \"missing/Main.zn\"\nsource-roots = [\"missing\"]\n",
                StandardCharsets.UTF_8);
        assertTrue(invalid("build", "--manifest", invalidProject.toString()).message()
                .contains("Project source root must be an existing directory"));
        assertTrue(invalid(directory.toString()).message().contains("Source file must be an existing file"));

        final var source = createFile("Main.zn");
        assertTrue(invalid("--root", source.toString(), "--entry", "Main.zn").message()
                .contains("Source root must be an existing directory"));
        assertTrue(invalid(source.toString(), "--library", directory.toString()).message()
                .contains("API index in Zeron library root"));

        final var outputFile = createFile("not-a-directory");
        final var outputDirectory = createDirectory("output.jar");
        assertTrue(invalid("--build-stdlib", outputFile.toString()).message()
                .contains("Standard-library output must be a directory"));
        assertTrue(invalid("--build-stdlib", temporaryFolder.getRoot().toPath().resolve("stdlib").toString(),
                "--jar-output", outputDirectory.toString()).message()
                .contains("JAR output must be a file path"));
        assertTrue(invalid("--build-stdlib", outputFile.resolve("child").resolve("stdlib").toString())
                .message().contains("parent must resolve"));
    }

    @Test
    public void preservesExplicitErrorsForRetiredOptions() {
        assertTrue(invalid("--run-class", "Main").message().contains("replaced"));
        assertTrue(invalid("--stdlib-jar", "stdlib.jar").message().contains("no longer supported"));
    }

    @Test
    public void reportsArgumentErrorsAsMessagesAndReturnsUsageExitCode() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalError = System.err;
        try {
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(64, Zeron.runCli("Main.zn", "--typo"));
        } finally {
            System.setErr(originalError);
        }
        assertEquals("error: Unknown option: --typo" + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));

        output.reset();
        try {
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(64, Zeron.runCli(temporaryFolder.getRoot().toPath()
                    .resolve("missing.zn").toString()));
        } finally {
            System.setErr(originalError);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Source file must be an existing file"));
    }

    @Test
    public void printsOverviewAndFocusedHelp() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("help"));
            assertEquals(0, Zeron.runCli("help", "dump"));
            assertEquals(0, Zeron.runCli("help", "--manifest"));
            assertEquals(0, Zeron.runCli("help", "version"));
            assertEquals(0, Zeron.runCli("help", "--version"));
        } finally {
            System.setOut(originalOut);
        }
        final var help = output.toString(StandardCharsets.UTF_8);
        assertTrue(help, help.contains("zeron dump <api-index|class-directory|library.jar>"));
        assertTrue(help, help.contains("zeron dump --stdlib [package]"));
        assertTrue(help, help.contains("Usage: zeron dump"));
        assertTrue(help, help.contains("Usage: zeron version"));
        assertTrue(help, help.contains("Alias for 'zeron version'"));
        assertTrue(help, help.contains("Select the project manifest"));
    }

    @Test
    public void dumpCommandDelegatesToLibraryIndexDumper() throws Exception {
        final var indexPath = Files.createTempFile(temporaryFolder.getRoot().toPath(), "zeron-dump-cli-", ".bin");
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        try {
            new ZeronLibraryIndex(StandardLibrary.API_VERSION, List.of()).writeTo(indexPath);
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("dump", indexPath.toString()));
        } finally {
            System.setOut(originalOut);
            Files.deleteIfExists(indexPath);
        }
        final var dump = output.toString(StandardCharsets.UTF_8);
        assertTrue(dump, dump.contains("Zeron public API index"));
        assertTrue(dump, dump.contains("Format version: " + ZeronVersions.API_INDEX_SCHEMA_VERSION));
        assertTrue(dump, dump.contains("Standard-library API version: "
                + ZeronVersions.STANDARD_LIBRARY_API_VERSION));
    }

    @Test
    public void groupsWitnessEvidenceByTargetWithoutGeneratedNames() throws Exception {
        final var indexPath = Files.createTempFile(temporaryFolder.getRoot().toPath(), "zeron-witness-dump-", ".bin");
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        final var target = TypeDescriptor.ofName("zeron.numeric.Integer");
        final var declarations = List.<ZeronLibraryIndex.ExportedDeclaration>of(
                new ZeronLibraryIndex.WitnessExport("zeron.numeric.$zeron$witness$12", List.of(),
                        TypeDescriptor.ofName("zeron.lang.Display"),
                        target, List.of()),
                new ZeronLibraryIndex.WitnessExport("zeron.numeric.$zeron$witness$13", List.of(),
                        TypeDescriptor.genericOf(TypeDescriptor.ofName("zeron.numeric.Integral"), target),
                        target, List.of()));
        try {
            new ZeronLibraryIndex(StandardLibrary.API_VERSION, declarations).writeTo(indexPath);
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("dump", indexPath.toString()));
        } finally {
            System.setOut(originalOut);
            Files.deleteIfExists(indexPath);
        }
        final var dump = output.toString(StandardCharsets.UTF_8);
        assertTrue(dump, dump.contains("witnesses for zeron.numeric.Integer:"));
        final var display = "  zeron.lang.Display";
        final var integral = "  zeron.numeric.Integral<zeron.numeric.Integer>";
        assertTrue(dump, dump.contains(display));
        assertTrue(dump, dump.contains(integral));
        assertTrue(dump, dump.indexOf(display) < dump.indexOf(integral));
        assertFalse(dump, dump.contains("$zeron$witness"));
    }

    @Test
    public void dumpCommandPrintsAndFiltersBundledStandardLibraryIndex() throws Exception {
        final var output = new ByteArrayOutputStream();
        final var originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals(0, Zeron.runCli("dump", "--stdlib"));
            final var allDeclarations = output.toString(StandardCharsets.UTF_8);
            assertTrue(allDeclarations, allDeclarations.contains("type zeron.collections.Step"));
            assertTrue(allDeclarations, allDeclarations.contains("type zeron.lang.Option"));

            output.reset();
            assertEquals(0, Zeron.runCli("dump", "--stdlib", "zeron.collections"));
            final var filtered = output.toString(StandardCharsets.UTF_8);
            assertTrue(filtered, filtered.contains("Package filter: zeron.collections"));
            assertTrue(filtered, filtered.contains("type zeron.collections.Step"));
            assertFalse(filtered, filtered.contains("type zeron.lang.Option"));
        } finally {
            System.setOut(originalOut);
        }
    }

    private static CliParser.Invocation parse(final String... args) {
        final var result = CliParser.parse(args);
        assertTrue(result.toString(), result instanceof CliParser.Parsed);
        return ((CliParser.Parsed) result).invocation();
    }

    private static CliParser.InvalidArguments invalid(final String... args) {
        final var result = CliParser.parse(args);
        assertTrue(result.toString(), result instanceof CliParser.InvalidArguments);
        return (CliParser.InvalidArguments) result;
    }

    private Path createFile(final String name) throws Exception {
        return createFile(temporaryFolder.getRoot().toPath().resolve(name));
    }

    private Path createFile(final Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.createFile(path);
    }

    private Path createDirectory(final String name) throws Exception {
        return Files.createDirectories(temporaryFolder.getRoot().toPath().resolve(name));
    }

    private Path createProjectManifest(final String name, final String sourceRootName) throws Exception {
        final var sourceRoot = createDirectory(sourceRootName);
        createFile(sourceRoot.resolve("Main.zn"));
        final var manifest = createFile(name);
        Files.writeString(manifest, "[project]\n"
                + "entry = \"" + sourceRootName + "/Main.zn\"\n"
                + "source-roots = [\"" + sourceRootName + "\"]\n"
                + "output = \"" + sourceRootName + "-dist\"\n", StandardCharsets.UTF_8);
        return manifest;
    }
}
