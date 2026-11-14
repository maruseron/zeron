# Lambda Lowering and Roadmap

## Purpose

This document records the current JVM lowering for Zeron lambdas, the limits of the implementation, and a recommended path toward the language's longer-term lambda goals. It complements the language overview in `design-document-00.md`. The encoded descriptor grammar in `design-document-02_type-grammar.cfgr` is retired and is not authoritative for current source syntax or type representation.

## Current Lowering

For each distinct function signature used by a lambda, the compiler emits a public functional interface in `zeron.runtime`. Its name begins with `Lambda$V2$`, includes a shortened, sanitized summary of the arity and parameter/result types, and ends with the SHA-256 digest of the canonical `FunctionShapeKey` encoding. The digest remains the identity; the readable portion is diagnostic only and may be truncated. The generated name is not the language-level type identity. Its single abstract method is named `invoke` and has the JVM parameter and return descriptors for that signature.

Each lambda expression gets a separate public static synthetic helper in the generated main class, such as `$lambda$0`. For file compilation, the class name comes from the source filename without its extension. At the expression's call site, `invokedynamic` links that helper to the signature interface through `java.lang.invoke.LambdaMetafactory`. Calls through function-valued parameters use `invokeinterface`.

This design has useful properties already:

- The function object is represented by a typed interface, not an arbitrary-arity `Object[]` protocol.
- Primitive parameters and results remain primitive at the JVM boundary where the language type maps to a primitive.
- Lambdas with the same signature share one interface while retaining separate implementations.
- The JVM creates the concrete lambda implementation object at runtime; the compiler emits the interface and the static body helpers.

Capturing lambdas use the same `LambdaMetafactory` path. Their factory descriptor accepts captured values, while the generated SAM interface and its `invoke` descriptor contain the lambda's invocation parameters. The static helper receives captures followed by those parameters. The JVM creates the concrete function object with the captured values bound to it; the compiler does not emit a separate closure class per lambda.

Receiver functions use a distinct `FunctionShapeKey` from ordinary functions, even when their
erased JVM descriptors match an ordinary function with the receiver as its first explicit
parameter. Their SAM invocation descriptor places the receiver before explicit parameters. A
contextually typed receiver lambda binds that first runtime argument as its implicit receiver;
qualified enclosing-receiver references are captured like other immutable values. The receiver
type and mutability view are also preserved in compiled-library API metadata.

The current sample in `src/main/resources/test.zn` exercises integer, floating-point, and string lambdas, including two different lambdas with the same integer shape and immutable local captures.

## Current Limits

### Capture model and closure design

The metafactory factory type is `(<captured types>) -> LambdaShape`. Captures are copied by value when the function object is created. The static helper receives those values followed by the lambda's declared parameters; captures do not change the structural function shape.

Supporting captures requires explicit language semantics as well as code generation. The recommended direction is to treat captures as immutable by default: a lambda may borrow a value from an enclosing scope, but it should not silently capture and mutate a local binding that is otherwise stack-scoped. This keeps the runtime model simple, avoids hidden aliasing of locals, and makes lambdas behave consistently with the language's value model.

The design should prefer the following rules:

- immutable values captured from the surrounding scope are copied by value into the metafactory-created function object,
- mutable state is captured only through a shared heap object or explicit cell/reference abstraction,
- mutable locals are not implicitly closed over and re-bound by lambdas,
- lambda capture semantics must remain consistent with future class, object, and method semantics.

This avoids a split personality between ordinary methods and lambdas: methods do not implicitly close over mutable locals, so lambdas should not either. If mutation is needed, it should be expressed through an explicitly shared object or a first-class reference/cell construct.

### Generic function callback boundaries

Generic top-level functions have explicit return types and are instantiated at direct call sites by
inference or explicit type arguments. Type variables are erased to `Object` in the JVM method
descriptor, with primitive boxing and unboxing at call boundaries. Rank-1 generic functions are
first-class only after explicit specialization or a fully constraining expected type. Contextually
typed lambdas remain monomorphic unless they use the ZEP-0109 binder-prefixed form.

ZEP-0109 adds rank-2 polymorphic function types (`<T> (T) -> T`) and generic lambdas. An immutable
`let`-bound generic lambda or a contextual generic-lambda argument is checked against the expected
polymorphic type; a bare generic named function reference eta-expands when the expected type is
fully known. Each application instantiates the value's binders at the call site. Unbounded,
non-reified values reuse the ordinary erased SAM shape; values whose binders require contract
evidence or `reified` descriptors use the hidden-slot adapter described below. Effect-kinded
binders and binder-dependent effect rows remain rejected.

When a generic signature contains a callback such as `(T) -> R`, its runtime SAM shape uses erased
parameter and return descriptors. The compiler emits public static synthetic adapter helpers at call sites
that cross between this erased SAM and a concrete lambda shape. These helpers perform the casts,
boxing, and unboxing explicitly; `LambdaMetafactory` still creates the adapter object. The same
boundary applies when a generic function returns a callback involving its type variables. Ordinary
concrete function-shape identities remain unchanged.

The direct generic callback test matrix covers every input/output pairing among `Int`, `Float`,
`Boolean`, and `String`. Runtime tests also cover stored callbacks, returned callbacks capturing
primitive and reference values, caller-local captures crossing a generic callback boundary, directional
adapter reuse, and deterministic repeated compilation. Broader incremental-compilation and shape
combinations across nested and nominal boundaries remain follow-up coverage.

### Generic nominal callback boundaries

Generic classes and contracts are supported and erase their type variables and parameterized nominal
types as described in [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).
Callback adaptation across erased nominal boundaries is implemented for generic class/contract
constructors, callback-valued fields and member parameters/results, and erased contract bridges.
Lambdas are contextually resolved against the substituted nominal signature, and adapter discovery
registers the conversion before code generation. Public synthetic static adapter helpers let
generated nominal bridge methods call back into the generated program class. Runtime and ABI tests
cover primitive/reference shapes, callback arguments/results, field reads/writes, contract bridges,
nested callbacks, nullable callbacks, and mutable function views. Generic named function references
are separately specialized to monomorphic SAM values with generated erased-boundary bridges. The
separate ZEP-0109 eta path can instead retain a polymorphic function type when the expected type is
polymorphic; it does not change monomorphic nominal callback adaptation. Broader shape combinations
and adapter reuse remain test coverage work.

### First-class lambda typing

Lambda resolution is now based on the original expression node, not a detached copy. A standalone lambda can be inferred, bound, and reused as a first-class value while preserving the same resolved `FunctionDescriptor` on both the AST node and the binding.

Contextual typing and inference are kept consistent across initializers, assignments, function arguments, and returns. The lambda's resolved signature is preserved on the original expression and binding rather than being reconstructed from a copied candidate node.

Unresolved lambda parameters in an immutable `let` binding can be generalized for identity and
constant-result bodies. The binding stores a rank-1 `FunctionDescriptor` scheme; each call or expected
function type instantiates its type parameters independently without mutating the scheme. At runtime,
this implemented slice stores one closure using its erased SAM shape, with boxing/unboxing at calls
and existing adapters when viewed as a concrete callback.

The accepted constrained extension in [ZEP-0003](../zeps/ZEP-0003.md) is limited to a lambda written
directly as an immutable `let` initializer. It infers multiple applied-contract bounds from direct
member use and substituted bounds of generic callees, quantifies only variables absent from the
captured environment, and leaves raised effects fixed. Schemes are local-only and are not serialized
in the library API index. Each use resolves required evidence at compile time; missing or ambiguous
evidence is reported at that use and names the lambda binding that introduced the requirement. A
generated template captures the lexical environment, then specializes to a monomorphic erased closure
with the selected evidence bound into its helper. Within each dynamic activation of the `let`,
repeated uses with the same erased function shape and evidence reuse one closure; different shapes or
evidence produce distinct closure variants. The cache is local to the template instance.

ZEP-0109's rank-2 support is separate from that rank-1 template path. A lambda may carry an
explicit binder list, such as `<T> (value: T) -> value`, and is accepted as an immutable `let`
initializer or as an inline argument whose expected type is polymorphic. Explicit binders are
matched positionally to the expected binders up to alpha-renaming, with identical bounds and
markers. When the lambda has no expected type, its annotated parameters determine the polymorphic
type. The runtime value uses the existing erased SAM shape when every binder is unbounded and
non-reified; a call instantiates the binders at the use site. Bounded and `reified` binders use the
hidden-slot ABI: evidence slots first, then one `AppliedTypeDescriptor` per reified binder, appended
after the erased source parameters. Effect-kinded binders and binder-dependent effect rows remain
diagnosed. Nested schemes and rank-3 values remain unsupported.

The hidden-slot ABI is implemented for lambda values, function references, and calls:

- `FunctionDescriptor` carries hidden evidence and descriptor counts, preserved by substitution,
  erasure, and resolved lambda/eta types. `FunctionShapeKey` encodes the counts in a distinct
  `zeron-function-shape-v2-e<evidence>-d<descriptors>;` key, so a polymorphic adapter cannot collide
  with an ordinary SAM shape; `FunctionShapeNames` and `toJavaClassDesc` inherit the distinction.
- The `LambdaMetafactory` implementation helper signature is captures, erased source parameters,
  evidence `MethodHandle`s, then reified descriptors. `samMethodType` and the generated interface
  method use the same order, and lambda implementations declare the hidden locals after their
  source parameters.
- A call through a binding with hidden slots emits the source arguments and then the selected
  evidence and reified descriptors before invoking the hidden-slot interface descriptor. Rank-1
  `LambdaTemplateSupport` specialization remains restricted to inferred, non-hidden-slot lambdas.
- Generic named-function eta-expansion forwards each application's evidence and descriptors to the
  source generic method instead of capturing them in the function object.
- The library API index already records function type binders, their bounds, and their `reified`
  markers. Imported function types are validated before lambda checking, which restores hidden
  counts from the bound and marker metadata, so rank-2 callback signatures survive separate
  compilation without a new wire record.

Remaining rank-2 work is separate from the hidden-slot ABI:

- Fixed `raises` clauses are checked on polymorphic function types, but binder-dependent effect
  rows and generic effect evidence are not part of this slice.
- Generic SAM implementations, mutable polymorphic bindings, nested schemes, and rank-3 values are
  still unsupported.

This is an initial, intentionally constrained generalization rule. Reassignable bindings, nested
polymorphic schemes, and explicit `forall` are not generalized. The current implementation leaves
operator-constrained lambdas monomorphic and does not infer their `Numeric<T>`, `Fractional<T>`, or
`Comparable<T>` bounds. [ZEP-0133](../zeps/ZEP-0133.md) is accepted for `0.8.0` and extends this same rank-1,
direct immutable-`let` path with numeric and comparison operator-constraint inference; implementation
is pending.

### Multi-parameter lambda syntax

Multi-parameter lambdas are now implemented as a parameter list in the AST, parser, resolver, and lowering pipeline. The language supports zero-argument, single-argument, and multi-argument lambdas, and generated helper descriptors use the full parameter list for each lambda shape.

Multi-parameter lambdas retain their structural lowering. Immutable let-bound constant-result forms
can generalize each unresolved parameter independently; operator-constrained forms remain
monomorphic and may use later call context to resolve their parameter types.

### Shape identity and generated names

The compiler now uses `FunctionShapeKey` as a versioned recursive semantic identity. It encodes arity, parameter order, return type, nested function signatures, nullability, generic arguments, and nominal names without including the source function name. Generated interface names are derived separately from a SHA-256 digest of that key, and the compiler checks that a generated name has not already been associated with a different full key.

The JVM method descriptor remains a separate lowering. For example, `String` and `String?` have different semantic shape keys but currently lower to the same JVM reference descriptor; nullable primitives lower to wrapper descriptors. Generic function signatures use erased callback shapes, and generic classes/contracts erase to raw JVM classes and interfaces. This separation keeps source type identity from being inferred from the generated interface name or JVM ABI.

Nominal descriptors carry structured package-qualified identities, and the key includes that identity. Module-qualified identity remains future work. Canonical encoding of generic arguments is separate from nominal JVM erasure and callback adaptation.

### Output layout

The compiler writes generated Zeron programs under `dist/`. A default-package entry such as
`test.zn` emits `test.class` at the output root; a packaged entry emits its class under the matching
package path (for example, `app.Main` at `dist/app/Main.class`). Generated lambda-shape interfaces
currently remain in the default package at the output root, independently of the entry package.
The no-argument REPL compiles each submission into a uniquely named `ZeronRepl` holder and loads it
from a session class loader over `dist/`. Expressions print their result, and import declarations
plus top-level `let` and `let mut` bindings persist across submissions; redeclaring a binding is
rejected. Imports are validated when entered and apply to subsequent expressions. Each submission
receives current bindings as typed parameters and returns their updated values to the host, so mutable
bindings can be reassigned between submissions without re-evaluating their initializers. Function,
type, and contract declarations are not yet persistent REPL declarations. Multi-line input is
accepted while delimiters, strings, or block comments are unfinished. Direct compiler use without a
source path falls back to `ZeronMain`. Maven's `target/classes` remains the output for the compiler
itself, not for generated Zeron programs.

The shared output root removes the previous split between the process working directory and Maven's class output. The CLI build paths clear the output directory after successful resolution and before bytecode emission, so obsolete hash-named shape interfaces do not accumulate; a staging directory with an atomic swap remains future work for interrupted builds. Current consumers should load the main class and its required generated interfaces from `dist/`.

## Recommended Direction

Keep the current JVM model: a generated SAM interface per unique structural function shape, a static implementation helper per lambda expression, and `LambdaMetafactory` for runtime object creation. Captured values belong to the factory call-site descriptor and implementation-helper descriptor, not the SAM shape. This separates language-level function types from implementation details and avoids hand-generating one concrete class for every lambda expression.

Address the remaining work in this order:

1. **Canonicalize function-shape identity: implemented baseline.** `FunctionShapeKey` is recursive and versioned; SHA-256 names are separate from semantic equality, and the compiler checks full keys for generated-name collisions. Package-qualified nominal identity is included; add module identity if modules are introduced, and evolve the encoding version if its canonical rules change.
2. **Make lambda typing first-class: implemented baseline.** Lambda signatures remain on the original
  expression and binding. Expected types flow through declarations, assignments, calls, and returns.
  Conditional expressions pass the expected type independently to both result branches.
  Safe immutable let-bound identity/constant lambdas generalize to rank-1 schemes and instantiate
  freshly at each use. The current implementation keeps operator-constrained lambdas on
  monomorphic contextual inference; ZEP-0133 extends the rank-1 path with inferred numeric and
  comparison operator bounds for eligible lambdas, pending implementation.
3. **Add multi-parameter lambdas: implemented.** The AST, parser, resolver, and lowering logic now support zero-argument, single-argument, and multi-argument lambda forms, and they lower each shape to the appropriate generated functional interface.
4. **Specify capture semantics before classes: implemented.** Captures are immutable by default: a lambda may close over immutable values, but it must not implicitly capture and mutate stack-scoped locals. Mutation must happen through explicitly shared heap state such as objects or a dedicated cell/reference abstraction.
5. **Extend polymorphic lambda inference: rank-1 generalization and rank-2 support
  implemented.** Direct immutable-let lambdas infer applied-contract bounds from body member use and
  bounded generic callees. Each use resolves evidence independently; the generated template
  specializes and caches erased closures per activation. ZEP-0109 adds explicit binder-prefixed
  lambdas, polymorphic function types, alpha-equivalent checking, eta-expansion, and per-application
  instantiation. Unbounded non-reified rank-2 values use the erased SAM shape; bounded and
  `reified` values use the hidden-slot adapter key and per-application evidence/descriptor slots.
  Polymorphic callback signatures survive the library API index and separate compilation. Generic
  SAM implementations, binder-dependent effect rows, nested schemes, rank-3 values, and a measured
  polymorphic-application cost comparison remain separate work.
6. **Specify and implement capture lowering: implemented.** Immutable captured values are passed through the `invokedynamic` factory descriptor and bound to the runtime function object; static helpers receive captures before the lambda's declared parameters. Explicit shared mutable state is available through the ordinary `zeron.lang.Cell<T>` library type; capturing a mutable cell reference preserves the existing by-value reference-capture model.
7. **Unify artifact output: implemented baseline.** Generated programs are emitted under `dist/`; source classes follow their declared package paths, while generated lambda interfaces remain in the default package. Stale-artifact handling is implemented by clearing the output directory after successful resolution and before emission; configurable output directories and a staging/atomic-swap policy remain future work if incremental or multi-project compilation requires them.
8. **Expand behavior tests: broader baseline implemented.** Tests cover same-shape lambdas with different bodies, zero and multiple parameters, the complete `Int`/`Float`/`Boolean`/`String` callback input-output matrix, stored and returned callbacks, conditional lambdas under expected function types, nested lambdas, immutable captures across generic boundaries, adapter reuse, and deterministic repeated compilation. Continue with incremental/multi-project compilation and additional nominal/nested shape combinations; verify both program output and emitted descriptors/call instructions.

## Acceptance Criteria for Broader Lambda Support

Lambda support should not be considered complete solely because a lambda can be passed inline to a function. The broader design should demonstrate that:

- Function signatures have stable, collision-free runtime identities.
- Function values work consistently in all supported expression contexts.
- Captures follow specified value and mutation semantics.
- Multiple parameters and JVM wide-slot values lower correctly.
- Generated interfaces and the main class are emitted and loaded from the configured output.
- Runtime tests and bytecode inspection agree on each supported case.
