# ZEP Tracker and Release Plan

| Field | Value |
| ----- | ----- |
| Date | 2026-11-21 |
| Scope | Current release state in `documents/zeps/ZEP.md` and near-term sequencing |
| Purpose | Planning companion for bundle contents, acceptance gates, and backlog order |

This is the current-state plan that accompanies the [ZEP tracker](../zeps/ZEP.md). The tracker
remains the source of truth for proposal status and release state. This document explains the
intended bundle shape, dependencies, and ordering; it does not change a row by itself. Update it
when bundle scope or sequencing changes.

## Current state

The tracker records 164 ZEPs: 103 shipped, 0 planned, 19 candidates, 35 unscheduled, and 7 rows
that are deferred or not applicable. `0.7.0` shipped on 2026-11-14, `0.8.0` shipped on 2026-11-20,
`0.9.0` is the proposed effect-control follow-on with fourteen candidates (ZEP-0112, ZEP-0118,
ZEP-0123, and ZEP-0147 implemented; remaining designs draft), and `0.10.0` is a
tentative model-metadata, scoped-type, and resource-handling bundle.

### Bundle summary

| Bundle | Theme | Current state | Contents |
| --- | --- | --- | --- |
| `0.6.0` | Stabilization of already implemented work | Shipped | 25 rows |
| `0.7.0` | Type identity, reification, and type bounds | Shipped 2026-11-14 | 15 shipped; acceptance queue closed |
| `0.8.0` | Normalization and nominal implementation | Shipped 2026-11-20 | 25 shipped; acceptance queue closed |
| `0.9.0` | Effect control and response typing plus library breadth | Proposed; 14 candidates | ZEP-0118 (headline, implemented), ZEP-0112 (implemented), ZEP-0123 (implemented), ZEP-0147 (implemented), ZEP-0119, ZEP-0120, ZEP-0066, ZEP-0067, and ZEP-0152–ZEP-0157; remaining designs `Draft` |
| `0.10.0` | Model system completion, scoped types, and resource handling | Proposed; 5 candidates | ZEP-0091 and ZEP-0097 model metadata; ZEP-0158, ZEP-0160, and ZEP-0161 scoped resource handling; ZEP-0162 generic admissibility is unscheduled |

`0.7.0` shipped on 2026-11-14. Its 15 rows are `Shipped`; release scope is approved and complete.
`0.8.0` shipped on 2026-11-20 with all twenty-five rows `Shipped`. It covered normalization,
nominal representation, standard-library ownership plus testing and logging APIs, refinement
correctness, operator inference, function-valued and nullable invocation, value blocks, witness
provision and selection, and the Java interop boundary. `0.9.0` is a planning target with fourteen
candidates, not a scheduled release; ZEP-0112, ZEP-0118, ZEP-0123, and ZEP-0147 are implemented
and the other designs remain draft.

The bundles stay separate because `0.7.0` changes type identity, runtime descriptors, type-bound
tests, and effect payload identity, while `0.9.0` changes control flow through response-typed
effects, resumption, and the async surface. `0.8.0` sits between them as a bounded normalization
release so the identity work and the new `raise` typing rule do not force response metadata plus
`Yield`, `Ask`, and `Log` into the same API-index and standard-library bumps.

## `0.7.0` — Type identity, reification, and type bounds

`0.7.0` bundles changes to runtime type descriptors, effect payload identity, exact type-bound
tests, and existential opening with supporting proposals that are already implemented. The release
validates one coherent identity story through one API-index and separate-compilation cycle.

### Released set

Every row below is `Shipped` / `0.7.0` in the tracker.

| ZEP | Name | Proposal status | Role |
| --- | --- | --- | --- |
| ZEP-0005 | Generic Effect Payloads with Closed Applied Identity | Implemented | Generic effect declarations with exact closed applications and payload-carried descriptors |
| ZEP-0013 | Effect Subtyping and the Top Effect Type | Implemented | `under` edges, inherited payloads, and the `Effect` top type |
| ZEP-0015 | Runtime Reification of Applied Type Identity | Implemented | Shared applied-type descriptor; API-index schema 14 adds ZEP-0106 holder metadata |
| ZEP-0054 | Nominal Effect Payload Types | Implemented | Ordinary member model for effect payloads |
| ZEP-0072 | Class-Scoped Static Functions as Companion Namespace Members | Implemented | Supporting language work; orthogonal to reification |
| ZEP-0100 | Callable References for Methods, Constructors, and Static Functions | Implemented | Orthogonal companion; static `owner + kind + name` identity |
| ZEP-0104 | Wildcard Existentials and Type-Bound Pattern Matching | Implemented | Bounded initial slice: star projections, anonymous existentials, exact type-bound tests |
| ZEP-0105 | General Existential Types and Escape Semantics | Implemented | Branch-scoped opening and escape semantics |
| ZEP-0106 | Stored Existentials and Holder ABI | Implemented | Conditional holders, stored field/local/array/collection positions, and API-index schema 14 landed; model holder cost recorded (24 B/field); ZEP-0109 callback comparison recorded (4.17–5.09 ns/field for the `Unit` shape, 15.5 B/field); no `Model` API change |
| ZEP-0109 | Rank-2 Polymorphism: Polymorphic Function Types and Generic Lambdas | Implemented | Rank-2 syntax, checking, alpha-equivalence, eta-expansion, the hidden-slot runtime ABI, per-application evidence/descriptor threading, API-index preservation, and separate compilation landed; application cost measured |
| ZEP-0111 | Witness-Dispatched Do-Notation | Implemented | Notation-only rider; does not change `raise` or `handle` |
| ZEP-0114 | Named Witness Declarations, Default Witnesses, and Explicit Witness Imports | Implemented | Supporting witness-scope work |
| ZEP-0115 | Warning-Severity Compiler Diagnostics | Implemented | Supporting compiler diagnostics work |
| ZEP-0116 | Remaining Existential Opening and Escape Work | Implemented | Mixed and nested opening, `else if` and value-producing `if` placements, and branch escape rejection landed; branch-local ABI unchanged |
| ZEP-0122 | Runtime Applied-Type Match Patterns | Implemented | Descriptor-backed exact applied patterns in `match`; required companion to ZEP-0005 |

ZEP-0122 is the required match-side companion to ZEP-0005. One exact-descriptor test serves both
ordinary applied patterns and generic effect handler cases; neither feature should ship without the
other in `0.7.0`.

ZEP-0052 is already `Shipped` in `0.5.0`; it is not part of this bundle.

### Acceptance queue for `0.7.0`

The `0.7.0` acceptance queue is closed. ZEP-0109 and ZEP-0116 have implemented designs and are now
`Shipped` rows for `0.7.0`. ZEP-0107 is `Accepted` but has been removed from the program bundle
and is `Unscheduled`; ZEP-0108 remains `Deferred` and outside the bundle. No acceptance-queue item
remains, and no further row joins `0.7.0` without a new, explicit bundle decision.

The implementation is complete and the bundle shipped on 2026-11-14. Its rows are `Shipped` /
`0.7.0`.

ZEP-0116's accepted scope is mixed and nested opening, the `else if` and value-producing `if`
placements, and complete escape rejection. Binder-declared bounds and markers, `C<in B>` opening,
result-position existentials, escape approximation, and variance changes remain outside the design,
so the proposal adds no holder or API-index ABI of its own.

### Implementation order

1. **ZEP-0054, then ZEP-0013.** ZEP-0054 is independent of reification and removes the resolver
   special case for payloads. ZEP-0013 then adds `under` edges, inherited payload properties,
   `raises Effect`, the explicit catch-all `case Effect`, and most-specific handler selection.
2. **ZEP-0015, then ZEP-0104, then ZEP-0005 and ZEP-0122 together.** ZEP-0015 supplies the shared
   descriptor. ZEP-0104 is the bounded existential and type-bound slice that consumes it. ZEP-0005
   applies the same identity to generic effect payloads with closed applications; ZEP-0122 applies
   it to ordinary `match` patterns. Factor one exact-descriptor test for both. ZEP-0105, ZEP-0106,
   and ZEP-0116 extend that line. ZEP-0116's mixed and nested patterns reuse the descriptors the
   pattern already requires and add no runtime metadata.
3. **ZEP-0109 is orthogonal and implemented.** Its hidden-slot runtime ABI and API-index function
   binders do not depend on the applied-descriptor line. It is validated in the same
   separate-compilation cycle without gating the existential work.
4. **ZEP-0100 and ZEP-0072 are orthogonal.** They share the value-level identity theme but not the
   runtime descriptor machinery. They can land in the same release without making either track
   depend on the other.
5. **ZEP-0111, ZEP-0114, and ZEP-0115 ride along.** They are already implemented, in the same
   compiler and library surface, and carry no new runtime mechanism. Bundling them is a versioning
   decision, not a design dependency.

### Scope and risk

`0.7.0` is broad by mechanism, not by unrelated surface area. Every planned row changes type identity,
runtime descriptors, type-bound tests, existential opening, or rank-2 function types; none changes
control flow or introduces a new `raise` typing rule. Bundle size is not the planning test: the
release stays coherent because its items share the same identity machinery and validation cycle.
ZEP-0116 deliberately leaves escape approximation and result-position existentials out so it does
not add a second ABI alongside ZEP-0106.

## `0.8.0` — Normalization and nominal implementation (shipped)

`0.8.0` shipped on 2026-11-20 with all twenty-five rows `Shipped`; the acceptance queue is closed.
The release covered project configuration, nominal representation, annotation targets and variance,
refinement correctness, intrinsic ownership, standard-library testing and logging APIs, operator
inference for typeless lambdas, function-valued invocation, value-producing blocks, witness
provision and selection, and the Java host-access and SAM boundary. It changed no `raise`/`handle`
typing and added no runtime control-flow mechanism. Per-row detail lives in the ZEPs and in
[`ZEP.md`](../zeps/ZEP.md); this review only records the bundle state.

## `0.9.0` — Effect control and response typing

`0.9.0` is the effect-control bundle plus a library track. It has fourteen `Candidate` rows;
ZEP-0112, ZEP-0118, ZEP-0123, and ZEP-0147 are implemented and the others retain `Draft` designs,
so the candidacy records the intended target rather than an approved scope; ZEP-0000 requires
acceptance before any row becomes `Planned`. ZEP-0107 is `Accepted` but is
`Unscheduled` outside the bundle.

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0118 | Response-Typed Effects and Tail-Resumptive Handlers | Implemented / `Candidate` | Headline: a `perform` returns a handler-supplied value |
| ZEP-0147 | Replace `raise` and `raises` with `perform` and `performs` | Implemented / `Candidate` | Hard-drop the exception-oriented keywords; use neutral effect-operation vocabulary |
| ZEP-0123 | Admissibility Clauses and Leading Reification | Implemented / `Candidate` | `admits` clause (`nullable`, `mutable`, `effect`, `effect...`) and leading `reified`; response constraints stay `Responds<R>` |
| ZEP-0112 | Structured Concurrency on Virtual Threads | Implemented / `Candidate` | Library-only `Scope`, `Task<T, E>`, cancellation, deadlines, effect capture at joins |
| ZEP-0120 | Async as an Effect and the Future/Task Surface | Draft / `Candidate` | Async surface; depends on ZEP-0118 and ZEP-0112 |
| ZEP-0119 | One-Shot Continuations, Deep Handlers, and Cancellation | Draft / `Candidate` | Prototype-gated continuation work; acceptance waits on the backend prototype |
| ZEP-0066 | Standard Library TOML Parser | Draft / `Candidate` | Additive `zeron.parsers.toml` package; independent of effect work |
| ZEP-0067 | Standard Library XML Parser | Draft / `Candidate` | Additive `zeron.parsers.xml` package; independent of effect work |
| ZEP-0152 | Standard Library Randomness | Draft / `Candidate` | Seedable PRNG, bounded values, pick and shuffle |
| ZEP-0153 | Descriptive Statistics in `zeron.math` | Draft / `Candidate` | Mean, median, variance, standard deviation |
| ZEP-0154 | Ordered Collections | Draft / `Candidate` | `PriorityQueue`, `SortedSet`, `SortedMap` by `Comparable` |
| ZEP-0155 | Binary Buffers and Encodings | Draft / `Candidate` | `ByteBuffer`, `ByteReader`, endianness, varints, hex, Base64 |
| ZEP-0156 | Text Formatting and Regular Expressions | Draft / `Candidate` | Placeholder `Formatter` and compiled `Regex` |
| ZEP-0157 | Collection Building APIs | Draft / `Candidate` | `List.build`, `Set.build`, `Map.build`, `Stream.build` |
| ZEP-0111 | Witness-Dispatched Do-Notation | Shipped `0.7.0` | Not part of `0.9.0` |
| ZEP-0121 | Multi-Shot Continuations and Backtracking | Deferred | Off the release list |

### Path to a `0.9.0` candidate set

1. **ZEP-0118 is implemented.** `responds`, response-typed `raise`, inline tail-resumptive cases,
   the thread-local handler frame, API-index schema 17, and the `Ask`, `Yield`, and `Log` library
   effects are in place. Named/first-class resuming cases, generic responding raises, and general
   continuation capture remain follow-up scope owned by ZEP-0119.
2. **Accept ZEP-0147 as the effect-operation rename.** It changes spellings, not effect semantics,
   and removes `raise`/`raises` rather than introducing aliases.
3. **Accept the dependent rows after their prerequisites.** ZEP-0123 is a syntax-only change and
   does not depend on response typing; it keeps `Responds<R>` as ZEP-0118's response bound. ZEP-0120
   needs ZEP-0118 and ZEP-0112, and ZEP-0119 needs its backend prototype and continuation ABI
   answers. ZEP-0112 is library-only, implemented, and independent of response typing.
4. **Mark the accepted rows `Planned` for `0.9.0`**, not before; the ten unimplemented rows hold
   `Candidate` while their designs remain `Draft`.
5. **ZEP-0111 shipped in `0.7.0`.** It is notation-only and is not part of the `0.9.0` candidate
   path. ZEP-0110 is superseded by [ZEP-0146](../zeps/ZEP-0146.md), which shipped in `0.8.0`.

ZEP-0107 is outside this plan. If it returns, it first needs the generic-effect propagation slice
that ZEP-0005 leaves deferred: applications containing type parameters, forwarding through generic
calls, and omitted handler arguments.

### Scope and risk

ZEP-0118 depends on the identity and payload work in `0.7.0`, but it also changes global `raise`
typing and handler reachability; `0.8.0` shipped without sharing its API-index and standard-library
validation cycle, so response metadata plus `Yield`, `Ask`, and `Log` arrive together here. ZEP-0147
is a deliberate source break across the standard library, tests, formatter, and editor surfaces and
needs its own migration pass. ZEP-0112 is library-only and has landed ahead of the response-typing
work. ZEP-0120 cannot precede ZEP-0118 and ZEP-0112, and ZEP-0119's candidacy is explicitly
prototype-gated. The library rows (ZEP-0066, ZEP-0067, and ZEP-0152–ZEP-0157) are additive surface
and can land in any order; each needs its own design acceptance. ZEP-0121 stays `Deferred`.
Bytecode-quality rows stay out of this bundle: ZEP-0095 is a compiler-wide
emission program and ZEP-0096 is ABI-changing primitive specialization, so both belong in a cycle
that is not already changing `raise` typing and handler codegen.

## `0.10.0` — Model system completion, scoped types, and resource handling

This is a tentative bundle for the accepted annotation orphans, the model-improvement drafts,
scoped-type prerequisites, and resource-handle semantics. It is not scheduled and follows `0.9.0`
in the proposed sequence.

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0091 | Annotation Consumption | Accepted / `Candidate` | Compiler-recognized annotation consumption; unblocks external names in ZEP-0092 |
| ZEP-0097 | Annotation Target for Model Mapping Clauses | Accepted / `Candidate` | Adds `MAPPING` annotations to model clauses; depends on ZEP-0091 |
| ZEP-0160 | Scoped Projection Types | Draft / `Candidate` | `scoped T` restriction, origin, escape rules, and canonical form; prerequisite for ZEP-0158 and ZEP-0161 |
| ZEP-0161 | Scoped Receiver Methods and Contract Conformance | Draft / `Candidate` | `scoped fn` / `scoped mut fn`, `Self` propagation, and conformance; prerequisite for ZEP-0158 |
| ZEP-0158 | Lambda Resource Handles with Scoped Receivers | Draft / `Candidate` | Expression-valued `File.open { ... }` with a `scoped &File` receiver; depends on ZEP-0160 and ZEP-0161 |
| ZEP-0101 | Field Representation Adapters for Model Mappings | Draft | Model-specific field adapters; acceptance required before candidacy |
| ZEP-0102 | Generic Models through Model Factories | Draft | Generic model construction; acceptance required before candidacy |
| ZEP-0103 | Polymorphic Models for Closed Sum Types | Draft | Model mappings over closed sums; acceptance required before candidacy |

The shipped foundation is ZEP-0079 (model declarations), ZEP-0092 (generated serialization
operations), ZEP-0093 (unified property identity), and ZEP-0098 (annotation targets for native
products, sums, and newtypes). ZEP-0091 should land first because ZEP-0097 consumes its
compiler-recognized annotation model. ZEP-0101–ZEP-0103 stay `Draft` / `Unscheduled` until their
designs are accepted; only then can they become candidates for this bundle.

ZEP-0160, ZEP-0161, and ZEP-0158 form the scoped resource-handling companion rather than model
proposals. ZEP-0160 defines the non-escape type and escape checker; ZEP-0161 defines the per-method
declaration surface and contract conformance; ZEP-0158 owns the expression-valued block, disposal,
failure payload, and nesting rules. ZEP-0162 records the generic admissibility design and stays
`Draft` / `Unscheduled` because version 1 deliberately rejects scoped type arguments and container
scoping. The resource rows are placed here because the lambda blocks depend on the `0.9.0`
effect-control and continuation work settling first, and because their cleanup rules must agree with
ZEP-0112's scope lifetime and ZEP-0119's resumption points. If those settle differently, the rows
move rather than forcing the bundle.

ZEP-0107 is not a model proposal. It extends runtime descriptors with ancestor metadata for generic
effects, so it remains `Accepted` / `Unscheduled` outside this bundle. If it returns, it needs the
generic-effect propagation slice that ZEP-0005 leaves deferred. Bundling it with model metadata
would combine an effect-runtime ABI change with a purely compile-time annotation and model change.

## Open planning decisions

- `0.7.0` was published on 2026-11-14; its 15 rows are `Shipped` / `0.7.0`, and its acceptance
  queue is closed.
- ZEP-0111 shipped in `0.7.0`; it does not fall back to `0.9.0`.
- `0.8.0` shipped on 2026-11-20 with its twenty-five rows marked `Shipped`; its acceptance queue is
  closed. ZEP-0146 and ZEP-0151 were accepted and implemented the same day. ZEP-0133 is
  `Implemented` / `Shipped` `0.8.0`; its proposal status was advanced after the release, when the
  shipped operator-constraint inference was noticed.
- ZEP-0050 was accepted on 2026-11-17 and implemented on 2026-11-18 as the host-access trust
  boundary and shipped in `0.8.0`. Direct Java access requires `unsafe`, facades stay the trusted
  tier, and `raises` stays control flow rather than purity.
- ZEP-0148 was split from ZEP-0050, accepted on 2026-11-17, implemented on 2026-11-18, and shipped
  in `0.8.0`. It owns Java SAM conversion for effect-free lambdas and function references. A
  converted callback may not carry a Zeron effect row. ZEP-0149 was split as a `Draft` /
  `Unscheduled` inventory for general
  inherited/interface member lookup, external contracts for Java interfaces, external enums, JPMS,
  and external-binding extensibility; none of it is implied by the `0.8.0` candidacy.
- Sequence ZEP-0132 first as the immediate safe-cast correction, then ZEP-0131, then the
  representation track (ZEP-0070, ZEP-0129, ZEP-0128), with ZEP-0098, ZEP-0113, ZEP-0130,
  ZEP-0133, ZEP-0134, ZEP-0150, ZEP-0146, ZEP-0151, ZEP-0050, ZEP-0148, ZEP-0064, and ZEP-0063
  independent.
  ZEP-0050's `unsafe` gate and ZEP-0148's SAM conversion are implemented; ZEP-0132, ZEP-0131, and
  ZEP-0134 are implemented.
  Implement ZEP-0136 before ZEP-0137 for the witness-bundle track.
- Settle the `String.split` empty-separator and return-type questions, the `charAt`/`at` shape, and
  the regex dialect questions in ZEP-0130 before treating the inventory as complete.
- ZEP-0126 implementation details are settled: `Result` failures print `error.toString()` to
  `System.err` and exit `1`, `main` visibility stays unconstrained, and the `zeron.io` helpers are
  ordinary `raises IoFailure` functions.
- Implement ZEP-0118's `Responds<R>` generic response bound, and resolve catch-all interaction and
  carrier policy, before proceeding with the `0.9.0` candidate set; accept ZEP-0147 before
  implementing the hard keyword drop.
- Decide whether the ZEP-0112 scope/task spike is complete enough to schedule independently.

## Backlog after the planned bundles

These items are not in `0.7.0`, `0.8.0`, `0.9.0`, or the tentative `0.10.0` model, annotation,
scoped-type, and resource bundle. They close already shipped gaps or start new subsystems.

1. **Collection completion (ZEP-0094 / ZEP-0060 follow-up).** `MapBackedSet`,
   `sameElements`/`elementsHash`, `Collector`-based `Stream.toList` construction, and the remaining
   separate-implementation coverage. `SortedMap` and `TreeMap` moved to ZEP-0154.
2. **Derivation expansion (ZEP-0008 follow-up).** Mutually recursive declaration groups, `Display`
   derivation, and the policy for deriving `Eq`/`Hashable` on container types.
3. **ZEP-0099 — Row-polymorphic derived witnesses.** Closes the limitation recorded by ZEP-0092.
4. **ZEP-0095 — Bytecode quality and evidence inlining.** Bundle the optimizations behind measurable
   acceptance criteria rather than landing them piecemeal.
5. **ZEP-0096 — Primitive specialization.** Follow the bytecode-quality work.
6. **ZEP-0163 — Automatic read-only widening for inherited generic method bounds.** Make
   inherited read-only binders solve against mutable-projection receivers, so operations such as
   `Stream.map` remain available without making the container generally covariant.

### Static verification drafts (unscheduled)

Seven `Draft` / `Unscheduled` proposals record a proposed static-verification track outside the
current bundles. They are not candidates and do not alter `0.8.0`, `0.9.0`, or `0.10.0`. The
proposed sequence is:

| Order | ZEP | Role |
| --- | --- | --- |
| 1 | [ZEP-0138](../zeps/ZEP-0138.md) | Predicate and fact IR; normalization, three-valued decision, constant evaluation, trust model, and predicate serialization |
| 2 | [ZEP-0139](../zeps/ZEP-0139.md) | Checked `new` for guarded newtypes; constant proof with a runtime fallback |
| 3 | [ZEP-0140](../zeps/ZEP-0140.md) | Comparison facts and type-intrinsic newtype facts in flow typing |
| 4 | [ZEP-0141](../zeps/ZEP-0141.md) | Predicate declarations and call-site fact transfer |
| 5 | [ZEP-0142](../zeps/ZEP-0142.md) | Interval and difference-bound facts, including stable length terms |
| 6 | [ZEP-0143](../zeps/ZEP-0143.md) | `IntRange` bounds, half-open ranges, and loop facts |
| 7 | [ZEP-0144](../zeps/ZEP-0144.md) | Parameter refinements and call-site preconditions |

The sequence is dependency-driven rather than a release commitment. ZEP-0138 should be reviewed
first because every feature slice consumes it. ZEP-0139 is the most separable first feature and can
start with constant evaluation. ZEP-0144 is the capstone and should not be scheduled until the fact
stores, predicate metadata, interval proofs, and range facts are stable. Promoting any row to
`Candidate` is a separate release decision; the current tracker rows remain `Unscheduled`.

### Documentation tooling (unscheduled)

[ZEP-0159](../zeps/ZEP-0159.md) records a generated standard-library reference: one hand-written
overview chapter plus deterministic per-package pages rendered from the public API index and its
`///` documentation. It is `Draft` / `Unscheduled`, outside every current bundle. It is a
repository and release-process change rather than a language or runtime feature, so it can be
scheduled independently once the output layout and CI check are accepted.

## Design decisions

These decisions shape the current plan. Details live in each ZEP.

- **ZEP-0041:** rejected. Fixed-width unsigned types would add a parallel numeric hierarchy,
  conversion rules, and JVM interop cases; nonnegative ranges and wire widths belong to checked
  newtypes and the static verification track.
- **ZEP-0042:** implemented through ZEP-0071's `Integer` and `Rational` types, shipped in `0.6.0`.
  Unified `BigNum` and arbitrary-precision floating point were not adopted; any future work needs a
  new proposal.
- **ZEP-0068:** implemented and shipped in `0.6.0` for its core contracts and generated operations.
  External names, mapping metadata, and concrete format adapters are separate proposals.
- **ZEP-0121:** proposal and release state are both `Deferred`; it is excluded from release
  planning and cannot enter a bundle without reopening the design.
- **ZEP-0158 / ZEP-0160 / ZEP-0161:** non-escape is a type capability, not a receiver naming trick.
  `scoped` is a restriction qualifier with an origin; version 1 limits it to non-generic nominal
  types and rejects scoped type arguments and container scoping. `scoped fn` is the per-method
  declaration surface; `scoped extension` is rejected. ZEP-0162 owns the future generic opt-in.

- **ZEP-0054:** full ordinary member model for effect payloads; canonical `new` plus delegating
  named constructors.
- **ZEP-0013:** `under` modeled on contract conformance; JVM-hierarchy representation; `Effect` is
  also a value type; explicit `case Effect` catch-all; most-specific handler selection.
- **ZEP-0015:** one shared internal applied-type descriptor; payload-carried for effects and a
  hidden parameter for existential hidden types and reified variables; API-index schema 13
  introduced these records, and schema 14 adds ZEP-0106 stored-existential holder metadata.
- **ZEP-0005:** accepted with closed applied identity; invariant exact fully written applications;
  generic effect declarations and closed applied `raises` entries need no schema change;
  implemented for closed applications; deferred propagation work remains owned by the proposal.
- **ZEP-0072:** class static functions lower to companion namespace functions with no class
  type-parameter capture, `this`, or member-name collisions.
- **ZEP-0100:** callable references carry static `owner + kind + name` identity; `F` carries the
  declaration's `raises`; static function references build on ZEP-0072.
- **ZEP-0104:** star projections and parameter-position anonymous existentials replace the former
  `let T: Bound` binder and `?` wildcard; exact type-bound tests against closed applied types;
  bounded implementation landed.
- **ZEP-0105:** branch-scoped opening and escape semantics; implemented.
- **ZEP-0106:** stored existentials and the conditional holder ABI; implemented for `0.7.0`.
  Implemented for fields, locals, array elements, collection elements, nested type arguments,
  conditional `Exists1`–`Exists3`/`ExistsN` holders, API-index schema 14, and separate-compilation
  holder round-trips. The array-backed model-field spike is recorded; the 2026-11-13 cost pass
  records 24 B/field, 1.92–1.96 ns/field to pack, and 0.54–0.99 ns/field to traverse against
  0 B and 0.30 ns/field for parallel arrays. The ZEP-0109 rank-2 callback comparison is recorded at
  3.63–3.81 ns/field for an `Int` result and 4.17–5.09 ns/field for the model-shaped `Unit` callback,
  both 15.5 B/field, against 0.52–0.72 ns/field direct monomorphic traversal. It no longer gates
  ZEP-0106; the measurements do not justify changing `AnyField`, `Model`, or property references.
- **ZEP-0107:** ancestor-aware descriptors; accepted 2026-11-13 but removed from the program
  bundle and `Unscheduled`. Reopening it requires scheduling the generic-effect propagation
  prerequisite first.
- **ZEP-0108:** contract-bound runtime tests; deferred because closed evidence makes every test
  statically decidable under the current model, and outside the bundle.
- **ZEP-0109:** rank-2 polymorphism; implemented and shipped in `0.7.0`. Application cost is
  recorded in the proposal and the ZEP-0015 validation matrix.
- **ZEP-0116:** remaining existential opening and escape work; implemented 2026-11-13 and shipped
  in `0.7.0`. Mixed and nested patterns, `else if` and value-producing `if`
  placements, and branch escape rejection landed; binder bounds, `in` opening, result-position
  existentials, escape approximation, and variance changes are out.
- **ZEP-0117:** declarative project targets and generated references; `[build]`, `[targets]`, a
  positional target for `build`/`run`, `--deny-warnings`/`--no-deny-warnings` precedence, and
  generated command and manifest reference pages. Implemented 2026-11-14 and `Candidate` for
  `0.8.0`; no language, runtime, standard-library, or API-index change.
- **ZEP-0110:** superseded by ZEP-0146. Its block form of `handle` and return-based value rule are
  historical; no part of it is carried forward.
- **ZEP-0111:** `do` with `let x <- e` binds, a final `yield` or monad-valued expression, and
  receiver-call lowering through the `Monad` witness; implemented and shipped in `0.7.0`.
- **ZEP-0112:** structured concurrency on virtual threads (`Scope`, `Task<T, E>`, cancellation,
  deadlines, effect capture at task boundaries); blocking suspension is the mechanism. Implemented
  2026-11-21.
- **ZEP-0114:** named witnesses in a parallel namespace, explicit `import witness`, defaults beside
  their target type, and anonymous witness deprecation.
- **ZEP-0115:** warning-severity compiler diagnostics; implemented.
- **ZEP-0118–ZEP-0121:** response-typed effects and tail-resumptive handlers, one-shot
  continuations, the async surface, and multi-shot continuations respectively. ZEP-0118 through
  ZEP-0120 are `Draft`; ZEP-0121 is `Deferred`; ZEP-0007 is `Superseded`.
- **ZEP-0124:** nominal static contract implementation; public class static functions satisfy
  type-position requirements, with implementation-block static entries for generic-class cases.
  Implemented 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0125:** optional HKT implementation blocks; `is` conformance plus public members supplies
  receiver-marked and receiverless operations, with explicit blocks retained for overrides and
  non-member implementations. Implemented 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0126:** compiler-owned `Main<A>` entry contract; `main` returns a closed entry value and
  the compiler calls `Main<A>.run`. `Unit`, `Int`, and `Result<Unit, E>` entry behavior is supplied
  by ordinary `default` witnesses in `zeron.lang`, and `Main.run` is declared `raises Effect`.
  Entry discovery reads the resolved return type, so an inferred `main` also selects its evidence.
  Implemented 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0127:** data-class components are public structural accessors while the type's visibility
  gates nameability; positional data-class patterns use only canonical components, generic type
  arguments may be inferred, and refutable applied cases with the same identity no longer make one
  another unreachable. Accepted 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0070:** generic and nominal newtype representations use direct carriers; the wrapper,
  `value` field, and `.value` accessor are removed; `this` and newtype-typed parameters are the
  representation inside newtype-owned extensions and witnesses, including synthetic witness helpers
  whose generic signatures use the carrier instead of the newtype name. Admissible representations
  include type parameters, function types, nominal Zeron types, and explicit Java external classes; guarded
  newtypes remain scalar-only. Implemented 2026-11-15 and `Candidate` for `0.8.0`.
- **ZEP-0098:** annotation targets cover native product and sum declarations, product components,
  and sum cases. The declaration sites are validated and recorded without defining annotation
  consumption, preserving the separation from ZEP-0091. Draft smoothing candidate for `0.8.0`.
- **ZEP-0113:** checked declaration-site `out` variance extends from classes and contracts to
  immutable sums and products. Synthesized payload fields are treated as read-only for variance
  checking, and `Option`, `Result`, and `Pair` adopt covariant parameters. Draft smoothing candidate
  for `0.8.0`.
- **ZEP-0128:** sum type parameters accept contract bounds; an amendment extends the same bound
  checking to product type parameters. Cases may declare marker-bounded existential type
  parameters; construction infers them, matching opens them with a branch-local `let` binder, and
  concrete case-local pattern arguments are rejected. Unqualified case names are accepted when the
  expected sum or scrutinee determines the case, or the name is uniquely visible; ordinary values
  shadow case names and ambiguity requires qualification. Implemented 2026-11-16 and `Candidate`
  for `0.8.0`.
- **ZEP-0129:** products remain binary; unary products are not legalized; single-value shapes use
  ZEP-0070 direct-representation newtypes with no `.value` accessor. Function and generic carriers
  are resolved recursively in newtype-owned bodies, including separate compilation of generic
  function newtypes and their extensions. Implemented 2026-11-16 and `Candidate` for `0.8.0`.
- **ZEP-0130:** intrinsic types keep compiler-owned identity and operations live in canonical
  `zeron.lang` extension/namespace modules, not in a second class. The initial `String` inventory
  covers literal and regex splitting, `charAt`/`at`, case conversion, trimming, replacement, lines,
  simple parsing, and joining. Accepted 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0131:** `is` and `matches` refine through `and`/`or`/`not`, stable read-only member paths
  participate, and unqualified field/property reads use the implicit `this` receiver. Accepted
  2026-11-14, implemented 2026-11-18, and `Candidate` for `0.8.0`. Implementation decisions:
  condition facts use the existing short-circuit transfer for `if`/`else if`/`while`/
  value-producing `if`; `matches` payloads are successful-path scoped and obey OR binding-set
  compatibility; stable paths are rooted at locals, parameters, or `this` with only final fields,
  storage-backed read-only properties, or sum payloads as segments; writes invalidate facts; custom
  getters, mutable members, array slots, and call results remain unrefined. Implicit receiver lookup
  is a read-only fallback after lexical and top-level lookup, applies to class/accessor/initializer/
  extension/contract-default bodies, and never retargets writes. Ambiguous reads are diagnosed;
  nullability and safe-navigation rules are unchanged. The language server completes and hovers
  implicit members and chained payload bindings, while the formatter preserves explicit `this.`
  spelling. No runtime or API-index ABI changed.
- **ZEP-0132:** `as?` always returns a nullable target, including for `T?`; null or mismatch yields
  null; the result requires a null check or safe navigation. Accepted 2026-11-14, implemented
  2026-11-18, and `Candidate` for `0.8.0`, with first implementation priority. Implementation
  normalizes a nullable target before runtime-test validation and emission, so the test uses the
  non-null base and the result keeps exactly one nullable layer.
- **ZEP-0134:** a postfix argument list may follow any expression whose type is a non-null ordinary
  function value. Accepted and implemented on 2026-11-18 and `Candidate` for `0.8.0`. The
  implementation adds `Expr.Invoke`; named, constructor, generic, and member-property calls keep
  their existing nodes. The callee resolves and emits once, arguments follow left to right, generic
  values reuse the stored-function evidence/reified-slot path, and the invocation lowers through
  the generated shape interface's `invokeinterface`. Nullable values use ZEP-0150's `?.()` safe
  invocation, receiver-function values require receiver-call syntax, and explicit type arguments
  after an arbitrary expression callee remain unsupported. No runtime, API-index, or function-shape
  ABI changed.
- **ZEP-0150:** a nullable function value invoked as `couldBeNull?.()` skips the argument list and
  the result when the callee is null, and otherwise invokes the value and produces a nullable
  result. It reuses safe-navigation parsing and the ZEP-0134 invocation shape rather than adding a
  node family or runtime representation. Accepted and implemented on 2026-11-18 and `Candidate` for
  `0.8.0`.
- **ZEP-0133:** an uncontextualized typeless lambda written directly as an immutable local `let`
  initializer may infer a rank-1 scheme from numeric or comparison operator use. `(a, b) -> a + b`
  infers `<T: Numeric<T>> (T, T) -> T`; `/` infers `Fractional<T>`; `(a, b) -> a < b` infers
  `<T: Comparable<T>> (T, T) -> Boolean`. Each use resolves evidence independently; string
  concatenation, equality, arbitrary member operations, concrete defaults, and arbitrary lambda
  positions remain outside the rule. Accepted 2026-11-14, implemented 2026-11-20, and `Shipped` /
  `0.8.0`.
- **ZEP-0063:** tests are top-level functions marked `@Test`; `zeron.test` provides assertions that
  raise a typed `TestFailure` effect, and `@Ignore` excludes a test. Test roots come from the project
  manifest, `kind = "test"` targets get a compiler-generated runner, and `zeron test` builds and runs
  them. Accepted for `0.8.0` on 2026-11-19 as the testing and tooling companion to ZEP-0064 and
  ZEP-0117; filters, machine-readable output, fixtures, parallelism, and process isolation are
  deferred.
- **ZEP-0064:** `zeron.log` uses `Level` (`Trace` through `Error`), a `Record` with a monotonic
  `Instant`, level, logger name, message, structured `Map<String, Value>` fields, and an optional
  cause. `LogSink` is the output boundary, with a console sink and `minLevel` filtering, and `Logger`
  binds a name, sink, clock, and context map whose `with` copies rather than aliases. Message thunks
  run only when the level is enabled, configuration is explicit with no global registry, and writes
  preserve call order per sink without cross-thread guarantees. Implemented 2026-11-19 and
  `Candidate` for `0.8.0`; destination failure reporting remains open.
- **ZEP-0136:** witness bundles group the providers for one target into a named or `default`
  declaration. Members are unnamed; identity is bundle plus applied contract and target. The
  prerequisite closure is resolved at declaration time; only canonical default dependencies are
  recorded and exported, while nominal and compiler-provided evidence stays authoritative. Named
  bundles are selection-only, `default` bundles are ambient canonical evidence, and an exported
  bundle may not expose package-local types. Accepted 2026-11-15 and `Candidate` for `0.8.0`.
- **ZEP-0137:** a statement-only `using witness (...)` block selects named bundles and named
  standalone witnesses lexically. Bundle members and recorded canonical default dependencies enter a
  scoped evidence map; ambient defaults are shadowed locally, nested blocks may re-pair one pair, and
  closures capture selected handles at creation. There is no runtime lookup or dynamic scope.
  Compiler-owned structural and built-in `Eq`/`Hashable`, derived serialization, `Main<A>`, and
  intrinsic arithmetic lowering are not selectable. Accepted 2026-11-15 and `Candidate` for `0.8.0`.
- **ZEP-0146:** a brace block in expression position ends with one final `yield` and produces that
  value. Statement blocks, `if`/`loop`/`using`/function bodies, and existing `for`/`do` yield clauses
  keep their current meanings; `handle { … } with …` protects an ordinary block expression. Draft,
  with a proposed `0.8.0` target; supersedes ZEP-0110.
- **ZEP-0147:** hard-drop `raise` and `raises` in favor of `perform` and `performs`. The operation
  spelling and effect-row clause change, while effect identity, response typing, handler behavior,
  and the runtime carrier remain unchanged. `raise` becomes an ordinary identifier and can name an
  effect constructor. Implemented for the proposed `0.9.0` target.

## Planning rules

- Proposal status and release state are independent. A row can be `Implemented` and still
  `Unscheduled`; that is not an error by itself.
- A ZEP normally cannot move to `Candidate` while it is `Draft` or `Deferred`; accept the design
  first. ZEP-0098 and ZEP-0113 were accepted and implemented on 2026-11-16, and ZEP-0146 and
  ZEP-0151 were accepted and implemented on 2026-11-20. ZEP-0123 and ZEP-0147 were accepted and
  implemented on 2026-11-21. ZEP-0119 and ZEP-0120 remain draft targets, as do ZEP-0066, ZEP-0067,
  and ZEP-0152–ZEP-0157; treat their `Candidate` rows as proposed until their design reviews are
  complete. ZEP-0112, ZEP-0118, ZEP-0123, and ZEP-0147 are implemented but stay `Candidate` until
  the bundle ships.
- `Candidate` is a proposed release target, not a commitment. `Planned` records an approved bundle.
- The `0.7.0` description is shipped scope: its 15 rows are `Shipped` / `0.7.0`. The `0.8.0`
  description shipped on 2026-11-20 with twenty-five rows; its acceptance queue is closed. The
  `0.9.0` description has fourteen candidates (ZEP-0066, ZEP-0067, ZEP-0112, ZEP-0118, ZEP-0119,
  ZEP-0120, ZEP-0123, ZEP-0147, and ZEP-0152–ZEP-0157); ZEP-0112, ZEP-0118, ZEP-0123, and ZEP-0147
  are implemented, and the rest remain draft targets.
  The `0.10.0` description has two accepted candidates, ZEP-0091 and ZEP-0097, three tentative draft
  candidates, ZEP-0158, ZEP-0160, and ZEP-0161, plus three draft model proposals that cannot become
  candidates until accepted. ZEP-0162 is `Draft` / `Unscheduled` and owns the future generic
  admissibility rules. ZEP-0158's receiver surface depends on ZEP-0160 and ZEP-0161, and its cleanup
  rules must agree with ZEP-0112's scope lifetime and ZEP-0119's resumption points before the bundle
  is scheduled.
- The `0.7.0` acceptance queue is closed. The 15 shipped rows are the release set; ZEP-0107 and
  ZEP-0108 are outside the program bundle.
- `0.6.0` shipped with 25 rows. ZEP-0068 is `Implemented` / `Shipped` `0.6.0`: its core contracts
  and generated operations landed, while external names and mapping metadata are owned by ZEP-0091
  and ZEP-0097. Do not keep the original row `Accepted` merely because follow-up proposals remain.
- ZEP-0041 is `Rejected`: unsigned fixed-width types would duplicate the numeric hierarchy; the
  nonnegative and wire-width use cases move to signed types plus checked newtypes under the static
  verification track.
- ZEP-0042 is `Implemented` / `Shipped` `0.6.0`: `Integer` and `Rational` are the adopted exact
  numeric types from ZEP-0071. Unified `BigNum` and arbitrary-precision floating point are not
  planned under this row.
- ZEP-0121 is `Deferred` / `Deferred`: the proposal has no approved design, and the release row is
  intentionally excluded rather than merely unscheduled.
- Do not reuse a ZEP number. The next available number is `0152`.
- Routine fixes and contained changes do not need a ZEP.
- `pom.xml` records `0.7.0`; `CHANGELOG.md` retains the `0.6.0` history and released `0.7.0` from
  the current entry. The `0.7.0` rows are `Shipped` / `0.7.0`; the next version bump belongs to the
  `0.8.0` bundle.

## References

- [ZEP tracker](../zeps/ZEP.md)
- [ZEP process](../zeps/ZEP-0000.md)
- [ZEP template](../zeps/ZEP-0001.md)
- [Classfile quality inventory](./classfile-quality-inventory.md)
- [ZEP follow-up inventory](./zep-follow-up-inventory.md)
