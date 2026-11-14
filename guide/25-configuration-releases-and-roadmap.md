# Configuration, releases, and the roadmap

## Project manifest

`zeron init` creates a starter `zeron.toml` and `src/main.zn` without overwriting existing files.
The manifest is TOML; paths are resolved relative to the manifest location:

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"

[platform]
stdlib = "compiled"

[dependencies]
zeron-libraries = ["lib/shared.jar"]
java-classpath = ["lib/host-api.jar"]
java-sources = ["java-src"]
```

At least one source root is required. `project.entry` is required unless a `default-target`
supplies an entry, and the output defaults to `dist`. A project can also declare typed build policy
and several runnable targets:

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"
default-target = "app"

[build]
deny-warnings = false

[targets.app]
entry = "src/main.zn"
output = "dist/app"

[targets.bench]
entry = "src/bench.zn"
deny-warnings = true
```

`zeron build [<target>]` and `zeron run [<target>]` select a named target, or the
`default-target`/implicit project target when no name is given. A target inherits
`[project].entry` and `[build].deny-warnings`, and defaults its output to
`<project.output>/<name>`. Effective outputs must be distinct, and every target entry must be under
a configured source root. Command-line `--deny-warnings` and `--no-deny-warnings` override manifest
policy. See the generated [command reference](39-command-reference.md) and
[manifest reference](40-manifest-reference.md) for every command, option, key, default, and
validation rule. When
`[platform].stdlib` is omitted, compilation prefers the compiled standard library bundled in the
Zeron installation's `lib/` directory when its API index matches the compiler. If the artifact is
missing or incompatible, the default falls back to the bundled standard-library sources. A complete
standard library listed in `zeron-libraries` takes precedence over the bundled one. Dependency
roots are local directories or JARs; Zeron does not fetch or lock them. `java-classpath` roots
supply Java metadata for compilation and execution. The language server additionally discovers the
running JDK's `lib/src.zip` and a `<name>-sources.jar` beside each `java-classpath` JAR for
go-to-definition. `java-sources` lists optional directories of attached Java sources as an explicit
supplement. These source attachments do not affect compilation, and each class is located by binary
name without scanning packages.

`zeron init` writes `compiled` in an installed distribution and `source` in a source checkout,
where no compiled artifact exists. `source` remains the explicit choice for standard-library
development. An explicit `compiled` setting never falls back silently: if no compatible artifact
is available, compilation reports the mismatch and suggests installing a matching Zeron release,
adding a library to `zeron-libraries`, or selecting `source`.

The compiler normally derives the installation home from the `zeron` executable found on `PATH`.
Set the JVM system property `zeron.home` to override that location, for example
`-Dzeron.home=/opt/zeron`; the value must be the installation root that contains `lib/` and
`share/zeron/stdlib/`. This is useful for source checkouts, test harnesses, and custom launchers
that do not place `zeron` on `PATH`.

Manifest paths are resolved relative to the manifest file, not to an assumed repository layout.
`source-roots` selects the source files included in the project, `entry` identifies the source
unit whose `main` is launched, and `output` names the generated-program directory. Keep that
directory separate from Maven's compiler output.

Command options are checked for the selected command. Invalid arguments produce a concise error on
stderr and exit with status 64. Unknown dash-prefixed arguments are reported as options rather than
treated as source filenames; for a filename beginning with `-`, provide a path with a directory
prefix such as `./-example.zn`.
Input paths are checked before the command runs: manifests must be existing TOML files, source
inputs must be existing `.zn` files, and library/classpath inputs must be readable JARs or
directories of the appropriate kind. Project-manifest source, dependency, and output paths are
checked before dispatch as well. Output directories and JARs may be new, but their nearest existing
parent must be a writable directory; an existing JAR output must be writable.

## Build, run, and diagnostics

`zeron build` reads the manifest in the current directory, and `zeron run` builds and launches the
selected target's entry point with the configured runtime classpath. Use `--manifest
path/to/zeron.toml` to select a different file, and a positional target name to select a declared
target. `zeron help` lists commands and flags; `zeron help run` or `zeron help --manifest`
shows focused help. `zeron explain ZR0000` displays a terminal-formatted bundled explanation for a
diagnostic code. `zeron dump <api-index|class-directory|library.jar>` prints the public API index
for a library artifact. Use `zeron version` (or `zeron --version`) to identify the running
distribution; it works without a project manifest.
Compiler diagnostics and explanations use ANSI colors when an interactive terminal is detected.
Zeron-language examples in explanations are syntax-highlighted. Output falls back to plain text
when non-interactive, when `NO_COLOR` has a non-empty value, or when `TERM` is `dumb`. REPL
sessions remain plain.
Single-file compilation remains available for scripts; project commands centralize the common
source, output, standard-library, and dependency options.

If a build succeeds but a program fails to launch, check that dependency paths resolve from the
manifest and that runtime dependencies are available. The `zeron-libraries` entries provide
Zeron API metadata for compilation and class files for execution; Java dependencies belong under
`java-classpath`. For compiler diagnostics, the explanation command provides details beyond the
short code and message.

## Distribution and current limits

The local release prototype packages the Zeron launcher/compiler, standard library, and diagnostic
explanations for installation outside a source checkout. It is a local packaging workflow, not yet
a mature cross-platform release service; check the repository's release script and platform
requirements before distributing it.

The packaged launcher requires Java 25 or later; a source checkout also needs Maven. A release
should keep the launcher,
standard library, and diagnostic descriptions from the same compatible build, so users receive
matching runtime support and actionable errors.

Zeron is evolving. Examples of current limits include no class inheritance, no general Java
generics or callback conversion, no dependency fetching or incremental build, no `Stable` generic
constraint for hash-key admissibility, and constraints on generic inference, pattern exhaustiveness,
and raised effects. `Hashable` and hash-based collections are implemented; keys must retain their
equality- and hash-relevant state while stored. The design documents distinguish implemented
behavior from proposals and deferred work; treat those documents' status sections as the source of
truth rather than assuming a roadmap item is already part of the language. In particular, see
[the compilation and host integration design](../documents/design_documents/design-document-11_compilation-libraries-and-host-integration.md)
for build/platform constraints and the [Zeron overview](../documents/design_documents/design-document-00.md) for
the current feature summary.
