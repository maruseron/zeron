# Changelog

## Unreleased

### Compiler and language work

- Added receiver-based and receiverless default operations to higher-kinded contracts, including
  compiled-library support and derived `Applicative.map2`, `Monad.flatten`, `Foldable.count`, `Foldable.isEmpty`,
  `Traversable.sequence`, and `Comonad.duplicate`.
- Required witness implementations for contracts with static method requirements, including
  inherited requirements; nominal class conformance now reports an error for these contracts.
- Added `delegating witness` declarations to forward compatible non-mutating instance requirements
  to public methods on the target class while keeping static and unmatched requirements explicit.
- Added `delegating implementation` blocks to forward compatible HKT receiver operations to
  public methods declared on the containing class, including methods with broader accepted
  parameter types and compatible return types.
- Replaced algebraic operator evidence with the `Numeric`, `Integral`, `Fractional`, and `Floating`
  hierarchy. Unsuffixed literals now retain exact values for expected-type conversion; the
  standard library provides arbitrary-precision `Integer` and exact `Rational` types.
- Added `///` declaration documentation comments, Markdown hover for source and compiled-library
  symbols, and public documentation records in API-index schema 7.
- Added transparent data classes with immutable canonical components, component-based structural
  equality, opt-in hashing, auxiliary-field initialization, and effect-aware construction.
  Compiled-library metadata preserves data-class shape and constructor effects. Added the
  serialization streaming protocol and aggregate `Encode`/`Decode`/`Codec` capability evidence;
  generated codec operations and typed format adapters remain pending. Bumped the API-index schema
  to 7 for data-class metadata.
- Loosened type substitution rules for generic calls to match general subtyping rules by allowing
  implicit narrowing from `&T` to `T` for type parameters not admitting mutable projections.
- Fixed a bug that would allow a field's mutating methods to be called from another method not
  marked as `mut`.

### Standard Library

- Made `zeron.lang.Display` non-generic; witness targets identify displayed types. Bumped the
  standard-library API version to 8.
- Broadened `zeron.collections.Stream.flatMap` to accept any `Iterable` result, allowing nested
  `for` generators over lists and other iterables to flatten lazily.
- Added `zeron.serialization.json` parsing, an ordered mutable JSON value model, exact number-lexeme
  preservation, checked integer conversion, and compact stringification with bounded parsing,
  Unicode validation, and source-positioned errors. Typed JSON codec integration remains pending
  type-directed serialization operations.
- Added the `zeron.function` package with general-purpose functional constructs, such as
  `identity`, `supply`, `constant`, `compose`, `pipe`, `flip`, partial application functions,
  predicates operators such as `negate`, `both` and `either`, a `memoize` function and a tap-like
  `Peekable` type class with a `peek` (tap) method.
- Added the numeric `Vector2` type with arithmetic operator support.
- Made all collections in `zeron.collections` null-hostile. Null support for `Array` remains.
- Made all type classes in `zeron.control` null-hostile.

### CLI and tooling

- Stopped offering compiler-generated witness helper definitions as completion candidates.
- Stopped offering completion suggestions while the cursor is inside line, documentation, or block
  comments.
- Wrapped long declaration-header clauses in the canonical formatter. `is`, `requires`, `permits`,
  and `deriving` clauses now break before the clause keyword when the flattened header exceeds the
  configured line width, with continuation entries aligned under the first entry.

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
