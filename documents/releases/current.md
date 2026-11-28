# Release Plan: 0.10.0

| Field | Value |
| --- | --- |
| Target version | `0.10.0` (planning; not assigned to any part) |
| Status | Planning |
| Theme | Model metadata, scoped resources, language ergonomics, and static verification |
| Release lead | — |
| Last reviewed | 2026-11-28 |

This plan records release-scoped intent only. Workstream pages own part status and dependencies; the
[ZEP tracker](../zeps/ZEP.md) owns proposal status and published history. A candidate is not a
commitment to ship. See [ZEP-0179](../zeps/ZEP-0179.md) and the [release-plan policy](./README.md).

## Candidates

| Part | ZEP | Workstream | Next gate | Depends on | Owner | Last reviewed | Disposition |
| --- | --- | --- | --- | --- | --- | --- | --- |
| scoped-resources 1 | [ZEP-0184](../zeps/ZEP-0184.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Implementation | — | — | 2026-11-27 | Candidate |
| scoped-resources 2 | [ZEP-0160](../zeps/ZEP-0160.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Implementation | scoped-resources 1 | — | 2026-11-27 | Candidate |
| scoped-resources 3 | [ZEP-0161](../zeps/ZEP-0161.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Implementation | scoped-resources 1–2 | — | 2026-11-27 | Candidate |
| scoped-resources 4 | [ZEP-0185](../zeps/ZEP-0185.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Implementation | scoped-resources 1–3 | — | 2026-11-27 | Candidate |
| scoped-resources 5 | [ZEP-0158](../zeps/ZEP-0158.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Design acceptance | scoped-resources 1–3 | — | 2026-11-27 | Candidate |
| model-metadata 1 | [ZEP-0091](../zeps/ZEP-0091.md) | [Model metadata](../workstreams/model-metadata/README.md) | Implementation | — | — | 2026-11-26 | Candidate |
| model-metadata 2 | [ZEP-0097](../zeps/ZEP-0097.md) | [Model metadata](../workstreams/model-metadata/README.md) | Implementation | model-metadata 1 | — | 2026-11-26 | Candidate |
| model-metadata 3 | [ZEP-0101](../zeps/ZEP-0101.md) | [Model metadata](../workstreams/model-metadata/README.md) | Implementation | — | — | 2026-11-26 | Candidate |
| language-ergonomics 1 | [ZEP-0163](../zeps/ZEP-0163.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Implementation | — | — | 2026-11-27 | Candidate |
| language-ergonomics 3 | [ZEP-0168](../zeps/ZEP-0168.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Implementation | — | — | 2026-11-27 | Candidate |
| language-ergonomics 5 | [ZEP-0186](../zeps/ZEP-0186.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Implementation | — | — | 2026-11-28 | Candidate |
| language-ergonomics 6 | [ZEP-0170](../zeps/ZEP-0170.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Implementation | — | — | 2026-11-28 | Candidate |
| test-infrastructure 2 | [ZEP-0175](../zeps/ZEP-0175.md) | [Test infrastructure](../workstreams/test-infrastructure/README.md) | Implementation | test-infrastructure 1 (shipped) | — | 2026-11-26 | Candidate |
| static-verification 1 | [ZEP-0138](../zeps/ZEP-0138.md) | [Static verification](../workstreams/static-verification/README.md) | Implementation | — | — | 2026-11-27 | Candidate |
| static-verification 2 | [ZEP-0139](../zeps/ZEP-0139.md) | [Static verification](../workstreams/static-verification/README.md) | Implementation | static-verification 1 | — | 2026-11-27 | Candidate |
| static-verification 3 | [ZEP-0140](../zeps/ZEP-0140.md) | [Static verification](../workstreams/static-verification/README.md) | Implementation | static-verification 1 | — | 2026-11-28 | Candidate |

Scoped-resources parts 1–4, model-metadata parts 1–3, language-ergonomics parts 1, 3, 5, and 6,
and static-verification parts 1–3 are accepted candidates. Scoped-resources part 5 and
test-infrastructure part 2 are `Draft` and must clear design acceptance before implementation. Static-verification parts 1–3 have implementation as their next
gate, with part 2 depending on part 1's IR and evaluator, and part 3 depending on part 1's fact IR.
No candidate is selected for the release yet.

## Selected scope

No parts are selected. Only `Ready` parts with satisfied dependencies may enter selected scope at
the scope-freeze checkpoint.

## Dependencies and risks

- The slate spans five workstreams; scope freeze should select a coherent subset rather than all 16
  candidates.
- Scoped-resources parts 1–4 are accepted: `Self`, the scoped value attribute, class receiver
  methods, and contract/extension forms. Part 5 is the resource block and needs parts 1–3; it cannot
  become `Ready` before the core is implemented and its cleanup questions are resolved. Part 4 can
  land in parallel with part 5. Generic and container scoping (ZEP-0162) is outside this plan.
- Model-metadata part 3’s core no longer depends on part 1: `via` names values rather than
  annotations. Only its reuse of adapters in serialization derivation waits for the annotation
  model.
- Static-verification parts 1–3 are accepted; implementation of the scalar IR and evaluator is
  part 1's next gate. Part 2 depends on that work, but its design is settled against the accepted
  interface. Part 3 (ZEP-0140) depends on part 1's fact IR and adds comparison and refined-type
  flow facts.
- Test-infrastructure part 2 builds on the shipped test split and adds reporting rather than a new
  test model.
- Language-ergonomics part 5 (`ZEP-0186`) is syntax-only and independent, but its implementation
  spans the grammar, scanner, parser, resolver, lowering, formatter, highlighters, and tests. It
  adds a fourth language-ergonomics candidate without a new runtime or type-system subsystem.
- Language-ergonomics part 6 (`ZEP-0170`) is an accepted advisory-warning proposal for
  comprehension-shaped concrete `Stream` targets in `do` blocks. It is independent of part 5, adds
  no runtime or lowering change, and requires the normative `for`/`do` guide section as part of
  implementation.
- Excluded this pass: the broad bytecode-quality program, the portable continuation backend, the Java
  interop inventory, and the later generic scoped layer. These need splitting or earlier
  dependencies before they are credible release candidates. The numeric-surface parts are deferred by
  priority, not readiness; see Dropped or deferred.
- The release remains compatible with the current `0.9.0` API-index and standard-library versions.
  The scoped and `Self` slices share one consolidated API-index schema bump.

## Release engineering

| Task | Owner | Status | Notes |
| --- | --- | --- | --- |
| Release notes | — | Pending | Summarize selected parts and ZEPs. |
| Project version bump | — | Pending | Follow [ZEP-0012](../zeps/ZEP-0012.md). |
| API-index and standard-library version check | — | Pending | Bump each at most once for the cycle if required. |
| Packaging and launcher smoke tests | — | Pending | Run `scripts/release.ps1` from the repository root. |
| Migration and documentation review | — | Pending | Update guide chapters and generated artifact references. |

## Dropped or deferred

- language-ergonomics 2 ([ZEP-0167](../zeps/ZEP-0167.md)) — dropped 2026-11-27. The design was
  rejected: requirements inferred from declaration bodies cannot capture escape, identity, or
  stability assumptions, and the motivating signatures already work through declared markers.
- language-ergonomics 4 ([ZEP-0085](../zeps/ZEP-0085.md)) — dropped 2026-11-27. Rejected: a read-only
  inference default makes capability a property of the binding site, breaks the shipped builder and
  iterator surfaces, and silently drops the view when a binding is copied.
- numeric-surface 1–3 ([ZEP-0075](../zeps/ZEP-0075.md), [ZEP-0074](../zeps/ZEP-0074.md), and
  [ZEP-0044](../zeps/ZEP-0044.md)) — dropped 2026-11-27. Not rejected: low-priority, back-burner
  proposals that mostly add construction conveniences rather than language power. Parts 1–3 share a
  literal-resolution and suffix model, so they are cheaper to design together outside a release
  cycle.

## Publishing checklist

- [ ] Selected parts are `Ready` and dependencies are satisfied.
- [ ] Release notes and migration guidance are complete.
- [ ] Project, API-index, and standard-library versions are checked.
- [ ] `scripts/release.ps1` completes and produces the expected archives.
- [ ] Workstream parts are marked `Shipped`.
- [ ] The release-ledger row and ZEP tracker rows match the published version.
- [ ] This plan is archived as `0.10.0.md` and `current.md` is reset to planning.
