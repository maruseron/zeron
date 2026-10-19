# Formatting and diagnostics

Zeron includes a canonical source formatter and diagnostic explanations in its CLI. Run these
commands from a terminal in your project directory.

## Format source files

Run `zeron fmt` with no paths to format `.zn` files discovered under the source roots in the
current directory's `zeron.toml`:

```text
zeron fmt
```

You can instead select individual source files or directories:

```text
zeron fmt src/main.zn
zeron fmt src
```

The formatter writes the canonical formatting back to changed files. It is safe to run repeatedly:
formatting already formatted source does not change it.

To check formatting without changing files, use `--check`:

```text
zeron fmt --check
```

The command reports files that would be reformatted and exits unsuccessfully if any differ from
canonical style. This makes it suitable for a CI check. Explicit paths can also be checked:

```text
zeron fmt --check src
```

The formatter parses the source before rewriting it. If a file has syntax errors, it reports the
diagnostics and leaves that file unchanged; fix the source before formatting it.

## Understand compiler diagnostics

Build a project to see its compiler diagnostics:

```text
zeron build
```

Diagnostics include a code such as `ZR2014`. Use `zeron explain` to display the bundled explanation
for a code:

```text
zeron explain ZR2014
```

The explanation provides more context than the short diagnostic message. `zeron help fmt` and
`zeron help explain` show command usage. In VS Code, the language server publishes diagnostics as
you edit; see [VS Code and the language server](28-vscode-and-language-server.md).
