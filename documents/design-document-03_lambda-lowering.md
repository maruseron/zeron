# Lambda Lowering and Roadmap

## Purpose

This document records the current JVM lowering for Zeron lambdas, the limits of the implementation, and a recommended path toward the language's longer-term lambda goals. It complements the language overview in `design-document-00.md`. The encoded descriptor grammar in `design-document-02_type-grammar.cfgr` is retired and is not authoritative for current source syntax or type representation.

## Current Lowering

For each distinct function signature used by a lambda, the compiler emits a public functional interface in the default package. Its name is `Lambda$V1_` followed by the SHA-256 digest of the canonical `FunctionShapeKey` encoding; the generated name is not the language-level type identity. Its single abstract method is named `invoke` and has the JVM parameter and return descriptors for that signature.

Each lambda expression gets a separate public static synthetic helper in the generated main class, such as `$lambda$0`. For file compilation, the class name comes from the source filename without its extension. At the expression's call site, `invokedynamic` links that helper to the signature interface through `java.lang.invoke.LambdaMetafactory`. Calls through function-valued parameters use `invokeinterface`.

This design has useful properties already:

- The function object is represented by a typed interface, not an arbitrary-arity `Object[]` protocol.
- Primitive parameters and results remain primitive at the JVM boundary where the language type maps to a primitive.
- Lambdas with the same signature share one interface while retaining separate implementations.
- The JVM creates the concrete lambda implementation object at runtime; the compiler emits the interface and the static body helpers.

Capturing lambdas use the same `LambdaMetafactory` path. Their factory descriptor accepts captured values, while the generated SAM interface and its `invoke` descriptor contain only the lambda's declared parameters. The static helper receives captures followed by those parameters. The JVM creates the concrete function object with the captured values bound to it; the compiler does not emit a separate closure class per lambda.

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
descriptor, with primitive boxing and unboxing at call boundaries. Generic functions are not
first-class values without specialization. Contextually typed lambdas remain monomorphic; safe
immutable let-bound lambdas support a limited rank-1 scheme.

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
are separately specialized to monomorphic SAM values with generated erased-boundary bridges; this
does not introduce polymorphic lambdas. Broader shape combinations and adapter reuse remain test
coverage work.

### First-class lambda typing

Lambda resolution is now based on the original expression node, not a detached copy. A standalone lambda can be inferred, bound, and reused as a first-class value while preserving the same resolved `FunctionDescriptor` on both the AST node and the binding.

Contextual typing and inference are kept consistent across initializers, assignments, function arguments, and returns. The lambda's resolved signature is preserved on the original expression and binding rather than being reconstructed from a copied candidate node.

Unresolved lambda parameters in an immutable `let` binding can be generalized for identity and
constant-result bodies. The binding stores a rank-1 `FunctionDescriptor` scheme; each call or expected
function type instantiates its type parameters independently without mutating the scheme. At runtime,
the closure is stored once using its erased SAM shape, with boxing/unboxing at calls and existing
adapters when viewed as a concrete callback.

This is an initial, intentionally constrained generalization rule. Reassignable bindings, nested
polymorphic schemes, explicit `forall`, and unresolved parameters used by operators are not
generalized. Operator-constrained lambdas retain deferred monomorphic contextual inference because
the language does not yet have operator constraints.

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
Prompt or direct compiler use without a source path falls back to `ZeronMain`. Maven's
`target/classes` remains the output for the compiler itself, not for generated Zeron programs.

The shared output root removes the previous split between the process working directory and Maven's class output. A cleanup or incremental-build policy for obsolete generated shape interfaces remains future work; current consumers should load the main class and its required generated interfaces from `dist/`.

## Recommended Direction

Keep the current JVM model: a generated SAM interface per unique structural function shape, a static implementation helper per lambda expression, and `LambdaMetafactory` for runtime object creation. Captured values belong to the factory call-site descriptor and implementation-helper descriptor, not the SAM shape. This separates language-level function types from implementation details and avoids hand-generating one concrete class for every lambda expression.

Address the remaining work in this order:

1. **Canonicalize function-shape identity: implemented baseline.** `FunctionShapeKey` is recursive and versioned; SHA-256 names are separate from semantic equality, and the compiler checks full keys for generated-name collisions. Package-qualified nominal identity is included; add module identity if modules are introduced, and evolve the encoding version if its canonical rules change.
2. **Make lambda typing first-class: implemented baseline.** Lambda signatures remain on the original
	expression and binding. Expected types flow through declarations, assignments, calls, and returns.
	Safe immutable let-bound identity/constant lambdas generalize to rank-1 schemes and instantiate
	freshly at each use; operator-constrained lambdas retain monomorphic contextual inference.
3. **Add multi-parameter lambdas: implemented.** The AST, parser, resolver, and lowering logic now support zero-argument, single-argument, and multi-argument lambda forms, and they lower each shape to the appropriate generated functional interface.
4. **Specify capture semantics before classes: implemented.** Captures are immutable by default: a lambda may close over immutable values, but it must not implicitly capture and mutate stack-scoped locals. Mutation must happen through explicitly shared heap state such as objects or a dedicated cell/reference abstraction.
5. **Extend polymorphic lambda inference.** Generalize additional lambda bodies using a sound constraint
	solver; design explicit `forall`, nested schemes, and operator constraints separately. The current
	implementation does not provide higher-rank polymorphism.
6. **Specify and implement capture lowering: implemented prototype.** Immutable captured values are passed through the `invokedynamic` factory descriptor and bound to the runtime function object; static helpers receive captures before the lambda's declared parameters. Explicit shared mutable cells remain future work.
7. **Unify artifact output: implemented baseline.** Generated programs are emitted under `dist/`; source classes follow their declared package paths, while generated lambda interfaces remain in the default package. Define stale-artifact handling and configurable output directories if incremental or multi-project compilation requires them.
8. **Expand behavior tests: broader baseline implemented.** Tests cover same-shape lambdas with different bodies, zero and multiple parameters, the complete `Int`/`Float`/`Boolean`/`String` callback input-output matrix, stored and returned callbacks, nested lambdas, immutable captures across generic boundaries, adapter reuse, and deterministic repeated compilation. Continue with incremental/multi-project compilation, explicit shared mutation, and additional nominal/nested shape combinations; verify both program output and emitted descriptors/call instructions.

## Acceptance Criteria for Broader Lambda Support

Lambda support should not be considered complete solely because a lambda can be passed inline to a function. The broader design should demonstrate that:

- Function signatures have stable, collision-free runtime identities.
- Function values work consistently in all supported expression contexts.
- Captures follow specified value and mutation semantics.
- Multiple parameters and JVM wide-slot values lower correctly.
- Generated interfaces and the main class are emitted and loaded from the configured output.
- Runtime tests and bytecode inspection agree on each supported case.
