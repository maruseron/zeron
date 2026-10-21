# Zeron Language Support for VS Code

This extension associates `.zn` files with Zeron and provides TextMate syntax highlighting, comment
toggling, bracket matching, and quote/bracket insertion behavior. It also connects VS Code to the
Zeron language server for diagnostics, completion, hover, and definition navigation.

Go-to-definition results targeting bundled standard-library sources open in a read-only virtual
document. Project source files continue to open normally and remain editable.

## Requirements

- Install the Zeron 0.3.0 or later distribution and ensure `zeron` is available on `PATH`.
- Install the Java runtime required by that Zeron distribution.
- Open a single-project Zeron workspace. A `zeron.toml` manifest is optional for language-server
  features; without one, the server analyzes Zeron sources under the workspace folder. Opening the
  repository root or the standard-library folder directly selects the standard-library sources.

By default, the extension launches `zeron lsp` from the workspace root. If `zeron.toml` is present,
it configures the project; otherwise the server infers a source workspace from the folder. Build and
run commands still require a project manifest. If the executable is not on `PATH`, set
`zeron.server.path` to its command or path, then reload the VS Code window.

To restart the language server, run **Zeron: Restart Language Server** from the Command Palette.

Multi-root workspaces are not supported by the current language server integration. Syntax
highlighting continues to work if the server is missing or cannot start; see the **Zeron Language
Server** output channel for details.

## Try it from a source checkout

From the repository root, install the extension dependency and launch an Extension Development Host:

```powershell
npm --prefix editors/vscode install
code --extensionDevelopmentPath=editors/vscode sample_programs/list_monad
```

The grammar is intentionally a lightweight editor grammar rather than a second implementation of
the compiler scanner. Contextual soft keywords are highlighted when local syntax cues identify
their use; other ambiguous occurrences may remain identifiers to avoid coloring ordinary variables
as keywords. Language semantics come from the LSP server, not the grammar.
