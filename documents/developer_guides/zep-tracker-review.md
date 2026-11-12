# ZEP Tracker Review and Release Planning

| Field | Value |
| ----- | ----- |
| Date | 2026-11-12 |
| Scope | Review of `documents/zeps/ZEP.md`, release planning, and near-term sequencing |
| Purpose | Record the reconciled proposal/release state, the `0.7.0` and `0.8.0` bundles, and the remaining backlog order |

This is a planning record. It reflects the tracker and each ZEP file as of 2026-11-12. The
[ZEP tracker](../zeps/ZEP.md) remains the source of truth for proposal status and release state;
this document explains the plan and does not change either by itself.

## Release plan at a glance

| Release | Theme | Current state |
| --- | --- | --- |
| `0.6.0` | Stabilization bundle of already implemented work | Shipped |
| `0.7.0` | Type identity, reification, and the type-bound mechanism | Candidate set recorded; design acceptance and implementation continue |
| `0.8.0` | Effect control and response typing | Proposed; no candidates until the designs are accepted |

The two planned bundles separate two different kinds of risk. `0.7.0` changes type identity,
runtime descriptors, type-bound tests, and effect payload identity. `0.8.0` changes control flow:
response-typed effects, resumption, and the async surface. Keeping them separate concentrates the
API-index schema and standard-library version bumps in one release instead of mixing them with a new
`raise` typing rule.

## `0.7.0` — Type identity, reification, and type bounds

`0.7.0` is the type-identity release. It consolidates the work that changes runtime type
descriptors, effect payload identity, exact type-bound tests, and existential opening, together with
the already implemented supporting proposals that are already marked `Candidate`. The goal is to
land one coherent identity story and validate it through one API-index and separate-compilation
cycle.

### Candidates

Every row below is already `Candidate` for `0.7.0` in the tracker.

| ZEP | Name | Proposal status | Role |
| --- | --- | --- | --- |
| ZEP-0005 | Generic Effect Payloads with Closed Applied Identity | Accepted | Generic effect declarations with exact closed applications; unimplemented |
| ZEP-0013 | Effect Subtyping and the Top Effect Type | Implemented | `under` edges, inherited payloads, and the `Effect` top type |
| ZEP-0015 | Runtime Reification of Applied Type Identity | Implemented | Shared applied-type descriptor; API-index schema 13 |
| ZEP-0054 | Nominal Effect Payload Types | Implemented | Ordinary member model for effect payloads |
| ZEP-0072 | Class-Scoped Static Functions as Companion Namespace Members | Implemented | Supporting language work; orthogonal to reification |
| ZEP-0100 | Callable References for Methods, Constructors, and Static Functions | Implemented | Orthogonal co-candidate; static `owner + kind + name` identity |
| ZEP-0104 | Wildcard Existentials and Type-Bound Pattern Matching | Accepted | Bounded initial slice: star projections, anonymous existentials, exact type-bound tests |
| ZEP-0105 | General Existential Types and Escape Semantics | Implemented | Branch-scoped opening and escape approximation |
| ZEP-0111 | Witness-Dispatched Do-Notation | Implemented | Notation-only rider; does not change `raise` or `handle` |
| ZEP-0114 | Named Witness Declarations, Default Witnesses, and Explicit Witness Imports | Implemented | Supporting witness-scope work |
| ZEP-0115 | Warning-Severity Compiler Diagnostics | Implemented | Supporting compiler diagnostics work |

ZEP-0052 is already `Shipped` in `0.5.0`; it is not part of this bundle.

### Acceptance queue for `0.7.0`

The intent is for `0.7.0` to cover the remaining type-bound drafts as well. They cannot be marked
`Candidate` yet: ZEP-0000 requires an Accepted design. They stay `Unscheduled` and join the
candidate set only after acceptance.

| ZEP | Name | Dependency or scope |
| --- | --- | --- |
| ZEP-0106 | Stored Existentials and Holder ABI | Stored positions and the holder ABI; builds on ZEP-0104 and ZEP-0105 |
| ZEP-0107 | Ancestor-Aware Runtime Type Descriptors | Generic effect matching under `under`; depends on ZEP-0013, ZEP-0005, and ZEP-0015 |
| ZEP-0108 | Contract-Bound Runtime Tests | Evidence selection, deliberately independent of descriptor matching |
| ZEP-0109 | Rank-2 Polymorphism: Polymorphic Function Types and Generic Lambdas | Polymorphic function types and generic lambdas |
| ZEP-0116 | Remaining Existential Opening and Escape Work | Completes the opening and escape scope left by ZEP-0105 |

If a queue item is accepted during the `0.7.0` cycle, mark it `Candidate` for `0.7.0` in the
tracker and include it in the release. If it is not accepted in time, leave it `Unscheduled` and let
it follow later; it must not hold the release open.

### Implementation order

1. **ZEP-0054, then ZEP-0013.** ZEP-0054 is independent of reification and removes the resolver
   special case for payloads. ZEP-0013 then adds `under` edges, inherited payload properties,
   `raises Effect`, the explicit catch-all `case Effect`, and most-specific handler selection.
2. **ZEP-0015, then ZEP-0104, then ZEP-0005.** ZEP-0015 supplies the shared descriptor. ZEP-0104 is
   the bounded existential and type-bound slice that consumes it. ZEP-0005 applies the same identity
   to generic effect payloads with closed applications. ZEP-0105, ZEP-0107, and ZEP-0106 extend that
   line.
3. **ZEP-0100 and ZEP-0072 are orthogonal.** They share the value-level identity theme but not the
   runtime descriptor machinery. They can land in the same release without making either track
   depend on the other.
4. **ZEP-0111, ZEP-0114, and ZEP-0115 ride along.** They are already implemented, in the same
   compiler and library surface, and carry no new runtime mechanism. Bundling them is a versioning
   decision, not a design dependency.

### Why this is large but coherent

`0.6.0` is not a size precedent. Its 24 rows were already implemented, so the release was a
stabilization bundle. `0.7.0` is the first bundle in which several new mechanisms land, so depth
matters more than length. It is still coherent because every item changes type identity, runtime
descriptors, or type-bound tests. None of them changes control flow or introduces a new `raise`
typing rule. The effect-control proposals belong to `0.8.0`.

## `0.8.0` — Effect control and response typing

`0.8.0` is the proposed effect-control release. It is not scheduled: every headline proposal is
`Draft`, so none can be a `Candidate` under ZEP-0000.

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0118 | Response-Typed Effects and Tail-Resumptive Handlers | Draft | Headline: a `raise` returns a handler-supplied value |
| ZEP-0110 | Block-Bodied Handler Expressions | Draft | Notation-only companion to the response-type work |
| ZEP-0111 | Witness-Dispatched Do-Notation | Candidate `0.7.0` | Fallback only if it does not ship in `0.7.0` |
| ZEP-0112 | Structured Concurrency on Virtual Threads | Draft | Independent library track: `Scope`, `Task`, cancellation, timeouts |
| ZEP-0120 | Async as an Effect and the Future/Task Surface | Draft | Async surface; depends on ZEP-0118 and ZEP-0112 |
| ZEP-0119 | One-Shot Continuations, Deep Handlers, and Cancellation | Draft | Prototype-gated research; not a release commitment |
| ZEP-0121 | Multi-Shot Continuations and Backtracking | Deferred | Off the release list |

### Path to a `0.8.0` candidate set

1. **Resolve ZEP-0118's open design points and accept it.** The remaining blockers are response
   syntax and generic response bounds, the interaction with ZEP-0013's explicit `case Effect`
   catch-all, and whether `Ask` and `Log` are library carriers or examples. The catch-all question
   is the one that must not be settled by accident inside `0.7.0`: a catch-all that covers a
   responding carrier has to abort it, cancel it, or refuse coverage, and that choice belongs to
   ZEP-0118.
2. **Accept ZEP-0110 as the notation companion.** It adds a block form to `handle` without changing
   semantics.
3. **Mark ZEP-0118 and ZEP-0110 `Candidate` for `0.8.0` after acceptance**, not before.
4. **Ship ZEP-0111 in `0.7.0` if possible.** It is already implemented and notation-only. If it
   slips, it can join `0.8.0`.

### Independent and conditional tracks

- **ZEP-0112 is library-only and independent.** It does not need ZEP-0118. It can be scheduled once
  the scope/task spike is complete and becomes a separate candidate bundle of its own.
- **ZEP-0120 waits for ZEP-0118 and ZEP-0112.** Its first backend is blocking `await` over
  `Task.value()`, so it cannot precede the runtime primitive.
- **ZEP-0119 stays research.** Acceptance is gated on the backend prototype and the continuation
  ABI questions recorded in the proposal. It is not a `0.8.0` commitment.
- **ZEP-0121 stays `Deferred`.** Multi-shot resumption needs a concrete use case that `do`-notation
  cannot serve, plus answers to the resource, mutation, handler-state, cancellation, and type-system
  questions in the proposal.

### Why not one release

ZEP-0118 depends on the identity and payload work in `0.7.0`, but it also changes global `raise`
typing and handler reachability. Bundling it with `0.7.0` would join two open designs and force
response metadata plus `Yield`, `Ask`, and `Log` into the same API-index and standard-library bumps.
ZEP-0119 is prototype-gated, ZEP-0120 depends on two other proposals, and ZEP-0121 is `Deferred`.
None of those is a defensible `0.7.0` commitment.

## Backlog after the planned bundles

These items are not in `0.7.0` or `0.8.0`. They close already shipped gaps or start new subsystems,
and they follow the two planned bundles.

1. **ZEP-0091 — Annotation consumption.** Accepted and unscheduled. Deliver Direction B
   (compiler-recognized annotations) first, then Direction A (contract-directed `from` bindings).
   Direction B unblocks `SerialName` in ZEP-0092 and the alias surface in ZEP-0097.
2. **ZEP-0097 — Annotation target for model mapping clauses.** Follows ZEP-0091 Direction B and the
   implemented ZEP-0079 model surface.
3. **Collection completion (ZEP-0094 / ZEP-0060 follow-up).** `MapBackedSet`,
   `SortedMap`/`TreeMap`, `sameElements`/`elementsHash`, `Collector`-based `Stream.toList`
   construction, and the remaining separate-implementation coverage.
4. **Derivation expansion (ZEP-0008 follow-up).** Mutually recursive declaration groups, `Display`
   derivation, and the policy for deriving `Eq`/`Hashable` on container types.
5. **ZEP-0099 — Row-polymorphic derived witnesses.** Closes the limitation recorded by ZEP-0092.
6. **ZEP-0095 — Bytecode quality and evidence inlining.** Bundle the optimizations behind measurable
   acceptance criteria rather than landing them piecemeal.
7. **ZEP-0096 — Primitive specialization.** Follow the bytecode-quality work.
8. **ZEP-0070 — Generic and nominal newtype representations.** A prerequisite for several numeric
   and serialization follow-ups.
9. **ZEP-0050 — Remaining Java interoperability work.** The SAM, inherited-member, JPMS, and
   extensible-binding gaps left by ZEP-0047 through ZEP-0049.

## Design decisions

These are the decisions that shape the plan. Details live in each ZEP.

- **ZEP-0054:** full ordinary member model for effect payloads; canonical `new` plus delegating
  named constructors.
- **ZEP-0013:** `under` modeled on contract conformance; JVM-hierarchy representation; `Effect` is
  also a value type; explicit `case Effect` catch-all; most-specific handler selection.
- **ZEP-0015:** one shared internal applied-type descriptor; payload-carried for effects and a
  hidden parameter for existential hidden types and reified variables; API-index schema 13.
- **ZEP-0005:** accepted 2026-11-10 with closed applied identity; invariant exact fully written
  applications; generic effect declarations and closed applied `raises` entries need no schema
  change; implementation pending.
- **ZEP-0072:** class static functions lower to companion namespace functions with no class
  type-parameter capture, `this`, or member-name collisions.
- **ZEP-0100:** callable references carry static `owner + kind + name` identity; `F` carries the
  declaration's `raises`; static function references build on ZEP-0072.
- **ZEP-0104:** star projections and parameter-position anonymous existentials replace the former
  `let T: Bound` binder and `?` wildcard; exact type-bound tests against closed applied types;
  bounded implementation landed.
- **ZEP-0105:** branch-scoped opening and escape semantics; implemented 2026-11-11.
- **ZEP-0106–ZEP-0109 and ZEP-0116:** stored existentials, ancestor-aware descriptors,
  contract-bound tests, rank-2 polymorphism, and remaining opening work; all `Draft`.
- **ZEP-0110:** block form of `handle`; notation only, no semantic change.
- **ZEP-0111:** `do` with `let x <- e` binds, mandatory final `yield`, and receiver-call lowering
  through the `Monad` witness; implemented 2026-11-11.
- **ZEP-0112:** structured concurrency on virtual threads (`Scope`, `Task`, cancellation, timeouts,
  effect capture at task boundaries); blocking suspension is the mechanism.
- **ZEP-0114:** named witnesses in a parallel namespace, explicit `import witness`, defaults beside
  their target type, and anonymous witness deprecation.
- **ZEP-0115:** warning-severity compiler diagnostics; implemented.
- **ZEP-0118–ZEP-0121:** response-typed effects and tail-resumptive handlers, one-shot
  continuations, the async surface, and multi-shot continuations respectively. ZEP-0118 through
  ZEP-0120 are `Draft`; ZEP-0121 is `Deferred`; ZEP-0007 is `Superseded`.

## History

- **2026-11-08.** Initial reconciliation of `documents/zeps/ZEP.md`. The `0.6.0` candidate set was
  recorded and the last file/tracker status mismatches were removed. The post-`0.6.0` program was
  identified as effect semantics and runtime identity.
- **2026-11-09.** `0.6.0` shipped, moving its 24 rows to `Shipped` / `0.6.0`. `0.7.0` candidacy
  began with ZEP-0054 and ZEP-0013; ZEP-0015 and the bounded ZEP-0104 slice were accepted and
  implemented under the recorded prototype and descriptor conditions.
- **2026-11-10.** ZEP-0005 was reopened and accepted with a narrower closed-applied-effect scope.
  ZEP-0104's revision was approved. ZEP-0110 through ZEP-0112 were recorded, ZEP-0114 was accepted,
  and ZEP-0111 was implemented.
- **2026-11-12.** The resumption exploration was split. ZEP-0007 became `Superseded`; ZEP-0118
  through ZEP-0120 carry the design direction, and ZEP-0121 becomes the `Deferred` multi-shot
  record. ZEP-0112 was retitled "Structured Concurrency on Virtual Threads" and keeps blocking
  suspension as its mechanism. ZEP-0005, ZEP-0105, ZEP-0111, ZEP-0114, and ZEP-0115 were added to
  the `0.7.0` candidate set, and the `0.8.0` effect-control bundle was proposed.

## `0.6.0` record

`0.6.0` shipped with the 24 rows that were `Candidate` for that bundle: `0031`, `0060`, `0061`,
`0065`, `0068`, `0069`, `0071`, `0073`, `0076`, `0077`, `0078`, `0079`, `0080`, `0081`, `0082`,
`0083`, `0084`, `0086`, `0087`, `0088`, `0090`, `0092`, `0093`, and `0094`. ZEP-0068 remains
`Accepted` after shipping; its external-name and adapter scope stays open under ZEP-0091, ZEP-0097,
and ZEP-0098.

## Notes

- Proposal status and release state are independent. A row can be `Implemented` and still
  `Unscheduled`; that is not an error by itself.
- A ZEP cannot move to `Candidate` while it is `Draft` or `Deferred`; accept the design first.
- `Candidate` is a proposed release target, not a commitment. `Planned` records an approved bundle.
- The `0.7.0` and `0.8.0` descriptions are planning records. They become approved scope only when
  the relevant rows move to `Planned`.
- Do not reuse a ZEP number. The next available number is `0122`.
- Routine fixes and contained changes do not need a ZEP.
- `pom.xml` and `CHANGELOG.md` record `0.6.0`; the tracker rows for the shipped bundle read
  `Shipped` / `0.6.0`.

## References

- [ZEP tracker](../zeps/ZEP.md)
- [ZEP process](../zeps/ZEP-0000.md)
- [ZEP template](../zeps/ZEP-0001.md)
- [Classfile quality inventory](./classfile-quality-inventory.md)
- [ZEP follow-up inventory](./zep-follow-up-inventory.md)
