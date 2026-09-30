# Lambda Lowering and Roadmap

## Purpose

This document records the current JVM lowering for Zeron lambdas, the limits of the prototype, and a recommended path toward the language's longer-term lambda goals. It complements the language overview in `design-document-00.md` and the type notation in `design-document-02_type-grammar.cfgr`.

## Current Lowering

For each distinct function signature used by a lambda, the compiler emits a public functional interface under `com.maruseron.zeron.runtime.lambda`. Its single abstract method is named `invoke` and has the typed JVM parameter and return descriptors for that signature. Examples include `Lambda$IntToInt`, `Lambda$FloatToFloat`, and `Lambda$StringToString`.

Each lambda expression gets a separate private static synthetic helper in `ZeronMain`, such as `$lambda$0`. At the expression's call site, `invokedynamic` links that helper to the signature interface through `java.lang.invoke.LambdaMetafactory`. Calls through function-valued parameters use `invokeinterface`.

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

### First-class lambda typing

Lambda resolution is now based on the original expression node, not a detached copy. A standalone lambda can be inferred, bound, and reused as a first-class value while preserving the same resolved `FunctionDescriptor` on both the AST node and the binding.

Contextual typing and inference are kept consistent across initializers, assignments, function arguments, and returns. The lambda's resolved signature is preserved on the original expression and binding rather than being reconstructed from a copied candidate node.

This current approach uses deferred contextual inference: a lambda may begin with `Infer` parameters and only become fully concrete when a call site or expected type provides enough information. This is a practical prototype for first-class lambda values, but it is intentionally not the final language model. It will eventually be replaced by true polymorphic lambda typing, in which the lambda can carry type variables or a more general function type without requiring a specific call context to finalize its shape.

### Multi-parameter lambda syntax

Multi-parameter lambdas are now implemented as a parameter list in the AST, parser, resolver, and lowering pipeline. The language supports zero-argument, single-argument, and multi-argument lambdas, and generated helper descriptors use the full parameter list for each lambda shape.

The current implementation remains a prototype for deferred contextual inference and should eventually be superseded by true polymorphic lambda typing, but the structural support for multiple parameters is already in place and the compiler emits shape-specific lowering accordingly.

### Shape identity and generated names

The compiler now uses `FunctionShapeKey` as a versioned recursive semantic identity. It encodes arity, parameter order, return type, nested function signatures, nullability, generic arguments, and nominal names without including the source function name. Generated interface names are derived separately from a SHA-256 digest of that key, and the compiler checks that a generated name has not already been associated with a different full key.

The JVM method descriptor remains a separate lowering. For example, `String` and `String?` have different semantic shape keys but currently lower to the same JVM reference descriptor; nullable primitives lower to wrapper descriptors. This separation keeps source type identity from being inferred from the generated interface name or JVM ABI.

Nominal descriptors currently contain only a type name, not a package/module-qualified symbol identity. The key therefore distinguishes the names the current type model provides, but package-qualified identity must be added when the class/module system defines it. Canonical encoding generic arguments does not imply that generic JVM lowering is implemented.

### Output paths are compiler-specific

Shape interfaces are currently written directly to `target/classes`, while `ZeronMain.class` is written relative to the process working directory. This works for the current local prototype but couples compilation to one layout and can leave stale generated classes after a source change.

Generated artifacts should eventually use one configured output root and a defined cleanup or incremental-build policy. Consumers should be able to load the main class and all generated interfaces from that output consistently.

## Recommended Direction

Keep the current JVM model: a generated SAM interface per unique structural function shape, a static implementation helper per lambda expression, and `LambdaMetafactory` for runtime object creation. Captured values belong to the factory call-site descriptor and implementation-helper descriptor, not the SAM shape. This separates language-level function types from implementation details and avoids hand-generating one concrete class for every lambda expression.

Address the remaining work in this order:

1. **Canonicalize function-shape identity: implemented baseline.** `FunctionShapeKey` is recursive and versioned; SHA-256 names are separate from semantic equality, and the compiler checks full keys for generated-name collisions. Extend nominal identity when packages/modules exist and evolve the encoding version if its canonical rules change.
2. **Make lambda typing first-class: implemented as a prototype.** Lambda signatures are now resolved and retained on the original expression and binding, and contextual typing/inference remain consistent across declarations, assignments, calls, and returns without relying on detached copied lambda nodes. This implementation uses deferred contextual inference for `Infer`-parameter lambdas and is suitable as an incremental step, but it is a temporary mechanism rather than the final semantics.
3. **Add multi-parameter lambdas: implemented.** The AST, parser, resolver, and lowering logic now support zero-argument, single-argument, and multi-argument lambda forms, and they lower each shape to the appropriate generated functional interface.
4. **Specify capture semantics before classes: implemented.** Captures are immutable by default: a lambda may close over immutable values, but it must not implicitly capture and mutate stack-scoped locals. Mutation must happen through explicitly shared heap state such as objects or a dedicated cell/reference abstraction.
5. **Introduce polymorphic lambdas as the long-term model.** Replace deferred, call-site-only inference with a true polymorphic lambda type system in which a lambda can be typed and reasoned about as a generic function value before a specific invocation fixes its argument types. This is the eventual replacement for the current infer-then-context approach.
6. **Specify and implement capture lowering: implemented prototype.** Immutable captured values are passed through the `invokedynamic` factory descriptor and bound to the runtime function object; static helpers receive captures before the lambda's declared parameters. Explicit shared mutable cells remain future work.
7. **Decouple artifact output.** Route generated interfaces and the main class through a shared compiler output configuration, and define stale-artifact handling.
8. **Expand behavior tests.** Cover same-shape lambdas with different bodies, zero and multiple parameters, primitive and reference types, lambdas stored and returned as values, nested lambdas, immutable captures, explicit shared mutation, and repeated/incremental compilation. Verify both program output and emitted descriptors/call instructions.

## Acceptance Criteria for Broader Lambda Support

Lambda support should not be considered complete solely because a lambda can be passed inline to a function. The broader design should demonstrate that:

- Function signatures have stable, collision-free runtime identities.
- Function values work consistently in all supported expression contexts.
- Captures follow specified value and mutation semantics.
- Multiple parameters and JVM wide-slot values lower correctly.
- Generated interfaces and the main class are emitted and loaded from the configured output.
- Runtime tests and bytecode inspection agree on each supported case.
