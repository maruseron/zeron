# Changelog

## [0.2.0] - 2026, 14 OCT

### Compiler and language work

- Added complete generic witness overlap checking across active source and compiled-library scopes.
  Conflicting witnesses are rejected when their target and fully applied contract patterns overlap.

## [0.2.0-alpha] - 2026, 13 OCT

### Compiler and language work

- Added literal pattern matching.
- Added resource scopes with automatic resource disposal.
- Expanded for-expressions with new operations.
- Expanded the typed raised effects boundary to better interface with Java facade classes.
- Expanded Java interop facades.
- Expanded lambda and runtime lowering.
- Expanded class and contract generic limitations to allow bounds. Conformance evidence is now
  lowered to an instance-carried `MethodHandle` for instance and default methods.

### Standard Library

- Added small filesystem and UTF-8 text I/O APIs under `zeron.fs.Path` and `zeron.io.text`, along
  with several `java.io` and `java.nio.file` features.

- Added `Hashable`, and the hash-based collections `Map` and `Set`.

### Utilities and CLI

- Added the Apache-licensed VS Code TextMate grammar and terminal color support for compiler
  diagnostics and explanations.
- Added two new compiler commands: `help` and `dump`.

### Miscellaneous

- The `Display` implementation now documents the conventions intended for future derived
  `Display` implementations.
- The release packaging script now smoke-tests both embedded standard-library source mode and
  compiled standard-library mode.
