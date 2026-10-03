# Small Language Features: Discussion Notes

## Status

This is a low-priority discussion note for nullable navigation, null fallback, and equality. It sketches
possible behavior and lists decisions that should be made before implementation. Nothing here commits
to syntax or semantics beyond behavior already present in the language.

Declaration-site class conformance to contracts is already implemented (`class C is Contract`); it is
not a proposed feature in this note. See [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

## Current Foundation

- Nullable types (`T?`), null checks, flow refinement, checked casts, and safe casts are implemented.
- `?.` is tokenized by the scanner but has no expression semantics. `??` and `??=` are not
  implemented.
- `==` and `!=` compare primitive values and strings by value. Reference comparisons currently use
  `Objects.equals`; generated classes inherit `Object.equals` unless behavior is supplied elsewhere,
  so nominal instances currently compare by identity.
- Compound assignments are supported for variables, but not for properties or array indices.

## Conditional Navigation: `?.`

Safe navigation would allow a property or method access on a nullable receiver without requiring an
explicit branch:

```zeron
let name: String? = person?.name;
let label: String? = person?.displayName();
```

When the receiver is null, the access is skipped and the expression produces null. Otherwise, the
ordinary property or method operation is performed. This fits nullable types, classes, contracts, and
existing flow typing without introducing a new type-system category.

Before implementation, pin down:

- Whether navigation is limited to properties and method calls, or also applies to indexing and
  other postfix operations.
- Whether a nullable result is flattened (`T??` is not currently a type) and how a `Unit` result is
  represented.
- Whether method arguments are evaluated only when the receiver is non-null; the natural choice is
  short-circuit evaluation.
- How mutable receiver requirements apply to `nullableReference?.mutatingMethod()`.
- How chained navigation and flow facts behave, while keeping properties and calls unstable across
  separate evaluations.

The compiler should evaluate the receiver once, branch around the access on null, and join a nullable
result. It should not duplicate a receiver expression that has side effects.

## Null Fallback: `??`

Null coalescing would select a fallback only when the left operand is null:

```zeron
let displayName = user.nickname ?? user.name;
```

This can make common nullable defaults more direct than an `if` expression. The operation should
short-circuit: evaluate the right operand only when the left operand is null.

Before implementation, pin down:

- Precedence relative to comparisons, `and`, and `or`, and whether it associates right-to-left.
- Whether the left operand must be nullable and whether `T? ?? T` always produces `T`.
- How unrelated branch types find a common result type, including `Any` and `Any?`.
- Whether `null ?? null` requires an explicit result type.
- Whether the result refines any source binding; the conservative choice is that it does not.

Flow analysis should match the short-circuit runtime behavior, and the resolver should use the same
branch-join rules as `if` expressions.

## Null-Fallback Assignment: `??=`

Null-fallback assignment would initialize a nullable binding only when its current value is null:

```zeron
let mut cached: String? = null;
cached ??= loadName();
```

This resembles existing variable-only compound assignment and may be useful for lazy defaults.

Before implementation, pin down:

- Whether the target is limited to mutable variable bindings initially; that is the conservative
  choice.
- Whether the right-hand side is evaluated only when the target is null.
- Whether the expression yields the assigned/current value or `Unit`.
- Whether property or indexed targets may be supported later, including rules to evaluate their
  receiver and index exactly once.
- How the assignment updates flow facts and whether a subsequent read is known non-null.

## Structural and Referential Equality: `==` and `===`

A distinct referential-equality operator could make object identity explicit while reserving `==` for
equality of values:

```zeron
left == right   // value equality
left === right  // same object identity
```

The current `==` behavior is mixed: primitive values and strings compare by value, while generated
nominal classes and arrays normally compare by identity through their inherited `Object.equals`.

Before changing this behavior, pin down:

- Which nominal types support structural equality: all classes, only explicitly opted-in types, or
  types implementing an equality contract.
- What `==` does when a type has no structural-equality implementation; implicit identity fallback
  or a compile-time error are both possible, but should not be mixed accidentally.
- Whether equality is recursive for fields/arrays, how cycles are handled, and whether field privacy
  affects the operation.
- Whether equality is available through `Any` and generic type parameters, and what constraints
  those operations require.
- Whether `===` accepts only reference-like operands, how null compares, and what the operator does
  for primitives.
- How equality remains consistent with null flow refinement and contract/interface views.

An equality contract is one possible foundation, but operator overloading and data/record types are
separate design decisions. The language should not silently derive equality from every private field
without an explicit decision about API stability and cycles.

## Suggested Order

1. Define a coherent equality contract alongside any future data/value-type design; avoid changing
   existing `==` behavior until compatibility is understood.
2. Specify and implement `??`, including short-circuit typing and flow behavior.
3. Add `?.` for properties and method calls, reusing nullable branch joins and enforcing receiver
   mutability.
4. Add `??=` for mutable variable bindings once its expression result and flow effects are settled.

The order can change if a concrete use case emerges. These features should reuse nullable and flow
analysis rather than add independent null-state rules.
