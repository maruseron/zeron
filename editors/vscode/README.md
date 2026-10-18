# Zeron Language Support for VS Code

This extension associates `.zn` files with Zeron and provides TextMate syntax highlighting, comment
toggling, bracket matching, and quote/bracket insertion behavior. It also connects VS Code to the
Zeron language server for diagnostics, completion, hover, and definition navigation.

## Requirements

- Install the Zeron 0.2.0 or later distribution and ensure `zeron` is available on `PATH`.
- Install the Java runtime required by that Zeron distribution.
- Open a single-project Zeron workspace containing its `zeron.toml` manifest.

By default, the extension launches `zeron lsp` from the workspace root. If the executable is not on
`PATH`, set `zeron.server.path` to its command or path, then reload the VS Code window.

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
the compiler scanner. Contextual soft keywords are generally left as identifiers to avoid coloring
ordinary variables as keywords. Language semantics come from the LSP server, not the grammar.
