# ZEP Tracker Review and Near-Term Shipping Order

| Field | Value |
| ----- | ----- |
| Date | 2026-11-08 |
| Scope | Review of `documents/zeps/ZEP.md` and near-term sequencing |
| Purpose | Record the reconciled proposal/release state, the post-`0.6.0` program, and the remaining near-term order |

This is a planning snapshot. It reflects the tracker and each ZEP file as of 2026-11-08. It does not
change any proposal's status or release state by itself.

## Summary

- **`0.6.0` is a stabilization bundle for work that has already landed**, not an opportunity to
  add new scope. The 2026-11-08 release decision marks the implemented-but-unscheduled set as
  `Candidate` for `0.6.0` alongside the existing candidates, so the post-`0.6.0` program below
  starts from a clean implemented baseline.
- The 2026-11-08 reconciliation removed the last file/tracker status mismatches. ZEP-0091 and
  ZEP-0097 are `Accepted`, matching their files, and ZEP-0069 is now `Implemented`.
- ZEP-0079, ZEP-0080, and ZEP-0081 are implemented. The composed ZEP-0081 example now compiles and
  verifies model-driven receiver inference, `PropertyId` field matching, and unmapped-property
  reporting. ZEP-0080's former `@CodeModel` marker is retired; a `CodeModel<F>` parameter type alone
  selects model mode.
- ZEP-0084 and ZEP-0092 are implemented. ZEP-0092 records its remaining limits explicitly:
  `SerialName` aliases wait for ZEP-0091 Direction B and ZEP-0098, and row-polymorphic derived
  witnesses wait for ZEP-0099.
- ZEP-0091 and ZEP-0097 are the only accepted proposals that remain unimplemented and unscheduled.
  They close the already-shipped annotation and serialization surfaces, but they are now queued after
  the first post-`0.6.0` program bundle below.
- The next post-`0.6.0` program is **effect semantics and runtime identity**: ZEP-0054 and ZEP-0013
  first, then a narrowed ZEP-0015 and ZEP-0005, with ZEP-0104 kept as research. ZEP-0100 is an
  independent co-candidate, not part of the reification dependency chain. None of these is
  `Candidate` yet because their designs are not accepted.

## Current `0.6.0` candidate set

| ZEP | Name | Proposal status | Notes |
| --- | --- | --- | --- |
| ZEP-0031 | Declarative Annotations and Declaration Metadata | Implemented | Candidate; annotation consumption remains ZEP-0091 |
| ZEP-0060 | Treating Arrays as Iterable Values | Implemented | Candidate |
| ZEP-0061 | Java and External Interop Language Support | Implemented | Candidate |
| ZEP-0065 | Standard Library JSON Parser | Implemented | Candidate |
| ZEP-0068 | Serialization Contracts for Zeron | Accepted | Candidate; core contracts and generated operations (ZEP-0092) implemented; external names/aliases and format adapters remain |
| ZEP-0069 | Transparent Data Classes | Implemented | Candidate; generated serialization operations are supplied by ZEP-0092 |
| ZEP-0071 | Numeric Hierarchy and Context-Directed Literals | Implemented | Candidate |
| ZEP-0073 | Documentation Comments for Declarations | Implemented | Candidate |
| ZEP-0076 | Delegating Witness Implementations | Implemented | Candidate |
| ZEP-0077 | Delegating Higher-Kinded Implementations | Implemented | Candidate |
| ZEP-0078 | Default Method Implementations in Higher-Kinded Contracts | Implemented | Candidate |
| ZEP-0079 | Model Declarations | Implemented | Candidate |
| ZEP-0080 | Typed Code Models for Selected Expressions | Implemented | Candidate |
| ZEP-0081 | Type-Safe Embedded DSL Support | Implemented | Candidate |
| ZEP-0082 | Receiver Lambdas and Type-Safe Builders | Implemented | Candidate |
| ZEP-0083 | Lambda and Function-Type Syntax Ergonomics | Implemented | Candidate |
| ZEP-0084 | 64-Bit Hashes for Zeron Values | Implemented | Candidate |
| ZEP-0086 | Effect-Row Polymorphism | Implemented | Candidate |
| ZEP-0087 | Negative Flow Refinement for Closed Sum Types | Implemented | Candidate |
| ZEP-0088 | Non-Null Assertion Operator | Implemented | Candidate |
| ZEP-0090 | Property References | Implemented | Candidate |
| ZEP-0092 | Generated Serialization Operations | Implemented | Candidate; `SerialName` aliases and row-polymorphic witnesses remain |
| ZEP-0093 | Unified Property Identity and a Typed Code-Model API | Implemented | Candidate |
| ZEP-0094 | Collection Contracts | Implemented | Candidate |

The table is the complete `0.6.0` candidate set. Every row has an implemented or accepted design;
the remaining tracker task is the release decision itself, not an implementation gap. ZEP-0068 is
the only row that still carries accepted scope outside `0.6.0`; its completion is tied to ZEP-0091,
ZEP-0098, and the format-specific adapter proposals.

## Implemented set marked for `0.6.0`

`0031`, `0060`, `0076`, `0077`, `0078`, `0079`, `0080`, `0081`, `0082`, `0083`, `0084`, `0086`,
`0087`, `0088`, `0090`, `0092`, `0093`, and `0094` were implemented and previously `Unscheduled`.
On 2026-11-08 they were recorded in the tracker as `Candidate` for `0.6.0`. They remain `Candidate`
until the release is published, at which point they move to `Shipped` / `0.6.0`. Proposal status and
release state remain independent.

## Post-`0.6.0` program: effect semantics and runtime identity

Recorded 2026-11-08 as the intended next program after `0.6.0`. This is a planning record only: it
changes no proposal status or release state, and it commits no release target.

### Tracks

| Track | ZEPs | Current status | Character |
| --- | --- | --- | --- |
| Effect semantics | ZEP-0054, ZEP-0013 | Draft | Nominal payload capability, subtyping, and the top effect |
| Runtime applied identity | ZEP-0015, ZEP-0005 | Draft / Deferred | Reification and generic effect payloads |
| Type abstraction | ZEP-0104 | Draft | Research only; no shipping commitment |
| Static declaration identity | ZEP-0100 | Draft | Orthogonal; useful independently of reification |

Two foundations are already implemented and should be reused, not redesigned: ZEP-0055 provides the
`effect` kind constraint and compiler-owned runtime effect marker, and ZEP-0086 provides `effect...`
rows and the empty row `{}`. ZEP-0013 adds the top of the lattice; it does not re-implement either.

### First bundle (provisional; next release after `0.6.0`)

**ZEP-0054, then ZEP-0013**, with ZEP-0100 as an optional co-candidate if its design is accepted.

- **ZEP-0054 first.** It is independent of reification and turns effect payloads into ordinary
  nominal classes with methods, properties, and contract conformance while retaining the effect
  marker. It removes a resolver special case and makes ZEP-0013's inherited-payload rules easier.
  Settle its open questions on member restrictions and index metadata during acceptance.
- **ZEP-0013 second.** It adds `under` edges, inherited payload properties, `raises Effect`, the
  explicit catch-all `case Effect`, and most-specific handler selection. It needs a stable nominal
  effect identity and explicit subtype edges, not full applied-type reification, so it can land
  before ZEP-0015.
- **Lattice and naming.** `{}` is already the empty row and the bottom of the lattice; `Effect` is
  the denotable top. `AnyEffect` is not defined by any current ZEP. Prefer one source-level top name
  (`Effect`) and keep any runtime marker compiler-owned, as ZEP-0055 already does; do not introduce
  a second public top without a distinct role. ZEP-0013 should record the decision.
- **ZEP-0100 is orthogonal.** Callable references carry static `owner + kind + name` identity and do
  not depend on ZEP-0015. Method and constructor references can land without ZEP-0072; static
  function references wait for it. Include ZEP-0100 in this bundle only if its design is accepted;
  do not make either track depend on the other.

### Second bundle (provisional; follows the first)

**ZEP-0015, then ZEP-0005.**

- **ZEP-0015 is narrowed first.** Scope the initial design to runtime identity for applied effect
  types: owner, type arguments, effect marker, propagation through generic calls and forwarding,
  handler dispatch without accidental catches, library-index encoding, and measured overhead. Keep
  general reusable applied-type identity as follow-on scope; do not start with general reflection.
  ZEP-0055's marker proves effect-kind membership only and does not preserve applied arguments, so
  ZEP-0015 must extend it rather than replace it.
- **ZEP-0005 follows ZEP-0015.** Generic effect declarations and applications require the applied
  identity descriptor. Follow ZEP-0005's own staging note: exact matching first, then integrate with
  ZEP-0013's `under` relation. Do not block generic payloads on generic subtyping.
- **ZEP-0005 is currently `Deferred`.** The first process step is to reopen it to `Draft`; it is not
  part of the accepted set yet.

### Research track (no release target)

**ZEP-0104.** Keep it `Draft` and do not nominate it for a bundle. Revisit after ZEP-0015 and
ZEP-0005: does reification change packing and opening, are existential evidence dictionaries the
same machinery as witnesses, and does the model-field use case justify a full feature? Only then
write a separate accepted-scope ZEP.

### Why not one bundle

ZEP-0054 and ZEP-0013 deliver user-visible value without waiting for reification. ZEP-0015 and
ZEP-0005 are research-heavy and should be gated on a bounded identity design. ZEP-0104 is
exploration. ZEP-0100 shares the value-level identity theme but not the runtime type-descriptor
machinery, so bundling all six would couple unrelated risks.

### Release-state caveat

None of these can move to `Candidate` yet: ZEP-0000 requires an Accepted design for a candidate or
planned item. They remain `Unscheduled` (and ZEP-0005 remains `Deferred`) until their designs are
accepted. Once accepted, the first bundle may be marked `Candidate` with
`Next release (proposed)`, or with an assigned version once one is chosen. This is a priority
change: ZEP-0091 and ZEP-0097 move behind the first bundle, not out of the plan.

## Recommended order after the first program bundle

### 1. ZEP-0091 - Annotation consumption

Accepted and unscheduled; it directly closes a shipped gap.
Deliver Direction B (compiler-recognized annotations) first, then Direction A (contract-directed
`from` bindings). Direction B unblocks `SerialName` in ZEP-0092 and the alias surface in ZEP-0097.

### 2. ZEP-0097 - Annotation target for model mapping clauses

Accepted and unscheduled. It follows ZEP-0091 Direction B and the implemented ZEP-0079 model
surface. Its metadata export must preserve the existing index-schema policy.

### 3. Collection completion (ZEP-0094 / ZEP-0060 follow-up)

`MapBackedSet`, `SortedMap`/`TreeMap`, `sameElements`/`elementsHash`, `Collector`-based
`Stream.toList` construction, and the remaining separate-implementation coverage. This completes
the collection contracts already shipped in ZEP-0094.

### 4. Derivation expansion (ZEP-0008 follow-up)

Mutually recursive declaration groups, `Display` derivation, and the policy for deriving
`Eq`/`Hashable` on container types. These are contract-derivation gaps, not a new subsystem.

### 5. ZEP-0099 - Row-polymorphic derived witnesses

Closes the limitation recorded by ZEP-0092: component evidence currently uses the empty row, so an
effectful component decoder and effectful data-class rows do not round-trip through the library
index.

### 6. ZEP-0095 - Bytecode quality and evidence inlining

Bundle the classfile-quality inventory's optimizations into one proposal with measurable
acceptance criteria rather than landing them piecemeal.

### 7. ZEP-0096 - Primitive specialization

Follow the bytecode-quality work so specialization starts from a stable baseline.

### 8. ZEP-0070 - Generic and nominal newtype representations

Newtypes remain a foundational type-system feature and a prerequisite for several numeric and
serialization follow-ups.

### 9. ZEP-0050 - Remaining Java interoperability work

The draft records the SAM, inherited-member, JPMS, and extensible-binding gaps left by the shipped
ZEP-0047/0048/0049 scope.

ZEP-0013 is not in this list: it is part of the post-`0.6.0` program above.

## Release sequencing guidance

1. **`0.6.0`**: finish the current accepted candidates and reconcile status. Do not add new scope.
2. **First post-`0.6.0` bundle (provisional)**: ZEP-0054 and ZEP-0013, with ZEP-0100 as an optional
   co-candidate. This is the effect-semantics bundle; it does not wait for reification.
3. **Second post-`0.6.0` bundle (provisional)**: narrowed ZEP-0015 and ZEP-0005. This is the
   runtime-identity bundle.
4. **Research**: ZEP-0104, with no target.
5. **After the first bundle**: ZEP-0091 and ZEP-0097, then collection completion and derivation
   expansion, as listed above. These close already-shipped gaps but no longer occupy the immediate
   post-`0.6.0` slot.
6. **New feature ZEPs**: continue from the follow-up inventory, beginning with
   [zep-follow-up-inventory.md](./zep-follow-up-inventory.md), subject to the program above.

## Tracker corrections (applied 2026-11-08)

- **ZEP-0091**: the tracker now reads `Accepted`, matching the file and the 2026-11-05 decision.
- **ZEP-0097**: the tracker now reads `Accepted`, matching the file and the 2026-11-05 decision.
- **ZEP-0069**: the file and tracker now read `Implemented`; the data-class feature shipped and its
  generated serialization operations are recorded under ZEP-0092.
- **ZEP-0068**: remains `Accepted`; its implementation text now records that ZEP-0092 delivered the
  generated operations and that external-name handling still waits for ZEP-0091/0098.
- **ZEP-0079 / ZEP-0081**: validation is complete and the composed DSL example passes.
- **ZEP-0084 / ZEP-0092**: already `Implemented`; the stale "draft" and "missing link" notes in this
  review were removed.

## Notes

- Proposal status and release state are independent. A row can be `Implemented` and still
  `Unscheduled`; that is not an error by itself.
- A ZEP cannot move to `Candidate` while it is `Draft` or `Deferred`; accept the design first.
- The post-`0.6.0` program is provisional. It records intended order, not an approved release scope.
- Do not reuse a ZEP number. The next available number is `0105`.
- Routine fixes and contained changes do not need a ZEP.
- The release process has moved `pom.xml` and `CHANGELOG.md` to `0.6.0`. Do not treat the candidate
  set above as shipped until the release artifact is published and the tracker rows move to
  `Shipped`.

## References

- [ZEP tracker](../zeps/ZEP.md)
- [ZEP process](../zeps/ZEP-0000.md)
- [ZEP template](../zeps/ZEP-0001.md)
- [Classfile quality inventory](./classfile-quality-inventory.md)
- [ZEP follow-up inventory](./zep-follow-up-inventory.md)
