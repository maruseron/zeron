# Changelog

## [Unreleased]

## [0.4.1] - 2026, 19 OCT

### Compiler and language work

- Corrected generic witness overlap checking for higher-kinded types.

### Compiler and tooling

- Open bundled standard-library definition targets as read-only virtual documents in the VS Code
  extension.
- Correct formatter handling for unary minus, keyword spacing, match cases, nested calls, and
  declaration layout.

### Standard Library

- Added read-only environment-variable lookup and current-process working-directory and exit APIs.

## [0.4.0] - 2026, 19 OCT

### Compiler and language work

- Added constraint-aware rank-1 generalization for local immutable let-bound lambdas, with inferred
  contract bounds and evidence-specialized closure caching.
- Added higher-kinded abstractions and declarative contracts, including standard-library support for
  `Stream`, `Option`, `List`, and intrinsic `Array`.
- Added nominal sum types and the `Result<T, E>` standard-library type, with structural equality and
  hashing support where derived.
- Added namespace-scoped wildcard imports for loading extensions and declarations.
- Added the Unicode-scalar `Char` type and strict String interoperation.
- Recognized `get` and `set` as soft keywords in property accessor positions.

### Standard Library

- Added `zeron.math` numeric extensions, constants, and Float special-value constants.
- Added higher-kinded collection operations and migrated `Result` to the sum-type representation.

### Compiler and tooling

- Added the project-aware `zeron fmt` source formatter and non-mutating `--check` mode.
- Bundled standard-library source files in the release distribution for language-server navigation.
- Added the query-engine sample project.

## [0.3.0] - 2026, 18 OCT

### Compiler and language work

- Added opt-in `deriving Eq, Hashable` support for products, sums, classes, and newtypes.
- Added anonymous object expressions that implement explicitly listed contracts.
- Made visibility modifiers optional on class and effect methods and properties; omitted visibility
  now defaults to private, while public visibility remains explicit.
- Added the compiler-backed Zeron Language Server Protocol server with diagnostics, completion,
  hover, and definition navigation.

### Compiler and tooling

- Set the minimum supported Java release to stable Java 25 and removed the preview-feature runtime
  requirement.

### Standard Library

- Added `zeron.lang.Cell<T>` for explicit shared mutable state captured by lambdas.
- Added byte and byte-array support, byte-oriented I/O with strict UTF-8 text adapters, signed
  64-bit `Long`, launcher argument forwarding, and monotonic elapsed-time APIs in `zeron.time`.

### Compiler API and tooling

- Added the public member-discovery API for querying source and compiled-library type members.
- Added a VS Code language client for launching and connecting to the Zeron language server.

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
