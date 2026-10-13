# Zeron Language Support for VS Code

This grammar-only extension associates `.zn` files with Zeron and provides TextMate syntax
highlighting, comment toggling, bracket matching, and quote/bracket insertion behavior. It does
not launch the compiler or provide diagnostics, completion, or semantic highlighting.

## Try it from a source checkout

From the repository root, launch an Extension Development Host:

```sh
code --extensionDevelopmentPath=editors/vscode .
```

Open a `.zn` file in the new window to test the grammar.

Alternatively, you could formally register Zeron globally with a symbolic link:

- Windows:

```powershell
New-Item -ItemType symboliclink -Path "$env:USERPROFILE\.vscode\extensions\zeron-syntax-highlighting" -Value "$pwd\editors\vscode"
```

- macOS or Linux:

```bash
ln -s "$(pwd)/editors/vscode" ~/.vscode/extensions/zeron-syntax-highlighting
```

The grammar is intentionally a lightweight editor grammar rather than a second implementation of
the compiler scanner. Contextual soft keywords are generally left as identifiers to avoid coloring
ordinary variables as keywords.
