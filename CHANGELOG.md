# Changelog

## [0.5.0] - 2026, 21 OCT

### Compiler and language work

- Added `is null` and `is not null` tests, including flow refinement for stable nullable bindings,
  while preserving existing type-test behavior.
- Added underscore digit separators and binary, octal, and hexadecimal integer prefixes. Literal
  validation reports malformed digits, separator placement, and out-of-range values.
- Added checked declaration-site covariance, use-site variance projections, and generic-aware
  runtime type tests and safe casts.
- Added generic admissibility markers: nullable and mutable-reference arguments are opt-in, while
  effect-kinded parameters support exact generic effect propagation.
- Added contract-based overloading for `+`, `*`, unary `-`, and binary `-` through explicit
  algebraic bounds and static contract evidence. Existing built-in arithmetic and string
  concatenation behavior remain unchanged.
- Added class-owned custom numeric literal suffixes with explicit suffix imports, aliases,
  category-specific constructor factories, lossless string input, and compiled-library metadata.
- Added clause-based for-expressions with lazy `yield` and eager `collect`.
- Added reusable named handlers and first-class typed-effect handler values, including structural
  handler types, anonymous handlers with immutable captures, and compiled-library metadata.
  Handler types may omit `raises {}` when handler cases raise no effects; explicit empty clauses
  remain accepted.
- Removed contract constructor requirements and witness factory mappings. The standard library now
  exposes `Collector<C<_>>` for generic collection construction and population, while `Sink<T>`
  remains the behavioral contract for existing sink values. `Collector.empty()` returns a mutable
  reference so the resulting collection can be populated through its mutating operation.
- Defined source-order-independent bounded member lookup: concrete methods and behavioral contract
  methods take precedence over HKT receiver operations, which remain available as a fallback.
- Made mutable-reference type arguments inadmissible for generic parameters by default. `T: mut`
  opts in without granting mutation operations; migrated standard-library APIs that carry mutable
  views and preserved the marker in compiled-library metadata.
- Added Java type and array mappings, supported Java generic signatures and projections, and
  checked-exception-to-effect mappings through explicit external declarations.
- Set the API-index schema version to 6 and the standard-library API version to 7.

### Standard Library

- Added a synchronous `zeron.net` HTTP client with request/response types, streaming response bodies,
  explicit timeouts and redirects, and typed failures.
- Added `zeron.collections.Step<T>` for iterator results, separating yielded values from exhaustion.
  Updated bundled iterators, stream pipelines, and `for`-loop lowering; `Option<T>` remains a
  general-purpose optional value.
- Added the `zeron.algebra` contracts `Add`, `Addition`, `Mult`, and `Multiplication`, with bundled
  witnesses for supported `Int`, `Long`, and `Float` operations. Updated the standard-library API
  version for the new public contracts.
- Added `zeron.math.BigInteger` as a custom-suffix proof of concept, including arbitrary-precision
  literal parsing and `Addition`/`Mult` implementations.

### CLI and tooling

- Added `zeron version` and `zeron --version`, reporting the version embedded in the running
  distribution without requiring a project manifest.
- Expanded terminal and VS Code syntax highlighting for current keywords, literal forms, and
  contextual `deriving`, handler `with`, and property `get` syntax.

## [0.4.1] - 2026, 19 OCT

### Compiler and language work

- Corrected generic witness overlap checking for higher-kinded types.

### CLI and tooling

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

### CLI and tooling

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

### Standard Library

- Added `zeron.lang.Cell<T>` for explicit shared mutable state captured by lambdas.
- Added byte and byte-array support, byte-oriented I/O with strict UTF-8 text adapters, signed
  64-bit `Long`, launcher argument forwarding, and monotonic elapsed-time APIs in `zeron.time`.

### CLI and tooling

- Added the public member-discovery API for querying source and compiled-library type members.
- Added a VS Code language client for launching and connecting to the Zeron language server.
- Set the minimum supported Java release to stable Java 25 and removed the preview-feature runtime
  requirement.

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

### CLI and tooling

- Added the Apache-licensed VS Code TextMate grammar and terminal color support for compiler
  diagnostics and explanations.
- Added two new compiler commands: `help` and `dump`.

### Miscellaneous

- The `Display` implementation now documents the conventions intended for future derived
  `Display` implementations.
- The release packaging script now smoke-tests both embedded standard-library source mode and
  compiled standard-library mode.
