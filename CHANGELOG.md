# Changelog

## [0.6.0] - 2026, 8 NOV

### Standard library

- `Hashable.hash` now returns a 64-bit `Long`, and built-in, nullable, and derived hashing all use
  full-width algorithms. `String` uses a 64-bit FNV-1a pass with final avalanche, `Long` retains all
  bits, and `Float` canonicalizes NaNs while distinguishing signed zero. Derived products, sums,
  classes, and newtypes use a shared order-sensitive combiner seeded by sum case; `HashMap` and
  `HashSet` mix the full hash with the Fibonacci constant before reducing it to a bucket index.
  This is a pre-1.0 breaking change to `Hashable.hash`, `Integer.hash`, `Rational.hash`, and
  `HashMap.withOperations`; the API-index and standard-library API versions are now 11. Int
  implementations receive the new `ZR2026` diagnostic.
- `Result<T, E>` now admits nullable type arguments for both parameters, as `Option<T>` already
  did, so a capability whose result mentions a nullable type can declare it directly.
- Added the effect-aware `Encode<T, E>`, `Decode<T, E>`, `Codec<T, E>`, and `Shape<T>` contracts to
  `zeron.serialization`, with the free `encode` and `decode` entry points and built-in codecs for
  `Int`, `Boolean`, `String`, and `Float` in `zeron.lang`. A codec declares the effects its
  operation raises, so streaming through a format that raises is tracked by the type system.
- Added the typed JSON adapter in `zeron.serialization.json.typed`: `parseAs<T>`, `stringifyAs<T>`,
  `decodeValue<T>`, and `encodeValue<T>` drive derived or hand-written `Encode` and `Decode`
  operations directly over the JSON value model. A tagged alternative is a single-member object (a
  payload-less case is JSON null), and maps report an error because JSON object keys are strings
  and the protocol has no map-key operation.
- Added the `Json` configuration value to `zeron.serialization.json` with its companion namespace.
  A configuration holds the number of spaces per indentation level and provides `encodeToString`,
  `encodeToStringWith` for model-selected fields, `encodeToValue`, `encodeToValueWith`,
  `decodeFromString`, `parseValue`, and `stringifyValue`. `Json.compact()` is the default,
  `Json.pretty(indent)` indents each nesting level, and the namespace functions
  `Json.encodeToString` and `Json.decodeFromString` delegate to the default.
- Added `PropertyRef.writer` and `Field.writer`, optional `(&T, V) -> Unit` functions that are
  present when the referenced or mapped property has an accessible setter.
- Renamed the collection classes to `ArrayList`, `HashSet`, and `HashMap`, and turned `List`, `Set`,
  and `Map` into contracts, so a function can accept any implementation. Added the `Sized`,
  `PositionalEditor`, `Removable`, `KeyedSink`, and `Clearable` capability contracts and split
  `Reversible` and `Sortable` into copying and in-place variants. `ArrayList.insert` now returns
  `Boolean` to report an out-of-range index.
- Moved the terminal operations onto `Iterable` as default methods: `forEach`, `fold`, `any`, and
  `all` are effect-row-polymorphic, and `count` and `isEmpty` work on any sequence. `Stream` repeats
  the row parameters on its overrides, and the hash-backed collections override `count` with their
  constant-time size. A generic call now binds only its own type parameters when inferring from
  arguments and leaves variance to assignability, so a broadly accepting callback such as
  `println` is accepted where a narrower function type is expected.
- Added baseline alternative implementations for the collection contracts: `LinkedList<T>` and
  `ArrayDeque<T>` are mutable deques, `ConsList<T>` is a persistent list, and `IdentityHashMap<K, V>`
  compares keys by reference identity without an `Eq` or `Hashable` bound. Added the `Deque<T>`
  capability contract and `System.identityHashCode` for identity-based hashing, and extracted the
  shared `HashTable` internals used by `HashMap` and `IdentityHashMap`.

### Language server

- Hover, completion, and go-to-definition now cover Java interop and external bindings. Hover
  identifies the resolved Java binary type, constructor, method, or field with its supported
  signature and checked exceptions, together with the external facade binding when one exists.
  Each hover ends with a `Go to definition` link to the same location definition navigation uses,
  so the readable signature remains discoverable. Match case names hover and navigate as the sum
  case resolved for the scrutinee, instead of falling back to a same-named type elsewhere in scope.
  Completion offers only facade-declared members when a facade restricts the API, and otherwise the
  Java members that the interop resolver supports, keeping static and instance context and
  constructors distinct. Definition keeps Zeron external uses on their Zeron declarations and
  attaches direct Java references to a matching Java source file, discovering the running JDK's
  `lib/src.zip` and `<name>-sources.jar` sidecars beside `java-classpath` JARs and reading the new
  optional `[dependencies].java-sources` directories. Archived sources open from a temporary cache
  at their original line positions; without source, definition falls back to the external
  declaration or no target.

### Compiler and language work

- Integer ranges now accept arbitrary `Int` expressions as endpoints instead of only integer
  literals, so `0..values.length - 1` and `start..end` compile. `..` is the lowest-precedence,
  right-associative operator and still constructs an inclusive `zeron.ranges.IntRange`; each
  endpoint is evaluated once when the range is created.
- Model declarations now track writable properties. Mapping a property with an accessible setter
  fills `Field.writer`, so a consumer can update an existing value through the model; `Model.field`
  forwards the writer that `PropertyRef` carries. Model tests cover writable and read-only mappings,
  writer use across separate compilation, and SQL-style and document-style consumers of one type.
- A model value can now be passed to a function expecting `Model<T>`, including through an
  intermediate contract such as `SqlModel<User>`; generic inference projects a class's contract
  arguments when matching a generic contract parameter. This fixes `jsonModel(UserWire, user)`
  rejecting the synthesized model implementation. The generated singleton's constructor is now
  public in bytecode so the compiled top-level value initializer can create it, fixing an
  `IllegalAccessError` when running a model program.
- A value whose type is a type parameter declared `T: nullable` is now treated as possibly null
  inside the generic body. Member access, contract-bound operations, operators, iteration, indexing,
  and passing the value where a non-null type is required are rejected until flow analysis proves it
  non-null; a branch or early exit on `null` refines it, an assignment invalidates that refinement,
  and safe navigation (`?.`) and the assertion `!!` remain available. The new diagnostic is `ZR2025`.
  `Sortable.sorted` and `InPlaceSortable.sort` no longer admit nullable elements, because
  `Comparable` defines no order for `null`.
- Derivation now generates streaming encode and decode operations for native products, native sums,
  and transparent data classes that derive `Encode`, `Decode`, or `Codec`, together with `Shape`
  metadata. Encoding writes fields and alternatives straight to the encoder, decoding reads fields
  by name and constructs the value through its canonical constructor, and a failure carries the
  component path that produced it. Unknown sum cases are decode errors, and a missing required
  field is reported rather than silently defaulted.
- Made the bundled compiled standard library the default for project and direct compilations.
  When the installed compiled library is incompatible with the compiler's API index, the default
  falls back to the bundled sources; explicit `compiled` mode still reports the mismatch. The
  installation location now honors the `zeron.home` property.

  `zeron` resolves `lib/zeron-stdlib-*.jar` from the installation, prefers an explicitly listed
  standard-library library, and falls back to bundled sources only when no artifact is available.
  `zeron init` selects `compiled` in installed distributions and `source` in source checkouts.
  `--stdlib source` and `[platform].stdlib = "source"` remain the explicit opt-in for
  standard-library development.

- Added `model` declarations: `model Name is Contract<T> { property -> "external"; property = constant; }`
  binds a singleton descriptor value that validates mapped properties through their identity,
  rejects duplicate and unknown mappings, and requires the parent contract's property assignments.
  The descriptor is backed by the new `zeron.lang.Model`, `zeron.lang.AnyField`, and
  `zeron.lang.Field` standard-library types. Public models are preserved in the API index, so a
  model from a compiled library can be imported, read, and passed as its contract.
- Added property references (`T::p`, such as `User::name`) with a new standard-library
  `PropertyRef<T, V>` value that exposes `get`, `name`, and a stable `identity`, converts
  implicitly to `T -> V`, and compares by declaration across separate compilations.
- Added typed declarative annotations with target and constant-argument validation, and preserved
  public annotation definitions and applications in API-index schema 9. Added runtime
  `CodeModel<F>` values for inspecting selected inline predicate lambdas.
- Added `PropertyId` to the standard library and a read-only `PropertyRef.id` whose `text()` equals
  `identity`. Replaced the `zeron.compiler` code-model API with sealed `CodeShape`, `CodeType`,
  `CodeLiteral`, and `ComparisonOperator` types, typed parameters and capture values, source
  locations, structural equality and hashing, and `selectedProperty`. A parameter of type
  `CodeModel<F>` selects model mode, and an argument may be written as a property-reference literal
  such as `User::name` as well as an inline lambda; an ordinary function type keeps the function
  value. The marker annotation originally proposed for this feature was retired.
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
- Fixed a bug that would allow a mutable array field to be mutated from a non-mutating method.
- Fixed nested statement `if` bytecode generation when branches do not complete normally, avoiding
  invalid branch targets after returning branches.
- Fixed expected-type propagation through conditional expressions.
- Refined stable closed-sum values on the negative path of direct `matches` tests, including
  grouped and negated conditions.
- Fixed adapter discovery for witness helper calls.
- Registered function adapters for conversions in `return` statements and explicitly typed local
  initializers. A function or lambda returned where the declared type differs only by a reference
  wrapper on the result (such as `(&Decoder) -> &Result<T, E>` returned where
  `(&Decoder) -> Result<T, E>` is expected) now gets its bridge instead of failing at emission.
  Restores compilation of the bundled `zeron.serialization.decodeNamed`.
- Rejected function, method, extension-method, and default-contract-method bodies that can fall off
  the end without returning a value when the return type is not `Unit`, reporting `ZR2011` instead
  of emitting bytecode that fails verification. Bodies ending in `raise` or another expression of
  type `Never` are recognized as non-returning, and Unit or inferred-Unit bodies remain allowed.
- Expanded constant folding to cover Int `%`, bitwise operators, shifts, relational operators,
  grouping, and Boolean `and`, `or`, and `not` for constant literals. Unsupported operators and
  division or remainder by zero now fall back to normal emission instead of crashing the compiler
  with `IllegalStateException`.
- Emit runtime support classes on demand. After generating program classes, the compiler scans them
  for support-class references and copies only the referenced units plus their transitive
  dependencies. Programs that do not use HTTP, rationals, or big integers no longer carry those
  support classes, while effects, resources, equality/hashing, Unicode, Java arrays, and array
  iterables are still emitted when used.
- Fixed captured function types in generic lambdas, enabling the `zeron.function` combinators and predicates.
- Improved inference for generic function references in open expected types, allowing `flatMap(identity)` and
  `Monad.flatten` without explicit type arguments.
- Captured ambient type-class evidence in lambdas created inside generic functions.
- Added the postfix `!!` non-null assertion with a stable `NullPointerException` failure and
  support for nullable references and boxed primitives.
- Added explicitly typed lambda parameters, right-associative unary function types, trailing
  lambdas for final function-valued arguments, and implicit `it` for unary trailing lambdas.
- Added receiver function types and contextually typed receiver lambdas, including receiver-member
  lookup, qualified enclosing receivers, receiver-call invocation, and compiled-library metadata.
- Added effect-row polymorphism with `effect...` parameters, empty-row inference, nested callback
  bounds, conservative handler residuals, and compiled-library support.
- Made non-null `Array<T>` intrinsically compatible with `zeron.collections.Iterable<T>` using
  exact element types and a fresh, non-copying adapter per conversion; array loops stay intrinsic.

### Standard Library

- Documented the foundational APIs of `zeron.lang`, `zeron.numeric`, `zeron.collections`, and
  `zeron.control` with hover-visible `///` comments covering contracts, types, and public members.
- Added the `zeron.compiler.CodeModel<F>` API for typed predicate nodes, source types, and eager
  immutable capture values. Bumped the standard-library API version to 9.
- Enabled the `Sortable` higher-kinded contract with `sort`, `sorted`, and comparator-based operations.
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

- Clear the configured output directory after resolution succeeds and before emitting bytecode, so
  stale classes from renamed lambdas or removed declarations no longer accumulate. Cleanup refuses
  unsafe targets: the filesystem root, the current directory, the user home, a directory containing
  `zeron.toml` or `.git`, an output path that is a file, and an output directory that contains a
  compilation source.
- Stopped offering compiler-generated witness helper definitions as completion candidates.
- Stopped offering completion suggestions while the cursor is inside line, documentation, or block
  comments.
- Wrapped long declaration-header clauses in the canonical formatter. `is`, `requires`, `permits`,
  and `deriving` clauses now break before the clause keyword when the flattened header exceeds the
  configured line width, with continuation entries aligned under the first entry.
- Fixed a bug that would override a contract method's signature with that of any available
  conformance witness for it.
- Expanded hints for LSP hovers.
- LSP hover now describes a model by its declared contract (`model UserWire: Model<User>`) instead
  of exposing the synthetic `$model` class, followed by its contract assignments and property
  mappings. Signatures include source parameter names, including for functions imported from
  compiled libraries, type parameters show their constraints at declarations and uses, and a value
  whose type mentions type parameters lists those constraints. Nominal types use simple names
  throughout hover, and long type and signature lines break at their first generic argument list or
  parameter list, one item per line. Function parameter names are preserved in API-index schema 10.
- Import completion after `import` now lists public declarations from compiled libraries, including
  the compiled standard library, instead of only source-project packages. Hover and completion use
  the public catalog's type kind and generic constraints, and definition opens the bundled source
  declaration for bundled standard-library types compiled from an artifact.

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
