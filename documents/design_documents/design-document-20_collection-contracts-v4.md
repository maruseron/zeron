# Design Document: Collection Contracts and Lazy Sequences

| Field | Value |
| ----- | ----- |
| Status | Discussion draft (revision 4: language constraints confirmed; effect rows ([ZEP-0086](./ZEP-0086.md)) considered) |
| Author | Marcelo Rodríguez |
| Created | 2026-11-02 |
| Scope | `List`, `Map`, `Set`, `Stream`, and the capability contracts between them |

## Purpose

This document discusses whether `List<T: mut>`, `Map<K: Hashable<K>, V: mut>`, and
`Set<T: Hashable<T>>` should move from classes to contracts, so that specialized implementations
(`ArrayList`, `DoublyLinkedList`, `ConstructList`, `Church`, `HashMap`, `IdentityHashMap`,
`TreeMap`, `RedBlackMap`, `HashSet`, and others) can coexist behind one API. It also discusses
whether `Stream<T: mut>`, or a contract for lazy sequences, is worth the same treatment, and how it
relates to `List`.

It is a discussion document, not a proposal. It records what the sources currently do, the design
axes, the options, and a tentative recommendation for each, and ends with experiments. The proposal
is [ZEP-0094](../zeps/ZEP-0094.md), which is accepted and settles the questions this document leaves
open; this document remains the discussion record.

## Current state

This section is taken from `list.zn`, `map.zn`, `set.zn`, `stream.zn`, and the definition of
`Iterable<T>`. It supersedes the assumptions of the first revision of this document, several of
which were wrong.

**`Iterable<T: mut>`** has one abstract method, `iterator(): &Iterator<T>`, and two defaults:
`forEach(consumer)` and `stream(): Stream<T> = Stream.from(this)`. `Iterator<T>` is pull-based:
`mut next(): Step<T>`, with `Step.Yield(value)` and `Step.Done()`.

**`List<T: mut>`** is a final, array-backed class. It conforms to `Iterable<T>`, `Sink<T>`,
`RandomAccess<T>`, `Sequenced<T>`, `Reversible<List<_>>`, `Foldable<List<_>>`, and
`Collector<List<_>>`. Its members are `size`, `isEmpty`, `at(index): Option<T>`, `first`, `last`,
`replaceAt`, `add` (returns `Boolean`), `insert`, `removeAt`, `clear`, and `foldLeft`. Its
constructors are `of(T...)`, `empty()`, and `fromArray`. It has no `map` or `filter`, and no `Eq` or
`Hashable`. Its iterator reads through the class's own `at` and `size`.

**`Map<K: Hashable<K>, V: mut>`** is a final hash-table class with chained, immutable `MapEntry`
nodes. **Each map instance holds its own `hashFunction` and `equalityFunction`**, supplied through
`withOperations`; `empty()` supplies the `Hashable` and `Eq` of `K`. Its members are `size`,
`isEmpty`, `containsKey`, `get`, `put`, `putIfAbsent`, `compute`, `computeIfAbsent`, `remove`,
`clear`, `iterator()` over `Pair<K, V>`, and `keyIterator()`. It has no `Eq` or `Hashable`. The
`Hashable` bound is on the class.

**`Set<T: Hashable<T>>`** is a final class that wraps a `&Map<T, Unit>` (`withMap`). It conforms to
`Iterable<T>` and `Sink<T>`, and iterates through `keyIterator()`.

**`Stream<T: mut>`** is a final class that holds a single `Iterable<T>` source. Each operator
(`map`, `filter`, `take`, ...) wraps the source in an internal `Iterable` class. It conforms to
`Iterable<T>`, `Monad<Stream<_>>`, and `Foldable<Stream<_>>`. The documentation states that each
traversal starts a fresh iterator from the source, so a stream can be traversed again whenever its
source can.

**Capability contracts already exist.** They are organized as type-class style instances over type
constructors such as `List<_>`, with `implementation` and `delegating implementation` blocks:

- `RandomAccess<T>` requires only `at(index): Option<T>`, documented as `O(1)`.
- `Sequenced<T>` requires `first()` and `last()`; `last` need not be constant time.
- `Sink<T>` requires `mut add(value): Boolean`.
- `Reversible<C<_>>` requires `mut reverse` (in place) and `reversed` (copy); `Sortable<C<_>>`
  requires `mut sortBy` and `sortedBy`, with `sort` and `sorted` as defaults.
- `Collector<C<_>>` requires `empty<A>(): &C<A>` and `mut add`, and is the generic builder.
- `Foldable<F<_>>` requires `foldLeft`, with `count` and `isEmpty` as defaults. `Functor`,
  `Applicative`, `Monad`, and `Traversable` exist beside it; `List` has no `Functor` instance.
- Contracts may declare `static` members (`Monoid.empty`).

Mutation is therefore already expressed by small capability contracts named for the operation
(`Sink`, `Reversible`, `Sortable`), not by a bundle named for a state.

## What this changes

1. **The abstraction layer largely exists.** Most of what a contract migration would introduce is
   already available as fine-grained capabilities. The open question is narrower than "should
   collections be contracts": it is whether a *name* for a bundle of capabilities is needed, and
   what happens to the existing class names.
2. **Key equivalence is already per instance.** A map carries its own hash and equality functions.
   The `Hashable<K>` bound on the class is what prevents an identity-keyed map; the machinery for
   one is already there.
3. **`Set` is already a wrapper over a map.** It can be backed by any map implementation without a
   set contract at all.
4. **`Stream` is already a lazy view over `Iterable`, and re-traversable.** `Iterable` is already
   the contract for lazy sequences.
5. **`List` has no equality, hashing, or transformations.** Equality across implementations is a
   missing feature, not a migration risk. Transformations already go through `stream()`.
6. **Existing capability instances are keyed to the class.** `Reversible<List<_>>`,
   `Foldable<List<_>>`, and `Collector<List<_>>` are tied to the class, and `Reversible` uses its
   private fields.
7. **Mutation is already expressed as capabilities.** `Sink.add`, `Reversible.reverse`, and
   `Sortable.sort` are named for what they do. A `MutableList` bundle would be the first contract
   named for a state (see the section on mutation below).

## Why consider contracts at all

A single class per collection means one representation and one set of complexity trade-offs.
Concretely:

- A persistent list (`ConstructList`) cannot be a `List`, so it cannot be passed to an API that takes
  one.
- A sorted map cannot satisfy `Map<K: Hashable<K>, V>`: it needs ordering, not hashing.
- An API that only reads a list takes the concrete array-backed class and so excludes every other
  representation. Code that needs to accept "anything ordered" must spell capability bounds
  directly.

The existing capability contracts already address the last point for code willing to name them.
What remains is the first two, and the ergonomics of naming.

## Constraints from the language

1. **No inheritance.** Hierarchies are built from `requires` between contracts and conformance by
   final classes. Shared implementation comes from default methods.
2. **No `Self`.** Contract methods cannot return "the same implementation".
3. **Mutability is a view.** A contract with `mut` methods can only be implemented by a
   representation that can mutate. Mutation is already isolated in capabilities (`Sink`) and `mut`
   methods.
4. **Erasure.** Generic collections store erased elements; use-site adapters recover behavior.
5. **Static dispatch is a stated project value.** A change should not make the common case slower
   or less predictable without measurement.
6. **Effects.** Function types carry effect rows ([ZEP-0086](./ZEP-0086.md), implemented):
   `(T) -> R raises E` with `E: effect...`. Row variables are quantified on declarations only, a
   contract method may not widen its row (an open row needs a row parameter on the method or the
   interface), and at most one row variable may appear in a single `raises` list. The standard
   library has not yet adopted rows.
7. **Two kinds of contract that do not connect.** Ordinary contracts are parameterized by element
   types (`Iterable<T>`, `Sink<T>`). Capability contracts over type constructors (`Foldable<F<_>>`,
   `Reversible<C<_>>`, `Collector<C<_>>`) cannot bound their constructor parameter, and `requires`
   cannot relate one kind to the other in either direction. Method-level generics can carry bounds,
   including intersections (`T: A + B`) and constructor bounds (`G<_>: Applicative<G>`).

## Design axes

### 1. Options

**Option 0: keep the classes.** `List`, `Map`, and `Set` remain the default implementations; other
implementations conform to the capability contracts and are accepted by APIs written against
capabilities. No breakage. The cost is that there is no short name for "an ordered collection",
and `ConstructList` is not a `List`.

**Option 1: contracts take the plain names; classes are renamed.** `List<T>` becomes a contract and
the array-backed class becomes `ArrayList<T>`; likewise `Map`/`HashMap` and `Set`/`HashSet`. The
contract is a bundle of capabilities, so defining it is nearly free:

```zeron
// illustrative
public contract List<T: mut> requires Iterable<T> + Sequenced<T> { }
```

Mutation is not part of this bundle; see "Mutation without a `MutableList` bundle". The cost is
source breakage (constructors, mutation through the plain name, inference).

**Option 2: new names for the contracts, keep the class names.** Bundle contracts under new names
(for instance `Seq`), classes untouched. No breakage, and a name exists, but `ConstructList` is
again a list that is not a `List`.

**Direction: Option 1**, chosen by the project owner, since it makes `ConstructList`,
`DoublyLinkedList`, and `Church` genuine `List`s. Option 2 remains the compromise if breakage turns
out to be unacceptable. The facts to confirm are how much code constructs `List.new(...)` directly
and how often the plain names appear in signatures.

### Mutation without a `MutableList` bundle

Under Zeron's view model, a contract named `MutableList<T>` would offer no method through a
read-only view that `List<T>` does not already offer: its extra operations are reachable only
through `&MutableList<T>`. The word "Mutable" then repeats what `&` already says, and the bundle's
only unique content is a promise that the implementation can be mutated. The friction is largely a
naming problem, and the standard library already avoids it: `&Sink<T>` is not redundant, because
`Sink` names an operation and `&` names the view.

Three ways to handle it:

- **M1: capability contracts only.** Keep `Sink<T>` for `add`, and introduce one positional
  capability (placeholder name `Editable<T>`) holding `mut insert`, `mut removeAt`, `mut replaceAt`,
  and `mut clear`. Code that edits a list declares `<L: List<T> + Sink<T> + Editable<T>>` and takes
  `&L`; intersection bounds are valid, so this is expressible today. Consistent with `Sink`,
  `Reversible`, and `Sortable`. The cost is verbose bounds, reduced by keeping the positional
  operations in a single contract. If a name for the combination is wanted, the existing
  `MonadPlus` pattern applies (`requires A + B {}`), at the price of nominal conformance.
- **M2: a `MutableList` bundle.** One name, but the redundancy above; an API taking an unmarked
  `MutableList<T>` cannot mutate anything, which invites confusion.
- **M3: an optional mutable face in the language.** A contract's `mut` members would be required
  only of implementations that opt in, so `&List<T>` means "a list that can be mutated" and `List<T>`
  means read-only. Removes the redundancy completely, but needs a language rule for which members
  belong to the mutable face and how a parameter typed `&List<T>` rejects a persistent list. Recorded
  as an option; it is outside this document's scope.

**Tentative recommendation: M1**, revisited in favor of M3 if composing capability bounds becomes
common. `Set` and `Map` have differently shaped `remove` and `clear`, so a single positional
capability shared by all three families is not natural; each family can have its own.

### 2. What `List` means: ordered or indexed

Today `List` is indexed (`at`) and conforms to `RandomAccess`, which requires only `at` and
documents it as `O(1)`; `Sequenced` requires only `first` and `last`. Linked and cons lists cannot offer
cheap indexing. If `List` the contract requires `RandomAccess`, those implementations cannot be
`List`s. If it does not, the name means "ordered sequence", and indexed access becomes an additional
capability that `ArrayList` has and `ConstructList` does not:

```zeron
// illustrative
public contract IndexedList<T: mut> requires List<T> + RandomAccess<T> { }
```

This is the most consequential naming decision after Option 1. Code that calls `at` on a `List`
today must, under this design, require `RandomAccess` or an indexed bundle. **Recommendation
(tentative):** `List` means ordered, with `RandomAccess` as the indexed capability, since otherwise
the alternative implementations are excluded. The precise meaning of `Sequenced` (does it carry
`first` and `last` only?) determines how small the core is.

### 3. Equality and hashing

Neither `List`, `Map`, nor `Set` is `Eq` or `Hashable` today, so a list cannot be a map key or be
compared with `==`. This is independent of the migration, but a contract is the place where
semantics must be fixed:

- For `List`, equality could be defined across implementations (same length, pairwise equal
  elements) with the hash algorithm fixed in the contract, so equal lists hash equally regardless of
  representation. It requires `T: Eq` and `T: Hashable` respectively, so conditional conformance is
  needed.
- For `Set` and `Map`, equality depends on the receiver's key equivalence. A set built over a
  sorted map and one built over a hash map may disagree on which elements are distinct, so
  cross-implementation equality is not well defined. Per-implementation equality, plus a comparison
  helper that states its equivalence, is safer.

**Recommendation (tentative):** treat equality as a follow-up, defined for `List` across
implementations and per implementation for `Set` and `Map`.

### 4. Key semantics for `Map` and `Set`

The per-instance functions in `withOperations` already express most of what implementations differ
on:

| Implementation | How it is expressed |
| --- | --- |
| Hash map | `Map.empty()` with the `Hashable` and `Eq` of `K` |
| Identity-keyed map | A class with no `Hashable` bound, using an identity hash and `===` |
| Sorted map (`TreeMap`, `RedBlackMap`) | A different structure; ordering function instead of hash and equality |

Under Option 1 the `Map<K, V>` contract carries no bound and each implementation states its own:
`HashMap<K: Hashable<K>, V>` keeps the `Hashable` bound, `IdentityHashMap<K, V>` has none, and sorted
maps require an ordering. The bound therefore disappears from the contract instead of moving to a
constructor. Bounds are confirmed for method-level generics; whether a constructor can bound a
class-level parameter is not confirmed, and it is not needed here. A table parameterized by hash and
equality functions, as `Map` is today, can be shared by `HashMap` and `IdentityHashMap`.

One change is independent of the migration: **generalize `Set.withMap` to take any map
implementation.** Sets over hash maps and over sorted maps then need no separate set contracts.
Sorted sets, which expose ordered operations, would be a separate class over a sorted map.

A `Map` contract would still be needed for sorted maps, since their structure differs. Its core is
what the hash map exposes: `size`, `isEmpty`, `containsKey`, `get`, `iterator`, `keyIterator`, plus
`mut` operations (`put`, `remove`, `clear`, `compute`) in a separate capability, for the same
reason as for lists. Views are not
needed: the current API iterates through `Iterator` and does not expose live key or value views, and
that choice should be kept.

### 5. Construction, literals, and inference

Constructors (`of`, `empty`, `fromArray`, `withMap`, `withOperations`) are declared on the classes.
If `List` becomes a contract, `List.of(...)` has no home. Contracts can declare `static` members
(as `Monoid.empty` does), but a static member is resolved through an implementing type, and a bare
contract name has none. Factories therefore live on the default classes (`ArrayList.of`), and generic
code builds through `Collector`. Inference changes: `let xs = ArrayList.empty()` yields a concrete class, whereas an API
returning `List<Int>` hides it.

### 6. Return types and building

`List` has no transformations, so the absence of `Self` matters less than it would elsewhere. The
existing `Collector<List<_>>` is the mechanism for building a collection generically, and
`Stream.toList()` could be expressed through it rather than hard-wiring `List`. The `Reversible`
instance is more constrained: `reversed` returns a `List<A>`, and `reverse` swaps in place through a
private method. With a contract, in-place reversal needs indexed read and write capabilities or an
override per implementation.

### 7. Dispatch and performance

Iteration already goes through an `Iterator` object and `next()` per element for every collection,
and every stream operator is a nested iterator, so dispatch is already present on the hot path of
traversal. The new cost from contract-typed lists falls mostly on indexed access (`at`) and on
`size`, which a loop calls repeatedly. This is expected to be small on a JIT that inlines
monomorphic and bimorphic sites, but it is unmeasured, and it is the first experiment listed below.

### 8. Interactions with the rest of the language

- **Two kinds of contract do not connect.** A capability contract over a type constructor
  cannot be written once for an ordinary contract such as `Iterable<T>` or the `List` contract, since
  its constructor parameter cannot be bounded and `requires` cannot relate the two kinds. Three
  consequences follow. First, each concrete class declares its own instances
  (`delegating implementation Foldable<ArrayList<_>> {}`), which `delegating` keeps cheap. Second,
  generic code over any collection uses a method-level constructor bound, as `Traversable` does
  (`<C<_>: Foldable<C>, A>`), and applies to concrete classes. Third, a value typed with an ordinary
  contract (`List<T>`, `Iterable<T>`) obtains operations only from methods and default methods of
  ordinary contracts. This is why terminal operations belong on `Iterable` as defaults, and why
  `reversed` and `sorted` for a contract-typed list would be defaults that build the default
  implementation.
- **Serialization** ([ZEP-0092](./ZEP-0092.md)). Encoding works for any implementation through its
  iteration. Decoding needs a concrete type: a field typed with a contract decodes into the default
  implementation, a field typed with a concrete class decodes into that.
- **Pattern matching.** `ConstructList` is naturally `Nil | Cons(head, tail)`. Sequence patterns
  over the `List` contract need a protocol, at least length and positional read.

### 9. What `Church` and `ConstructList` tell us about the core

A Church-encoded list is its own right fold: it supports push-style traversal natively, and a
pull-style iterator only by materializing. This favors keeping `Foldable` (push) as a fundamental
capability beside `Iterable` (pull), which the capability design already does. A cons-list pattern
matches directly and shares structure, but cannot index cheaply. A core `List` that both can
implement is one whose mandatory surface is traversal only, with the rest as capabilities.

## Streams and lazy sequences

### What the sources already do

`Stream` is a final wrapper over an `Iterable`, and `Iterable` is already the contract that lazy
sequences implement. A stream is re-traversable: each traversal asks the source for a fresh
iterator, nothing is cached, and a transformation's function may run once per traversal. `List` has
no `map` or `filter`; transformations exist only on `Stream`, and lists reach it through
`Iterable.stream()`. Strict and lazy transformations therefore do not currently share names.

This corrects the first revision of this document, which assumed that a stream was single-pass and
therefore could not be an `Iterable`. As written, it is a re-traversable lazy view, and being an
`Iterable` is appropriate.

### Should `Stream` be a contract

**No.** What varies between lazy sequences is the *source*, and that variation is already expressed
by `Iterable`. The operators are a fixed set over any source, so a second implementation of the same
operators would duplicate code without adding behavior. `Stream` as a final class also gives
`Monad<Stream<_>>` and `Foldable<Stream<_>>` a concrete type constructor to attach to.

### Relationship to `List`

- `Iterable.stream()` starts a lazy pipeline over any collection. It already exists.
- `Stream.toList()` collects into the default list; it can be generalized through `Collector` with
  a method-level constructor bound (`collect<C<_>: Collector<C>>()`) so it does not hard-wire the
  class.
- `Stream` does not conform to any list contract: it has no length and no random access, and it
  re-evaluates.
- Terminal operations (`fold`, `count`, `any`, `all`) are currently on `Stream` only. Because
  `Foldable` cannot be instantiated for the `Iterable` contract, they should become default
  methods on `Iterable`, so that any collection can use them without `.stream()`, while the
  lazy transformations (`map`, `filter`, `take`) stay on `Stream`, keeping laziness explicit in the
  type. A name collision needs care: `Stream.count()` is a traversal, whereas `List.size` is a
  property.

### Points that remain

- **Re-traversal as an invariant.** `Iterable.iterator()` must return an independent iterator on
  each call. A one-shot source (a connection, a generator with state) cannot honestly be an
  `Iterable`, and such sources need either caching or a different type. This should be written down.
- **Laziness and effects.** Effect-row polymorphism is implemented
  ([ZEP-0086](./ZEP-0086.md)), but the standard-library APIs have not adopted it. Three consequences
  for collections:
  1. *Strict higher-order operations are straightforward.* `forEach`, `foldLeft`, and `fold` take a
     row parameter and propagate it, which is correct because the callback runs during the call:
     `forEach<E: effect...>(consumer: (T) -> Unit raises E): Unit raises E`.
  2. *Lazy transformations cannot be made polymorphic by a row parameter on the method.* A callback
     given to `map` runs at consumption, not during the call to `map`; declaring that call as
     `raises E` would let the effect escape the handler scope enclosing the call. The stream type
     itself would have to carry the row (`Stream<T, E: effect...>`), and each operator would return a
     stream whose row is the union of the upstream row and the callback's row: a union of two row
     variables in a type argument. [ZEP-0086](./ZEP-0086.md) allows one row variable per `raises`
     list and does not say whether that restriction covers type-argument rows.
  3. *Iteration itself has the empty row.* `Iterator.next` and `Iterable.iterator` declare no row, so
     an implementation cannot raise. An effectful source (reading a file) must report failures as
     data, or the contracts need a row parameter (`Iterator<T, E>`), which changes the arity of the
     most used contract.

  **Tentative recommendation:** lazy transformations take pure callbacks, and terminal operations are
  row-polymorphic. Effectful per-element work uses a terminal operation or a strict collection. This
  is sound, adds no type parameters, and can be extended if unions of rows are later supported.
- **Type classes.** If `Functor.map` gained a row parameter so strict collections accept effectful
  callbacks, `Stream` could not implement it soundly, since its `map` is lazy. `Functor` would stay
  pure-only or be split into pure and effect-polymorphic forms.
- **Allocation.** Each operator allocates a `Stream` wrapper and an operator `Iterable`. This is
  probably acceptable and is measurable if it matters.

## Summary of tentative recommendations

| Decision | Tentative recommendation |
| --- | --- |
| Class versus contract | Contracts (project direction: Option 1) |
| Names | Plain names to the contracts and classes renamed (`ArrayList`, `HashMap`, `HashSet`), or new names for the contracts if breakage is unacceptable |
| Meaning of `List` | Ordered sequence; indexing through `RandomAccess` |
| Mutation | Capability contracts (`Sink` plus one positional capability); no `MutableList` bundle |
| Key semantics | `Map` contract unbounded; each implementation states its bound (`HashMap`, `IdentityHashMap`, sorted maps) |
| `Set` | Stay a wrapper over any map implementation; no separate set contract |
| Equality and hash | Follow-up; cross-implementation for `List`, per implementation for `Set` and `Map` |
| `Stream` | Stay a final class; `Iterable` is the lazy-sequence contract |
| Terminal operations | Hoist to `Iterable` defaults; keep lazy operators on `Stream` |
| Capability instances | Declared per concrete class; contract-typed values use ordinary default methods |
| Effects | Strict operations and terminal operations row-polymorphic; lazy transformations take pure callbacks |

## Migration path

If Option 1 is chosen, the breakage is concentrated in a few categories:

1. **Construction.** `List.of`, `Map.empty`, and similar must name a concrete class.
2. **Mutation through the plain names.** Code that mutates through `List<T>` must take `&L` with
   capability bounds (`Sink`, the positional editing capability).
3. **Capability instances.** `Reversible`, `Foldable`, and `Collector` instances move to the concrete
   classes; contract-typed values use default methods.
4. **Inference.** Unannotated bindings infer a concrete class.
5. **Serialization.** Decoding contract-typed fields picks the default implementation.

A phased approach: first the changes that are independent of the migration (generalizing
`Set.withMap`, generalizing `Stream.toList` through `Collector`, hoisting terminal operations to
`Iterable`); then the
bundle contracts and renamed classes with behavior identical to today; then widening of standard
library parameters to the contracts; and only then alternative implementations.

## Open questions

- What should the positional editing capability be called and contain?
- Can a constructor or static method on a class introduce its own bounded generic parameters? This
  is not needed for Option 1, but would help factories.
- Does Zeron have conditional conformance, as list equality requires?
- How do `TreeMap` and `RedBlackMap` differ: two implementations, or a contract and one implementation?
- Does the one-row-variable restriction of [ZEP-0086](./ZEP-0086.md) apply to rows in type-argument
  position, and would a union of two row variables there be admissible?
- Should `Iterator` and `Iterable` gain a row parameter to admit effectful sources, or should such
  sources report failures as data?
- Should `Functor` stay pure-only so that lazy structures can implement it?
- Should terminal operations move to `Iterable`?
- What does a one-shot source use if it cannot be an `Iterable`?

## Experiments

1. **Quick win first.** Generalize `Set.withMap` to any map implementation and back a `Set` with an
   identity-keyed map class. This tests the key-semantics analysis with no contract migration.
2. **Dispatch cost.** Benchmark `at` and `size` on an array-backed list as a class and as a contract
   implementation, with primitive and object elements, at monomorphic, bimorphic, and megamorphic
   sites.
3. **Minimality.** Implement `ConstructList` and `Church` against the drafted contracts to confirm
   the mandatory surface is small.
4. **Capability instances.** Declare delegating instances on concrete classes, and check that generic
   code written with constructor bounds works on `ArrayList` and `ConstructList` alike.
5. **Standard-library audit.** Classify every public signature taking or returning a collection as
   read-only parameter, mutating parameter, or return.
6. **Serialization.** Derive codecs over fields of contract and concrete type.
7. **Effects.** Prototype row-polymorphic `forEach` and `foldLeft` on `Iterable`, and check that an
   effectful callback to a lazy `Stream.map` is rejected, as recommended.
