# Release Plan: 0.10.0

| Field | Value |
| --- | --- |
| Target version | `0.10.0` (planning; not assigned to any part) |
| Status | Planning |
| Theme | Model metadata, language ergonomics, and supporting foundations |
| Release lead | — |
| Last reviewed | 2026-11-26 |

This plan records release-scoped intent only. Workstream pages own part status and dependencies; the
[ZEP tracker](../zeps/ZEP.md) owns proposal status and published history. A candidate is not a
commitment to ship. See [ZEP-0179](../zeps/ZEP-0179.md) and the [release-plan policy](./README.md).

## Candidates

| Part | ZEP | Workstream | Next gate | Depends on | Owner | Last reviewed | Disposition |
| --- | --- | --- | --- | --- | --- | --- | --- |
| model-metadata 1 | [ZEP-0091](../zeps/ZEP-0091.md) | [Model metadata](../workstreams/model-metadata/README.md) | Implementation | — | — | 2026-11-26 | Candidate |
| model-metadata 2 | [ZEP-0097](../zeps/ZEP-0097.md) | [Model metadata](../workstreams/model-metadata/README.md) | Implementation | model-metadata 1 | — | 2026-11-26 | Candidate |
| model-metadata 3 | [ZEP-0101](../zeps/ZEP-0101.md) | [Model metadata](../workstreams/model-metadata/README.md) | Design acceptance | model-metadata 1 | — | 2026-11-26 | Candidate |
| test-infrastructure 2 | [ZEP-0175](../zeps/ZEP-0175.md) | [Test infrastructure](../workstreams/test-infrastructure/README.md) | Implementation | test-infrastructure 1 (shipped) | — | 2026-11-26 | Candidate |
| language-ergonomics 1 | [ZEP-0163](../zeps/ZEP-0163.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| language-ergonomics 2 | [ZEP-0167](../zeps/ZEP-0167.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| language-ergonomics 3 | [ZEP-0168](../zeps/ZEP-0168.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| language-ergonomics 4 | [ZEP-0085](../zeps/ZEP-0085.md) | [Language ergonomics](../workstreams/language-ergonomics/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| numeric-surface 1 | [ZEP-0075](../zeps/ZEP-0075.md) | [Numeric surface](../workstreams/numeric-surface/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| numeric-surface 2 | [ZEP-0074](../zeps/ZEP-0074.md) | [Numeric surface](../workstreams/numeric-surface/README.md) | Design acceptance | numeric-surface 1 (shared model) | — | 2026-11-26 | Candidate |
| numeric-surface 3 | [ZEP-0044](../zeps/ZEP-0044.md) | [Numeric surface](../workstreams/numeric-surface/README.md) | Design acceptance | numeric-surface 1–2 (shared grammar) | — | 2026-11-26 | Candidate |
| scoped-resources 1 | [ZEP-0160](../zeps/ZEP-0160.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| scoped-resources 2 | [ZEP-0161](../zeps/ZEP-0161.md) | [Scoped resources](../workstreams/scoped-resources/README.md) | Design acceptance | scoped-resources 1 | — | 2026-11-26 | Candidate |
| static-verification 1 | [ZEP-0138](../zeps/ZEP-0138.md) | [Static verification](../workstreams/static-verification/README.md) | Design acceptance | — | — | 2026-11-26 | Candidate |
| static-verification 2 | [ZEP-0139](../zeps/ZEP-0139.md) | [Static verification](../workstreams/static-verification/README.md) | Design acceptance | static-verification 1 | — | 2026-11-26 | Candidate |

The model-metadata parts are the only accepted candidates. The remaining candidates are `Draft` and
must clear design acceptance before implementation. No candidate is selected for the release yet.

## Selected scope

No parts are selected. Only `Ready` parts with satisfied dependencies may enter selected scope at
the scope-freeze checkpoint.

## Dependencies and risks

- The slate spans six workstreams; scope freeze should select a coherent subset rather than all 15
  candidates.
- Model-metadata part 3 cannot be implemented before part 1’s annotation model is stable.
- Numeric-surface parts 1–3 share a literal-resolution and suffix model and must be designed
  together. Parts 4–5 are excluded until their alternative/complementary relationship is resolved.
- Scoped-resources part 2 cannot become `Ready` before part 1’s projection and escape checker.
- Static-verification part 2 depends on the fact IR and constant evaluation from part 1.
- Test-infrastructure part 2 builds on the shipped test split and adds reporting rather than a new
  test model.
- Excluded this pass: the broad bytecode-quality program, the portable continuation backend, the Java
  interop inventory, and the later scoped, static, numeric, and model layers. These need splitting or
  earlier dependencies before they are credible release candidates.
- The release remains compatible with the current `0.9.0` API-index and standard-library versions.

## Release engineering

| Task | Owner | Status | Notes |
| --- | --- | --- | --- |
| Release notes | — | Pending | Summarize selected parts and ZEPs. |
| Project version bump | — | Pending | Follow [ZEP-0012](../zeps/ZEP-0012.md). |
| API-index and standard-library version check | — | Pending | Bump each at most once for the cycle if required. |
| Packaging and launcher smoke tests | — | Pending | Run `scripts/release.ps1` from the repository root. |
| Migration and documentation review | — | Pending | Update guide chapters and generated artifact references. |

## Dropped or deferred

No candidates have been dropped from this plan.

## Publishing checklist

- [ ] Selected parts are `Ready` and dependencies are satisfied.
- [ ] Release notes and migration guidance are complete.
- [ ] Project, API-index, and standard-library versions are checked.
- [ ] `scripts/release.ps1` completes and produces the expected archives.
- [ ] Workstream parts are marked `Shipped`.
- [ ] The release-ledger row and ZEP tracker rows match the published version.
- [ ] This plan is archived as `0.10.0.md` and `current.md` is reset to planning.
