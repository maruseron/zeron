# ZEP Follow-Up Inventory

| Field | Value |
| ----- | ----- |
| Date | 2026-11-13 |
| Scope | Remaining follow-up work found in implemented or shipped ZEPs |
| Purpose | Inventory remaining follow-up work and identify which items are covered by an existing proposal |

This inventory scans the ZEPs marked `Implemented` (shipped or targeted for a release) for deferred
scope, non-goals, open questions, future-work sections, and partial implementations. Items marked
`[E]` are explicitly deferred or excluded by the ZEP text. Items marked `[I]` are inferred from
non-goals or from a partial implementation.

It is an inventory, not a proposal. It does not change any ZEP status or release state.

## 1. Follow-up work covered by an existing proposal

These are confirmed gaps, but they should extend an existing proposal rather than become new ZEPs.

| Follow-up | Source ZEPs | Existing proposal |
| --- | --- | --- |
| Numeric literals, newtypes, widening, heterogeneous operators | 0036, 0043, 0071 | ZEP-0041, 0042, 0044, 0045, 0046, 0070, 0074, 0075 |
| Remaining Java interop (SAM, inherited members, JPMS, extensible bindings) | 0047-0049 | ZEP-0050 (Draft) |
| Standard-library testing/logging/TOML/XML | 0011, 0065 | ZEP-0063, 0064, 0066, 0067 |
| Model, callable, and existential follow-ups | 0079, 0081, 0090, 0093 | ZEP-0100–0109 (implemented, accepted, deferred, or Draft) |
| 64-bit hashing, mutable projections, unchecked casts | 0008, 0085, 0089 | ZEP-0084, 0085, 0089 |
| Annotation consumption | 0031 | ZEP-0091 (Accepted) |
| Annotation target for model mapping clauses | 0079 | ZEP-0097 (Accepted) |
| Block handlers, `do` notation, suspension | 0007, 0058, 0019 | ZEP-0110, ZEP-0111, ZEP-0112 (Draft; ZEP-0111 Implemented); resumption split across ZEP-0118–ZEP-0121 |

**Overlap:** ZEP-0092 is implemented and closed the generated serialization operations for
ZEP-0068/0069. The remaining serialization follow-ups are annotation consumption in ZEP-0091 and the
model-mapping clause target in ZEP-0097; ZEP-0091 Direction B also covers the `SerialName` names and
aliases that ZEP-0092 defers.

## 2. New ZEP candidates

#### A. Collection completion and ordered collections

Source: ZEP-0094, ZEP-0060.

- `MapBackedSet`, `SortedMap`, `TreeMap` (red-black, internal), later B-tree and skip-list
  implementations `[E]`.
- `sameElements` / `elementsHash`, conditional `Eq`/`Hashable` derivation on `ArrayList` `[E]`.
- `Collector`-based construction in `Stream.toList` `[E]`.
- Decoding contract-typed fields in generated serialization `[E]`.
- Optional: cached array `Iterable` adapter and member-discovery metadata for intrinsic array
  conformance (ZEP-0060) `[E/I]`.
- Effect-tracking streams were explicitly left out and should remain a separate proposal `[E]`.

#### B. Derivation expansion

Source: ZEP-0008.

- Mutually recursive declaration groups `[E]`.
- `Display` derivation `[E]`.
- Policy for deriving `Eq`/`Hashable` on container types `[E]`.

#### C. Handler composition and handler assignability

Source: ZEP-0056, ZEP-0057.

- Handler-composition operator `[E]`.
- Handler assignability and structural handler subtyping `[E]`.
- Effect-row interactions with handlers `[E]`.
- Coordinate with ZEP-0013 rather than duplicating effect subtyping.

#### D. LSP navigation and refactoring

Source: ZEP-0021.

- References, rename, code actions, refactoring `[E]`.
- LSP formatting integration, reusing `zeron fmt` from ZEP-0028 `[I]`.
- Multi-root and multi-project workspaces `[E]`.
- Incremental analysis caches and cross-project isolation `[E]`.

#### E. Java array and wrapper completion

Source: ZEP-0047, ZEP-0048, ZEP-0053.

- Boxed `Byte`, `Float`, `Short`, and `Character` mappings `[E]`.
- Java `char`, `float` parameters, and narrowing conversions `[E]`.
- Java array fields, packed varargs arrays, typed reference arrays, and multidimensional arrays `[E]`.
- Reference-array covariance and runtime element checks `[E]`.
- Java generic arrays `[E]`.
- Java external generic class-file relationship metadata `[E]`.

Keep this separate from ZEP-0050, which explicitly excludes broadening the ZEP-0047/0048
semantics.

#### F. Contravariant type parameters

Source: ZEP-0051, ZEP-0052.

- Declaration-site `in T` `[E]`.
- Array projections and Java generic-array projection behavior `[E]`.
- Complete the use-site projection slice alongside it.

#### G. Explicit shared mutable cells

Source: ZEP-0002.

- A language-defined cell for closures to share and update a local `[E]`.

#### H. Closed-set `numeric` marker

Source: ZEP-0010, ZEP-0024.

- Deferred pending a concrete use case `[E]`.
- Should not replace operation-bearing contracts.

#### I. Property-reference and code-model extensions

Source: ZEP-0090, ZEP-0093.

- Bound references (`value::property`) `[E]`.
- Method and constructor references `[E]` — drafted as [ZEP-0100](../zeps/ZEP-0100.md).
- Writable references / `MutPropertyRef` `[E]`.
- Inherited and statically dispatched member references `[E]` — policy recorded in
  [ZEP-0100](../zeps/ZEP-0100.md).
- Selector-based collection APIs such as `sortedBy` `[E]`.
- Public `PropertyId` construction, property paths, and further model nodes `[E]`.
- Model field representation adapters — [ZEP-0101](../zeps/ZEP-0101.md).
- Generic and polymorphic models — [ZEP-0102](../zeps/ZEP-0102.md), [ZEP-0103](../zeps/ZEP-0103.md).
- Bounded existentials and type-bound pattern matching — [ZEP-0104](../zeps/ZEP-0104.md);
  general existentials and storage — [ZEP-0105](../zeps/ZEP-0105.md) and
  [ZEP-0106](../zeps/ZEP-0106.md); ancestor matching — [ZEP-0107](../zeps/ZEP-0107.md); rank-2
  polymorphism and polymorphic function types — [ZEP-0109](../zeps/ZEP-0109.md). Contract-bound
  runtime tests — [ZEP-0108](../zeps/ZEP-0108.md) — are deferred.

#### J. Class-side and static HKT operations

Source: ZEP-0019, ZEP-0076, ZEP-0078.

- Class-side methods on HKT contracts `[E]` — receiverless structural satisfaction proposed by
  [ZEP-0125](../zeps/ZEP-0125.md).
- Static and class-side default operations `[E]`.
- Delegating static requirements through companion types `[E]` — nominal static implementation
  proposed by [ZEP-0124](../zeps/ZEP-0124.md).
- Note that [ZEP-0072](../zeps/ZEP-0072.md) (class-scoped static functions) is implemented and
  supplies the companion surface for ZEP-0124.

#### K. Annotation value and relationship extensions

Source: ZEP-0031, ZEP-0091.

- Type/class literals and nested annotation values `[E]`.
- Annotation-value relationships `[E]`.
- Annotations on clauses such as handler cases `[I]`.
- General macros remain deliberately out of scope `[E]`.

#### L. Standard Library Phase 3

Source: ZEP-0011, ZEP-0026.

- Dedicated serialization and encoding API `[E]`.
- Wall-clock timestamps and timezone details `[E]`.
- Subprocess management `[E]`.
- Richer environment and process properties `[E]`.
- Platform-specific APIs `[E]`.
- Streaming HTTP uploads `[E]`.
- HTTP/3 remains excluded initially `[E]`.

#### M. Documentation tooling

Source: ZEP-0073.

- Block documentation syntax `[E]`.
- Documentation on parameters and local bindings `[E]`.
- Documentation inheritance through overrides and witnesses `[E]`.
- Lint, site generation, and example evaluation are separate product decisions `[E]`.

#### N. Lambda result-type inference without an expected type

Source: ZEP-0083.

- Explicit open question: which body forms determine the result type `[E]`.

#### O. Negative flow refinement extensions

Source: ZEP-0087.

- Complements for open class hierarchies `[E]`.
- Arbitrary predicates and unsupported tests `[E]`.
- `is` test complements, currently not inferred `[E]`.

#### P. Generic effect propagation

Source: ZEP-0005.

- Generic effect applications containing a type parameter, forwarding through a generic call, and
  omitted handler arguments `[E]`.
- Applied ancestry is owned by [ZEP-0107](../zeps/ZEP-0107.md); this item covers the remaining
  propagation work.

#### Q. Additional follow-up items

- **Generator functions, async sequences, fused `for` lowering, and `break`/`continue` in
  comprehensions** - ZEP-0058 `[E/I]`. Generator functions are the main language feature; fusion is
  an optimization.
- **General `Self` type** - ZEP-0082 `[E]`.
- **User-defined syntax extensions** - ZEP-0082 `[E]`.
- **Higher-rank effect-row quantification inside function types** - ZEP-0086 `[E]`. Requires
  first-class polymorphic function types. Binder-dependent rank-2 effect rows remain excluded by
  [ZEP-0109](../zeps/ZEP-0109.md).
- **Complete generic overlap solver** - ZEP-0004 `[E]`. Internal coherence hardening; likely a
  design note plus tests rather than a feature ZEP.
- **Effect-polymorphic lambda schemes** - ZEP-0003 `[E]`.
- **Associated types, static abstract class members, and implicit factory discovery** - ZEP-0059
  `[E]`.
- **Configurable formatter styles** - ZEP-0028 `[E]`.
- **Bundled language-server and runtime packaging for VS Code** - ZEP-0027 `[E]`.
