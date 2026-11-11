# ZEP Tracker Review and Near-Term Shipping Order

| Field | Value |
| ----- | ----- |
| Date | 2026-11-08 |
| Scope | Review of `documents/zeps/ZEP.md` and near-term sequencing |
| Purpose | Record the reconciled proposal/release state, the post-`0.6.0` program, and the remaining near-term order |

This is a planning snapshot. It reflects the tracker and each ZEP file as of 2026-11-08. It does not
change any proposal's status or release state by itself.

## Update 2026-11-09: `0.6.0` shipped

`0.6.0` was published, so all 24 rows in the candidate set below moved from `Candidate` to `Shipped`
with release reference `0.6.0` in the [ZEP tracker](../zeps/ZEP.md). The `0.6.0` scope is unchanged:
the release contains exactly that candidate set. ZEP-0068 remains `Accepted` and ships with its
accepted external-name and adapter scope still open under ZEP-0091, ZEP-0097, and ZEP-0098.

The candidate table below is retained as the record of the `0.6.0` bundle. The post-`0.6.0` program
and the recommended order after it are unaffected.

## Update 2026-11-09: `0.7.0` candidacy begins

The first post-`0.6.0` bundle now has accepted designs:

- **ZEP-0054** is `Implemented` and `Candidate` for `0.7.0`. Its library-index round trip passes
  with no schema change, and effect payloads now take the ordinary class member model.
- **ZEP-0013** is `Implemented` and `Candidate` for `0.7.0`. Its three open questions are resolved:
  parent-declared members are reachable only through a parent-typed view, a late-declared
  incomparable match is a runtime ambiguity error, and one index schema revision adds `under`
  edges, shared with ZEP-0015, ZEP-0005, and the bounded ZEP-0104 slice. Implementation landed on
  2026-11-09; that slice used schema 12, and the shared ZEP-0015 revision later advanced the
  schema to 13 while the standard-library API version stayed at 12.
- **ZEP-0100** is implemented and remains an optional co-candidate for `0.7.0`.
- **ZEP-0015** was accepted on 2026-11-09 and is now `Implemented` as a shared applied-type
  descriptor. The declaration ABI, hidden descriptor arguments, function-value capture, API-index
  schema 13, separate-compilation tests, exact-test consumer, and applicable overhead rows are
  validated in its 2026-11-10 validation matrix. It remains `Candidate` for `0.7.0`. Generic
  effect construction and handler dispatch are not part of this implementation.
- **ZEP-0104** was split into a bounded initial slice and four follow-on proposals. The initial
  slice was accepted on 2026-11-09, its 2026-11-10 revision was approved, and the bounded
  implementation landed: star views, parameter-position anonymous existentials with implicit
  packing and hidden type parameters, and exact descriptor tests for reified values. It remains
  `Candidate` for `0.7.0` under the recorded scope and prototype conditions. ZEP-0005 was reopened
  on 2026-11-10 and accepted the same day with a narrower closed-applied-effect scope.

`0.7.0` currently means the effect-semantics bundle plus the bounded type-bound-matching slice.
ZEP-0015 is implemented and remains a release candidate; its applicable descriptor gates are
closed, while generic effects remain a separate ZEP-0005 effort.

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
  first, then a shared ZEP-0015 descriptor, the bounded ZEP-0104 slice, and ZEP-0005. ZEP-0100 is
  an independent co-candidate, not part of the reification dependency chain. ZEP-0054 (implemented),
  ZEP-0013, the bounded ZEP-0104 slice, and ZEP-0015 are now implemented `Candidate`s for
  `0.7.0`; ZEP-0005 is accepted with a narrowed closed-application scope and no release target.

## `0.6.0` candidate set (shipped)

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
| Effect semantics | ZEP-0054, ZEP-0013 | Implemented | Nominal payload capability, subtyping, and the top effect; `Candidate` for `0.7.0` |
| Runtime applied identity | ZEP-0015, ZEP-0005 | Implemented / Accepted | Shared reification descriptors validated and `Candidate` for `0.7.0`; generic effect payloads accepted with closed applications and no release target |
| Type abstraction | ZEP-0104, ZEP-0105, ZEP-0106, ZEP-0107, ZEP-0108, ZEP-0109 | Accepted / Draft | Initial type-bound matching slice is `Candidate` for `0.7.0`; later existential, storage, ancestor, evidence, and rank-2 work is research |
| Static declaration identity | ZEP-0100 | Draft | Orthogonal; useful independently of reification |
| Witness declarations and evidence scope | ZEP-0004, ZEP-0114 | Implemented / Accepted | Named, default, and explicitly imported witnesses replace anonymous declarations; accepted with no release target |
| Effect notation and suspension | ZEP-0110, ZEP-0111, ZEP-0112 | Draft / Implemented | Block-bodied abortive handlers, witness-dispatched do-notation, and direct-style structured concurrency; none changes `raise` or `handle` semantics |

Two foundations are already implemented and should be reused, not redesigned: ZEP-0055 provides the
`effect` kind constraint and compiler-owned runtime effect marker, and ZEP-0086 provides `effect...`
rows and the empty row `{}`. ZEP-0013 adds the top of the lattice; it does not re-implement either.

### First bundle (provisional; next release after `0.6.0`)

**ZEP-0054, then ZEP-0013**, with ZEP-0100 as an optional co-candidate if its design is accepted.

- **ZEP-0054 first.** It is independent of reification and turns effect payloads into ordinary
  nominal classes with methods, properties, and contract conformance while retaining the effect
  marker. It removes a resolver special case and makes ZEP-0013's inherited-payload rules easier.
  Member restrictions are settled (none beyond ordinary classes), and the library-index round trip
  passed on 2026-11-09 with no schema change. Implemented and `Candidate` for `0.7.0`.
- **ZEP-0013 second.** It adds `under` edges, inherited payload properties, `raises Effect`, the
  explicit catch-all `case Effect`, and most-specific handler selection. It needs a stable nominal
  effect identity and explicit subtype edges, not full applied-type reification, so it can land
  before ZEP-0015. Implemented 2026-11-09 and `Candidate` for `0.7.0`.
- **ZEP-0100 is orthogonal.** Callable references carry static `owner + kind + name` identity and do
  not depend on ZEP-0015. Method and constructor references can land without ZEP-0072; static
  function references now build on the implemented ZEP-0072. Include ZEP-0100 in this bundle only if
  its design is accepted; do not make either track depend on the other.

### Second bundle (provisional; follows the first)

**ZEP-0015, then ZEP-0104, then ZEP-0005.**

- **ZEP-0015 is implemented and shared first.** It defines one internal applied-type descriptor for
  owner, type arguments, nullability, mutability markers, arrays, generic forwarding, exact type
  tests, and library-index encoding. The descriptor and its ZEP-0104 consumer are validated; it
  remains `Candidate` for 0.7.0. Descriptors stay separate from contract witnesses. ZEP-0055's
  marker proves effect-kind membership only and does not preserve applied arguments.
- **ZEP-0104 is the initial type-abstraction slice.** It adds parameter-position bounded
  existentials, implicit packing/capture, and exact type-bound tests against closed applied types.
  It does not add storage, result positions, variance, ancestor matching, or contract-bound tests.
  It is implemented and `Candidate` for 0.7.0.
- **ZEP-0005 follows, accepted 2026-11-10.** Its first slice allows generic effect declarations
  and exact closed applications only; it rejects applications containing type variables,
  wildcards, omitted arguments, and applied `under`. Ancestor-aware matching waits for ZEP-0107.
  Generic propagation and effect-variable handlers are follow-up work. Acceptance settles the
  design but does not schedule it: it has no release target.

### Later and research tracks (no release target)

- **ZEP-0105:** pattern-scoped opening (`if (value is C<let T>)`), result positions, escape
  approximation, variance, and projection sharing.
- **ZEP-0106:** stored existentials and holder ABI, including the model-field use case. Compare it
  honestly against visitors and generated per-field operations.
- **ZEP-0107:** ancestor-aware descriptors and generic effect matching under `under`. Depends on
  ZEP-0013, ZEP-0005, and ZEP-0015.
- **ZEP-0108:** contract-bound runtime tests. This is evidence selection, not type identity, and is
  deliberately independent of descriptor matching.

### Why not one bundle

ZEP-0054 and ZEP-0013 deliver user-visible value without waiting for reification. ZEP-0015 and the
bounded ZEP-0104 slice are implemented; their applicable descriptor and exact-test gates are
validated, so they are no longer blocked on the descriptor prototype. Generic effects remain a
separate ZEP-0005 effort. Storage, ancestor matching, and contract-bound tests each add a different
runtime mechanism and should not block the initial slice. ZEP-0100 shares the value-level identity
theme but not the runtime type-descriptor machinery, so bundling all of them would couple unrelated
risks.

### Release-state caveat

ZEP-0015, ZEP-0054, ZEP-0013, and ZEP-0104 are `Candidate` for `0.7.0`: ZEP-0000 requires an
Accepted design, and each accepted design now satisfies that gate. ZEP-0015 and ZEP-0104 are
implemented; their applicable descriptor and exact-test gates are validated. ZEP-0100 is
implemented and an optional co-candidate. ZEP-0005 is accepted with no release target. This is a
priority change: ZEP-0091 and ZEP-0097 move behind the first bundle, not out of the plan.

## Design decisions recorded 2026-11-09

Details live in each ZEP. No proposal status or release state changed.

- **ZEP-0054:** full ordinary member model; canonical `new` plus delegating named constructors.
- **ZEP-0013:** `under` modeled on contract conformance (children redeclare inherited payload);
  behavior only through `is`; JVM-hierarchy representation; `Effect` is also a value type and wraps
  host exceptions only through library constructors; canonical effect sets and row rules.
- **ZEP-0015:** one shared internal applied-type descriptor; payload-carried for effects and
  supplied as a hidden parameter for existential hidden types and reified variables; explicit
  `reified` marker for exported declarations, inferred only for non-exported ones.
- **ZEP-0005:** reopened as `Draft` on 2026-11-09 and accepted on 2026-11-10; exact matching with
  closed, fully written applications; consumes the implemented shared descriptor. Generic
  propagation, effect-variable handlers, and applied ancestry remain deferred. The settled points
  are recorded below.
- **ZEP-0100:** open questions resolved; `F` carries the declaration's `raises`; ZEP-0072 is
  implemented, so static function references are no longer blocked on its amendment.
- **ZEP-0072:** accepted and implemented 2026-11-09; class static functions lower to companion
  namespace functions with no class type-parameter capture, `this`, or member-name collisions.
- **ZEP-0104:** accepted and `Candidate` for 0.7.0. The 2026-11-10 revision replaces the former
  `let T: Bound` binder and `?` wildcard with `F<*>` star projections and parameter-position
  `F<: B + C>` anonymous existentials, implicit packing/capture, and exact type-bound tests against
  closed applied types. Acceptance is conditional on one type-test mechanism, separate evidence,
  coordinated index encoding, validation, and the ZEP-0015 descriptor ABI. General existential semantics, storage, ancestor matching, and contract-bound tests move to
  ZEP-0105 through ZEP-0108.
- **ZEP-0105–ZEP-0109:** later existential semantics, stored holders, ancestor-aware descriptors,
  contract-bound runtime tests, and rank-2 polymorphic function types respectively; all `Draft` and
  `Unscheduled`.
- **Index schema:** ZEP-0015's descriptor schema has landed. ZEP-0005 settled that generic effect
  declarations and closed applied `raises` entries need no schema change. A later coordinated
  revision covers wildcard signatures and rank-2 function types; ZEP-0054 needs none.
- **ZEP-0013 open questions (2026-11-09):** parent-declared members are reachable only through a
  parent-typed view; a late-declared incomparable match is a runtime ambiguity error rather than an
  order-based choice; one schema revision adds `under` edges, with the effective payload shape
  reusing the canonical-constructor metadata already in `ClassExport`.

## Design decisions recorded 2026-11-10

Details live in each ZEP. No release state changed.

- **ZEP-0005 accepted.** The closed-application design is approved: generic effect declarations,
  invariant exact fully written applications, payload-carried ZEP-0015 descriptor, descriptor
  comparison before payload binding, exact coverage plus `Effect` coverage, nominal-only `under`
  with no generic parent, and no index-schema change. The former open questions are resolved in the
  proposal, including handler case syntax (`case Failure<Int> as failure ->`), the fail-safe rule for
  null or forged foreign descriptors, and duplicate normalization through the existing ordered set.
  The proposal remains unscheduled and unimplemented.
- **ZEP-0110–ZEP-0112 recorded** from the effect-notation discussion. ZEP-0110 adds a block form to
  `handle` with no semantic change and stays `Draft`; ZEP-0112 defines direct-style suspension
  through virtual threads and a structured-concurrency surface rather than `async`/`await` or
  monadic lowering and stays `Draft`; both are `Unscheduled`. ZEP-0007 gained a mechanism analysis
  of monadic lowering and stays `Deferred`.
- **ZEP-0111 implemented** on 2026-11-11 as a notation-only design: `do` with `let x <- e` binds,
  plain `let x = e` locals after the first bind, mandatory final `yield`, at least one bind, no
  guards or `collect`, and receiver-call lowering that never names `pure`. The constructor comes
  from the first bind because Zeron has no expected-type-directed constructor selection, so an
  explicit `do<Option<_>>`/`do<Result<_, E>>` validates the inferred constructor instead of
  steering inference; that narrowing, and the plain-`let` ordering limit, are recorded in the
  proposal. `Stream` stays a valid constructor through its existing witness and `for` is
  unchanged. Steps dispatch through the receiver's contract witness rather than a same-named
  instance method, which also required fixing receiver-based HKT contract calls on constructor
  parameters bounded by `Monad<F>`. The evidence gate was waived by the author because nested
  `match` chains compose poorly. Implemented but `Unscheduled`; `DoNotationTest` covers `Option`,
  `Result`, `Stream`, a nested cartesian chain, a bounded constructor parameter, locals, a
  constructor mismatch, and the malformed-block diagnostics.
- **ZEP-0114 accepted** as the replacement for anonymous witnesses. Witnesses get stable names in a
  parallel namespace, normal visibility, `import witness` activation (including a witness-only star
  form),  and `default` declarations that travel with their target type and require a name. Witness
  declarations may live in any package, including for foreign type/contract pairs; the same-package
  orphan rule is removed for every form, and only defaults stay beside their target. Fully qualified
  type references never activate defaults, and
  overlapping defaults stay declaration errors rather than a precedence ladder. Anonymous syntax is
  deprecated at introduction, warns during the migration release, and is removed one release after
  the standard library migrates; the index gains named exports and a default marker under the
  existing one-bump-per-release-cycle rule. Accepted but unscheduled.

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

1. **`0.6.0`**: shipped. The accepted candidates moved to `Shipped`.
2. **`0.7.0` (effect semantics)**: ZEP-0054 and ZEP-0013, both `Candidate` for `0.7.0`, with
   ZEP-0100 as an optional co-candidate if its design is accepted. ZEP-0054 is implemented; ZEP-0013
   is accepted and not yet implemented. This bundle does not wait for reification.
3. **Runtime-identity slice**: shared ZEP-0015 descriptor and bounded ZEP-0104 type-bound matching
   are implemented and `Candidate` for 0.7.0. ZEP-0005 generic effects are accepted with a
   closed-application scope and have no release target.
4. **Later research**: ZEP-0105 (general existentials), ZEP-0106 (storage), ZEP-0107 (ancestor
   matching), and ZEP-0108 (contract-bound tests), with no release target.
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
- Do not reuse a ZEP number. The next available number is `0113`.
- Routine fixes and contained changes do not need a ZEP.
- `pom.xml` and `CHANGELOG.md` record `0.6.0`, and the tracker rows for the candidate set above now
  read `Shipped` / `0.6.0`.

## References

- [ZEP tracker](../zeps/ZEP.md)
- [ZEP process](../zeps/ZEP-0000.md)
- [ZEP template](../zeps/ZEP-0001.md)
- [Classfile quality inventory](./classfile-quality-inventory.md)
- [ZEP follow-up inventory](./zep-follow-up-inventory.md)
