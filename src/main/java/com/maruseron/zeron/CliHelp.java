package com.maruseron.zeron;

import java.util.Optional;

final class CliHelp {
    private static final String OVERVIEW = """
            Zeron command line

            Usage:
              zeron                         Start the REPL
              zeron version
              zeron --version
              zeron <source.zn>... [flags]  Compile source files
              zeron --root <dir> --entry <source.zn> [flags]
              zeron init
              zeron build [<target>] [--manifest <file>] [--debug] [--deny-warnings | --no-deny-warnings] [--no-warnings]
              zeron run [<target>] [--manifest <file>] [--debug] [--deny-warnings | --no-deny-warnings] [--no-warnings]
              zeron fmt [--check] [<file-or-directory>...]
              zeron lsp [--manifest <file>] [--stdio]
              zeron explain <ZR0000>
              zeron dump <api-index|class-directory|library.jar>
              zeron dump --stdlib [package]
              zeron --build-stdlib <dir> [--jar-output <file>]
              zeron help [command|option]

            Flags:
              --manifest <file>       Select a project manifest for build/run
              --debug, -d             Print compiler traces
              --deny-warnings         Fail the build when warnings are reported
              --no-deny-warnings      Do not fail the build when warnings are reported
              --no-warnings           Hide warning diagnostics; errors still print
              --root <dir>            Add a standalone project source root (repeatable)
              --entry <source.zn>     Select the standalone project entry source
              --library <path>        Add a Zeron library directory or JAR (repeatable)
              --java-classpath <path> Add a Java class directory or JAR (repeatable)
              --stdlib <mode>         Select source or compiled stdlib for direct compilation
                                        (default: compiled when the installation bundles it)
              --jar-output <file>     Package compiler output as a Zeron library JAR
              --build-stdlib <dir>    Compile the bundled standard library

            Use 'zeron help <command-or-option>' for details. See guide/39-command-reference.md
            and guide/40-manifest-reference.md for the generated reference.
            """;

    private CliHelp() {}

    static boolean hasTopic(final String topic) {
        return CliMetadata.helpTopics().contains(topic);
    }

    static void print(final Optional<String> topic) {
        System.out.print(topic.map(CliHelp::topicText).orElse(OVERVIEW));
    }

    private static String topicText(final String topic) {
        if (topic.equals("--version")) {
            return """
                    Usage: zeron --version

                    Alias for 'zeron version'.
                    """;
        }
        if (topic.equals("manifest")) {
            return """
                    Usage: zeron build|run [<target>] [--manifest <file>]

                    The project manifest is TOML. Its top-level tables are [project], [platform],
                    [dependencies], [build], and [targets.<name>]. Paths resolve relative to the
                    manifest file. See guide/40-manifest-reference.md for the generated reference.

                    """ + CliMetadata.tableSchemaSummary();
        }
        final var command = CliMetadata.command(topic);
        if (command.isPresent()) {
            final var spec = command.get();
            final var builder = new StringBuilder();
            builder.append("Usage: ").append(spec.synopsis()).append("\n\n")
                    .append(spec.summary()).append('\n');
            if (!spec.details().isEmpty()) builder.append(spec.details()).append('\n');
            final var options = CliMetadata.optionList(spec);
            if (!options.isEmpty()) builder.append('\n').append(options).append('\n');
            return builder.toString();
        }
        final var option = CliMetadata.option(topic);
        if (option.isPresent()) {
            final var spec = option.get();
            final var builder = new StringBuilder();
            builder.append("Usage: zeron ");
            if (!spec.commands().isEmpty()) {
                builder.append(String.join("|", spec.commands().stream().sorted().toList())).append(' ');
            } else {
                builder.append("<compilation-input> ");
            }
            builder.append(spec.synopsis()).append("\n\n")
                    .append(spec.summary()).append('\n');
            if (!spec.details().isEmpty()) builder.append(spec.details()).append('\n');
            return builder.toString();
        }
        throw new IllegalArgumentException("Unknown help topic: " + topic);
    }
}
