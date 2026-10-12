# Structural Equality and Hashing

## Status

This document records the agreed direction and implementation status for value equality and hashing.
`Eq<T>` and evidence-driven equality are now implemented for built-in scalar types, generic bounds,
nominal conformances, and explicit witnesses. Nullable values lift custom `Eq<T>` evidence, and sums
and nominal products use compiler-generated structural equality with generic component evidence.
Direct self-recursive sums and products are supported; mutually recursive declarations are not yet
synthesized as a recursive evidence group. Contract composition with `requires` is implemented. A
`Hashable<T>` contract skeleton exists
in the bundled source, but hash-evidence selection, implementations, hash laws, and hash-based
collections remain future work; explicit derivation is also deferred.

The direction is to make `==` and `!=` evidence-driven value comparisons, keep `===` as explicit
reference identity, provide equality for sums by comparing their case and payloads, and let other
types opt in through equality evidence. Hashing is a separate capability that depends on equality.
Explicit derivation remains a later stage rather than a prerequisite for the equality contract.

## Agreed direction

The work is staged:

1. **Implemented: establish `Eq<T>` and make `==`/`!=` use it.** The implicit fallback to
   `Objects.equals` was removed for types without equality evidence. `===` remains reference
   identity. Sums receive structural equality based on case identity and payload equality.
2. **Contract composition implemented.** `requires` composes applied contracts transitively,
   including members, declaration-site conformance, generic-bound evidence, constructors, and
   separate-compilation metadata.
3. **Complete `Hashable<T>` requiring `Eq<T>`.** The bundled declaration is a skeleton; define its
   hash result type, evidence selection, and implementation contract before hash-based collections
   require the bound. Equality-only operations continue to require only `Eq<T>`.
4. **Explore explicit contract derivation.** Decide whether and how a type declaration or witness
   declaration can request generated `Eq` and `Hashable` evidence. Derivation is opt-in for ordinary
   nominal types and must not be invented silently at a use site.

This order establishes the equality semantics before designing their hash-key extension. It also
avoids making equality-only values provide an irrelevant hash implementation merely to support
`==`.

## Equality contract and operators

`Eq<T>` represents value comparison between two values of the same applied type. A generic function
using equality must state that evidence as a bound:

```zeron
fn same<T: Eq<T>>(left: T, right: T): Boolean = left == right;
```

`Eq<T>` is declared in `zeron.lang` with the method `eq(other: T): Boolean`. The contract provides
one operation that answers whether the receiver and its argument are equal under the type's value
semantics. It does not expose ordering, hashing, or identity.

For resolved operands of a type `T`:

- `left == right` requires applicable `Eq<T>` evidence and evaluates that comparison.
- `left != right` is the logical negation of the same comparison; it does not define a second
  equality relation.
- If no evidence exists, resolution fails with a diagnostic directing the programmer to provide or
  import `Eq<T>`, or to use `===` if reference identity is intended.
- Equality does not silently fall back to JVM `Object.equals`, identity, or a runtime type test.
- `T?` equality is lifted: null equals null, null differs from a non-null value, and two non-null
  values compare with `Eq<T>`. Both operands are evaluated once.

Equality remains statically typed. After existing inference and flow refinement, operands must have
the same applied type. Equality provides no widening or common-type inference; callers explicitly
widen both values to a common type when desired.

Built-in evidence covers `Int`, `Float`, `Boolean`, `String`, and `Unit`, using compiler-generated
method handles. Floating-point equality treats NaN as equal to NaN and distinguishes positive from
negative zero, consistently for direct and generic comparisons. `Unit` values are all equal.

`===` remains a separate operator for compatible reference-valued operands. It does not require
`Eq<T>`, does not call the equality witness, and must not be made available for primitive values
merely because they have `Eq<T>`.

## Sum equality

Nominal sums are intended to have out-of-the-box value equality. Two values of the same applied sum type compare
equal exactly when:

1. They belong to the same declared case; and
2. Corresponding payloads compare equal under `Eq` evidence for each payload type.

Different cases compare unequal without comparing payloads. A zero-payload case compares equal to
another value of that same case. This is value equality; it does not alter the existing singleton
identity guarantee for nullary cases, and `===` can still distinguish identity where applicable.

For generic sums, equality is conditional on payload equality evidence. A value of `Option<T>` can
use `==` when the relevant `Eq<T>` evidence is available; a sum remains constructible and matchable
when payloads lack `Eq`, but equality is then unavailable. The compiler-generated sum equality
evidence must recursively select evidence for each payload and preserve the applied generic types
during resolution.

The generated equality must inspect only the declared sum tag and payloads. It must not compare
generated JVM class names as source semantics, invoke arbitrary methods discovered at runtime, or
include unobservable representation details.

The compiler synthesizes sum equality evidence from payload evidence and emits the comparator on the
sum interface, so existing sum exports in the library index are sufficient for separately compiled
consumers. A user-defined witness for `Eq<Sum<...>>` is rejected as a conflict; structural sum
equality cannot be overridden or disabled. Direct self-recursive payloads (including nullable
self-recursive tails) call the same comparator recursively. Indirect mutual recursion between
different sum declarations currently fails evidence resolution rather than creating a cyclic
comparator-handle graph.

## Ordinary nominal types and derivation

Ordinary classes do not gain structural equality merely because they have fields. Without `Eq<T>`
evidence, `==` is unavailable for them; users use `===` when they intend identity or provide an
explicit equality implementation.

## Relational comparison operators

Custom types opt into `<`, `<=`, `>`, and `>=` through `zeron.lang.Comparable<T>`, whose
`compareTo(other: T): Ordering` returns the `zeron.lang.Ordering` sum with nullary cases `LT`, `EQ`,
and `GT`. The operators preserve their conventional meanings: `<` accepts only `LT`, `<=` accepts
`LT` or `EQ`, `>` accepts only `GT`, and `>=` accepts `GT` or `EQ`.

Integer and floating-point relational comparisons continue to use their existing built-in lowering.
Other operands require applicable `Comparable<T>` evidence, including an explicit
`Comparable<T>` bound in generic code. Operands must have the same non-null applied type. This
contract is separate from `Eq<T>`: ordering does not imply equality, and `==` does not imply an
ordering.

The later derivation stage will consider explicit requests to synthesize contract evidence from
declared fields. Such a feature must define:

- Which fields participate, including private fields and initialized fields.
- How inherited or generated members participate, if those become possible.
- Whether mutable state is permitted and the consequences of changing a value after insertion into
  a hash collection.
- How generic fields create prerequisite bounds such as `T: Eq<T>`.
- How recursive references, cycles, nullable values, arrays, and `Any` are treated.
- How a derived implementation is represented in library metadata and selected across compilation
  boundaries.
- Whether a class can supply custom `Eq` while requesting derived `Hashable`, and how equality/hash
  consistency is guarded.

Derivation must be explicit and stable across library consumers. The compiler should not silently
invent evidence just because a particular use site requests it; that would make a type's capabilities
depend on the consumer's compilation context.

Sums are distinct from ordinary classes: structural `Eq` for sums is part of their built-in
algebraic value semantics, subject to payload evidence. This does not imply automatic structural
equality for arbitrary class fields.

## Hashing

Hashing is not required for equality. `Hashable<T>` is a later, stronger capability that requires
`Eq<T>` and supplies a hash operation. Its central law is:

> If `a == b`, then `hash(a) == hash(b)`.

The converse is not required; hash collisions are valid. The compiler generally cannot prove this
law for user-written methods, so it must be documented as an implementation obligation and tested for
built-in and generated implementations.

Hash-based collections should require `Hashable<T>`, not merely `Eq<T>`. Generic code that only
compares values should require `Eq<T>` and should not require hashing. Contract composition lets
clients use one `Hashable<T>` bound that entails `Eq<T>`, without conflating the capabilities.

For sums, generated hashing should include a stable distinction for the declared case and combine
the hashes of that case's payloads. A sum is hashable only when the payload types satisfy the
required `Hashable` evidence. Hash values are not a serialized representation or a stable
cross-process identifier unless a separate guarantee is designed.

Mutable values are a key concern: changing equality-relevant state after insertion into a hash-based
collection can make an entry unfindable. The type system may not be able to prevent this generally.
Hashable implementations and collection documentation must state the stability requirement.
`Stable<T>` could be a compiler-derived admissibility constraint, but immutable views do not prevent
mutation through other aliases; guaranteeing stability may require deep copying reachable mutable
state. Whether to require this constraint, and how to establish it soundly, remain open.

Zeron `Eq`/`Hashable` evidence does not automatically implement Java `Object.equals`/`hashCode`.
Java interoperability may need an explicit bridge policy. Any future bridge must keep equality and
hash code consistent and account for generic evidence erased at JVM boundaries. Until then, use of
Zeron value equality as a Java hash key must not be implied.

## Contract composition

`Hashable<T> requires Eq<T>` is the intended relationship once hashing is added. The settled
composition syntax and semantics—including transitive applied requirements, inherited defaults,
constructor requirements, witness forwarding, cycles, and JVM interface inheritance—are documented
in [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

## Compatibility and migration

Changing `==` for references and unconstrained generic parameters is a source-compatibility change:
programs currently relying on the default `Objects.equals` fallback will need `Eq` evidence, while
programs intending identity should use `===`. The migration must update error documentation, bundled
APIs, tests, and design notes together.

The rollout should not retain a silent compatibility fallback. A temporary opt-in migration warning
or staged language version may be considered if the affected code volume warrants it, but after the
transition `==` must have one statically determined value-equality meaning.

Compiled-library metadata must preserve enough information to export built-in, witnessed, and
generated evidence, including generic prerequisites. API-index versioning and standard-library
compatibility policy must be evaluated when the implementation representation is selected.

## Settled `Eq<T>` decisions

The following choices define the implementation contract for the first equality stage:

1. **Contract surface:** `zeron.lang.Eq<T>` declares `eq(other: T): Boolean`.
2. **Built-in evidence:** `Int`, `Float`, `Boolean`, `String`, and `Unit` use compiler-known
   evidence. Direct comparisons lower to specialized operations; generic bounds receive hidden
   evidence handles to the same semantics.
3. **Operand compatibility:** after existing type inference and flow refinement, both operands must
   have the same applied type. Equality adds no implicit widening or common-type inference; callers
   explicitly widen both values when they intend to compare at a common supertype.
4. **Missing and ambiguous evidence:** non-null equality requires one applicable `Eq<T>` instance.
   Missing evidence is a resolution error directing the programmer to provide/import an Eq bound,
   nominal conformance, or witness, or to use `===` when identity is intended. Multiple applicable
   witnesses are an ambiguity error; equality does not pick one by declaration order.
5. **Nullable lifting:** `T?` equality is lifted from `Eq<T>`: null equals null, null differs from
   non-null, and two non-null values use `Eq<T>`. Each operand is evaluated exactly once. Lowering
   stores operands in temporaries, tests nulls, and invokes the evidence only on the both-non-null
   path. This applies to user-defined evidence as well as built-ins.
6. **Floating-point semantics:** NaN equals NaN; positive zero differs from negative zero. Direct,
   nullable, and generic equality must agree on these rules.
7. **Sum evidence:** the compiler synthesizes `Eq<Sum<...>>` evidence. It compares the declared
   case first, then corresponding payloads with their selected Eq evidence; different cases are
   unequal without payload comparisons. Generic payload requirements are represented as hidden
   evidence prerequisites and preserved in compiled-library metadata. Recursive payloads use the
   synthesized equality recursively. A user-defined witness for a sum's `Eq<Sum<...>>` is rejected
   as a conflict; the structural definition is coherent and cannot be overridden.
8. **Constant folding:** only built-in scalar literal equality may be folded, using the same
   semantics as runtime equality. The compiler never executes user witnesses or synthesized sum
   equality while compiling.

There is no universal `Eq<Any>` instance: an erased `Any` value has no statically known equality
semantics. Compare values at a statically known Eq-bearing type, or use `===` for reference identity.

## Related design documents

- [Small language features](design-document-10_small-miscelaneous.md) records current equality and
  identity behavior pending migration.
- [Sum types, products, and newtypes](design-document-14_sum-product-types-and-newtypes.md) defines
  current sum representation and matching.
- [Type-class witnesses and generic bounds](design-document-15_type-class-witnesses-and-generic-bounds.md)
  defines the existing witness and applied-bound foundation.
- [Classes and contracts](design-document-05_classes-and-contracts.md) defines declaration-site
  conformance and the current contract boundary.
