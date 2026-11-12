# ZEP Follow-Up Inventory

| Field | Value |
| ----- | ----- |
| Date | 2026-11-08 |
| Scope | Follow-up work found in already implemented or shipped ZEPs |
| Purpose | Identify candidates for new ZEPs and separate them from work already covered by an existing proposal |

This inventory scans the ZEPs marked `Implemented` (shipped or targeted for a release) for deferred
scope, non-goals, open questions, future-work sections, and partial implementations. Items marked
`[E]` are explicitly deferred or excluded by the ZEP text. Items marked `[I]` are inferred from
non-goals or from a partial implementation.

It is a planning aid, not a proposal. It does not change any ZEP status or release state.

> Update 2026-11-10: the model, callable, and existential follow-ups below are tracked by
> [ZEP-0100](../zeps/ZEP-0100.md) through [ZEP-0109](../zeps/ZEP-0109.md). ZEP-0100 is implemented and
> a candidate; ZEP-0104 is accepted and a candidate; ZEP-0101 through ZEP-0103 and ZEP-0105 through
> ZEP-0109 remain Draft and unscheduled.
> Deviations from the initial [ZEP-0079](../zeps/ZEP-0079.md) model surface would still gate any
> implementation, but that surface is now validated and [ZEP-0079](../zeps/ZEP-0079.md) and
> [ZEP-0081](../zeps/ZEP-0081.md) are `Implemented`. [ZEP-0092](../zeps/ZEP-0092.md) is also
> `Implemented`, so the generated serialization operations are no longer a follow-up; the remaining
> serialization gaps are the external-name and alias work tracked by ZEP-0091 and ZEP-0098.
>
> Effect notation and suspension follow-ups are tracked separately by
> [ZEP-0110](../zeps/ZEP-0110.md) (block-bodied handlers), [ZEP-0111](../zeps/ZEP-0111.md)
> (witness-dispatched do-notation), and [ZEP-0112](../zeps/ZEP-0112.md) (structured concurrency on
> virtual threads). ZEP-0110 and ZEP-0112 are `Draft`; ZEP-0111 is `Implemented` as a
> notation-only design; all three are `Unscheduled`. Resumption is split into
> [ZEP-0118](../zeps/ZEP-0118.md), [ZEP-0119](../zeps/ZEP-0119.md), and
> [ZEP-0120](../zeps/ZEP-0120.md); multi-shot remains `Deferred` in
> [ZEP-0121](../zeps/ZEP-0121.md), and [ZEP-0007](../zeps/ZEP-0007.md) is `Superseded`.

## 1. Work already covered by an existing proposal

These are confirmed gaps, but they should extend an existing proposal rather than become new ZEPs.

| Follow-up | Source ZEPs | Existing proposal |
| --- | --- | --- |
| Generic effect payloads | 0005, 0056, 0057, 0086 | ZEP-0005 (Accepted; closed applied-effect scope, unscheduled) |
| Effect subtyping / top `Effect` | 0056, 0057, 0086 | ZEP-0013 (Draft) |
| Nominal effect payload types | 0055, 0086 | ZEP-0054 (Draft) |
| Numeric literals, newtypes, widening, heterogeneous operators | 0036, 0043, 0071 | ZEP-0041, 0042, 0044, 0045, 0046, 0070, 0074, 0075 |
| Remaining Java interop (SAM, inherited members, JPMS, extensible bindings) | 0047-0049 | ZEP-0050 (Draft) |
| Standard-library testing/logging/TOML/XML | 0011, 0065 | ZEP-0063, 0064, 0066, 0067 |
| Model, callable, and existential follow-ups | 0079, 0081, 0090, 0093 | ZEP-0100–0109 (implemented/candidate or Draft) |
| 64-bit hashing, mutable projections, unchecked casts | 0008, 0085, 0089 | ZEP-0084, 0085, 0089 |
| Annotation consumption | 0031 | ZEP-0091 (Accepted) |
| Annotation target for model mapping clauses | 0079 | ZEP-0097 (Accepted) |
| Block handlers, `do` notation, suspension | 0007, 0058, 0019 | ZEP-0110, ZEP-0111, ZEP-0112 (Draft; ZEP-0111 Implemented); resumption split across ZEP-0118–ZEP-0121 |
| Generated serialization operations | 0068, 0069, 0094, 0099 | ZEP-0092 (Implemented; delivered) |

**Important overlap:** ZEP-0092 is implemented and closed the generated serialization operations
for ZEP-0068/0069. The next accepted, unimplemented work is ZEP-0091 (annotation consumption) and
ZEP-0097 (annotation target for model mapping clauses); ZEP-0091 Direction B also unblocks the
`SerialName` names and aliases that ZEP-0092 defers. Take those before a broader serialization API
ZEP.

## 2. New ZEP candidates, prioritized

### Near-term

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

Why first: it unlocks the collection contracts just shipped, and several deferred items depend on
having a second conformer.

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

### Medium-term

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
  general existentials, storage, ancestor matching, and contract-bound tests —
  [ZEP-0105](../zeps/ZEP-0105.md) through [ZEP-0108](../zeps/ZEP-0108.md); rank-2 polymorphism and
  polymorphic function types — [ZEP-0109](../zeps/ZEP-0109.md).

#### J. Class-side and static HKT operations

Source: ZEP-0019, ZEP-0076, ZEP-0078.

- Class-side methods on HKT contracts `[E]`.
- Static and class-side default operations `[E]`.
- Delegating static requirements through companion types `[E]`.
- Note that ZEP-0072 (class-scoped static functions) is the existing draft for part of this space.

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

### Later / research

- **Generator functions, async sequences, fused `for` lowering, and `break`/`continue` in
  comprehensions** - ZEP-0058 `[E/I]`. Generator functions are the main language feature; fusion is
  an optimization.
- **General `Self` type** - ZEP-0082 `[E]`.
- **User-defined syntax extensions** - ZEP-0082 `[E]`.
- **Higher-rank effect-row quantification inside function types** - ZEP-0086 `[E]`. Requires
  first-class polymorphic function types.
- **Complete generic overlap solver** - ZEP-0004 `[E]`. Internal coherence hardening; likely a
  design note plus tests rather than a feature ZEP.
- **Effect-polymorphic lambda schemes** - ZEP-0003 `[E]`.
- **Associated types, static abstract class members, and implicit factory discovery** - ZEP-0059
  `[E]`.
- **Configurable formatter styles** - ZEP-0028 `[E]`.
- **Bundled language-server and runtime packaging for VS Code** - ZEP-0027 `[E]`.

## 3. Low-value or explicitly rejected

Do not spend ZEP numbers on these unless a concrete need appears:

- Arbitrary radix literals and radix-prefixed floats (ZEP-0039/0040) `[E]`.
- `zeron --version` alias - already implemented and tested (ZEP-0038).
- A dedicated numeric module (ZEP-0024) `[E]`.
- Public `RedBlackMap` - explicitly rejected (ZEP-0094).
- An optional mutable face for contracts - non-goal (ZEP-0094).
- Structural typing (ZEP-0017).
- A complete documentation-site generator as part of ZEP-0073.
- Guaranteed stream fusion (ZEP-0058).
- Runtime reflection as a general feature (ZEP-0032 is only exploration).

## 4. Tracker hygiene

These inconsistencies were corrected on 2026-11-05 and 2026-11-08 and are recorded here for
traceability:

- **ZEP-0047**: the file `Status` now reads `Implemented`, matching the tracker's
  `Implemented / Shipped 0.5.0`.
- **ZEP-0073**: the file `Status` now reads `Implemented`, matching the tracker's
  `Implemented / Candidate 0.6.0`.
- **ZEP-0079**: the file `Status` was aligned to `Draft` on 2026-11-05; after completed validation it
  now reads `Implemented`, matching the tracker.
- **ZEP-0076/0077/0078**: the `0.6.0` reference was removed from these `Implemented / Unscheduled`
  rows.
- Several implemented ZEPs (`0031`, `0060`, `0076`–`0084`, `0086`–`0088`, `0090`, `0092`–`0094`) were
  previously unscheduled. On 2026-11-08 they were recorded as `Candidate` for `0.6.0`, together with
  the existing `0.6.0` candidates.

Update 2026-11-08: ZEP-0079 and ZEP-0081 completed validation against their declared scope, and
both the file and tracker statuses now read `Implemented`.

Update 2026-11-08 (reconciliation): the remaining file/tracker status mismatches were corrected.
ZEP-0091 and ZEP-0097 now read `Accepted` in the tracker, matching their files and 2026-11-05
decisions. ZEP-0069 now reads `Implemented` in both places, since its data-class feature shipped and
its generated serialization operations are recorded under ZEP-0092. ZEP-0068 stays `Accepted`; its
implementation text now attributes the generated operations to ZEP-0092. ZEP-0084 and ZEP-0092 were
already `Implemented`. No file/tracker status mismatches remain.

## 5. Suggested drafting order

Use this order for the work after the post-`0.6.0` effect program.

The immediate post-`0.6.0` slot is reserved for the effect semantics and runtime identity program
recorded in the [ZEP release plan](./zep-tracker-review.md): ZEP-0054 and ZEP-0013 first, then shared
ZEP-0015, the bounded ZEP-0104 slice, and ZEP-0005; ZEP-0105 through ZEP-0109 are later research.
The list below applies after that program's first bundle.

1. Implement **ZEP-0091** (annotation consumption, Direction B first) and **ZEP-0097** (annotation
   target for model mapping clauses); they are the only accepted, unimplemented proposals and they
   close gaps in already-shipped annotation and serialization surfaces.
2. Draft **collection completion** (A) and **derivation expansion** (B).
3. Draft **handler composition** (C) after the effect program settles ZEP-0013.
4. Draft **LSP navigation and refactoring** (D).
5. Fold **Java array and wrapper completion** (E) into a ZEP-0047 revision or a companion ZEP.
6. Take **contravariance** (F) when variance consumers are ready.

A and B are the strongest new-ZEP candidates because they complete capabilities that are already
shipped but currently stop short of being useful in ordinary code. ZEP-0091 and ZEP-0097 come first
among the follow-ups because they are already accepted and need no new proposal.
