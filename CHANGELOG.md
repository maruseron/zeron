# Changelog

## [0.9.0] - 2026, 25 NOV

### Standard library

- Added the ZEP-0152–ZEP-0157 library modules. `zeron.random` provides seeded xoshiro256**
  generation, bounded values, `pick`, and `shuffle`; `zeron.math.Statistics` provides population and
  sample descriptive statistics; `zeron.collections` adds `PriorityQueue`, `SortedSet`, `SortedMap`,
  and receiver-lambda builders; `zeron.buffer` adds byte buffers and readers, endianness, LEB128
  varints, bit helpers, and checked hex/Base64 codecs; and `zeron.text` adds `Formatter` and a
  compiled `Regex` wrapper with typed errors, groups, iteration, and replacement. Ordered maps and
  sets currently use sorted parallel arrays, so insertion and removal are `O(n)` rather than the
  proposed red–black-tree cost.
- Added the ZEP-0066 TOML parser and ZEP-0067 XML parser. `zeron.parsers.toml` exposes ordered
  tables and a typed value/diagnostic model over `tomlj`; `zeron.parsers.xml` exposes namespaced
  elements, attributes, direct text, and children with DOCTYPE and external-resource access
  disabled.
- Added the ZEP-0171 concurrency surface. `ConcurrentScope` is fail-fast by default, `openManual`
  and `openManualWith` preserve caller-coordinated failure handling, `dispose` is effect-free, and
  `concurrent`/`concurrentWith` run a read-only receiver body with the blocking `Await` handler and
  cancel-and-join leftovers on exit. Task cancellation causes and failure observation are tracked,
  siblings are cancelled by the first genuine failure, and an unobserved failure is reported at
  close.
- Added the `Filterable` contract and its standard implementations. `zeron.control.Filterable`
  provides the `mapFilter` primitive plus derived `filter` and `compact`; `Option` and `Stream`
  supply default witnesses, with `Stream.mapFilter` filtering lazily and preserving element order.
  `Result<_, E>` deliberately has no default witness.
- Extended `zeron.test` with `@CompileFailure` and the `assertContains`, `assertNotContains`,
  `assertStartsWith`, `assertEndsWith`, `assertGreaterThan`, and `assertLessThan` helpers.
  `STANDARD_LIBRARY_API_VERSION` is now 23 for this `0.9.0` cycle's public library additions.
- Migrated the standard library's Java runtime tests to the Zeron suite in `stdlib-tests/`. Its
  `stdlib-runtime` target compiles the bundled sources with `stdlib = "source"` and runs 147 tests
  plus three `@CompileFailure` fixtures covering `Option`, `Result`, `Cell`, strings, math and
  statistics, collections and builders, streams, for-expressions, buffers, randomness, text
  formatting and regular expressions, generators, and ranges. The parity map in
  `documents/developer_guides/stdlib-suite-parity.md` records every removed Java test and the
  assertions that deliberately stay Java, and `scripts/release.ps1` runs the suite with the packaged
  launcher.
- `Formatter.format` now reports `MissingArgument(index)` for a sequential `{}` field that has no
  argument left, matching its documented error model. An explicit index beyond the argument array
  still reports `IndexOutOfRange(index)`.
- Removed the `MonadPlus` contract. It bundled `Alternative` and `Monad` without operations of its
  own; because Zeron has no structural contract checking, generic code obtained nothing from the
  name over an intersection bound. Use `Alternative<M> + Monad<M>` directly.
- Implemented the ZEP-0169 compile-failure runner. A test target may configure
  `compile-failure-roots`; those fixtures are excluded from the suite compilation, and each
  `@CompileFailure(fixture, code, message)` is compiled on its own in process after the generated
  runner finishes, matching the reported diagnostics and cleaning up its output directory. A fixture
  that compiles, is missing, or fails internally is reported as a failed test. `zeron test` gained
  `--filter`, `--list`, and `--fail-fast`; the generated runner now prints a failed assertion's
  message and the summary reports a distinct error count. A JSON report, per-test timing, and JSON
  diagnostics remain pending under ZEP-0175.

### Compiler and language work

- Added the ZEP-0174 `if (condition);` guard clause to `do` blocks. A guard filters the preceding
  generator through witness-dispatched `Filterable.filter` before that bind's `map`/`flatMap`, so a
  false guard skips the rest of the path — including later binds, locals, and the `yield`
  expression — and `Stream` guards stay lazy. A guarded block requires `Filterable<F>` in addition
  to `Monad<F>`. Guards must follow a bind and precede the bind group's plain `let` locals; guards
  before the first bind and in bind-free `do<C<_>>` blocks are rejected, and the condition must be
  an effect-free `Boolean`. Higher-kinded bounds now keep one parameter descriptor whose bounds are
  published after parsing, so chained bound-dispatched calls such as `S<_>: Monad<S> + Filterable<S>`
  resolve against a constrained parameter. Editor surfaces also recognize `responds`: the terminal
  highlighter and TextMate grammar color it only in an effect response clause, the language server
  offers it as a keyword completion, and an effect's hover detail shows its declared response type.
- Added the ZEP-0119 pull-generator surface and cleanup rules. `zeron.collections` gains
  `Generator<T>` (an `Iterator<T>` with `cancel` and `isDone`), a replayable `GeneratorSource<T>`,
  and `generator<T>(body)`. A body suspends at each `perform Yield<T>(...)` and resumes on the next
  pull; `cancel` stops it with explicit `DisposeFailure`, while `close` is the quiet path. Open
  generic effect applications are accepted in function types, so `body` is a typed
  `() -> Unit performs Yield<T>, DisposeFailure` value and the direct `generator<Int> { ... }`
  trailing-lambda form infers `T`. The body's cleanup effect is delivered through `cancel()` or
  logged by `close()`, leaving `next()` effect-free. `Iterator<T>` gains an idempotent
  `close` default, and `for` plus the `Stream` adapters now close the cursor they own on exhaustion,
  `break`, `return`, and exceptional exit. `Cont` gains source-level `isDone` and `cancel`; `GeneratorSupport` drives the provider session
  without the level-2 frame. `DisposeGuard` rejects `Await` performs and new continuations during
  `dispose`, and a `dispose` implementation cannot declare `Await` or install a continuation-capturing
  handler. `STANDARD_LIBRARY_API_VERSION` is now 22.
- Completed the remaining ZEP-0119 runtime work. A level-3 handler case whose body is exactly
  `k.resume(value)`, or a block whose yielded value is that call, now lowers to a tail-resume
  transfer: the case frame is discarded and `Level3Support` drives the body and later cases from one
  loop, so a user-written handler keeps a bounded stack across suspensions. Each `handle` also owns
  a generation token that invalidates every `Cont` it created when the handle exits, and task
  cancellation now reaches a suspended continuation through `CancellationSupport`: `Task.cancel` and
  scope cancellation set a private token, the continuation unwinds its `using` cleanup in LIFO order,
  and `Task.value()` raises a `CancellationException` with cleanup failures suppressed. A task
  cancelled before its body starts does not run it.
- Added the ZEP-0119 suspension-boundary runtime check. `zeron.runtime.SuspensionGuard` records a
  thread-local host-region depth; a level-3 or generator frame snapshots that depth when installed,
  and suspension is rejected with `BoundaryException` when host regions were entered afterwards. The
  compiler marks a Java call that hands a Zeron callback to host code with `enter`/`exit` in a
  `try`/`finally`, covering synchronous Java, JNI, and native callbacks, while a handler installed
  inside the callback still suspends. `DisposeGuard` remains the stricter cleanup marker.
- Added `ZR3019`, a warning on a direct `Task.value()` join whose task effect row is concretely empty.
  The join bypasses `Await`, so an enclosing handler cannot apply its waiting policy; `await(task)`
  is the async-facing spelling. Tasks with a non-empty row keep re-raising their effects from
  `value()` and are not reported.
- Completed ZEP-0166 open generic effect applications. Effect applications such as `Yield<T>` now
  infer their arguments structurally from lambdas and function values, including nested forms like
  `Failure<Array<T>>`, and nested applied effects keep their identity through substitution. The
  existing API-index type-parameter reference encoding preserves the open application across
  separate compilation, so no schema bump was required. `generator` uses this path for its typed
  body, and a separately compiled client can infer `T` from the same exported signature.
- Enforced the immutable-capture rule for explicitly typed lambdas. A lambda passed to a typed
  function parameter now rejects reassignable local captures just like an inferred lambda, closing
  a path that previously skipped capture validation. Mutable object references remain the supported
  way to share state.
- Fixed `continue` inside a `for` loop over an intrinsic array. The array-loop emitter now records
  the iterator depth expected by break/continue cleanup, so such loops compile instead of failing
  with a missing-loop-state error.
- Added the ZEP-0119 continuation-provider gate. `zeron.runtime.ContinuationProvider` is a
  backend-neutral boundary; the Loom implementation lives in the isolated `src/continuation/java`
  source set and is compiled with the internal `jdk.internal.vm` export. `-Dzeron.continuation.backend`
  accepts `auto`, `a`, or `b`: `auto` uses A when the export is present and otherwise reaches the
  placeholder owned by ZEP-0172, while explicit A fails with a launcher hint. The distribution
  launchers and `zeron run`'s generated-program process pass the export; plain `java -jar` must
  carry it for A. The portable compiler-managed backend is now a separate unscheduled proposal
  (ZEP-0172), not a fallback in ZEP-0119.
  `zeron.runtime.Cont<R, T>` adds the one-shot lifecycle wrapper with owner confinement, terminal
  states, idempotent cancellation, and stack-trace delegation. `zeron.runtime.Level3Support` adds the
  perform-to-`Cont` runtime bridge: a perform suspends the A session, the owner constructs the `Cont`
  for the handler, and later suspensions re-enter the same handler. The compiler now lowers a
  general resuming `handle` case to that frame: `k` is a `zeron.runtime.Cont<Response, T>`, a case
  may do work around `k.resume`, and the resume drives the protected body and returns its result.
  Exact-tail cases keep the level-2 lowering and do not capture continuations. Storing a
  continuation and resuming it through its inferred type works. The cancellation token, the
  runtime `dispose` marker, and the generator surface are now implemented as described above.
- Corrected newtype subtyping. A newtype is now covariant with its carrier in read positions, so
  `table == "users"`, passing a `UserId` where an `Int` is expected, returning it as the carrier,
  and string-concatenating it are all valid without an accessor. The write direction stays nominal:
  a carrier value is still rejected where the newtype is expected, and construction goes through
  `.new` or the validating `tryNew`. The synthetic `.value` accessor is gone, including for
  function-shaped newtypes, which are now invoked directly through their carrier
  (`Doubler.new(f)(21)` and `formatter(record)`). The now-redundant `HttpMethod.value` and
  `HttpUri.value` standard-library accessors are removed, so callers pass those newtypes directly
  where a `String` is expected. This is a source-level correction of the direct-carrier
  representation from ZEP-0070; the runtime representation is unchanged.
- Unqualified sum case construction is now limited to the arms of a match over a concrete sum.
  Patterns, guards, and the constructed values on the right side of an arm may omit the sum
  qualifier, while `Some(42)`, `None()`, and similar constructions anywhere else require the
  qualified form such as `Option.Some(42)`. This narrows the context-resolved spelling accepted by
  ZEP-0128 and keeps unqualified case names from acting as package-level values.
- Implemented ZEP-0118 response-typed effects and tail-resumptive handlers. An effect may declare
  `responds R`; `perform` then has type `R`, and an inline `handle` case may bind a resume token
  with `case Ask<Int>, k -> k.resume(8);`. The handler answer returns directly to the perform site
  through
  a synchronous thread-local handler frame with no continuation capture, while effects without a
  response and cases without a token keep the existing abortive behavior. The standard library
  gains `Ask<T>`, `Yield<T>`, and `Log`; compiled-library API metadata stores response types under
  schema 17 and reads older indexes as abortive, and `STANDARD_LIBRARY_API_VERSION` is now 20.
  Stored or captured resume tokens, non-tail resumption, resuming cases in named or first-class
  handler values, and generic or row-variable responding performs are rejected or remain
  unsupported; general continuations are ZEP-0119.
- Implemented ZEP-0147 by replacing `raise` and `raises` with `perform` and `performs`. Effect
  operations are now written `perform Effect.new(...)`, and callable signatures, function types,
  handler types, and contracts use `performs`. There are no aliases: old spellings produce
  targeted migration diagnostics, while a member named `raise` such as `IoFailure.raise(...)`
  remains an ordinary constructor call. Runtime carriers, effect identity, and library-index
  metadata are unchanged.
- Implemented ZEP-0123 admissibility clauses and leading reification. Ordinary contract bounds
  remain after `:`, while admissibility markers move to `admits`: `T: Render admits nullable`,
  `T admits mutable`, and `E admits effect...`. Anonymous existentials accept the same split,
  including `Box<reified _: Render admits nullable>` and marker-only `Box<: admits nullable>`.
  `reified` is now a leading capability (`reified T`), and legacy marker spellings such as
  `T: mut` or `T: nullable` produce migration diagnostics. Response constraints remain the
  `Responds<R>` bound, and effect markers do not take response type arguments.
- Renamed the structured-concurrency entry point from `zeron.concurrent.Scope` to
  `zeron.concurrent.ConcurrentScope`. `Scope` was overloaded with lexical scopes and `using`
  blocks; the noun ending in `Scope` reads as the concurrency type in context, while the
  `using (let scope = ConcurrentScope.open())` declaration keeps the local short. `Task`, `spawn`,
  cancellation, and the runtime support classes are otherwise unchanged.
- Implemented ZEP-0112 structured concurrency on virtual threads. `zeron.concurrent.ConcurrentScope` opens a
  `using`-scoped lifetime over virtual threads (or an injected `ExecutorService`), starts
  `Task<T, E>` handles, and joins or cancels them on close; `Task.value()` blocks and re-raises the
  task body's captured effects at the join point, so the caller's `performs` row covers them.
  Cancellation interrupts running tasks before the closing join, deadlines request cancellation,
  and `using` cleanup inside a task keeps the normal primary-effect/suppressed-failure precedence.
  The compiler now preserves substituted effects on generic member calls and records effects for
  member calls resolved as arguments. This is a standard-library and runtime addition: no
  suspension syntax, task monad, compiler transform, or runtime ABI change.
- Implemented ZEP-0165 by unifying the async surface on `Task`. `zeron.concurrent.Future` and
  `FutureImpl` are removed; `spawn(scope, body)` now returns `&Task<T, {E}>`, and `await(task)`
  accepts a `Task<T, E>` and reads it through `Task.value()`. The handle keeps `value()`,
  `isDone()`, `isCancelled()`, and `mut cancel()`, and a scope still owns every task it starts.
  Source needs no `async`/`await` keyword, suspend modifier, wrapper return type, or second handle
  type.
- Implemented ZEP-0164's interceptable await policy. `zeron.concurrent.Await` is a non-generic
  effect that responds `Unit` and carries a `Completion`; `await(task)` performs `Await`, then
  returns `task.value()`, so the value comes from the typed handle rather than an effect response.
  `blocking(body)` installs the default waiting handler for explicit boundaries, and the generated
  program entry installs the same host-side frame so `main` can await without declaring `Await`.
  Because the effect no longer returns the value, there is no erased join and no unchecked recovery
  cast. Outside the entry point, a function that awaits must declare `Await` in its `performs` row
  or handle it; task-body awaits are not resumable in this slice. The entry launch is covered in a
  child process, and the `Task`/`spawn`/`await` surface round-trips through the API index.
  `STANDARD_LIBRARY_API_VERSION` is 22 for this unreleased cycle.
- Fixed syntax highlighting after the `perform`/`performs` and `admits`/leading-`reified` syntax
  changes. The TextMate grammar recognizes the new keywords, data-class `performs` headers, and
  admissibility markers; the ANSI highlighter applies the same contextual rules.
- Stopped reporting `ZR3017` shadowing warnings for compiler-generated anonymous-object capture
  bindings. Resolving an object expression no longer makes captured locals such as `l` and `r` in
  the standard library look like user-authored shadowing.

### Standard library

- Completed the standard-library style pass. `Json` value operations now live in the `Json`
  namespace (`Json.parse`, `Json.stringify`, `Json.build`, `Json.model`, `Json.emptyArray`,
  `Json.emptyObject`), constructors that adapt an existing value use `from`
  (`HttpClient.fromConfig`, `TextWriter.fromWriter`, `TextReader.fromReader`, `HashSet.fromMap`,
  `HashMap.fromOperations`, `HashTable.fromOperations`, `StringJoiner.fromConfig`,
  `Logger.fromClock`), and `String.at` is the canonical character accessor with `charAt` retained
  as a compatibility bridge. `Ordering` cases are now `Less`, `Equal`, and `Greater`. `AnyField`
  is a product, and `Field` and `Vector2` are data classes. `Option` gains `flatMap`, and
  `Array.fold` is canonical with `foldLeft` as the `Foldable` bridge. The concurrent-scope source
  file follows the `snake_case` convention. `STANDARD_LIBRARY_API_VERSION` stays 22 because this
  remained part of the same 0.9.0 API cycle.

## [0.8.0] - 2026, 20 NOV

### Compiler and language work

- Added value-producing block expressions from ZEP-0146. A brace block in expression position ends
  with one final `yield` and produces that value, so `let total = { let subtotal = a + b; yield
  subtotal * 2; };` works without a helper function. A block in statement position keeps its `Unit`
  meaning, so `if` branches, loop bodies, `using` bodies, and function bodies do not change. The
  emitter writes the statements and the value inline with no closure or runtime ABI, the parser
  rejects a missing or non-final `yield`, and the formatter indents the form.
- Added spread arguments for variadic parameters from ZEP-0151. `f(...values)` forwards an existing
  `Array<E>` to the variadic parameter without repacking it, or expands any other `Iterable<E>`
  through the existing iteration protocol into the packed array. A call allows one spread, as its
  final argument, its element type participates in generic inference, and a nullable operand is
  rejected. The feature adds no runtime representation or API-index field.
- Fixed the formatter dropping the space before an array literal, which turned `let values = [1];`
  into `let values =[1];` and joined a literal to the preceding comma.
- Contract methods that declare no receiver can be called on a constructor name when its witness
  is active, so `Option.pure(5)` and `Result<Int, String>.pure(5)` dispatch through the
  `Applicative` implementation's `pure` with no receiver. A `do` block with no `let name <-` bind
  now lifts unit with that `pure` and maps its remaining clauses over the result, so
  `do<Option<_>> { let base = 4; yield base * 2; }` is valid; the declared constructor fixes any
  argument the witness leaves open, such as `E` in `do<Result<_, String>>`.
- `do` blocks now accept plain `let` bindings before the first bind clause. The leading bindings
  are lowered into a lambda that encloses the monadic chain, so they run before the first step and
  constructor inference still comes from the first `<-` bind. The option/result guide documents the
  form.
- Class `static fn` declarations are now emitted, and `static fn`s and named constructors satisfy
  contract methods that the contract does not mark `static`, including through type-class evidence
  and type-level calls such as `T.create(...)`.
- Contract-conformance and array-slot mutation diagnostics now point at the class contract clause
  and the assignment operator instead of a synthetic identifier at the start of the file.
- Fixed a generic call followed by a trailing lambda. `invokeWith<Int> { 42 }` now parses as a
  call with explicit type arguments plus a trailing lambda; previously the block was parsed as a
  value block and reported `ZR1101`, which blocked `buildList<Int> { add(1); }`-style builders.
- Implemented ZEP-0136 witness bundles and coherent prerequisite evidence. `witness bundle`
  declarations group members for one target, named bundles are selection-only, `default witness
  bundle` members are ambient canonical evidence, and selecting a bundle shadows an ambient default
  for the same applied contract and target as a unit. Bundle members are registered through the
  existing witness machinery, so owner-directed dispatch and the hidden-evidence ABI are unchanged.
- Implemented ZEP-0137 explicit witness selection. `using witness (Provider, ...) { ... }` activates
  named bundles or standalone witnesses lexically, and a direct call may carry a single-invocation
  `with witness (Provider, ...)` clause. Selected evidence forwards through generic calls and lambda
  captures, and the block restores the previous selection on exit.
- Implemented ZEP-0063 standard-library testing APIs. `zeron.test` provides `@Test`, `@Ignore`, the
  `TestFailure` effect, and the assertion functions; `zeron test` discovers annotated top-level
  tests in project test roots, generates a deterministic runner, and reports failures and skips
  with a status exit. The manifest gains `[project].test-roots` and `[targets.<name>].kind`.
- String literals now support escape sequences. The scanner decodes `\\`, `\"`, `\'`, `\n`, `\r`,
  `\t`, and braced scalar escapes such as `\u{1F600}` before the value reaches the program, so
  strings can contain newlines, tabs, quotes, backslashes, and arbitrary Unicode scalars. Character
  literals share the same escape set and now also accept `\"`. Strings may still span lines, and a
  backslash immediately before a line break continues the string on the next source line without
  adding a line break to the value. An invalid escape reports the new `ZR1007` diagnostic, and the
  grammar reference and strings guide document the sequences.
- Implemented ZEP-0050 Java host-access trust boundary. Direct Java invocations, constructors, and
  field accesses now require an explicit `unsafe` expression; curated `external class` and
  `external fn` facades remain trusted and unchanged. Under `unsafe`, checked Java exceptions may
  escape without a mapping and add no raised effect, while Zeron effects raised inside the operand
  still escape normally. Public `static final` fields with a class-file `ConstantValue` are exempt.
  The scanner, parser, formatter, ANSI highlighter, TextMate grammar, language-server keyword
  completion, guides, and compiler diagnostics were updated, and direct JDK calls in the standard
  library migrated to `unsafe`.
- Implemented ZEP-0148 Java SAM conversion. An effect-free Zeron lambda or top-level function
  reference now implements an explicitly written Java functional interface at direct Java calls,
  curated facades, and Zeron function parameters, including compiled-library signatures. The
  resolver discovers the single abstract method from class-file metadata, including inherited and
  classpath interfaces, substitutes the interface's type arguments, and defers callback arguments
  during Java overload selection. Lowering reuses `LambdaMetafactory` with the Java interface as
  the call-site type, so no runtime class, generated interface, or API-index field is added.
  Converted callbacks must have an empty `raises` row, and Java nullability, callback lifetime, and
  thread-safety limitations are documented in the Java interoperability guide.
- Implemented ZEP-0132 nullable safe-cast correction. `as?` now always produces a nullable result,
  and a nullable target such as `value as? T?` is normalized to `T?` before runtime-test validation
  and emission. The runtime test uses the non-null base `T`, a null or mismatched source yields
  null, and member access still requires a null check or safe navigation.
- Implemented ZEP-0134 invocation of function-valued expressions. An argument list may follow any
  non-null function-valued expression, so curried calls, call results, groupings, conditionals,
  indexed values, function-valued properties, and trailing-lambda results can be invoked directly.
  The callee is evaluated once before its arguments, generic and rank-2 values reuse the existing
  evidence and reified-slot lowering, and effects from the callee expression and invocation are
  both tracked. Named, member, constructor, and generic calls keep their existing paths, and no
  runtime or API-index ABI changed.
- Implemented ZEP-0150 safe invocation of nullable function values. `couldBeNull?.()` evaluates the
  callee once, skips argument evaluation and produces `null` when it is null, and otherwise invokes
  the function value with the existing shape-interface lowering. The result is nullable, generic and
  rank-2 values reuse their normal instantiation, receiver functions remain receiver-call syntax,
  and no runtime or API-index ABI changed.
- Implemented ZEP-0131 chained flow refinement and implicit receiver access. `is` and `matches`
  facts transfer through composed `and`, `or`, and `not` conditions; `matches` payload bindings are
  available in later operands and the successful branch, with OR alternatives checked for
  compatible binding sets. Refinement now covers stable read-only member paths rooted at locals,
  parameters, or `this`, including final fields, stored read-only properties, and sum payloads,
  while mutable members, array slots, call results, and custom getters stay unrefined. Unqualified
  field and property reads resolve to the implicit `this` receiver as a fallback after lexical and
  top-level lookup, including in contract defaults; implicit writes remain rejected. The language
  server now completes and hovers implicit receiver members and chained `matches` payload bindings,
  including bindings before their first use.
- Receiver lambdas now accept unqualified writes to the receiver's public `mut` properties when
the receiver is mutable. `value = 42` resolves as `this.value = 42`; read-only receivers and
non-`mut` properties continue to use the normal access diagnostics.
- Closed four implementation gaps around existing features. `do` blocks accept a final
  monad-valued expression in place of `yield`, avoiding nested monadic results. Entry-point
  selection uses the resolved return type, so `fn main() = println("...")` infers `Unit` and its
  host-boundary effects without an explicit annotation. Handler results unify with the protected
  result through their common assignable parent, allowing a non-null arm to handle a nullable
  computation. Synthetic witness helpers for newtype targets now resolve representation context,
  emit carrier descriptors, and omit nonexistent newtype names from generic signatures.
- Implemented ZEP-0129 unary products versus arbitrary-valued newtypes. Products remain at two or
  more components, while single-value shapes use ZEP-0070 direct-representation newtypes. Carrier
  resolution now recurses through function types and generic applications, extension declarations
  resolve `this` and newtype-typed parameters to their carriers, and generic function-carrier
  returns adapt between erased function shapes. Function-shaped newtypes and their generic
  extensions also work across separate compilation.
- Implemented ZEP-0128 sum-type parameter bounds and case-local type parameters. Sum parameters
  accept contract and marker bounds, cases may declare marker-bounded existential parameters such
  as `Bind<B>`, construction infers or accepts complete explicit case-local arguments, and matching
  opens them through branch-local `let` binders. Unqualified case construction and patterns resolve
  through the expected sum or a unique visible case name while ordinary values continue to shadow
  case names. Public library metadata now records case-local parameters and sum bounds, advancing
  the API index schema to `16`; the standard-library API version is unchanged.
- Extended the ZEP-0128 bound rules to product type parameters, with checking performed on the
  generated product class. `deriving` now resolves qualified contract names and import aliases
  through the same import table as other type references. Generic-arity and unary-product
  diagnostics now describe the actual problem, and the grammar and guides document effect headers,
  deriving names, product bounds, and the nullary-sum alias trap.
- Implemented ZEP-0098 annotation targets for native products and sums. `PRODUCT`,
  `PRODUCT_COMPONENT`, `SUM`, `SUM_CASE`, and `NEWTYPE` applications parse and validate through the
  existing annotation rules, remain metadata-only, and survive the library index for public
  declarations. Annotation consumption remains separate.
- Implemented ZEP-0113 declaration-site covariance for sums and products. Generic `type`
  declarations accept checked `out` parameters over read-only payloads and components, reject
  unsafe references, arrays, mutable positions, effects, and invariant derived contracts, and
  reject use-site projections over ADT applications. `Option`, `Result`, and `Pair` are now
  covariant; generated class names, JVM descriptors, equality, and hashing are unchanged.
- Implemented ZEP-0070 generic and nominal newtype representations. Newtypes may bind type
  parameters and use any statically known representation, including classes, products, sums,
  function types, and Java external classes. Newtypes now lower directly to their carrier: the
  wrapper class, backing field, and `.value` accessor are gone, and `this` plus newtype-typed
  parameters are the representation inside newtype-owned extensions, witnesses, and namespace
  bodies. Runtime type tests and casts targeting a newtype are rejected. Newtype library metadata
  records type parameters and representation types, advancing the API index schema to `15` and the
  standard-library API to `17`.
- Added ZEP-0127 data-class positional patterns. A data class can be destructured at the match root
  and in nested positions using its canonical components in declaration order; auxiliary and computed
  properties are excluded. Generic patterns accept a complete applied type or infer it from the
  scrutinee. Canonical components are readable by any declaration that can name the data class even
  when the class itself is not `public`, and refutable applied cases with the same identity no
  longer make later cases unreachable.
- Added `ZR3018`, a warning for public functions and public methods on public classes that return
  `T?`, with guidance to return `Option<T>` or `Result<T, _>` instead; package-private classes and
  private methods remain quiet. Class-body method and implementation-block analysis now participates in
  nullability, mutable-projection, and unused-function warnings, so a free function referenced from
  a class body counts as used and mutable-reference arguments forwarded to other functions count as
  mutable uses. Parameters of contract-implemented methods no longer receive the unnecessary
  mutable-projection warning, and iterability now follows transitive contract requirements.
- Named constructors may satisfy a static contract requirement when their parameters match and
  their `&T` return is compatible with a readonly `T` or mutable `&T` requirement. Named-constructor
  evidence is selected before contract-interface projection, so a conforming class supplies its own
  static implementation instead of an unusable interface handle.
- Added ZEP-0126 compiler-owned `Main<A>` entry values. A top-level `main` may return `Unit`, `Int`,
  `Result<Unit, E>`, or another type with one applicable `Main<A>` witness; the generated launcher
  evaluates `main` and then calls `Main<A>.run`. `Main<Int>` treats `0` as normal completion and
  exits otherwise, while `Result` failures print through the new `System.err` binding and exit with
  status `1`. Missing or ambiguous entry evidence is diagnosed at `main`.
- `zeron.io.print`, `println`, `readln`, and `prompt` are now ordinary effectful functions that
  raise `IoFailure`; the `IO` action monad and its `Main<IO<Unit>>` witness were removed. The
  standard `zeron.control` contracts no longer admit mutable-reference operation arguments; the
  `Option`, `Result`, `Stream`, `List`, and array instances use unbounded operation parameters, while
  those types keep their own admissibility rules. Advanced the
  standard-library API version to `15`.
- Added ZEP-0124 and ZEP-0125 nominal static contract implementation and optional higher-kinded
  implementation blocks. A class's `is` conformance now satisfies static requirements through public
  class `static fn` declarations or `static fn` entries in a first-order `implementation` block;
  explicit entries win, defaults fill the remainder, and a witness cannot replace a nominally
  conforming class. HKT conformances no longer require an implementation block: omitting it matches
  public instance methods for receiver operations and public class static functions for receiverless
  operations, while explicit blocks remain authoritative. Both paths share one deterministic
  signature matcher and need no API-index schema change.
- Added ZEP-0135 witness provision and owner-directed dispatch. A witness now provides only the
  contract it declares; each prerequisite in a `requires` chain resolves to its own canonical
  provider, preferring `default` evidence and otherwise a unique named provider. Operations dispatch
  through the contract that declares them, so `map` comes from `Functor`, `pure` and `ap` from
  `Applicative`, and `flatMap` from `Monad`, even when several sibling witnesses share those
  ancestors. Child witnesses may no longer redeclare inherited operations; the compiler rejects
  them with a strict diagnostic and no migration window. Split the `Option`, `Result`, `Stream`, and
  numeric standard-library witness chains and class implementation blocks one contract at a time,
  and advanced the standard-library API version to `16`.
- Fixed code generation for function-shaped newtypes. A newtype's `value` member is its
  representation, so a call such as `formatter.value(record)` now invokes the carrier directly
  instead of emitting a call to a synthetic newtype class that is never generated. Non-generic
  function newtypes such as `zeron.log.LogFormatter` compile and run without a missing-class error.

### Tooling and project configuration

- Added `textDocument/signatureHelp` to the language server, including parameter labels and the
  active argument, and advertise `(` and `,` as trigger characters. Hovering the closing parenthesis
  of a function-valued expression invocation now shows its function type, and both hover and
  signature help cover safe invocation.
- Fixed TextMate highlighting so a character literal containing a double quote (`'"'`) no longer
  starts a double-quoted string, and escaped quotes inside strings no longer end them early.
- Added ZEP-0117 declarative project targets and generated CLI/manifest references. `zeron.toml`
  accepts `[build].deny-warnings`, `[targets.<name>]` entries with optional `entry`, `output`, and
  `deny-warnings`, and `[project].default-target`. `zeron build [<target>]` and `zeron run
  [<target>]` select a target, resolve manifest policy and command-line overrides, and launch from
  the target's output directory while retaining one project-wide source and dependency universe.
  Command, option, and manifest-schema metadata now drive `zeron help` and the generated
  `guide/39-command-reference.md` and `guide/40-manifest-reference.md` chapters; a test fails when
  the checked-in pages are stale. No language, runtime, standard-library, or API-index compatibility
  changes are expected.

### Standard library

- Added `String.splitWhitespace()`, which splits on runs of Unicode whitespace, ignores leading and
  trailing whitespace, and returns `Stream<String>`.
- Added default `Sortable` and `InPlaceSortable` witnesses. `ArrayList` and `LinkedList` support
  both a sorted copy (`sorted`, `sortedBy(compare)`) and in-place sorting (`sort`,
  `sortBy(compare)`), while the persistent `ConsList` supports the sorted copy only. The shared
  implementation is an unstable heapsort with `O(1)` extra space over an `ArrayList` buffer.
- Added `Stream.sortedBy(compare)`, a stateful intermediate operation that buffers each traversal,
  sorts it, and yields the elements in order.
- Added `Path.list()` and `Path.walk()`. `list` returns a directory's immediate children and
  `walk` returns the starting path plus every descendant; both return `Array<Path>` and raise
  `IoFailure` when the filesystem cannot be read.
- Implemented ZEP-0130 standard-library-owned intrinsic declarations and its `String` inventory.
  Intrinsic types keep compiler-owned identity, while `zeron.lang.string` now owns the public
  operations: `split` (literal text, including a one-character separator), `splitRegex` returning
  `Result<Stream<String>, RegexError>`, `lines`, `charAt`/`at`, locale-independent `toLowerCase`
  and `toUpperCase`, `trim`/`trimStart`/`trimEnd`, `isBlank`, literal `replace`, and `toInt`/
  `toFloat`. Multi-value results are `Stream<String>` so they compose with the existing stream
  operations. `StringJoiner` is the reusable joining collector and `String.join` delegates to it.
  `zeron.collections.Collector<T, R>` now names the value-level collector capability, with `add` and
  `finish`; `Stream.collect` builds a collector from an initial accumulator, a step, and a
  conversion, `Stream.collectWith` drives an already-built collector, and `Collector.of` supplies
  the canonical `CollectorImpl` for ad-hoc strategies. The `Collector` namespace also provides
  concrete collectors: `toArrayList`, `toLinkedList`, `toConsList`, `toHashMap`, `toHashSet`, and
  the `Numeric<T>`-generic `summing`. The higher-kinded collection-construction
  capability keeps its members under the clearer name `CollectionBuilder<C<_>>`. The platform
  primitives live in `zeron.runtime.StringUnicode`, no intrinsic ID was added, and the
  standard-library API version advanced to `18`.
- Added the `zeron.log` logging API. `Level` supplies severity ordering and display text,
  `Record` carries a monotonic time, level, logger name, message, structured `Value` fields, and an
  optional cause, and `LogSink` exposes filtering plus a flush hook. `LogSink.console` and the
  `LogSink.minLevel` extension return mutable sinks so filters can be layered, while `Logger` adds
  named children, copied context maps, `isEnabled`, and `flush`. `LogFormatter.plain` renders the
  level, logger, message, and structured fields with escaping for nested lists and text. Sinks,
  formatters, and loggers are supplied explicitly, so tests can record or replace output.
- Added `zeron.time.Clock` for injectable monotonic time. `Clock.system()` samples the same
  monotonic source as `Instant.now()`, and `Clock.fixed(instant)` always reports the supplied
  instant for deterministic tests; `Logger.withClock` accepts either. Wall-clock timestamps remain
  outside `zeron.time` per ZEP-0011.

## [0.7.0] - 2026, 13 NOV

### Compiler and language work

- Added ZEP-0109 rank-2 polymorphism. Function types and lambdas accept a leading binder list
  (`<T: Bound, U> (T, U) -> R`), generic lambdas check bidirectionally against expected polymorphic
  types and infer their own binders from annotated parameters, and polymorphic function types
  compare up to alpha-renaming. Bare generic named function references eta-expand to a fully
  constraining polymorphic type, and each application instantiates the value's binders. Unbounded,
  non-reified values use the existing erased function shape; bounded and `reified` values use a
  distinct `FunctionShapeKey` with hidden evidence and descriptor slots supplied at each
  application. Effect-kinded binders and binder-dependent effect rows remain follow-up work. The
  2026-11-13 benchmark records 3.63–3.81 ns/field for an `Int`-returning bounded callback and
  4.17–5.09 ns/field for the model-shaped `Unit` callback, both at 15.5 B/field, against 0.52–0.72
  ns/field direct; polymorphic callback signatures, including bounded and `reified` binders,
  survive the library API index and separate compilation.
- Added the first ZEP-0106 stored-existential slice. Fields, locals, array elements, collection
  elements, and nested type arguments accept `<: Bound>` and `reified` anonymous existentials,
  using the conditional `Exists1`–`Exists3`/`ExistsN` holder ABI when a descriptor or evidence
  must survive storage and keeping marker-only storage erased. Packing resolves descriptors and
  evidence, stored reads restore the hidden tokens, holders compare by payload identity, and
  API-index schema 14 records the stored binder and its slot metadata. `ArrayList` construction,
  mutation, reads, iteration, nested collections, and separately compiled collection fields retain
  per-element holders. An array-backed model-field prototype stores one evidence group per field;
  its 2026-11-13 cost pass records 24 B/field and sub-nanosecond traversal overhead, so the model
  API stays unchanged. The ZEP-0109 callback comparison is also recorded at 4.17–5.09 ns/field for
  the model-shaped callback, still slower than holder traversal, so no `Model` change is warranted.
- Added generic effect payloads with closed applied identity from ZEP-0005. `effect Failure<T>` may
  be declared and used as `Failure<Int>` in `raise`, `raises`, and handler cases; applications are
  invariant, fully written, and closed. Generic payloads carry one cached ZEP-0015 descriptor
  through canonical and named constructors, and handlers compare the erased payload class plus the
  exact descriptor before binding. `case Effect` still catches any application, while non-generic
  effects remain descriptor-free. Separately compiled libraries preserve applied identities through
  the existing schema-13 index. Generic propagation through type variables, effect-variable
  handlers, omitted handler arguments, wildcards, applied ancestry, and generic `external` effects
  remain deferred.
- Added runtime applied-type match patterns from ZEP-0122. A `match` over a descriptor-backed
  value now distinguishes closed class and product applications such as `Box<Int>` and
  `Box<String>` instead of stopping at the erased class test. Roots may be closed applications,
  reified type parameters, or reified anonymous existentials; non-reified parameters, `Any`, star
  views, projections, and wildcard targets are rejected. A statically identical target remains
  total without a descriptor test, a different closed target is rejected as statically impossible,
  and refutable applied roots require a wildcard or statically total fallback. Matching compares
  the shared ZEP-0015 descriptor before casting, binding, or destructuring, supports aliases and
  class/product component patterns, and keys coverage by the full applied identity. Sum-case and
  sealed-contract patterns keep their existing static semantics, and no descriptor field is added
  to ordinary classes.
- A `Codec<T, E>` bound is now satisfied by available `Encode<T, {E}>` and `Decode<T, {E}>`
  evidence when no dedicated `Codec` witness exists. This makes the built-in directional codecs
  for `Int`, `Boolean`, `String`, and `Float` usable through `Codec` directly, while the declared
  `Codec` contract still requires both capabilities.
- Added branch-scoped existential opening from ZEP-0105. A direct `if` or `while` condition may
  use `C<let T>`, and every argument of the applied class or contract target must be a binder.
  The true branch sees a fresh rigid `T` in local type annotations and generic calls; the tested
  binding is refined to the opened application, and a source type already known to be that
  application makes the pattern total so its false path is unreachable. Binders inherit bounds,
  contract evidence, reified descriptors, or a known concrete argument from the source type,
  while star views and raw `Any` tests invent no evidence.   Returning or assigning a type that
  mentions an opened binder outside the branch is rejected. Composed and `in` openings are
  diagnosed, mixed and nested forms are implemented by ZEP-0116, and the form adds no API-index or
  runtime ABI. Remaining opening, escape, result-position, and variance work is tracked in
  ZEP-0116.
- Extended branch-scoped existential opening with ZEP-0116. Opening patterns now accept mixed
  exact/open arguments such as `Pair<Int, let V>`, nested applications such as `Box<Box<let T>>`,
  and the `else if` and value-producing `if` placements. Exact arguments must be statically
  justified by the source application, binders are restricted to invariant positions, and a raw
  `Any` test cannot invent bounds or evidence. Returns, outer assignments, and raised-effect types
  that mention an escaping binder are rejected. Mixed and nested patterns reuse the descriptors
  already required by the pattern and add no runtime or API-index ABI; composed `and`/`or`
  openings, binder-declared bounds, `in` projections, result positions, escape approximation, and
  variance changes remain unsupported.
- Added named witnesses, default witnesses, and explicit witness imports from ZEP-0114. A witness
  may be declared as `witness Name<T>: Contract for Target`, with normal `public` visibility and a
  separate package-scoped name namespace; `delegating` combines with either named or anonymous
  form. `import witness pkg.Name;`, aliases, and `import witness pkg.*;` activate named public
  witnesses without importing types, functions, values, or extensions, while a plain `import pkg.*;`
  keeps activating every exported witness. A `default witness Contract for Target` is unnamed: its
  identity is the contract/target pair and the compiler derives its index key. It must be declared
  in the target type's file, or in the canonical package for an intrinsic target, takes its
  visibility from the target, and is activated by the target type's own scope (the current package,
  an explicit type import, or a package star import) rather than by a witness import or a fully
  qualified type name. Intrinsic defaults are always active. The
  same-package orphan rule is removed for every witness form; overlap and ambiguity checks are
  unchanged. Public named witnesses are exported under their source names; unnamed defaults use a
  generated reserved index key and the default marker, and package-local witnesses and defaults of
  non-public targets are omitted from the API index (schema 13). Anonymous witnesses remain source-compatible for the migration
  release and now emit the ZEP-0115 deprecation warning. Class-scoped `implementation` blocks are
  canonical evidence for their owner type: importing the type, explicitly or through a package
  star import, activates the block's HKT witness without requiring the whole package.
- Completed the ZEP-0114 migration: standard-library canonical defaults are unnamed `default`
  declarations beside their target types or in the canonical package for an intrinsic target.
  `Int`/`Long` now get default `Integral` evidence, and `Float` gets default `Floating` evidence.
  Array `Foldable` evidence remains the named public `ArrayFoldable` witness, imported explicitly,
  so intrinsic evidence is not universally active. A default witness for a public target must use
  a public contract, and the standard-library API version is now 13. Member
  discovery reports default and class-scoped implementation evidence as evidence attached to a type
  rather than as callable members.
- Added warning-severity compiler diagnostics from ZEP-0115. Warnings share the existing catalog,
  formatter, and source spans but do not block a build; `--deny-warnings` turns them into a failed
  invocation after the stage completes, and `--no-warnings` hides warning output without changing
  the exit status. The initial set reports unused local variables, imports,
  and private functions, unreachable statements, unnecessary mutable projections, `while (true)`
  loops, shadowed locals, and deprecated anonymous witness declarations. Compiler results expose
  warnings separately from errors, and the language server publishes them with warning severity.
- Receiver-based calls to HKT contract methods that declare an explicit `this` parameter now
  subtract the receiver from the call's arity and argument positions. `value.map(...)` and
  `value.flatMap(...)` resolve on a constructor parameter bounded by `Monad<F>` instead of
  reporting an argument-count mismatch, and their evidence handles no longer repeat the receiver.
- Added `do` expressions from ZEP-0111. `do { let value <- source; yield expression; }` binds
  through the `Monad` witness of the first bind's constructor, lowers to ordinary `flatMap` and
  `map` call sites marked for witness dispatch, and works for `Option`, `Result<_, E>`, `Stream`,
  and library-defined constructors. Dispatch ignores instance methods of the same name, so
  `Stream`'s instance `flatMap`, which takes `(T) -> Iterable<R>`, does not collide with
  `Monad.flatMap` inside a block. A plain `let name = expression` clause after the first bind
  computes a local, a final `yield` is required, and an explicit `do<Option<_>>` or
  `do<Result<_, E>>` asserts the result constructor. The emitted code is the hand-written chain,
  so there is no runtime, index, or ABI change.
- Generic type parameters can opt into runtime applied-type identity with `T: reified`. Reified
  parameters receive an immutable `AppliedTypeDescriptor` as a hidden argument after type-class
  evidence; monomorphic call sites use interned constants and generic callers compose descriptors
  from their own reified parameters. The marker is preserved in the API index for exported
  functions, methods, extension methods, and contract methods, while private and
  local declarations infer it transitively. Reified generic function values specialize and
  capture the descriptor. Class and contract type parameters cannot be reified, and generic-effect
  work remains deferred to ZEP-0005. The API-index schema version is now 13; the standard-library
  API version remains 12.
- Added star views and anonymous existentials from ZEP-0104. `F<*>` is the read-only projection
  `F<out Any?>`, while `F<: bounds + markers>` introduces an unknown argument with upper bounds
  and optional `nullable`, `mut`, and `reified` markers. Anonymous existentials are accepted only
  as direct arguments of declared callable parameters, are packed implicitly, and forward bound
  evidence; they lower to hidden type parameters and are inferred rather than supplied as explicit
  call-site type arguments. Reified values and type parameters now support exact applied-type
  tests through ZEP-0015 descriptor equality, including arrays and nullability distinctions.
  Stored and result-position existentials, scoped opening, rank-2 function-type existentials, and
  contract-bound runtime tests remain separate proposals.
- `Type::name` references now cover instance and contract methods, constructors (`Type::new` and
  named constructors), and class-scoped static functions in addition to properties. A reference
  carries a stable `CallableId` (`owner#kind#name`), exposes `name`, `identity`, `id`, and a
  `callable` function value, compares by declaration identity, and converts implicitly to its
  function type. Generic callable references specialize through `::<...>` or a fully constraining
  expected function type. Bound references, extensions, witness methods, and static contract
  requirements are rejected.
- Ordinary classes can declare `static fn` members. A class-scoped static function is lowered to
  a function in the namespace sharing the class's simple name, so it is called through the class,
  follows namespace visibility and API-index rules, and remains available to separately compiled
  clients. Static functions cannot use `this` or instance state, cannot capture class type
  parameters, may declare their own generics, and may not share a name with an instance member or
  an applicable extension.
- Effects now support explicit effect-only subtyping with `under`, inherited payload
  redeclaration with canonical-constructor delegation, and the denotable top `Effect` from
  `zeron.lang`. Subeffects satisfy broader `raises` requirements and handler cases select the
  most-specific matching effect independent of source order; incomparable visible cases are a
  compile-time error, and a subtype added by a later library aborts dispatch with a runtime
  ambiguity error. Stateless effects are emitted as marker interfaces with generated
  implementations, payload-bearing effects as non-final classes whose single payload parent is the
  JVM superclass, and the library index records direct `under` edges. The API-index and
  standard-library API versions are now 12.
- Effect payload declarations now use the ordinary class member model: they may declare methods,
  implement contracts, and keep non-public payload properties. Effects remain non-generic, and
  contract conformance neither makes an ordinary class raisable nor turns a contract into a handler
  case.
- Generic call inference now sees through an argument's mutable-reference capability when the
  parameter is a read-only class type such as `Box<T>`, so `extract(Box.new(1))` type-checks. This
  restores the documented mutable-to-read-only projection; capability is still not a subtyping
  axis, so a read-only value cannot satisfy `&Box<T>`.

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

- Hovering a built-in type such as `Int`, `String`, or `Array<T>` now describes the intrinsic type
  and lists the evidence active in the current file. That includes compiler-provided `Display`,
  `Eq`, and `Hashable`, active default witnesses such as `Integral<Int>`, and explicitly imported
  named evidence such as `ArrayFoldable`.
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
- Compilation and project analysis with a compiled standard library or other compiled libraries
  no longer rescans every exported declaration for each library function when binding JVM
  owners. The lookup is indexed once per pass, so language-server diagnostics update in
  milliseconds instead of taking seconds on every edit, and builds no longer pay the same
  repeated cost.

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
