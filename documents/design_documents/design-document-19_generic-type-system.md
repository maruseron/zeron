# Generic Type System: Current Semantics and Roadmap

## Status and scope

This document is the current cross-cutting overview of Zeron's generic type system. It consolidates
implemented features, known limitations, acceptance criteria, and a roadmap. Detailed feature
decisions remain in their respective ZEPs and design documents; this document does not replace them.

The generic system is intentionally bounded rather than a complete implementation of System F or a
general-purpose constraint solver. Source-level generic identities are preserved for checking and
library metadata, while JVM method descriptors erase type parameters unless a bridge or hidden
evidence parameter is required.

Pattern matching has its own design document. Its generic-pattern erasure direction is static-only:
generic arguments do not distinguish runtime cases, and an unknown or erased scrutinee cannot be
narrowed to a specific generic application by a pattern. An explicit safe projected view may still
be used for a raw-class test, with the binding retaining that projection. See
[design-document-16_patterns-and-pattern-matching.md](design-document-16_patterns-and-pattern-matching.md).

## Current model

Generic parameters are scoped to their declarations. Resolution substitutes source-level type
arguments through callable, nominal, nullable, reference, array, and function descriptors. Calls
infer substitutions from supported argument shapes and contextual information, validate bounds, and
retain the resolved source types for subsequent checking. The compiler emits erased JVM
representations and uses casts, boxing, unboxing, generated function adapters, or hidden evidence
where required.

Unannotated generic parameters are invariant. Generic classes and contracts may declare checked
covariant parameters with `out`; use-site `out T` and `in T` projections provide restricted views of
invariant types. These mechanisms are distinct and are specified in
[ZEP-0051](../zeps/ZEP-0051.md) and [ZEP-0052](../zeps/ZEP-0052.md).

Contract bounds retain applied arguments during source checking even when their JVM representation
is erased. Generic functions, methods, classes, and contracts use the implemented contract-bound
and hidden-evidence model described in
[design-document-15_type-class-witnesses-and-generic-bounds.md](design-document-15_type-class-witnesses-and-generic-bounds.md).
Higher-kinded constructor bounds are the first-order subset specified by
[ZEP-0019](../zeps/ZEP-0019.md).

Higher-kinded contracts may provide receiver-based or receiverless non-static default operations.
Their bodies can use the declaring contract's operations and operations from its transitive
`requires` graph. Explicit implementations override defaults; unrelated inherited defaults require
an explicit implementation to resolve the conflict. Defaults are compiled as static helpers on the
contract artifact and remain available to separately compiled clients. See
[ZEP-0078](../zeps/ZEP-0078.md).

Generic type parameters admit non-null, read-only arguments by default. `T: nullable` explicitly
permits nullable arguments, and `T: mut` permits mutable-reference arguments without granting
mutation operations to the generic body. `nullable` and `mut` may be combined; `effect` is exclusive
with both and restricts a parameter to effect types for exact propagation through `raise` and
`raises`. A mutable view of an effect value is normalized to its nominal effect identity rather than
propagated as part of the effect type. Generic raise sites perform a runtime marker check because
type arguments erase on the JVM.
Mutable admissibility is checked at each generic argument boundary; nested generic applications
enforce their own parameter policies. Imported Java generic parameters retain their existing
nullable- and mutable-admitting behavior because Java declarations do not provide reliable
nullability or capability metadata.

## Feature and limitation matrix

| Area | Current state | Limits and follow-up |
| ---- | ------------- | -------------------- |
| Generic functions and methods | Implemented. Calls accept explicit type arguments or infer them from supported arguments and contextual lambdas. Generic method signatures compose with receiver substitutions. | No general subtype solver or overload search. A type variable left unresolved by supported constraints requires an explicit argument. Expected result types alone do not generally infer function-call type arguments. |
| Generic classes and contracts | Implemented. Type arguments are substituted through members, constructors, conformance, and library metadata. Constructor calls can use argument and expected-type constraints. | No implicit raw view. Inference is bounded and does not compute an arbitrary common supertype. Standalone class references and later statements do not provide constraints. |
| Declaration-site variance | Implemented for checked covariance using `out`; unannotated parameters remain invariant. Unsafe positions are rejected. | Declaration-site contravariance is not part of the implemented model. Arrays, mutable references, writable properties, and generic effects retain their documented invariance rules. |
| Use-site projections | Implemented for `out T` and `in T` generic applications, including supported imported Java generic signatures and wildcard mappings. | Capture is limited to the supported member-resolution model. Raw Java types, generic arrays, and unsupported Java signature forms remain unsupported. |
| Contract bounds and witnesses | Applied contract bounds, multiple bounds in supported declarations, HKT constructor-family evidence, and non-nominal witnesses are implemented. | Mutating bound calls require a mutable receiver view and admissible arguments; `T: mut` admits mutable-reference arguments but grants no operations. Generic operators require explicit `Numeric<T>` or `Fractional<T>` bounds. The witness solver rejects overlaps it can identify; complete generic-pattern overlap analysis remains follow-up hardening. |
| Higher-kinded abstractions | The first-order constructor kind `F<_>`, constructor bounds, associated evidence, and receiver-based or receiverless non-static default operations are implemented. Default bodies can use operations from their contract's transitive `requires` graph and are available across compiled-library boundaries. | Static HKT defaults, unrestricted higher-kinded polymorphism, and type-level computation are not supported. Unrelated inherited defaults need an explicit implementation to resolve conflicts. |
| Generic function values | Generic named functions can be explicitly specialized or specialized from a fully constraining expected function type. A constrained rank-1 scheme is implemented for supported immutable `let` lambda initializers. | General polymorphic values, nested schemes, mutable polymorphic bindings, explicit `forall`, partial function specialization, and arbitrary lambda-valued expressions remain unsupported. |
| Type inference | Bounded structural inference is implemented for functions, methods, constructors, arrays, function types, and supported variance/projection contexts. Conflicts and unresolved variables are diagnosed. | Inference is not a general-purpose constraint solver. Inference from result types alone and common-supertype inference are not general features. Exact variance/projection inference boundaries should be maintained by tests as the implementation evolves. |
| Java generic interoperability | Supported class/member signatures, generic methods and constructors, supported bounds, wildcard projections, explicit/inferred method arguments, and erased invocation are implemented by ZEP-0048. | Raw types, generic arrays, unsupported bounds/signatures, generic `throws` variables, inherited-member discovery, and Java callback/SAM conversion remain unsupported. |
| Generic admissibility markers | Implemented in ZEP-0010, ZEP-0055, and ZEP-0062. Nullable and mutable-reference arguments are rejected by default; `nullable` and `mut` opt in independently, while `effect` is exclusive. The API index preserves these markers. | Unresolved generic handler cases and generic effect declarations remain unsupported. Numeric operations use explicit `Numeric<T>` and `Fractional<T>` bounds; a closed-set `numeric` marker is not part of the design. |
| Generic runtime tests | Statically justified `is` and `as?` targets for Zeron generic classes/contracts are implemented without reification. Safe `out Any?` views from `Any`/`Any?` retain their projection. | Ambiguous covariant applications, checked `as`, and Java generic relationship proofs remain unsupported. |
| Runtime representation | Generic methods use erased JVM descriptors. The compiler inserts boundary casts and boxing/unboxing, emits callback adapters, and passes hidden contract evidence where needed. Source-level generic signatures remain in class metadata and the versioned Zeron API index. | Applied type arguments are not generally reified. JVM class identity alone cannot distinguish `C<A>` from `C<B>`. |
| Runtime type identity | No general runtime reification model is implemented. | ZEP-0015 remains Draft. Reification has ABI, runtime-cost, and separate-compilation consequences and should be justified by concrete runtime features. |

The table is a status summary, not a promise that every combination of implemented features has
already been tested. A feature is not complete merely because its basic syntax resolves; variance,
projections, bounds, inference, erasure, callbacks, and compiled-library boundaries must compose
soundly.

## Roadmap

Roadmap items are ordered by dependency and risk, not assigned release targets. A later step should
not be taken as a commitment to implement every deferred feature.

### 1. Stabilize and document the current system

- Keep this matrix aligned with the parser, resolver, compiler, and API-index behavior.
- Reconcile older feature documents with implemented declaration-site covariance, use-site
  projections, and Java generic method/constructor support.
- Maintain a focused conformance suite covering invariant, covariant, and projected applications;
  nested substitutions; applied bounds; generic constructors; callback adaptation; and separate
  compilation.
- Document the exact points where inference is intentionally insufficient and requires explicit
  type arguments.

### 2. Close soundness and composition gaps

- Test variance-position validation through nested generic types, function parameters/results,
  nullable and mutable-reference wrappers, properties, bounds, and contract implementations.
- Test the composition of declaration-site variance and use-site projections, including rejected
  unsafe reads or writes.
- Exercise inference against those assignability rules and ensure it neither invents a common
  supertype nor silently erases conflicting source-level constraints.
- Enforce the accepted static-only generic-pattern contract: generic arguments must be justified by
  the scrutinee's static type, and cases that differ only by erased generic arguments are not
  distinct runtime cases.
- Preserve ZEP-0053's entailment boundary with positive and negative tests for exact applied
  conformances, covariance through nested generic arguments, safe projections, and library-loaded
  declarations.

### 3. Decide whether broader polymorphic values are needed

- Evaluate nested schemes, polymorphic values beyond direct immutable `let` initializers, mutable
  bindings, partial function specialization, and explicit `forall` as separate use cases.
- Any accepted extension must define capture, effects, bound/evidence selection, specialization
  identity, recursion, and separate-compilation behavior before implementation.
- Preserve the current monomorphic runtime representation of each specialized closure unless a
  feature explicitly requires another model.

### 4. Consider runtime reification separately

- Advance ZEP-0015 only for concrete behavior that cannot be expressed with static generic identity
  and erased runtime classes.
- Specify which source distinctions are observable, how type-variable identity is propagated
  through calls, how descriptors cross library boundaries, and what ABI/runtime overhead is
  accepted.
- Keep runtime type identity distinct from contract-witness evidence.

## Acceptance criteria

### Current-system conformance

- Every implemented generic feature has positive and negative tests for its stated boundary.
- Declaration-site variance and use-site projections agree across assignability, member access,
  method calls, constructors, bounds, and contract conformance.
- Generic inference is deterministic: valid constraints resolve consistently; conflicting or
  unresolved constraints produce diagnostics rather than permissive erased fallbacks.
- Type substitution preserves applied source identity recursively through supported descriptor
  forms, while erasure produces the JVM descriptors expected by generated bytecode.
- Generic callback arguments and results remain correct across inline calls, stored and returned
  values, nullable and mutable views, and erased nominal boundaries.
- The versioned library index preserves all generic metadata required for separately compiled
  clients; source-built and library-loaded uses resolve equivalently.
- Runtime behavior, including pattern matching, does not claim to distinguish applied generic types
  unless an explicit, separately specified reification mechanism is implemented. An unknown
  scrutinee may be tested as a raw class only when the resulting binding has a statically sound
  projected view.

### Future extensions

- Each extension names motivating APIs and distinguishes admissibility constraints, behavioral
  contract bounds, inference rules, and runtime evidence.
- Its rules compose explicitly with variance, projections, nullability, bounds, callbacks, effects,
  and separate compilation where applicable.
- Compatibility, diagnostics, generated descriptors, API-index encoding, and runtime costs are
  specified before implementation.
- Acceptance tests cover both source resolution and emitted/runtime behavior; bytecode-sensitive
  changes verify erased descriptors and bridges.

## Related documents and proposals

- [Generic functions, historical implementation note](./design-document-07_generic-functions.md)
- [Classes and contracts](./design-document-05_classes-and-contracts.md)
- [Type-class witnesses and generic bounds](./design-document-15_type-class-witnesses-and-generic-bounds.md)
- [Patterns and pattern matching](./design-document-16_patterns-and-pattern-matching.md)
- [Java generic interoperability, ZEP-0048](../zeps/ZEP-0048.md)
- [Generic covariance, ZEP-0051](../zeps/ZEP-0051.md)
- [Use-site variance, ZEP-0052](../zeps/ZEP-0052.md)
- [Generic-aware runtime type tests and safe casts, ZEP-0053](../zeps/ZEP-0053.md)
- [Generic admissibility markers, ZEP-0010](../zeps/ZEP-0010.md)
- [Runtime reification of generic type witnesses, ZEP-0015](../zeps/ZEP-0015.md)
