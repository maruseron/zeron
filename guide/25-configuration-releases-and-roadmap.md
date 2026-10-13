# 25. Configuration, releases, and the roadmap

## Project manifest

`zeron init` creates a starter `zeron.toml` and `src/main.zn` without overwriting existing files.
The manifest is TOML; paths are resolved relative to the manifest location:

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"

[platform]
stdlib = "source"

[dependencies]
zeron-libraries = ["lib/shared.jar"]
java-classpath = ["lib/host-api.jar"]
```

`project.entry` and at least one source root are required. The output defaults to `dist`, and
standard-library mode defaults to bundled source. `source` is convenient for development;
`compiled` requires a compatible standard-library library artifact. Dependency roots are local
directories or JARs; Zeron does not fetch or lock them.

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

`zeron build` reads the manifest in the current directory, and `zeron run` builds and launches its
entry point with the configured runtime classpath. Use `--manifest path/to/zeron.toml` to select a
different file. `zeron help` lists commands and flags; `zeron help run` or `zeron help --manifest`
shows focused help. `zeron explain ZR0000` displays a terminal-formatted bundled explanation for a
diagnostic code. `zeron dump <api-index|class-directory|library.jar>` prints the public API index
for a library artifact.
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

The packaged launcher enables the Java preview features used by the current compiler. A source
checkout instead needs the development JDK and build tooling. A release should keep the launcher,
standard library, and diagnostic descriptions from the same compatible build, so users receive
matching runtime support and actionable errors.

Zeron is evolving. Examples of current limits include no class inheritance, no general Java
generics or callback conversion, no dependency fetching or incremental build, incomplete
`Hashable`/`Stable` support, and constraints on generic inference, pattern exhaustiveness, and
raised effects. The design documents distinguish implemented behavior from proposals and deferred
work; treat those documents' status sections as the source of truth rather than assuming a roadmap
item is already part of the language. In particular, see
[the compilation and host integration design](../documents/design-document-11_compilation-libraries-and-host-integration.md)
for build/platform constraints and the [Zeron overview](../documents/design-document-00.md) for
the current feature summary.
