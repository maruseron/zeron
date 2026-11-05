# Class-file generation quality inventory

| Field | Value |
| ----- | ----- |
| Status | Audit / inventory |
| Scope | JVM bytecode, generated class count and size, allocation, dispatch, and initialization emitted by the Zeron compiler |
| Date | 2026-11-04 |
| Baseline | One 55-line source file compiled twice: with the bundled **source** standard library and with a prebuilt **compiled** one |

This document inventories concrete places where the compiler currently produces larger, slower,
or more allocation-heavy `.class` files than necessary. It is an audit, not an accepted design.
Items marked **needs ZEP** change a public representation or the standard-library protocol and
should go through `documents/zeps/ZEP-0000.md` before implementation.

Line references are to the current `src/main/java/com/maruseron/zeron/compile` sources. The
measured numbers come from the sample and commands in the appendix; they are representative
orders of magnitude, not a benchmark suite.

## Baseline measurements

The sample imports only `zeron.collections.ArrayList`, `zeron.lang.Eq`, and `zeron.lang.Hashable`.
It declares one newtype, one product, one data class, one top-level value, and a `compute` function
that uses an array literal, a `for` statement, an eager `for … collect`, a lazy `for … yield`, and
several lambda callbacks.

| Measurement | Result |
| --- | --- |
| User source classes | 4 (`main`, `UserId`, `Position`, `Point`) |
| Total emitted class files | **320** |
| Total emitted bytes | **381,625** (~373 KiB) |
| User classes | 17,163 B (4.5%) |
| Standard-library classes | **273 classes, ~322 KB** (84%) |
| Runtime/support and function-shape classes | 43 classes, ~42.6 KB (11%) |
| `main.class` | 15,477 B, 60 methods |
| `main.class` lambdas / adapters / function refs | 35 / 11 / 3 |
| Emitted function-shape interfaces | 24 |
| `HttpSupport` classes (pre-change baseline) | 7 classes, 22,364 B |
| `Unit.class` | 305 B singleton class |

Two observations dominate the baseline:

1. **Source-standard-library mode compiles the whole standard library into every program.**
   `StandardLibrary.withBundledUnits` adds every bundled unit to the compilation
   (`CompilationService.java:105`), and the emitter writes a class for every unit that has storage.
   A program that imports three declarations still gets `zeron.net`, `zeron.serialization`,
   `zeron.compiler`, `zeron.time`, and so on.
2. **Runtime support classes used to be emitted unconditionally**, including the entire HTTP
   stack. Emission is now demand-driven (`RuntimeSupportEmitter.emitReferencedRuntimeSupport`), so
   the baseline below records the pre-change behavior; see F for the implemented gating.

### Compiled-standard-library baseline

The same sample compiled against a prebuilt standard-library JAR (`zeron --build-stdlib … --jar-output
…`; project `stdlib = "compiled"` plus a `zeron-libraries` entry) changes the picture:

| Measurement | Source stdlib | Compiled stdlib | Change |
| --- | --- | --- | --- |
| Total emitted class files | 320 | **29** | −91% |
| Total emitted bytes in `dist/` | 381,625 | **55,138** | −86% |
| Standard-library classes in `dist/` | 273 / ~322 KB | **0** | −100% |
| `main.class` | 15,477 B, 60 methods | **7,019 B, 22 methods** | −55% |
| `main.class` lambdas / adapters | 35 / 11 | **8 / 3** | −77% / −73% |
| Emitted function-shape interfaces | 24 | **5** | −79% |
| Runtime/support classes in `dist/` | 43 / ~42.6 KB | **24 / 46,128 B** | roughly unchanged |
| Prebuilt stdlib artifact | — | 260,859 B JAR / 314 classes | — |

Two cautions about interpreting this:

- **The win is sharing, not per-program compression.** `dist/` shrinks to 55 KB, but a
  self-contained deployment still needs the 261 KB standard-library JAR, so one application ships
  roughly 316 KB versus 381 KB (−17%). The large per-program win only appears when many programs
  share one installed standard-library artifact, which is exactly the “precompiled binaries at the
  Zeron path” model.
- **The remaining `dist/` was mostly runtime support, not user code.** In the measured baseline,
  46,128 B of the 55,138 B was unconditional support (`HttpSupport` alone is 7 classes / 22,364 B).
  Support emission is now demand-driven (F), so a minimal program keeps only the handful of support
  classes it actually references; the baseline table has not been re-measured.

### Shipping status and the implemented default

The release pipeline already performs the packaging that a custom linker would otherwise replace.
`scripts/release.ps1` builds `lib/zeron-stdlib-index-v<schema>-api-v<api>.jar` with
`--build-stdlib --jar-output`, stages the stdlib sources under `share/zeron/stdlib`, and smoke-tests
the default project plus an explicit `stdlib = "source"` project. The launcher exports
`-Dzeron.home` (`distribution/bin/zeron` and `zeron.cmd`), and the compiler uses it both for
standard-library *source* lookup and for compiled-artifact discovery.

The binary-size work was therefore **selecting the existing artifact by default**, not building a
linker. That default is now implemented:

- An omitted `[platform].stdlib` selects `StandardLibraryMode.DEFAULT`, which prefers the compiled
  standard library and falls back to bundled sources when no artifact is available.
- `Zeron.resolveStandardLibrary` auto-discovers `${zeron.home}/lib/zeron-stdlib-*.jar`. It rejects
  multiple matching JARs, and explicit `stdlib = "compiled"` reports a clear error when no artifact
  is installed.
- A library explicitly listed in `zeron-libraries` that exports the standard-library types takes
  precedence over the bundled JAR.
- `zeron init` writes `stdlib = "compiled"` in an installed distribution and `stdlib = "source"`
  in a source checkout, where no compiled artifact exists.
- Direct-file compilation through `--stdlib` uses the same resolution; omitting the flag now means
  the compiled default rather than bundled sources.
- The language server resolves the same standard-library setup when it opens a project.
- `guide/24`, `guide/25`, CLI help, and the release smoke test document and exercise the default
  path with no `zeron-libraries` entry. The release smoke test also asserts that no standard-library
  class lands in the default build output.

Version safety is already handled: the JAR name encodes both versions, and
`ZeronLibraryIndex.read` rejects schema and standard-library API mismatches when loading
(`ZeronLibraryIndex.java:1474-1501`). Making compiled mode the default removes unused stdlib
classes from `dist/`; it does **not** remove the per-program runtime support (F) or the boxing and
evidence overhead (L).

## A. `Unit` is a heap object at every boundary

**Severity:** medium (pervasive, small per site). **Needs ZEP.**

`Unit` is emitted as a final class with a singleton allocated in `<clinit>`
(`RuntimeSupportEmitter.java:45-68`). `TypeDescriptor.toJavaClassDesc` maps both `Unit` and
`Unit?` to `zeron.lang.Unit` (`TypeDescriptor.java:189,209,236`). Consequently:

- Every `Unit`-returning method returns a reference and executes `getstatic Unit.INSTANCE; areturn`.
- Every call site in statement position executes `pop`.
- `Unit` values stored in arrays, fields, or generic positions are references to the singleton.
- Function shapes that return `Unit` carry a reference return type instead of `void`.

Measured in `main.ignore`:

```text
public static zeron.lang.Unit ignore(int);
   2: getstatic  zeron/lang/Unit.INSTANCE
   5: areturn
caller:
   135: invokestatic ignore:(I)Lzeron/lang/Unit;
   138: pop
```

The allocation is one-time, but the `getstatic`/`areturn`/`pop` sequence, the reference return
type, and the extra shape distinctions are paid everywhere. It also prevents direct interop with
Java `void` methods without adapters.

**Direction:** lower `Unit` to JVM `void` for returns and expression statements, and materialize a
singleton only where the type survives erasure (generic arguments, `Array<Unit>`, nullable
`Unit?`, reflective positions). Method descriptors and function-shape identities must distinguish
"returns nothing" from "returns `Object`"; adapter generation needs a Unit/void bridge at
boundaries. Track as a representation ZEP with a compatibility plan for compiled libraries.

**Alternative short step:** keep the singleton but emit `void`-returning methods for
`Unit`-returning declarations and suppress `pop` at call sites. This removes most instructions
without changing generic representation.

## B. Newtypes are wrapper objects

**Severity:** medium-high in newtype-heavy code. **Needs ZEP.**

`DeclarationRegistry.registerNewtype` synthesizes a class declaration with a `value` property, and
`NominalTypeEmitter.generateNominalClass` emits a normal final class
(`NominalTypeEmitter.java:370-461`). A newtype therefore costs:

- one heap allocation per `.new`,
- one field store and a constructor call,
- one getter invocation per `.value` access,
- a distinct class file and metadata entry,
- boxing when the newtype crosses a generic boundary.

Measured `UserId = Int`:

```text
new UserId; dup; iload; invokespecial UserId."<init>":(I)V
...
invokevirtual UserId.$zeron$get$value:()I
```

`UserId.class` is 264 B, plus the call sites. For a type whose entire purpose is a nominal
distinction, this is all overhead except the class identity.

**Direction:** erase newtypes to their underlying JVM type and enforce nominal identity only in
the type checker. `.new` and `.value` become no-ops. Keep a wrapper only when the value is used in
a position that needs an object (generic type argument, `Array<T>`, nullable reference, contract
evidence, Java interop). This requires care with overload/erasure collisions and with the API
index; it is a representation ZEP.

**Short step:** inline `.value`/`.new` at the call site when the newtype does not escape to a
generic/object boundary, and emit `final` fields/getters only for escaping cases.

## C. `Array<T>` erases to `Object[]`, so primitives are boxed end-to-end

**Severity:** high for numeric/data code. **Needs ZEP for a representation change.**

`TypeDescriptor.toJavaClassDesc` maps every `ArrayDescriptor` to `Object[]`
(`TypeDescriptor.java:205,258`). As a result:

- Array literals allocate `Object[]` and call `Integer.valueOf`/`Long.valueOf`/etc. per element
  (`BytecodeEmitter.java:385-401`).
- Reads emit `aaload; checkcast Integer; invokevirtual intValue` per element
  (`BytecodeEmitter.java:3285-3316`).
- Writes box the value and `aastore` (`BytecodeEmitter.java:430-448`).
- `for` loops over arrays unbox each element even when the loop body only does primitive math
  (`StatementEmitter.java:411`).
- A `Byte` value is read as `byteValue()` then masked with `iand 255` on every element.

Measured array literal and loop:

```text
ldc 5; anewarray Object
dup; ldc 0; ldc 1; invokestatic Integer.valueOf; aastore   // repeated 5x
...
aaload; checkcast Integer; invokevirtual Integer.intValue
```

Small integer literals hit the `Integer` cache, so the literal itself does not allocate, but the
call, the cast, and the boxing path remain, and arbitrary runtime values do allocate.

**Direction:** specialize arrays whose element type has a JVM primitive representation:
`Array<Int>` → `int[]`, `Array<Long>` → `long[]`, `Array<Float>` → `double[]`,
`Array<Boolean>` → `boolean[]`, `Array<Byte>` → `byte[]`. Emit `iaload`/`iastore` and friends,
and keep a boxing bridge only for generic positions. This removes per-element boxing, casts, and
wrapper calls, and makes `Object[]` a fallback instead of the universal representation. A full
reified-array design (distinct descriptors per element type) is a larger ZEP; primitive
specialization can be staged first.

**Short step:** add a primitive-array lowering for the intrinsic operations that already know the
element type (`ARRAY_LITERAL`, `INDEX`, `INDEX_ASSIGNMENT`) while keeping the public type as
`Array<T>`.

## D. Eager for-expressions are not fused; `Step` allocates per element

**Severity:** high for collection code. **Needs ZEP for the iterator protocol.**

Source-level `for … collect` and `for … yield` comprehensions are lowered during resolution:
`ExpressionFlowResolver.resolvePipeline` calls `ComprehensionLowerer.lower`
(`ExpressionFlowResolver.java:1692`), and `ComprehensionLowerer` rewrites guards to
`filter`, `let` and nested generators to `flatMap`, the result to `map`, and `collect` to
`fold` (`ComprehensionLowerer.java:16-85`). The emitter therefore sees a chain of `Stream` calls,
not a comprehension.

The emitter does contain an inlined loop path (`ExpressionFlowEmitter.emitCollectedPipeline`,
`ExpressionFlowEmitter.java:232-368`), but source comprehensions are already lowered before
emission, so that path is bypassed for normal programs.

Measured lowering of `for number in values if number % 2 == 0 collect number * 10 into ArrayList.empty()`:

```text
invokestatic Stream.fromArray(Object[])
invokedynamic #0  () -> IntToBoolean            // guard lambda
invokedynamic #1  (IntToBoolean) -> ObjectToBoolean   // adapter
invokevirtual Stream.filter(ObjectToBoolean)
invokedynamic #2  () -> IntToInt                // projection lambda
invokedynamic #3  (IntToInt) -> ObjectToObject  // adapter
invokevirtual Stream.map(ObjectToObject)
invokestatic ArrayList.empty()
invokedynamic #4  () -> RefArrayListIntToRefArrayList
invokedynamic #5  (...) -> ObjectObjectToObject
invokevirtual Stream.fold(Object, ObjectObjectToObject)
checkcast ArrayList
```

This is the opposite of stream fusion: it allocates the `Stream` and each stage object, allocates
an adapter closure for every callback crossing the erased `Stream` boundary, boxes every element
twice (into the lambda argument and back out), and then traverses through the `Step` protocol.

The `Step` protocol itself allocates one `Step.Yield` per element
(`stdlib/zeron/collections/iterator.zn:7-9`, `stream.zn:15-19`). A `for` statement over an
`Iterable`/`Stream` compiles to:

```text
invokeinterface Iterator.next:()LStep;
astore step
aload step; instanceof Step$$sum$Done; ifne exit
aload step; checkcast Step$$sum$Yield
invokevirtual Step$$sum$Yield.$zeron$payload$0:()Object
checkcast Integer; invokevirtual Integer.intValue
```

`Step.Done` is a singleton, but `Step.Yield` is a fresh object for every element.

**Direction:**

1. Do not lower comprehensions to `Stream` in the resolver. Keep the pipeline IR and compile it
   to one fused loop, reusing the existing `emitCollectedPipeline` logic for both `yield` and
   `collect`. For `collect`, the loop should call `Sink.add` directly with the projected value,
   with no `Stream`, `map`, `filter`, or `fold` objects.
2. Replace the `Step` protocol with `hasNext(): Boolean` + `next(): T` (or
   `advance(): Boolean` + `current(): T`). This removes one object per element and one `instanceof`
   plus one `checkcast` per iteration. This changes the standard-library iterator contract and
   needs a ZEP plus a migration path for compiled libraries.
3. For lazy `yield` streams, either fuse adjacent stages into one iterator object or adopt a
   pull-based fusion scheme; at minimum, avoid one adapter and one stage object per operator.
4. Keep the array fast path for `for` statements and extend it to `Iterable` where the concrete
   source type is known (e.g., `ArrayList`), so no virtual `Iterator`/`Step` path is used.

## E. Lambdas and adapters: duplicated Object shapes, hoisting, and bridges

**Severity:** high for binary size and call overhead. **Partly needs ZEP for the shape ABI.**

Several effects compound:

1. **Every non-Object function shape gets an Object-shaped super-interface and a boxing bridge.**
   `RuntimeSupportEmitter.emitFunctionShape` creates `Object`-parameter/Object-return shape
   interfaces and emits a bridge that unboxes arguments, calls the typed `invoke`, and boxes the
   result (`RuntimeSupportEmitter.java:764-795`). The Object shape is registered as another
   generated interface (`LambdaCompilationPlan.java:245`). A program with ~12 distinct callback
   signatures emitted **24 shape interfaces**, and each typed shape carries a synthetic bridge
   method. This doubles shape count and adds boxing code to every non-Object shape.
2. **Every erased callback crossing needs a static adapter.** `LambdaCompilationPlan` registers
   one `$adapter$N` (and one `$nullableAdapter$N`) per source/target shape pair
   (`LambdaCompilationPlan.java:1111-1152`); `DeclarationEmitter` emits them into the entry holder
   (`DeclarationEmitter.java:209-233`). The baseline program emitted **11 adapters** and 35 lambda
   methods in `main.class`, even though its source has only a handful of lambdas.
3. **All lambda implementations are hoisted into the entry holder.** `DeclarationEmitter` emits
   every `lambdaPlan.lambdaImplementations()` method only when `entryHolder` is true
   (`DeclarationEmitter.java:180-207`), and `LambdaSupportEmitter` targets
   `context.mainClassName` (`LambdaSupportEmitter.java:41,211,284`). Standard-library lambda
   bodies therefore live in the application's `main.class`; the stdlib holder classes contain
   zero `$lambda$` methods. This makes `main.class` grow with the library and couples library
   bytecode to the application entry class.
4. **Hoisting forces synthetic access bridges.** Because lambdas live outside their declaring
   class, `NominalTypeEmitter.emitCrossPackageLambdaAccessBridges` generates public static
   `$zeron$lambda$read$…`, `$zeron$lambda$write$…`, and `$zeron$lambda$call$…` methods for
   private fields/methods used by lambdas in other packages
   (`NominalTypeEmitter.java:1486-1542`). These widen the class surface, add methods, and expose
   otherwise private state to Java reflection/callers.
5. **Shape names are very long.** `FunctionShapeNames.interfaceName` concatenates a readable
   signature and the full 64-character SHA-256 hex digest
   (`FunctionShapeNames.java:14-19`), e.g.
   `zeron.runtime.Lambda$V2$2$Ref$ArrayList$Int$To$Ref$ArrayList$<64 hex>`. Each reference repeats
   that UTF-8 string in constant pools and class-file names.

**Direction:**

- Emit lambda bodies in the unit/class where the lambda is written (javac-style), not in the entry
  holder. That removes the access bridges and stops `main.class` from absorbing stdlib lambdas.
- Make Object-shape interfaces and bridges demand-driven: only emit them when a value actually
  flows through an `Object`-typed callback position. Do not create an Object super-interface for
  every typed shape by default.
- Reuse an existing adapter when an equivalent erased conversion is already available; canonicalize
  `(sourceShape, targetShape)` adapters globally rather than per compilation unit.
- Consider moving canonical adapters/shapes into a shared runtime artifact so separate
  compilations do not each generate them.
- Shorten shape names: a 16-hex prefix is sufficient for practical collision resistance, or use a
  stable numeric id with a manifest. This reduces constant-pool and directory overhead and makes
  stack traces readable.

## F. Unused standard-library and runtime code in every output

**Severity:** high for binary size and startup. **No language change required for runtime gating.**

- **Whole stdlib:** source mode adds all bundled units (`CompilationService.java:105-107`).
  Baseline: 273 stdlib classes, ~322 KB, including `zeron.net` (45 classes), `zeron.serialization`
  (35), `zeron.compiler` (37), and `zeron.time` — none imported by the sample.
- **Runtime support (implemented):** `BytecodeEmitter.emit` used to emit `Unit`, `RaisedEffect`,
  effect support, resource support, equality/hash support, Unicode, BigInteger, numeric support,
  Java array support, and HTTP support unconditionally. It now calls
  `RuntimeSupportEmitter.emitReferencedRuntimeSupport` after all program, namespace, and nominal
  classes are written. That method scans the generated `.class` files for support-class internal
  names, ignores classes managed by the support registry so stale copies cannot keep themselves
  alive, and emits only referenced units plus their transitive dependencies. `HttpSupport` is one
  unit covering all 7 classes (22,364 B in the earlier baseline); the
  `com.maruseron.zeron.runtime.RationalValue` unit (6,479 B) pulls in `NumericSupport`.
- **Output cleanup:** the CLI build paths (`Zeron.runUnits`, `Zeron.buildStandardLibrary`) now clear
  the output directory after resolution succeeds and before bytecode emission. Because shape
  interfaces are content-hash-named, changing a lambda shape would otherwise leave the old
  interface behind and repeated builds could accumulate dead classes. The cleanup refuses unsafe
  targets such as the filesystem root, the current directory, the user home, a directory containing
  `zeron.toml` or `.git`, an output path that is a file, and any output directory that contains a
  compilation source.

**Direction:**

- The bundled compiled stdlib is now the default (see “Shipping status” above), with source mode
  kept as an explicit opt-in for standard-library development. Reachability within source mode
  should still include extension methods and derived evidence, not just explicit imports; if full
  reachability is hard, split the stdlib into independently compilable modules and link only the
  used ones.
- Runtime support is emitted on demand by scanning generated class files for support-class
  references and copying only the referenced units. The scan is conservative: any byte-level
  occurrence of a support class internal name, including descriptor-only references, counts.
  `HttpSupport` and its nested classes form one unit, and declared dependencies (`EffectSupport` →
  `EffectValue`, `ResourceSupport` → `RaisedEffect`/`Unit`, `RationalValue` → `NumericSupport`,
  `ArrayIterable` → `Unit`) are closed transitively. A remaining refinement would be to record
  references during emission instead of scanning the finished class files.
- Output cleanup is implemented in the CLI orchestration rather than in `CompilationService`, so
  separately compiled library and client outputs can still share a directory across invocations.
  A staging directory with an atomic swap remains an option if interrupted builds must preserve the
  previous successful output.

## G. Top-level values pay an initialization check on every read

**Severity:** medium (hot for globals, small per read). **No ABI change required.**

`DeclarationEmitter.generateTopLevelValueMethods` emits:

- a `public static` field (not `final`), even for immutable `let` (`DeclarationEmitter.java:25-29`),
- a private `$zeron$initialized$<name>` boolean,
- an `$zeron$init$<name>()` initializer that stores the value and the flag,
- an accessor `$zeron$get$<name>()` that calls `$zeron$initialize()` (which is emitted as an empty
  method, `DeclarationEmitter.java:165-166`), reads and branches on the flag, builds an
  `IllegalStateException` on the failure path, then reads the field
  (`DeclarationEmitter.java:433-452`),
- a `<clinit>` that sequentially invokes every initializer in dependency order, including
  standard-library globals (`DeclarationEmitter.java:164-177`).

Measured accessor for `globalOffset`:

```text
invokestatic main.$zeron$initialize:()V   // no-op
getstatic main.$zeron$initialized$globalOffset:Z
ifne ready
new IllegalStateException; ...; athrow
ready:
getstatic main.globalOffset:I
```

Measured `<clinit>` also initializes `zeron.math.PI`, `TAU`, and `E` even though the sample never
uses `zeron.math`.

**Direction:**

- Remove the empty `$zeron$initialize` call and the per-read boolean check. The JVM already runs
  `<clinit>` before any static access; the flag is only needed for a genuinely lazy scheme.
- For immutable top-level values with compile-time-constant initializers, emit `static final` plus
  a `ConstantValue` attribute and read the constant directly (or inline it at use sites).
- For non-constant immutable values, use an initialization-on-demand holder class or a single
  checked initialization at class init, then plain `getstatic`.
- Initialize only values reachable from the program (ties into the reachability work in F) instead
  of every stdlib global in the entry `<clinit>`.
- Mark immutable top-level fields `final` so the JIT can constant-fold and so Java interop cannot
  mutate them.

## H. Derived equality and hashing go through `MethodHandle.invoke`

**Severity:** medium-high for equality/hash-heavy code. **No language change required.**

Generated `$zeron$eq` and `$zeron$hash` helpers take one `MethodHandle` per component and call
`MethodHandle.invoke` for each component, as measured for `Point`:

```text
invokevirtual java/lang/invoke/MethodHandle.invoke:(II)Z
...
invokevirtual java/lang/invoke/MethodHandle.invoke:(I)I
```

This is a signature-polymorphic call site per component. It is harder for the JIT to inline than a
direct `==`, `Integer.compare`, or `Objects.equals`, and it bloats every derived class with
`MethodHandle` parameters and invocation code. `EqSupport`/`HashSupport` dispatch uses the same
pattern (`RuntimeSupportEmitter.java:683,730`).

**Direction:**

- When the component type is statically known (the common case), emit direct comparisons:
  primitive `==`/`Float.compare`/`Double.compare`, `Objects.equals` for references, and direct
  `hashCode`/`HashSupport` calls.
- Keep `MethodHandle` only for components whose type is a method/class type parameter.
- Where the contract evidence is a known concrete implementation, inline it or call a static
  helper directly.
- Optionally cache derived hashes for deeply immutable data classes; do not cache for mutable
  components.

## I. Pattern extraction still uses `Object[]` for legacy extractors

**Severity:** low-medium (only custom/legacy extractor patterns). **No language change required.**

Sum and product patterns already destructure with direct payload getters
(`ExpressionFlowEmitter.java:689-727`). Legacy/extractor patterns call a helper returning
`Object[]`, store it in a temporary, load each element with `aaload`, and cast/unbox
(`ExpressionFlowEmitter.java:742-773`). This allocates an array and boxes each extracted value.

**Direction:** replace the `Object[]` extractor protocol with typed out-parameters, a generated
typed result record, or direct getters. For a fixed arity, generate a typed carrier (or use
`invoke` with multiple return slots via a generated holder) so no array or boxing is needed.

## J. Local codegen and peephole opportunities

These are smaller, independent wins.

1. **Redundant array bounds check.** Every array read/write emits `Objects.checkIndex`
   (`BytecodeEmitter.java:3277-3283`) before `aaload`/`aastore`, which performs its own bounds
   check. Unless Zeron's error type/message requires `Objects.checkIndex`, remove the explicit
   call. This removes a static call, a `swap`, and a duplicate index computation per access.
2. **String concatenation is not flattened.** Each binary `+` emits its own
   `StringConcatFactory.makeConcatWithConstants` call site with a two-slot recipe
   (`BytecodeEmitter.java:644-664`). `a + b + c + d` becomes three indy call sites and two
   intermediate strings. Flatten a left-associative `+` chain over strings into one recipe and one
   indy site.
3. **Redundant casts.** `emitConversion` and `emitArrayReadConversion` sometimes emit
   `checkcast`/`Objects.requireNonNull` when the source type already guarantees the target
   (`BytecodeEmitter.java:3081-3111`, `3285-3316`). Add a static subtype check before emitting a
   checkcast, and skip the cast when the verifier already knows the type.
4. **Constant folding covers the common operators and never crashes.** `ConstantFolder.fold` handles
   literals, grouping, unary `+`/`-`/`not`/`~`, Int/Long/Float `+ - * / %`, Int/Long bitwise operators and
   shifts, relational operators, Boolean `and`/`or`, equality, and string `+`. Unsupported operators
   and division or remainder by a literal zero return `null`, so emission falls back to the normal
   expression path instead of throwing. A local `let remainder: Int = 5 % 3;` now folds successfully.
5. **`using` allocates a resource array and counters.** `StatementEmitter.emitUsing` always
   allocates an `Object[]`, an acquired counter, and a failure local, then calls
   `ResourceSupport.dispose` with the array (`StatementEmitter.java:171-238`). For a statically
   known number of resources, keep them in locals or a generated holder and inline disposal; keep
   the array path only for dynamic/variadic cases.
6. **Property compound assignments expand into many temporaries.** The emitter loads the receiver,
   duplicates it, reloads the property, applies the operation, converts, and stores, producing
   long `iload/istore` sequences. Review `duplicateValueUnderReceiver` and property-assignment
   lowering for cases where a single `dup_x1`/`dup2_x1` sequence suffices.
7. **Dead evidence stubs.** Generic methods with unavailable evidence emit a body
   that simply throws `UnsupportedOperationException` (`BytecodeEmitter.java:1731-1739`, used from
   `DeclarationEmitter.java:131,288` and `NominalTypeEmitter.java:126,451,506,549`). These methods
   are callable dead weight. If the resolver can prove a call can never use the erased entry
   point, omit the stub; otherwise keep it but mark it clearly.

## K. Correctness and robustness issues observed during the audit

These are not class-file size issues, but they surfaced while measuring generated output and
should be tracked alongside the optimization work.

1. **Fall-through non-`Unit` bodies are rejected.** A function, method, extension method, or
   default contract method with a non-`Unit` return type whose body can fall off the end now gets
   `ZR2011` during resolution. Unit and inferred-Unit bodies remain allowed. A trailing `raise`, or
   any expression of type `Never`, marks the remaining flow unreachable, so a body such as
   `readAll(): String { ... raise IoFailure.new(...); }` is accepted.
2. **Constant folding no longer crashes the compiler** for valid constant expressions such as
   `5 % 3` in a local `let` (see J.4). Unsupported operators and zero divisors return `null`, which
   becomes a missed optimization rather than a compiler failure.
3. **Output cleanup is implemented** in the CLI build paths, so stale, hash-named class files no
   longer survive source changes (see F). Tests that assert exact output contents should still
   compile into a fresh directory.

## L. Generic contract calls erase `Int` to `Object`

**Severity:** high for generic numeric and collection code. **Needs ZEP for specialization.**

A concrete `Int` is a JVM `int`: locals, fields, parameters, and returns hold primitives. Direct
arithmetic (`iadd`, `isub`, `imul`, `idiv`, `irem`) and direct calls to the `Integral<Int>`
witness are primitive as well. Measured witness methods:

```text
public static final int $zeron$witness$…$add(int, int);      // iload_0; iload_1; iadd; ireturn
public static final int $zeron$witness$…$div(int, int);      // idiv/irem-based floor division
public static final int $zeron$witness$…$rem(int, int);      // irem
public static final int $zeron$witness$…$compareTo(int, int);
```

`10.div(3)`, `10.rem(3)`, `10.mod(3)`, `10.quot(3)`, `(-10).abs()`, and `10.compareTo(3)` all
compile to direct static calls with primitive signatures, with no allocation. The wasteful path is
**contract-polymorphic code**, where `T` is erased to `Object`:

```text
fn addGeneric<T: Numeric<T>>(a: T, b: T): T = a + b;
fn divGeneric<T: Integral<T>>(a: T, b: T): T = a.div(b);
```

generates:

```text
public static final Object addGeneric(Object, Object, MethodHandle ×7);
     3: invokevirtual MethodHandle.invoke:(Object,Object)Object

public static final Object divGeneric(Object, Object, MethodHandle ×16);
     3: invokevirtual MethodHandle.invoke:(Object,Object)Object
```

and each call site performs:

```text
invokestatic  Integer.valueOf:(I)Ljava/lang/Integer;   // box each argument
ldc           MethodHandle …                          // 7 or 16 constants
invokevirtual MethodHandle.invoke:(Object,Object)Object
checkcast     Integer
invokevirtual Integer.intValue:()I                    // unbox result
```

So the common claim is half right: `Int` is not represented by `Integral`, and direct `Int` math is
primitive, but **any call that goes through a generic bound boxes every argument and result and
dispatches through `MethodHandle.invoke`**. This is frequent in practice because the standard
library is written generically:

- `Numeric`/`Integral`/`Fractional`/`Floating` helpers (`addGeneric`, `divGeneric`, any generic
  numeric utility).
- `Comparable`-bounded algorithms such as `sorted`/`sort`
  (`stdlib/zeron/collections/collections.zn:70,82`).
- `Hashable`/`Eq`-bounded helpers such as `hashKey<T: Hashable<T>>` and
  `equalKeys<T: Eq<T>>` (`stdlib/zeron/collections/hashmap.zn:9,11`), so a
  `HashMap<Int, V>` boxes every key on `put`/`get` and dispatches hash/equality through evidence.
- `ArrayList<Int>`, `Stream<Int>`, and `Step<Int>` element boundaries (sections C and D), which box
  independently of any numeric contract.

Measured `HashMap<Int, String>`:

```text
ldc 7
invokestatic  Integer.valueOf:(I)Ljava/lang/Integer;
invokevirtual HashMap.put:(Ljava/lang/Object;Ljava/lang/Object;)Lzeron/lang/Option;
```

`Integral<T>` costs **16 `MethodHandle` parameters** because it requires
`Numeric<T> + Hashable<T> + Comparable<T>` and each of those pulls in further evidence
(`Comparable` requires ordering, which requires equality). The parameters are loaded with `ldc`
constants, so they are not heap allocations per call, but they lengthen every descriptor, prevent
simple call shapes, and give the JIT 16 signature-polymorphic call targets to reason about. Erased
generic entry points also emit throwing stubs (J.7).

`zeron.numeric.Integer` is a separate arbitrary-precision class and is not the representation of
`Int`; the boxing above is JVM `java.lang.Integer`, produced by erasure.

**Direction:**

1. **Inline statically known evidence (short step).** When the resolver knows the concrete type
   argument, call the witness method directly (`$zeron$witness$…$add(II)I`) and skip
   `Integer.valueOf`, `MethodHandle.invoke`, and the result cast. This removes the boxing and the
   polymorphic dispatch without changing the generic ABI.
2. **Monomorphize or specialize generic functions for primitive type arguments.** Emit a
   `$Int`-specialized entry point (`addGeneric$Int(int, int)int`) for calls whose type arguments are
   primitive, and keep the erased `Object` entry point for dynamic/compiled-library callers. This is
   the durable fix, but it needs a ZEP covering ABI, code-size policy, and library boundaries.
3. **Specialize the hot standard-library types.** `ArrayList<Int>`/`HashMap<Int, V>` could use
   primitive backing storage and direct hashing, which is where generic numeric code spends most of
   its per-element boxing.
4. **Shrink the evidence protocol.** Pass one evidence dictionary object (or a bootstrapped
   `invokedynamic` call site) instead of 7–16 `MethodHandle` parameters, and merge contract
   requirements that are always used together.
5. **Regression-test the direct path.** Assert that a concrete `Int` call to a witness method
   contains no `Integer.valueOf`, `checkcast Integer`, or `MethodHandle.invoke`.

## Prioritized backlog

| Priority | Item | Expected effect | Effort / risk |
| --- | --- | --- | --- |
| Shipped | Fix `ConstantFolder` crash and fall-through function verification | Correctness | Small |
| Shipped | Add output cleanup/staging | Reproducible builds, no stale classes | Small |
| Shipped | Make the bundled compiled stdlib the default and auto-discover its JAR from `zeron.home` | 320 → 29 classes and 382 → 55 KB per program (before sharing) | Small-medium |
| Shipped | Emit runtime support on demand; gate HTTP/Rational/BigInteger plus other unused support | Removes the ~22 KB dead HTTP stack and other unreferenced support from each program | Small-medium |
| P1 | Inline statically known contract evidence and skip `Object` boxing | Removes `Integer.valueOf`/`MethodHandle.invoke` from concrete generic calls | Small-medium |
| P1 | Fuse `for` comprehensions instead of lowering to `Stream` chains | Removes stage objects, adapters, and double boxing per element | Medium-high |
| P1 | Replace `Step` with `hasNext`/`next` or a fused iterator | One allocation per element | Medium; **needs ZEP** |
| P1 | Move lambdas to their owning unit; remove cross-package bridges | Smaller entry holder, no synthetic public accessors | Medium |
| P1 | Direct equality/hash codegen for known component types | Removes `MethodHandle.invoke` from hot paths | Small-medium |
| P2 | Primitive-specialized arrays (`int[]`, `long[]`, …) | Removes per-element boxing/casts | High; **needs ZEP** |
| P2 | Monomorphize generic functions for primitive type arguments | Removes argument/result boxing and shrinks evidence signatures | High; **needs ZEP** |
| P2 | Primitive-specialized `ArrayList<Int>`/`HashMap<Int, V>` backing storage | Removes per-element/key boxing | High; **needs ZEP** |
| P2 | Zero-cost newtype erasure | One allocation/call per use | High; **needs ZEP** |
| P2 | `Unit` as `void` at boundaries | Removes pervasive `getstatic`/`areturn`/`pop` | Medium-high; **needs ZEP** |
| P2 | Demand-driven Object shapes; canonical/shared adapters | Fewer shape classes, fewer adapters | Medium |
| P2 | Shorten shape names | Smaller constant pools, readable traces | Small |
| P2 | Top-level init cleanup (`static final`, lazy holder, no no-op call) | Fewer instructions per global read; less startup work | Small-medium |
| P3 | Flatten string concat; remove redundant bounds checks/casts | Local instruction reduction | Small |
| P3 | Typed pattern extraction; `using` locals; property-assignment peepholes | Local allocation/instruction reduction | Small-medium |

## Suggested measurement and regression harness

Add a small bytecode-quality suite so these regressions are visible:

1. Compile a fixed set of `.zn` fixtures into a fresh output directory.
2. Record total class count, total bytes, per-holder method counts, and counts of `$lambda$`,
   `$adapter$`, `$functionRef$`, and generated shape interfaces.
3. Run `java -verify` (or load every generated class in a test JVM) to catch verify errors like
   K.1.
4. Add allocation microbenchmarks for: numeric array read/write, `for` over `Array<Int>` vs
   `ArrayList<Int>`, `for … collect` with/without a guard, lazy `map/filter/take`, newtype
   construction, and a `Unit`-returning callback loop.
5. Track `HttpSupport` and other support classes for absence in programs that do not use them.

The fixtures should be small enough to inspect with `javap -p -c` and stable enough that
class-count changes are meaningful.

## Appendix: reproduction

The baseline was produced by compiling the sample below with the project's compiled classes and
the bundled source standard library. The sample is not committed.

```zeron
import zeron.collections.ArrayList;
import zeron.lang.Eq;
import zeron.lang.Hashable;

public newtype UserId = Int;
public type Position = (x: Int, y: Int);
public data class Point(x: Int, y: Int) deriving Hashable {
    public squaredDistance(): Int = this.x * this.x + this.y * this.y;
}

public let globalOffset: Int = 7;
fn apply(value: Int, transform: (Int) -> Int): Int = transform(value);
fn ignore(value: Int): Unit { let copy = value; }

fn compute(limit: Int): Int {
    let values: Array<Int> = [1, 2, 3, 4, 5];
    let mut total = 0;
    for (let value in values) { total = total + value; }

    let id = UserId.new(limit);
    let position = Position.new(1, 2);
    let point = Point.new(position.x, position.y);
    ignore(total);

    let evens = for number in values
        if number % 2 == 0
        collect number * 10 into ArrayList<Int>.empty();

    let doubled = for number in values yield number * 2;
    let limited = doubled.map(n -> n + 1).filter(n -> n > 2).take(2);
    let projected = for number in limited
        yield number + id.value + point.squaredDistance();

    let mut sum = 0;
    for (let number in projected) { sum = sum + number; }
    return globalOffset + sum + evens.size + apply(limit, n -> n * 3);
}

fn main() { println("" + compute(10)); }
```

Representative commands (PowerShell, from the sample directory):

```powershell
$cp = "<repo>\target\classes;" + (Get-Content <repo>\target\classpath.txt)
java -cp $cp com.maruseron.zeron.Zeron build

$files = Get-ChildItem dist -Recurse -Filter *.class
"classes=$($files.Count) bytes=$(($files | Measure-Object Length -Sum).Sum)"

javap -p -c -classpath dist main
javap -p -c -classpath dist Point
javap -p -c -classpath dist UserId
javap -p -c -classpath dist zeron.lang.Unit
javap -p -c -classpath dist 'zeron.collections.Step$$sum$Yield'
```

Compiled-standard-library mode builds the artifact once and then compiles the same sample with:

```powershell
java -cp $cp com.maruseron.zeron.Zeron --build-stdlib <stdlib-dir> --jar-output <stdlib>.jar
```

```toml
[platform]
stdlib = "compiled"

[dependencies]
zeron-libraries = ["<stdlib>.jar"]
```

The dependency entry is required today; with the auto-discovery change described above, the
installed compiler would supply the bundled JAR without a manifest entry.

The generic-evidence measurement in L uses:

```zeron
fn addGeneric<T: Numeric<T>>(a: T, b: T): T = a + b;
fn divGeneric<T: Integral<T>>(a: T, b: T): T = a.div(b);

fn main() {
    let direct = 1 + 2;
    let generic = addGeneric(1, 2);
    let div = divGeneric(10, 3);
    println("" + direct + generic + div);
}
```

inspect with `javap -p -c -classpath dist main`.

The former constant-folding crash was reproduced with:

```zeron
fn main() {
    let remainder: Int = 5 % 3;
    println("" + remainder);
}
```

That program now compiles and folds `5 % 3` to `2` before emission.

## References

- [`NAVIGATION.md`](../../NAVIGATION.md) — compiler pipeline and module responsibilities.
- [`design-document-03_lambda-lowering.md`](../design_documents/design-document-03_lambda-lowering.md) — current lambda lowering and known gaps.
- [`design-document-06_intrinsic-arrays.md`](../design_documents/design-document-06_intrinsic-arrays.md) — intrinsic array types and operations.
- [`design-document-14_sum-product-types-and-newtypes.md`](../design_documents/design-document-14_sum-product-types-and-newtypes.md) — newtype representation.
- [`design-document-17_structural-equality-and-hashing.md`](../design_documents/design-document-17_structural-equality-and-hashing.md) — derived equality/hash behavior.
- [`ZEP-0000.md`](../zeps/ZEP-0000.md) — proposal process for A, B, C, D, and L.
- [`guide/20-generic-bounds-and-witnesses.md`](../../guide/20-generic-bounds-and-witnesses.md) — bounds and contract evidence as seen by users.
- [`guide/24-libraries-and-separate-compilation.md`](../../guide/24-libraries-and-separate-compilation.md) — compiled standard library and library JARs.
