# Small Language Features: Discussion Notes

## Status

This note records the implementation status and semantics for nullable navigation, null fallback, and
equality, including remaining design questions.

Declaration-site class conformance to contracts is already implemented (`class C is Contract`); it is
not a proposed feature in this note. See [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

## Current Foundation

- Nullable types (`T?`), null checks, flow refinement, checked casts, safe casts, and safe member
  navigation are implemented.
- `?.` is implemented for property reads and method calls. `??` is implemented with
  right-associative short-circuit evaluation, nullable-path flow joins, and common-branch typing.
  `??=` is implemented for mutable local bindings.
- `==` and `!=` compare primitive values and strings by value. Reference comparisons currently use
  `Objects.equals`; generated classes inherit `Object.equals` unless behavior is supplied elsewhere,
  so nominal instances currently compare by identity.
- `==` and `!=` are also allowed on matching generic type parameters without an explicit equality
  constraint. At generic erasure they use `Objects.equals`; other operators on unconstrained type
  parameters remain rejected.
- Compound assignments are supported for variables, but not for properties or array indices.

## Conditional Navigation: `?.`

Safe navigation allows a property or method access on a nullable receiver without requiring an
explicit branch:

```zeron
let name: String? = person?.name;
let label: String? = person?.displayName();
```

When the receiver is null, the access is skipped and the expression produces null. Otherwise, the
ordinary property or method operation is performed. This fits nullable types, classes, contracts, and
existing flow typing without introducing a new type-system category.

The receiver is evaluated exactly once. A null receiver skips the member operation and, for method
calls, skips argument evaluation. Otherwise, the ordinary member operation is performed. The result
is nullable; an already-nullable result stays nullable rather than introducing a nested nullable
type. `Unit` results use the existing nullable reference representation.

Navigation applies only to property reads and named method calls, not indexing or assignment.
Arguments are resolved on the non-null receiver path, and resulting flow facts are joined with the
null path. A nullable mutable reference (for example, `&Counter?`) retains its mutable capability for
mutating calls; a read-only nullable receiver cannot call a mutating method. Safe navigation on a
statically non-null receiver is accepted as redundant. Chained `?.` operations are supported.

## Null Fallback: `??`

Null coalescing selects a fallback only when the left operand is null:

```zeron
let displayName = user.nickname ?? user.name;
```

This makes common nullable defaults more direct than an `if` expression. The operator is lower
precedence than `or` and higher precedence than assignment, and it associates right-to-left:

```zeron
a ?? b ?? c       // a ?? (b ?? c)
value ?? a or b   // value ?? (a or b)
```

The left operand must have a nullable flow-effective type or the `Null` type; statically non-null
left operands are rejected. The non-null left value and fallback are joined with the same common-type
rules as `if` expressions. Thus `Int? ?? Int` produces `Int`, `Int? ?? null` produces `Int?`, and
unrelated non-null alternatives use `Any`. If either joined alternative remains nullable, the result
is nullable too (for example, `Int? ?? String?` produces `Any?`). `null ?? value` has the fallback's
type, while `null ?? null` is rejected because its result type cannot be inferred.

Evaluation is left-to-right and short-circuiting: the fallback is resolved and evaluated only on the
left-null path. Direct local and parameter reads receive null/non-null branch facts while resolving
the fallback. The result joins facts from both paths; it does not itself refine the original binding.
The compiler evaluates the left expression once and branches to the fallback only when that value is
null.

The scanner, parser, resolver, compiler, and runtime/resolution tests implement this contract. The
null-only inference case and redundant coalescing are diagnosed during resolution.

## Null-Fallback Assignment: `??=`

Null-fallback assignment initializes a nullable local binding only when its current value is null:

```zeron
let mut cached: String? = null;
cached ??= loadName();
```

The target must be a mutable local binding with a nullable declared type. Top-level
bindings, immutable bindings, properties, and indexed targets are not supported. The expression
yields the target's nullable type and value, consistent with existing assignment expressions; it
does not yield `Unit`.

The current value is read once. A non-null current value is returned unchanged, and the right-hand
side is evaluated only on the null path. When a non-null fallback is assigned, the binding is
refined to non-null after the expression. A nullable fallback may leave it nullable, so no
non-null refinement is retained. Flow facts are joined between the unchanged non-null path and the
assignment path; assignment writes invalidate refinements within enclosing short-circuit expressions.

## Structural and Referential Equality: `==` and `===`

Existing `==` and `!=` semantics are preserved. They compare primitive values and strings by value
and use `Objects.equals` for other reference values. Generated nominal classes normally inherit
`Object.equals`, so they compare by identity unless behavior is supplied elsewhere.

`===` explicitly checks reference identity:

```zeron
left == right   // value equality
left === right  // same object identity
```

`===` accepts classes and contracts, `Any`, arrays, function values, and `String`, including their
nullable forms. A null literal can be compared with any of these reference-valued types. Nullable
primitive wrappers and `Unit` are rejected so identity does not depend on boxing or singleton
representation. Non-null operands must have compatible reference views; for example, a class can be
compared through a contract it implements or through `Any`. Unconstrained type parameters and
unrelated reference types are rejected. A null literal compared with itself is rejected because no
reference type can be inferred.

The operator does not call `equals` and never inspects fields. It has the same equality precedence as
`==`. Null checks using `=== null` participate in the existing null-flow refinement.

Structural equality remains deferred. If added later, it should require an explicit equality
contract or opt-in; the language must not derive it silently from private fields. Recursive values,
cycles, `Any`, and generic constraints remain open design questions for that separate feature.

### Equality on generic type parameters

Generic functions may compare two operands of the same unconstrained type parameter with `==` or
`!=`. This narrow exception does not add general operator constraints or overload resolution.
Resolved operand types must still match exactly, and arithmetic or relational operations on
unconstrained type parameters remain unsupported.

Generic type parameters erase to reference values, so their equality currently lowers through
`Objects.equals`. This can differ from direct primitive `Float` comparison for NaN and signed zero.
Whether `Float` equality should consistently follow boxed `Double.equals` semantics remains open.

## Suggested Order

1. **Null coalescing: implemented.** Keep short-circuit typing and flow behavior aligned with `if`
   expression joins.
2. **Safe navigation: implemented.** `?.` supports property reads and method calls, reusing nullable
   branch joins and enforcing receiver mutability.
3. **Null-fallback assignment: implemented.** `??=` is limited to mutable local bindings, with its
   expression result and flow effects specified above.
4. **Reference identity: implemented.** `===` compares compatible reference values without calling
   `equals`; `==` and `!=` retain their existing behavior.
5. Define a coherent structural-equality contract alongside any future data/value-type design; do
   not change existing `==` behavior until compatibility is understood.

The order can change if a concrete use case emerges. These features should reuse nullable and flow
analysis rather than add independent null-state rules.
