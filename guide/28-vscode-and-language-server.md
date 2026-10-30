# VS Code and the language server

The Zeron VS Code extension combines TextMate syntax highlighting with compiler-backed language
features. The language server provides diagnostics, completion, hover information, definition navigation,
and quick fixes for missing contract and witness method implementations. Definitions in bundled
standard-library sources open in read-only virtual documents; project source files remain editable.

## Requirements

- Install a Zeron 0.3.0 or later distribution and make `zeron` available on `PATH`.
- Install the Java runtime required by that Zeron distribution.
- Open one Zeron project folder containing its `zeron.toml` manifest.

The extension starts `zeron lsp` from the workspace folder. The language server currently supports
one project per VS Code workspace, not a multi-root workspace.

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

## Try the extension from a source checkout

From the repository root, install the extension's dependency and open a sample project in an
Extension Development Host:

```powershell
npm --prefix editors/vscode install
code --extensionDevelopmentPath=editors/vscode sample_programs/list_monad
```

The sample folder is a project with a manifest, so the language server can load its project
configuration. To use a different Zeron executable, set the VS Code setting `zeron.server.path` to
its command or path, then reload the window. The extension appends the `lsp` subcommand itself.

## Troubleshooting

If language features do not start, check that the workspace root contains a valid `zeron.toml`,
that the configured executable can be launched, and that its Java runtime is available. Open the
**Zeron Language Server** output channel for startup errors. Syntax highlighting remains available
even when the server is missing or cannot start. If you opened a multi-root workspace, open the
Zeron project as the single workspace folder instead.

The extension does not currently provide formatting or refactoring features. Use the CLI formatter
described in [Formatting and diagnostics](29-formatting-and-diagnostics.md).

For extension-specific details, see the
[VS Code extension README](../editors/vscode/README.md).
