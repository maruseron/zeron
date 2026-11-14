package com.maruseron.zeron;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared command, option, and manifest-schema metadata. Runtime help and the generated reference
 * chapters both render from this description so adding a flag or manifest key cannot leave the
 * checked-in documentation silently stale.
 */
final class CliMetadata {
    private CliMetadata() {}

    record Command(String name,
                   String synopsis,
                   String summary,
                   List<String> optionNames,
                   String details) {
        Command {
            optionNames = List.copyOf(optionNames);
        }
    }

    record Option(String name,
                  String shortName,
                  String valuePlaceholder,
                  Set<String> commands,
                  String summary,
                  String details) {
        Option {
            commands = Set.copyOf(commands);
            if (shortName == null) shortName = "";
        }

        String synopsis() {
            final var flag = name + (valuePlaceholder.isEmpty() ? "" : " <" + valuePlaceholder + ">");
            return shortName.isEmpty() ? flag : flag + ", " + shortName;
        }
    }

    record ManifestTable(String name, String summary, List<ManifestKey> keys) {
        ManifestTable {
            keys = List.copyOf(keys);
        }
    }

    record ManifestKey(String table,
                       String name,
                       String type,
                       String defaultValue,
                       String required,
                       String description,
                       String constraints) {}

    static final List<Command> COMMANDS = List.of(
            new Command("version", "zeron version", "Print the running Zeron distribution version.",
                    List.of(), "The alias 'zeron --version' has the same behavior."),
            new Command("init", "zeron init", "Create a starter manifest and entry source.",
                    List.of(), "Writes zeron.toml and src/main.zn in the current directory and refuses "
                    + "to overwrite either file."),
            new Command("build", "zeron build [<target>] [options]",
                    "Compile the selected project target without launching it.",
                    List.of("--manifest", "--debug", "--deny-warnings", "--no-deny-warnings", "--no-warnings"),
                    "With no target name, selects [project].default-target, or the implicit project target "
                    + "described by [project].entry and [project].output."),
            new Command("run", "zeron run [<target>] [options]",
                    "Compile and launch the selected project target.",
                    List.of("--manifest", "--debug", "--deny-warnings", "--no-deny-warnings", "--no-warnings"),
                    "Uses the same target resolution and warning policy as 'zeron build', then launches the "
                    + "generated main class from that target's output directory."),
            new Command("fmt", "zeron fmt [--check] [<file-or-directory>...]",
                    "Format selected sources or the project's source roots.",
                    List.of("--check"),
                    "With no paths, discovers .zn files under the source roots in ./zeron.toml."),
            new Command("lsp", "zeron lsp [--manifest <file>] [--stdio]",
                    "Start the language server over stdio.",
                    List.of("--manifest", "--stdio", "--debug"),
                    "Does not take a target; analysis and formatting use the project-wide source set."),
            new Command("explain", "zeron explain <ZR0000>",
                    "Print the bundled explanation for a diagnostic code.", List.of(), ""),
            new Command("dump", "zeron dump <api-index|class-directory|library.jar>\n"
                    + "       zeron dump --stdlib [package]",
                    "Print a public API index.", List.of("--stdlib"),
                    "The optional --stdlib package filter accepts a dot-separated package name."),
            new Command("help", "zeron help [command|option]",
                    "Show command and option help.", List.of(), ""));

    static final List<Option> OPTIONS = List.of(
            new Option("--manifest", "", "file", Set.of("build", "run", "lsp"),
                    "Select the project manifest.",
                    "Defaults to zeron.toml in the current directory. Paths inside the manifest resolve "
                    + "relative to the manifest file."),
            new Option("--debug", "-d", "", Set.of("build", "run", "lsp"),
                    "Print compiler traces.",
                    "Writes parser, resolver, and compiler traces to stderr. The short alias is -d."),
            new Option("--deny-warnings", "", "", Set.of("build", "run"),
                    "Treat reported warnings as a failed build.",
                    "Reports warnings normally, then fails after the stage completes when any warning was "
                    + "produced. Overrides [build].deny-warnings and a target's deny-warnings setting."),
            new Option("--no-deny-warnings", "", "", Set.of("build", "run"),
                    "Allow warnings without failing the build.",
                    "Overrides [build].deny-warnings and a target's deny-warnings setting. Cannot be combined "
                    + "with --deny-warnings."),
            new Option("--no-warnings", "", "", Set.of("build", "run"),
                    "Hide warning diagnostics.",
                    "Errors still print, and collected warnings still participate in --deny-warnings."),
            new Option("--root", "", "dir", Set.of(),
                    "Add a standalone project source root.",
                    "Repeatable. Standalone project compilation requires both --root and --entry."),
            new Option("--entry", "", "source.zn", Set.of(),
                    "Select the standalone project entry source.",
                    "Resolved relative to the first --root."),
            new Option("--library", "", "path", Set.of(),
                    "Add a Zeron library directory or JAR.",
                    "Repeatable. Project builds configure libraries in zeron.toml instead."),
            new Option("--java-classpath", "", "path", Set.of(),
                    "Add a Java class directory or JAR.",
                    "Repeatable. Project builds configure Java roots in zeron.toml instead."),
            new Option("--stdlib", "", "source|compiled", Set.of(),
                    "Select the standard-library mode for direct compilation.",
                    "Project builds select this in [platform].stdlib instead. With 'zeron dump --stdlib', "
                    + "the same flag prints the bundled index; an optional package name filters its output."),
            new Option("--jar-output", "", "file.jar", Set.of(),
                    "Package compiler output as a Zeron library JAR.",
                    "Also accepted with --build-stdlib."),
            new Option("--build-stdlib", "", "dir", Set.of(),
                    "Compile the bundled standard library.",
                    "Writes a class directory and optionally packages it with --jar-output."),
            new Option("--stdio", "", "", Set.of("lsp"),
                    "Use the default stdio transport.",
                    "Explicitly selects the transport used by language clients."),
            new Option("--check", "", "", Set.of("fmt"),
                    "Report formatting differences without modifying files.",
                    "The command exits unsuccessfully when a selected file differs from canonical style."));

    static final List<ManifestTable> MANIFEST_TABLES = List.of(
            new ManifestTable("project", "Project-wide source and output configuration.", List.of(
                    new ManifestKey("project", "entry", "string", "none",
                            "required unless default-target supplies an entry through a target",
                            "Entry source whose main is compiled and launched by the implicit project target.",
                            "Must have a .zn extension and be under a configured source root."),
                    new ManifestKey("project", "source-roots", "array of strings", "none", "required",
                            "Directories included in the project source universe.",
                            "At least one directory. Paths resolve relative to the manifest."),
                    new ManifestKey("project", "output", "string", "dist", "optional",
                            "Default generated-program directory and parent for named target outputs.",
                            "Paths resolve relative to the manifest."),
                    new ManifestKey("project", "default-target", "string", "none", "optional",
                            "Target selected by build and run when no positional target is supplied.",
                            "Must name an existing [targets.<name>] entry."))),
            new ManifestTable("platform", "Runtime platform configuration.", List.of(
                    new ManifestKey("platform", "stdlib", "string", "automatic", "optional",
                            "Standard-library mode.", "One of source or compiled. Compiled never falls back silently."))),
            new ManifestTable("dependencies", "Project-wide dependency roots.", List.of(
                    new ManifestKey("dependencies", "zeron-libraries", "array of strings", "[]", "optional",
                            "Zeron library directories or JARs.", "Paths resolve relative to the manifest."),
                    new ManifestKey("dependencies", "java-classpath", "array of strings", "[]", "optional",
                            "Java class directories or JARs used for resolution and execution.",
                            "Paths resolve relative to the manifest."),
                    new ManifestKey("dependencies", "java-sources", "array of strings", "[]", "optional",
                            "Attached Java source directories for editor navigation.",
                            "Does not affect compilation. Paths resolve relative to the manifest."))),
            new ManifestTable("build", "Typed compiler policy shared by all targets.", List.of(
                    new ManifestKey("build", "deny-warnings", "boolean", "false", "optional",
                            "Treat reported warnings as a failed build after the stage completes.",
                            "A target may override this setting; command-line options override both."))),
            new ManifestTable("targets.<name>", "Named runnable targets.", List.of(
                    new ManifestKey("targets.<name>", "entry", "string", "[project].entry", "optional",
                            "Entry source for this target.",
                            "Must have a .zn extension and be under a configured source root."),
                    new ManifestKey("targets.<name>", "output", "string", "<project.output>/<name>", "optional",
                            "Generated-program directory for this target.",
                            "Effective target outputs must be distinct after normalization."),
                    new ManifestKey("targets.<name>", "deny-warnings", "boolean", "[build].deny-warnings",
                            "optional", "Per-target warning policy.", "Command-line options take precedence."))));

    static Optional<Command> command(final String name) {
        return COMMANDS.stream().filter(command -> command.name().equals(name)).findFirst();
    }

    static Optional<Option> option(final String nameOrAlias) {
        return OPTIONS.stream()
                .filter(option -> option.name().equals(nameOrAlias)
                        || option.shortName().equals(nameOrAlias))
                .findFirst();
    }

    static Set<String> helpTopics() {
        final var topics = new LinkedHashSet<String>();
        COMMANDS.forEach(command -> topics.add(command.name()));
        topics.add("--version");
        OPTIONS.forEach(option -> {
            topics.add(option.name());
            if (!option.shortName().isEmpty()) topics.add(option.shortName());
        });
        topics.add("manifest");
        return Set.copyOf(topics);
    }

    static String optionList(final Command command) {
        if (command.optionNames().isEmpty()) return "";
        return "Options: " + String.join(", ", command.optionNames());
    }

    static String tableSchemaSummary() {
        final var builder = new StringBuilder();
        for (final var table : MANIFEST_TABLES) {
            builder.append('[').append(table.name()).append("]\n");
            for (final var key : table.keys()) {
                builder.append("  ").append(key.name()).append(" (").append(key.type()).append(')')
                        .append(" — ").append(key.description()).append('\n');
            }
        }
        return builder.toString();
    }
}
