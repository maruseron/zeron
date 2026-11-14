# ZEP Tracker and Release Plan

| Field | Value |
| ----- | ----- |
| Date | 2026-11-14 |
| Scope | Current release state in `documents/zeps/ZEP.md` and near-term sequencing |
| Purpose | Planning companion for bundle contents, acceptance gates, and backlog order |

This is the current-state plan that accompanies the [ZEP tracker](../zeps/ZEP.md). The tracker
remains the source of truth for proposal status and release state. This document explains the
intended bundle shape, dependencies, and ordering; it does not change a row by itself. Update it
when bundle scope or sequencing changes.

## Current state

The tracker records 134 ZEPs: 77 shipped, 0 planned, 12 candidates, and 39 unscheduled. The
remaining six rows are process entries, deferred work, or not applicable. `0.7.0` shipped on
2026-11-14; `0.8.0` is the normalization bundle with twelve candidates, and `0.9.0` is the proposed
effect-control follow-on.

### Bundle summary

| Bundle | Theme | Current state | Contents |
| --- | --- | --- | --- |
| `0.6.0` | Stabilization of already implemented work | Shipped | 24 rows |
| `0.7.0` | Type identity, reification, and type bounds | Shipped 2026-11-14 | 15 shipped; acceptance queue closed |
| `0.8.0` | Normalization and nominal implementation | Proposed; 12 candidates | ZEP-0070, ZEP-0117, ZEP-0124–ZEP-0133; ZEP-0132 lands first |
| `0.9.0` | Effect control and response typing | Proposed; no candidates | ZEP-0118 and ZEP-0110 headline the bundle |

`0.7.0` shipped on 2026-11-14. Its 15 rows are `Shipped`; release scope is approved and complete.
`0.8.0` is the next target: its twelve candidates are accepted designs and cover normalization,
nominal representation, standard-library ownership, refinement correctness, and a bounded
operator-inference improvement rather than control flow. `0.9.0` is a planning target with no
candidates yet, not a scheduled release.

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

## `0.8.0` — Normalization and nominal implementation

`0.8.0` is the proposed normalization release. Its twelve candidates are accepted and cover project
configuration, nominal static evidence, optional HKT blocks, the entry-point contract, data-class
structural access, generalized newtypes, sum parameter bounds, intrinsic declaration ownership,
refinement correctness, and operator-constraint inference for typeless lambdas. None changes
`raise` or `handle` typing, and none introduces a new runtime control-flow mechanism. ZEP-0132 is
a correctness fix and should land before the rest.

### Candidate set

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0117 | Declarative Project Targets and Generated CLI/Manifest Reference | Accepted | Tooling and configuration: `[build]`, `[targets]`, and generated CLI/manifest references; no runtime ABI |
| ZEP-0124 | Nominal Static Contract Implementation | Accepted | Nominal static evidence through public class static functions and first-order block entries |
| ZEP-0125 | Optional Higher-Kinded Implementation Blocks | Accepted | Header `is` conformance plus public members replaces the mandatory block |
| ZEP-0126 | Compiler-Owned Main Entry-Point Contract | Accepted | Entry values are interpreted through `Main<A>` evidence; standard witnesses for `Unit`, `Int`, and `Result` |
| ZEP-0127 | Data Class Component Access and Positional Destructuring | Accepted | Canonical components are readable structural accessors; positional patterns use canonical components at root and nested positions |
| ZEP-0070 | Generic and Nominal Newtype Representations | Accepted | Generic and nominal direct carriers; no `.value` field or wrapper; `this` is the representation in newtype-owned bodies |
| ZEP-0128 | Sum-Type Parameter Bounds and Case-Local Type Parameters | Accepted | Contract bounds on sum parameters; existential case-local parameters such as `Bind<B>`; context-resolved unqualified case names |
| ZEP-0129 | Unary Products versus Arbitrary-Valued Newtypes | Accepted | Keep products binary; use ZEP-0070 direct-representation newtypes for single-value shapes |
| ZEP-0130 | Standard-Library-Owned Intrinsic Declarations | Accepted | Intrinsic identity stays compiler-owned; a comprehensive `String` inventory lives in `zeron.lang` modules |
| ZEP-0131 | Chained Flow Refinement and Implicit Receiver Access | Accepted | `is`/`matches` through composed conditions and stable paths; implicit `this` field/property reads |
| ZEP-0132 | Nullable Safe-Cast Correction | Accepted | `as?` always yields a nullable target; immediate correctness fix before the rest of the bundle |
| ZEP-0133 | Operator-Constraint Inference for Typeless Lambdas | Accepted | Infer a rank-1 numeric or comparison scheme for eligible typeless lambdas; `(a, b) -> a + b` becomes `<T: Numeric<T>> (T, T) -> T` and `(a, b) -> a < b` becomes `<T: Comparable<T>> (T, T) -> Boolean` |

ZEP-0132 is the first implementation item. It is a localized correctness fix, and leaving the old
behavior in place while ZEP-0131 adds chained refinement would let new conditions rely on an
unsound cast result.

ZEP-0070 and ZEP-0129 form one representation track: ZEP-0129 chooses generalized newtypes and
keeps unary products illegal, while ZEP-0070 supplies the generic and nominal representation rules.
ZEP-0128 is independent but shares the generic-bound and API-index work; its case-local metadata and
ZEP-0070's newtype metadata should be versioned in the same schema pass. ZEP-0131 reuses the flow
resolver changed by ZEP-0132. ZEP-0130 is independent and can land as a standard-library change, but
its `String` inventory should follow the same API-index and separate-compilation cycle. ZEP-0133 is
independent of the representation track: it extends the existing ZEP-0003 constrained-lambda path
and needs focused lambda, operator, and resolver validation rather than an API-index schema bump.

ZEP-0117 is independent. It should land in two stages: first extract command, option, and
manifest-schema metadata and generate the reference pages with a staleness test; then add `[build]`,
`[targets]`, default-target selection, inheritance and precedence, and target-aware compilation and
launch. It changes the CLI and manifest surface but not language semantics, the runtime, the
standard library, or the API index.

ZEP-0124 and ZEP-0125 are independently decidable but share one static/receiverless matcher.
Implement ZEP-0124 first, then reuse the matcher for receiverless HKT operations in ZEP-0125.
Validate both through the same resolver, emitter, API-index, separate-compilation, LSP, and
formatter cycle. ZEP-0124 removes the blanket witness-only diagnostic; ZEP-0125 replaces the
mandatory-block diagnostic. Both changes need their tests and guide examples updated together.

ZEP-0126 depends on the static-requirement matcher for nominal `Main<A>` conformance, so it should
land after ZEP-0124. Its witness-only form is independently implementable, but shipping the entry
rule before the matcher exists would require rewriting the entry resolution a second time. It also
touches the launch path that ZEP-0117 parameterizes per target, so its entry tests must run against
the target-aware build rather than a single default entry.

### Acceptance queue for `0.8.0`

The queue is open for further normalization items. ZEP-0126 and ZEP-0127 were the first reviewed
entries, and ZEP-0128–ZEP-0133 were added in the second pass; all are promoted. ZEP-0070 moved from
the backlog into the same bundle:

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0126 | Compiler-Owned Main Entry-Point Contract | Accepted / `Candidate` | Formalize `main` return values, entry evidence, and effectful entry interpretation |
| ZEP-0127 | Data Class Component Access and Positional Destructuring | Accepted / `Candidate` | Make canonical components readable and add positional data-class patterns |
| ZEP-0070 | Generic and Nominal Newtype Representations | Accepted / `Candidate` | Promote direct-carrier single-value representations from the backlog |
| ZEP-0128 | Sum-Type Parameter Bounds and Case-Local Type Parameters | Accepted / `Candidate` | Remove the sealed-contract workaround for polymorphic cases and bounded sums |
| ZEP-0129 | Unary Products versus Arbitrary-Valued Newtypes | Accepted / `Candidate` | Keep unary products illegal and route single-value shapes through ZEP-0070 |
| ZEP-0130 | Standard-Library-Owned Intrinsic Declarations | Accepted / `Candidate` | Settle intrinsic-operation ownership and add the practical `String` inventory |
| ZEP-0131 | Chained Flow Refinement and Implicit Receiver Access | Accepted / `Candidate` | Complete guard-chain refinement and remove routine `this.` reads |
| ZEP-0132 | Nullable Safe-Cast Correction | Accepted / `Candidate` | Correct `as?` to return `T?` and land before the broader refinement work |
| ZEP-0133 | Operator-Constraint Inference for Typeless Lambdas | Accepted / `Candidate` | Infer numeric or comparable operator bounds for eligible typeless let-bound lambdas without defaulting to a concrete type |

ZEP-0126 was accepted on 2026-11-13. The review settled the `Main.run` effect row (`raises Effect`,
with pure witnesses allowed by row subtyping), the placement of the standard witnesses (ordinary
`default` witnesses in `zeron.lang`, with no new intrinsic kind), `Main<Int>` semantics (`0` returns
normally, anything else exits), and the `Result` policy (one non-zero status in the first release,
message text deferred). What remains open is implementation detail: the exact failure text, the
`System.err` binding, `main` visibility, and future async or response-typed entry behavior.

ZEP-0127 was accepted on 2026-11-14. It makes canonical components readable regardless of the
data class's public modifier, adds root and nested positional patterns over canonical components,
and fixes the false-unreachable diagnosis for refutable applied cases with the same identity.

ZEP-0128 through ZEP-0133 were accepted on 2026-11-14. ZEP-0132 is a corrective candidate: `as?`
must not produce a non-null `T` from a nullable source or strip the `?` from a `T?` target. ZEP-0131
then makes chained `is`/`matches` conditions carry refinement and allows implicit receiver reads.
ZEP-0128 adds bounded sum parameters, existential case-local parameters, and context-resolved
unqualified case names; ZEP-0129 records why single-value shapes use ZEP-0070 direct-representation
newtypes rather than unary products; ZEP-0130 settles intrinsic operation ownership and adds a
bounded inventory covering literal and regex splitting, character access, case conversion,
trimming, replacement, lines, parsing, and joining. ZEP-0133 extends the existing constrained
let-bound lambda path with numeric and comparison operator-constraint inference, but does not add a
general constraint solver or a runtime ABI.

ZEP-0070 was promoted from the backlog. It supplies the generic and nominal newtype representations
that ZEP-0129 depends on. Its initial scope uses direct carriers, removes `.value`, makes `this` the
representation in newtype-owned extensions and witnesses, keeps guarded newtypes scalar-only, and
records the newtype metadata in the API index.

### Scope and risk

The bundle is broader than it first appears, but it remains normalization rather than control flow.
ZEP-0132 is the only item with an immediate correctness motivation; it should land first and be
validated independently. ZEP-0131 then changes the flow resolver that every guard and match example
uses. ZEP-0128, ZEP-0129, and ZEP-0070 share generic-bound, metadata, and representation work, so
their API-index schema changes must be coordinated rather than landed as three independent schema
bumps. ZEP-0130 is a standard-library ownership change; its `String` inventory must use the
extension/API-index path and must not become a compiler special case. The direct-carrier newtype
change also alters generated descriptors, so its separate-compilation and bytecode tests must be
part of the same release validation cycle. ZEP-0133 is independent of that schema work, but its
inferred schemes must reuse the existing constrained-lambda template and stay local-only; check
that it does not accidentally become a general-purpose solver or change contextual inference.

The established risks remain: ZEP-0124 and ZEP-0125 must not diverge on static matching,
diagnostics, or separate-compilation resolution; ZEP-0117 makes generated documentation part of
the tested build; and ZEP-0126 changes the entry rule that every other row's examples and
integration tests go through. ZEP-0124 and ZEP-0125 should still prefer deterministic re-matching
unless a concrete export gap justifies a schema change; ZEP-0126 needs only existing witness
metadata.

## `0.9.0` — Effect control and response typing

The effect-control bundle moves to `0.9.0` now that `0.8.0` carries normalization. It is not
scheduled: the headline proposals remain `Draft`, and the bundle has no candidates yet. ZEP-0107 is
`Accepted` but has been removed from the program bundle and is `Unscheduled`.

| ZEP | Name | Status | Role |
| --- | --- | --- | --- |
| ZEP-0118 | Response-Typed Effects and Tail-Resumptive Handlers | Draft | Headline: a `raise` returns a handler-supplied value |
| ZEP-0110 | Block-Bodied Handler Expressions | Draft | Notation-only companion to the response-type work |
| ZEP-0111 | Witness-Dispatched Do-Notation | Shipped `0.7.0` | Not part of `0.9.0` |
| ZEP-0112 | Structured Concurrency on Virtual Threads | Draft | Independent library track: `Scope`, `Task`, cancellation, timeouts |
| ZEP-0120 | Async as an Effect and the Future/Task Surface | Draft | Async surface; depends on ZEP-0118 and ZEP-0112 |
| ZEP-0119 | One-Shot Continuations, Deep Handlers, and Cancellation | Draft | Prototype-gated research; not a release commitment |
| ZEP-0121 | Multi-Shot Continuations and Backtracking | Deferred | Off the release list |

ZEP-0123 is a Draft syntax companion: it proposes the `admits` clause and response-indexed effect
markers, and its `effect<R>` form depends on ZEP-0118. It remains `Unscheduled` and is not a
candidate.

### Path to a `0.9.0` candidate set

1. **Resolve ZEP-0118's open design points and accept it.** The remaining blockers are response
   syntax and generic response bounds, the interaction with ZEP-0013's explicit `case Effect`
   catch-all, and whether `Ask` and `Log` are library carriers or examples. The catch-all question
   is the one that must not be settled by accident inside `0.7.0`: a catch-all that covers a
   responding carrier has to abort it, cancel it, or refuse coverage, and that choice belongs to
   ZEP-0118.
2. **Accept ZEP-0110 as the notation companion.** It adds a block form to `handle` without changing
   semantics.
3. **Mark ZEP-0118 and ZEP-0110 `Candidate` for `0.9.0` after acceptance**, not before.
4. **ZEP-0111 shipped in `0.7.0`.** It is notation-only and is not part of the `0.9.0` candidate
   path.

ZEP-0107 is outside this plan. If it returns, it first needs the generic-effect propagation slice
that ZEP-0005 leaves deferred: applications containing type parameters, forwarding through generic
calls, and omitted handler arguments.

### Independent and conditional tracks

- **ZEP-0112 is library-only and independent.** It does not need ZEP-0118. It can be scheduled once
  the scope/task spike is complete and becomes a separate candidate bundle of its own.
- **ZEP-0120 waits for ZEP-0118 and ZEP-0112.** Its first backend is blocking `await` over
  `Task.value()`, so it cannot precede the runtime primitive.
- **ZEP-0119 stays research.** Acceptance is gated on the backend prototype and the continuation
  ABI questions recorded in the proposal. It is not a `0.9.0` commitment.
- **ZEP-0121 stays `Deferred`.** Multi-shot resumption needs a concrete use case that `do`-notation
  cannot serve, plus answers to the resource, mutation, handler-state, cancellation, and type-system
  questions in the proposal.

### Scope and risk

ZEP-0118 depends on the identity and payload work in `0.7.0`, but it also changes global `raise`
typing and handler reachability. `0.8.0` is intentionally limited to normalization, so response
metadata plus `Yield`, `Ask`, and `Log` do not share its API-index and standard-library validation
cycle. ZEP-0119 is prototype-gated, ZEP-0120 depends on two other proposals, and ZEP-0121 is
`Deferred`. None of those is a defensible `0.9.0` commitment.

## Open planning decisions

- `0.7.0` was published on 2026-11-14; its 15 rows are `Shipped` / `0.7.0`, and its acceptance
  queue is closed.
- ZEP-0111 shipped in `0.7.0`; it does not fall back to `0.9.0`.
- The `0.8.0` candidate set is now twelve accepted designs. Decide whether to freeze scope there or
  admit further normalization items.
- Sequence ZEP-0132 first as the immediate safe-cast correction, then ZEP-0131, then the
  representation track (ZEP-0070, ZEP-0129, ZEP-0128), with ZEP-0130 and ZEP-0133 independent.
  Confirm that ordering before implementation begins.
- Settle the `String.split` empty-separator and return-type questions, the `charAt`/`at` shape, and
  the regex dialect questions in ZEP-0130 before treating the inventory as complete.
- Settle the ZEP-0126 implementation detail before coding begins: the exact `Result` failure text,
  whether it uses a new `System.err` binding, and whether `main` must be `public`.
- Resolve ZEP-0118's response syntax, generic response bounds, catch-all interaction, and carrier
  policy before proposing `0.9.0` candidates.
- Decide whether the ZEP-0112 scope/task spike is complete enough to schedule independently.

## Backlog after the planned bundles

These items are not in `0.7.0`, `0.8.0`, or `0.9.0`. They close already shipped gaps or start new
subsystems, and they follow the three planned bundles.

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
8. **ZEP-0050 — Remaining Java interoperability work.** The SAM, inherited-member, JPMS, and
   extensible-binding gaps left by ZEP-0047 through ZEP-0049.

## Design decisions

These decisions shape the current plan. Details live in each ZEP.

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
  generated command and manifest reference pages. Accepted and `Candidate` for `0.8.0`; no
  language, runtime, standard-library, or API-index change.
- **ZEP-0110:** block form of `handle`; notation only, no semantic change.
- **ZEP-0111:** `do` with `let x <- e` binds, mandatory final `yield`, and receiver-call lowering
  through the `Monad` witness; implemented and shipped in `0.7.0`.
- **ZEP-0112:** structured concurrency on virtual threads (`Scope`, `Task`, cancellation, timeouts,
  effect capture at task boundaries); blocking suspension is the mechanism.
- **ZEP-0114:** named witnesses in a parallel namespace, explicit `import witness`, defaults beside
  their target type, and anonymous witness deprecation.
- **ZEP-0115:** warning-severity compiler diagnostics; implemented.
- **ZEP-0118–ZEP-0121:** response-typed effects and tail-resumptive handlers, one-shot
  continuations, the async surface, and multi-shot continuations respectively. ZEP-0118 through
  ZEP-0120 are `Draft`; ZEP-0121 is `Deferred`; ZEP-0007 is `Superseded`.
- **ZEP-0124:** nominal static contract implementation; public class static functions satisfy
  type-position requirements, with implementation-block static entries for generic-class cases.
  Accepted 2026-11-13 and `Candidate` for `0.8.0`.
- **ZEP-0125:** optional HKT implementation blocks; `is` conformance plus public members supplies
  receiver-marked and receiverless operations, with explicit blocks retained for overrides and
  non-member implementations. Accepted 2026-11-13 and `Candidate` for `0.8.0`.
- **ZEP-0126:** compiler-owned `Main<A>` entry contract; `main` returns a closed entry value and
  the compiler calls `Main<A>.run`. `Unit`, `Int`, and `Result<Unit, E>` entry behavior is supplied
  by ordinary `default` witnesses in `zeron.lang`, and `Main.run` is declared `raises Effect`.
  Accepted 2026-11-13 and `Candidate` for `0.8.0`.
- **ZEP-0127:** data-class components are public structural accessors while the type's visibility
  gates nameability; positional data-class patterns use only canonical components, generic type
  arguments may be inferred, and refutable applied cases with the same identity no longer make one
  another unreachable. Accepted 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0070:** generic and nominal newtype representations use direct carriers; the wrapper,
  `value` field, and `.value` accessor are removed; `this` and newtype-typed parameters are the
  representation inside newtype-owned extensions and witnesses. Admissible representations include
  type parameters, function types, nominal Zeron types, and explicit Java external classes; guarded
  newtypes remain scalar-only. Accepted 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0128:** sum type parameters accept contract bounds; cases may declare marker-bounded
  existential type parameters; construction infers them, matching opens them with a branch-local
  `let` binder, and concrete case-local pattern arguments are rejected. Unqualified case names are
  accepted when the expected sum or scrutinee determines the case, or the name is uniquely visible;
  ordinary values shadow case names and ambiguity requires qualification. Accepted 2026-11-14 and
  `Candidate` for `0.8.0`.
- **ZEP-0129:** products remain binary; unary products are not legalized; single-value shapes use
  ZEP-0070 direct-representation newtypes with no `.value` accessor. Accepted 2026-11-14 and
  `Candidate` for `0.8.0`.
- **ZEP-0130:** intrinsic types keep compiler-owned identity and operations live in canonical
  `zeron.lang` extension/namespace modules, not in a second class. The initial `String` inventory
  covers literal and regex splitting, `charAt`/`at`, case conversion, trimming, replacement, lines,
  simple parsing, and joining. Accepted 2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0131:** `is` and `matches` refine through `and`/`or`/`not`, stable read-only member paths
  participate, and unqualified field/property reads use the implicit `this` receiver. Accepted
  2026-11-14 and `Candidate` for `0.8.0`.
- **ZEP-0132:** `as?` always returns a nullable target, including for `T?`; null or mismatch yields
  null; the result requires a null check or safe navigation. Accepted 2026-11-14 and `Candidate`
  for `0.8.0`, with first implementation priority.
- **ZEP-0133:** an uncontextualized typeless lambda written directly as an immutable local `let`
  initializer may infer a rank-1 scheme from numeric or comparison operator use. `(a, b) -> a + b`
  infers `<T: Numeric<T>> (T, T) -> T`; `/` infers `Fractional<T>`; `(a, b) -> a < b` infers
  `<T: Comparable<T>> (T, T) -> Boolean`. Each use resolves evidence independently; string
  concatenation, equality, arbitrary member operations, concrete defaults, and arbitrary lambda
  positions remain outside the rule. Accepted 2026-11-14 and `Candidate` for `0.8.0`.

## Planning rules

- Proposal status and release state are independent. A row can be `Implemented` and still
  `Unscheduled`; that is not an error by itself.
- A ZEP cannot move to `Candidate` while it is `Draft` or `Deferred`; accept the design first.
- `Candidate` is a proposed release target, not a commitment. `Planned` records an approved bundle.
- The `0.7.0` description is shipped scope: its 15 rows are `Shipped` / `0.7.0`. The `0.8.0`
  description has twelve accepted candidates; its acceptance queue remains open. The `0.9.0`
  description remains a planning record.
- The `0.7.0` acceptance queue is closed. The 15 shipped rows are the release set; ZEP-0107 and
  ZEP-0108 are outside the program bundle.
- `0.6.0` shipped with 24 rows. ZEP-0068 remains `Accepted` because its external-name and adapter
  scope stays open under ZEP-0091, ZEP-0097, and ZEP-0098.
- Do not reuse a ZEP number. The next available number is `0134`.
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
