# Zeron Language Support for VS Code

This extension associates `.zn` files with Zeron and provides TextMate syntax highlighting, comment
toggling, bracket matching, and quote/bracket insertion behavior. It also connects VS Code to the
Zeron language server for diagnostics, completion, hover, and definition navigation.

Go-to-definition results targeting bundled standard-library sources open in a read-only virtual
document. Project source files continue to open normally and remain editable.

## Requirements

- Install JDK 25 or later when launching from a source checkout.
- Otherwise, install the Zeron 0.3.0 or later distribution and ensure `zeron` is available on
  `PATH`.
- Open a single-project Zeron workspace. A `zeron.toml` manifest is optional for language-server
  features; without one, the server analyzes Zeron sources under the workspace folder. Opening the
  repository root or the standard-library folder directly selects the standard-library sources.

When a built Zeron checkout is found in the workspace or a parent directory, the extension launches
its compiled classes from `target/classes` with runtime dependencies from `target/dependency`.
Otherwise it launches `zeron lsp` from `PATH`. If `zeron.toml` is present, the server configures the
project; otherwise it infers a source workspace from the folder. Build and run commands still
require a project manifest. Set `zeron.server.path` to explicitly override the selected executable,
then reload the VS Code window.

To restart the language server, run **Zeron: Restart Language Server** from the Command Palette.

Multi-root workspaces are not supported by the current language server integration. Syntax
highlighting continues to work if the server is missing or cannot start; see the **Zeron Language
Server** output channel for details.

## Try it from a source checkout

From the repository root, install the extension dependency and launch an Extension Development Host:

```powershell
mvn compile
npm --prefix editors/vscode install
code --extensionDevelopmentPath=editors/vscode .
```

Run `mvn compile` again after changing compiler sources to refresh the language server. Opening a
sample project nested inside the checkout also finds the compiled server in the parent directory.

The grammar is intentionally a lightweight editor grammar rather than a second implementation of
the compiler scanner. Contextual soft keywords are highlighted when local syntax cues identify
their use; other ambiguous occurrences may remain identifiers to avoid coloring ordinary variables
as keywords. Language semantics come from the LSP server, not the grammar.
