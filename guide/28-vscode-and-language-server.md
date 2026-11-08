# VS Code and the language server

The Zeron VS Code extension combines TextMate syntax highlighting with compiler-backed language
features. The language server provides diagnostics, completion, hover information, definition navigation,
and quick fixes for missing contract and witness method implementations. Definitions in bundled
standard-library sources open in read-only virtual documents; project source files remain editable.

## Requirements

- For a source checkout, install JDK 25 or later and run `mvn compile`.
- Otherwise, install a Zeron 0.3.0 or later distribution and make `zeron` available on `PATH`.
- Open one Zeron project folder containing its `zeron.toml` manifest.

The extension prefers compiled classes from a Zeron checkout's `target/classes`, with runtime
dependencies from `target/dependency`. It searches the workspace folder and its parents. If no local
build is found, it starts `zeron lsp` from `PATH`. The language server currently supports one
project per VS Code workspace, not a multi-root workspace.

## Declaration documentation

Write consecutive `///` comments immediately before a declaration to show Markdown documentation
in hover:

```zeron
/// Parses a value without converting through Float.
public fn parseExact(value: String): Rational;
```

Place the comments before visibility or other declaration modifiers. A blank line or ordinary
comment breaks the documentation group. Hover shows documentation for declarations in the current
source and for public declarations loaded from compiled libraries. Private documentation is
available only while working from source; it is not included in compiled-library metadata. The
formatter preserves documentation comments.

## Hover presentation

Hover shows a declaration's documentation, type, and signature. Signatures carry parameter names from
the source declaration, including declarations loaded from compiled libraries. Type parameters show
their constraints at their declaration and at uses, for
example `type parameter T: nullable + Comparable<T>`; a value whose type mentions a type parameter
lists the same information after its type. A model value is described by its declared contract, for
example `model UserSql: SqlModel<User>`, followed by its contract assignments and property mappings;
the compiler-generated implementation class is hidden because its name is not usable in source.
Nominal types use simple names throughout hover. A type or signature that does not fit a comfortable
line breaks at its first generic argument list or parameter list and lists one item per line. A
hover over a reference ends with a `Go to definition` link to the declaration that definition
navigation resolves, including Java and external declarations. Hovering a match case name describes
the sum case selected for the scrutinee and navigates to its declaration, even when a type with the
same name is visible in the project.

## Java and external bindings

Hover and completion follow the same Java interop boundary the compiler resolves. A qualified Java
type shows its binary name and supported members; a member shows its Java signature and checked
exceptions, plus the Zeron external facade binding when one exposes it. Completion after a facade
type or value offers only the members that facade declares, since the resolver rejects the rest.

Definition on a Zeron external function or facade member opens that Zeron declaration. A direct Java
reference opens its Java source from the running JDK's `lib/src.zip`, from a `<name>-sources.jar`
beside a `java-classpath` JAR, or from an explicit `[dependencies].java-sources` directory.
Archived sources open from a temporary cache with their original line positions; without source it
falls back to the external declaration, or reports no target. See
[Java interoperability](23-java-interoperability.md).

## Import completion and compiled libraries

Completion after `import` lists the public declarations of project packages and compiled libraries,
including the compiled standard library. Hover and completion describe catalog types with their
declaration kind and generic constraints, and definition on a bundled standard-library type opens
its bundled source declaration even when the project uses `stdlib = "compiled"`.

## Try the extension from a source checkout

From the repository root, install the extension's dependency and open a sample project in an
Extension Development Host:

```powershell
npm --prefix editors/vscode install
code --extensionDevelopmentPath=editors/vscode sample_programs/list_monad
```

The sample folder is a project with a manifest, so the language server can load its project
configuration. From a source checkout, run `mvn compile` after changing compiler sources to refresh
the local server. To use a different Zeron executable, set the VS Code setting
`zeron.server.path` to its command or path, then reload the window. The extension appends the `lsp`
subcommand itself.

## Troubleshooting

If language features do not start, check that the workspace root contains a valid `zeron.toml`,
that the local Maven build completed or the configured executable can be launched, and that Java is
available. Open the
**Zeron Language Server** output channel for startup errors. Syntax highlighting remains available
even when the server is missing or cannot start. If you opened a multi-root workspace, open the
Zeron project as the single workspace folder instead.

The extension does not currently provide formatting or refactoring features. Use the CLI formatter
described in [Formatting and diagnostics](29-formatting-and-diagnostics.md).

For extension-specific details, see the
[VS Code extension README](../editors/vscode/README.md).
