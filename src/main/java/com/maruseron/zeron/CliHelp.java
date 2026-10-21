package com.maruseron.zeron;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
              zeron build [--manifest <file>] [--debug]
              zeron run [--manifest <file>] [--debug]
              zeron fmt [--check] [<file-or-directory>...]
              zeron lsp [--manifest <file>]
              zeron explain <ZR0000>
              zeron dump <api-index|class-directory|library.jar>
              zeron dump --stdlib [package]
              zeron --build-stdlib <dir> [--jar-output <file>]
              zeron help [command|flag]

            Flags:
              --manifest <file>       Select a project manifest for build/run
              --debug, -d             Print compiler traces
              --root <dir>            Add a standalone project source root (repeatable)
              --entry <source.zn>     Select the standalone project entry source
              --library <path>        Add a Zeron library directory or JAR (repeatable)
              --java-classpath <path> Add a Java class directory or JAR (repeatable)
              --stdlib <mode>         Select source or compiled stdlib for direct compilation
              --jar-output <file>     Package compiler output as a Zeron library JAR
              --build-stdlib <dir>    Compile the bundled standard library

            Use 'zeron help <command-or-flag>' for details.
            """;

    private static final Map<String, String> TOPICS = Map.ofEntries(
            Map.entry("version", """
                    Usage: zeron version

                    Print the version of the running Zeron distribution.
                    """),
            Map.entry("--version", """
                    Usage: zeron --version

                    Alias for 'zeron version'.
                    """),
            Map.entry("init", """
                    Usage: zeron init

                    Create zeron.toml and src/main.zn in the current directory.
                    Refuses to overwrite either file if it already exists.
                    """),
            Map.entry("build", """
                    Usage: zeron build [--manifest <file>] [--debug]

                    Compile the project described by zeron.toml without launching it.
                    The manifest defaults to ./zeron.toml.
                    """),
            Map.entry("run", """
                    Usage: zeron run [--manifest <file>] [--debug]

                    Compile the project described by zeron.toml and launch its entry point.
                    The manifest defaults to ./zeron.toml.
                    """),
            Map.entry("fmt", """
                    Usage: zeron fmt [--check] [<file-or-directory>...]

                    Format selected .zn files, or discover them under the source roots in
                    ./zeron.toml when no paths are provided. With --check, report files that
                    differ from the canonical style without modifying them.
                    """),
            Map.entry("lsp", """
                    Usage: zeron lsp [--manifest <file>] [--stdio]

                    Start the Zeron Language Server Protocol server over stdio. When present,
                    ./zeron.toml is used by default; otherwise the current directory is treated
                    as a source workspace. The standard-library source root is detected when
                    opening the repository root or the standard-library directory directly.
                    An explicit --manifest must name a valid project manifest.
                    --stdio explicitly selects the default stdio transport for language clients.
                    """),
            Map.entry("explain", """
                    Usage: zeron explain <ZR0000>

                    Display the bundled explanation for a diagnostic code.
                    """),
            Map.entry("dump", """
                    Usage: zeron dump <api-index|class-directory|library.jar>
                           zeron dump --stdlib [package]

                    Print a public API index from an API index file, class directory, or Zeron
                    library JAR. With --stdlib, build and print the bundled standard-library
                    index; an optional package name filters the output (for example,
                    'zeron.collections').
                    """),
            Map.entry("help", """
                    Usage: zeron help [command|flag]

                    Show the command and flag overview, or details for one command or flag.
                    """),
            Map.entry("--manifest", """
                    Usage: zeron build|run [--manifest <file>]

                    Select the project manifest. Defaults to zeron.toml in the current directory.
                    """),
            Map.entry("--debug", """
                    Usage: zeron build|run|<source.zn> --debug

                    Print parser, resolver, and compiler traces to stderr during compilation.
                    The short alias is -d.
                    """),
            Map.entry("-d", """
                    Alias for --debug. Print parser, resolver, and compiler traces to stderr.
                    """),
            Map.entry("--root", """
                    Usage: zeron --root <dir>... --entry <source.zn>

                    Add a source root for standalone project compilation. Repeat for multiple roots.
                    For regular projects, prefer zeron.toml and 'zeron build' or 'zeron run'.
                    """),
            Map.entry("--entry", """
                    Usage: zeron --root <dir>... --entry <source.zn>

                    Select the entry source relative to the first source root for standalone
                    project compilation.
                    """),
            Map.entry("--library", """
                    Usage: zeron <compilation-input> --library <class-directory-or-JAR>

                    Add a Zeron library for compilation and runtime. Repeat to add multiple libraries.
                    In project builds, configure libraries in zeron.toml instead.
                    """),
            Map.entry("--java-classpath", """
                    Usage: zeron <compilation-input> --java-classpath <class-directory-or-JAR>

                    Add Java classes for compile-time resolution and runtime. Repeat to add multiple
                    roots. In project builds, configure roots in zeron.toml instead.
                    """),
            Map.entry("--stdlib", """
                    Usage: zeron <compilation-input> --stdlib <source|compiled>

                    Select bundled standard-library sources or a compiled standard-library library.
                    Project builds select this in zeron.toml instead.
                    """),
            Map.entry("--jar-output", """
                    Usage: zeron <compilation-input> --jar-output <file.jar>

                    Package compiled output as a Zeron library JAR instead of writing to dist/.
                    Also accepted with --build-stdlib.
                    """),
            Map.entry("--build-stdlib", """
                    Usage: zeron --build-stdlib <directory> [--jar-output <file.jar>]

                    Compile the bundled standard library into a class directory and optionally
                    package that output as a JAR.
                    """));

    private static final Set<String> COMMANDS =
            Set.of("version", "init", "build", "run", "fmt", "lsp", "explain", "dump", "help");

    private CliHelp() {}

    static boolean hasTopic(final String topic) {
        return COMMANDS.contains(topic) || TOPICS.containsKey(topic);
    }

    static void print(final Optional<String> topic) {
        System.out.print(topic.map(TOPICS::get).orElse(OVERVIEW));
    }
}
