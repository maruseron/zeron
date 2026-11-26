# Project Guidance

## Environment and Build

- Use JDK 25 or later; `pom.xml` targets stable Java 25 without preview features. Use Maven:
  `mvn compile` to compile and `mvn test` to run the test suite. The project depends on
  Maven-managed libraries, so do not use a direct `javac` fallback.
- Before compiling Zeron source (including tests that generate Zeron programs), remove `dist/` so
  stale generated classes and interfaces do not accumulate. The compiler recreates it as needed;
  do not remove Maven's `target/` directory for this cleanup.
- Use `zeron build` and `zeron run` for project workflows. See
  [`guide/25-configuration-releases-and-roadmap.md`](./guide/25-configuration-releases-and-roadmap.md)
  for project configuration, [`guide/23-java-interoperability.md`](./guide/23-java-interoperability.md)
  for Java interop, and
  [`guide/24-libraries-and-separate-compilation.md`](./guide/24-libraries-and-separate-compilation.md)
  for compiled libraries and API-index inspection.

## Code Navigation

Start with
[`documents/developer_guides/change-surface-map.md`](./documents/developer_guides/change-surface-map.md)
to route a change to the files that must move together. See [NAVIGATION.md](./NAVIGATION.md) for the
repository map and the compiler pipeline.

Workflow guides:

- [`style-conventions.md`](./documents/developer_guides/style-conventions.md) — naming, feature
  grouping, declaration choice, data modeling, parameters, and fluent APIs.
- [`compiler-changes.md`](./documents/developer_guides/compiler-changes.md) — statements,
  declarations, expressions.
- [`syntax-surface-checklist.md`](./documents/developer_guides/syntax-surface-checklist.md) —
  grammar, formatter, highlighters, and LSP surfaces.
- [`registries-and-modules.md`](./documents/developer_guides/registries-and-modules.md) — bindings,
  intrinsics, and standard-library modules.
- [`diagnostics-and-error-codes.md`](./documents/developer_guides/diagnostics-and-error-codes.md) —
  catalog, explanation resources, and severities.
- [`testing-and-verification.md`](./documents/developer_guides/testing-and-verification.md) — test
  layout, focused runs, and `dist/` handling.
- [`stdlib-suite-parity.md`](./documents/developer_guides/stdlib-suite-parity.md) — the Zeron
  standard-library suite, its command line, and the Java assertions that remain.
- [`generated-artifacts-and-release.md`](./documents/developer_guides/generated-artifacts-and-release.md) —
  generated output, API index versions, and release packaging.

## Workstreams and Shipping

- Planning uses concern-based workstreams, not future version candidates. Read
  `documents/workstreams/README.md` and the relevant workstream page before starting feature work.
  Follow `documents/zeps/ZEP-0176.md` for the planning model.
- Release-scoped candidates and selected work are tracked in `documents/releases/current.md`, not
  in workstream part tables. Follow `documents/zeps/ZEP-0179.md` for the release-plan model.
- For feature work requiring a proposal, draft and resolve a ZEP before implementation. Follow
  `documents/zeps/ZEP-0000.md` and `ZEP-0001.md`, and register it in `documents/zeps/ZEP.md`.
- Record the work as a numbered part on its workstream page. Do not assign a future version or add a
  `Candidate`/`Planned` release state. A part becomes `Ready` only after its design is accepted, its
  implementation lands, and dependencies are satisfied; a release selects `Ready` parts. Nominate
  candidates through the active release plan, not through a release column on the workstream page.
- Implement the accepted design, then update `CHANGELOG.md`, relevant design references, and the
  ZEP proposal status. Keep the workstream part table, central index rollup, and ZEP tracker
  synchronized whenever status or scope changes.
- At release time, mark shipped parts, update the release ledger, archive the release plan, and mark
  the corresponding ZEP rows `Shipped` with the published version.
- Update `README.md` and/or relevant `guide/` chapters to document user-facing behavior.

## Release Cycle and Process

- Follow `documents/zeps/ZEP-0012.md` for artifact versioning. Bump the project version in
  `pom.xml`, then run `powershell -ExecutionPolicy Bypass -File scripts/release.ps1` from the
  repository root.
- `API_INDEX_SCHEMA_VERSION` and `STANDARD_LIBRARY_API_VERSION` in `ZeronVersions.java` can each
  be bumped only once per release cycle. Consolidate required API and schema changes before
  incrementing either version.

## Validation

- For compiler or resolver changes, run `mvn test` (or compile all Java sources using the command above when Maven is unavailable). Keep in mind `mvn test` takes around 9 minutes for the current build, so the full test suite should be ran sparingly.
- For function-shape identity changes, run the canonical key tests; inspect generated interface names and method descriptors with `javap` when bytecode identity matters.
- For lambda-lowering changes, and inspect generated shape interfaces with `javap -classpath dist` when bytecode shape matters.
- Keep changes scoped to the behavior being changed. Maintain the stable Java 25 target and do not change generated outputs as incidental cleanup.

## Design References

- For changes covered by the proposal process, follow `documents/zeps/ZEP-0000.md` and use the
  template in `documents/zeps/ZEP-0001.md`. Check `documents/zeps/ZEP.md` for the next available
  number; assign sequential zero-padded IDs, never reuse an ID, and add every new ZEP to the tracker.
  Keep the tracker in numerical order with links, titles, and current statuses, and update it whenever
  a ZEP's status or title changes. Maintain proposal status and published release state only in that
  tracker; future planning belongs in workstreams and release-scoped intent in release plans. Routine
  fixes and contained changes do not need a ZEP.
- `documents/workstreams/README.md`: central workstream index, statuses, and release ledger.
- `documents/workstreams/<name>/README.md`: mission, numbered parts, dependencies, and statuses for
  one concern. ZEPs stay central and are linked, not copied.
- `documents/releases/README.md`: release-plan policy, candidate/selected scope, and archived plans.
  The active plan is `documents/releases/current.md`.
- `documents/zeps/ZEP.md`: numerical tracker of Zeron Enhancement Proposals and published release
  state.
- `documents/zeps/ZEP-0000.md`: ZEP scope, numbering, lifecycle, and decision process.
- `documents/zeps/ZEP-0001.md`: ZEP authoring template.
- `documents/zeps/ZEP-0012.md`: canonical artifact and public release-series versioning policy.
- `documents/zeps/ZEP-0176.md`: workstream planning, part statuses, and release-ledger rules.
- `documents/zeps/ZEP-0179.md`: release plans, candidate tracking, and selected-scope rules.
- `documents/design_documents/design-document-01_grammar.ebnf`: implemented source grammar in ISO/IEC 14977 EBNF.
- Retired, archive only: `documents/design_documents/design-document-02_type-grammar.cfgr`. Its encoded descriptor grammar is not authoritative for current source syntax or type representation.
- `documents/design_documents/design-document-00.md`: language overview and intended features.
- `documents/design_documents/design-document-03_lambda-lowering.md`: current lambda lowering, known gaps, and roadmap.
- `documents/design_documents/design-document-04_mutability.md`: mutability semantics and implementation.
- `documents/design_documents/design-document-05_classes-and-contracts.md`: classes and contracts.
- `documents/design_documents/design-document-06_intrinsic-arrays.md`: intrinsic array types and operations.
- `documents/design_documents/design-document-19_generic-type-system.md`: current generic type system status,
  limitations, acceptance criteria, and roadmap.
- Superseded, historical note: `documents/design_documents/design-document-07_generic-functions.md`.
- `documents/design_documents/design-document-08_flow-typing-type-tests-and-casts.md`: flow typing, type tests, and casts.
- `documents/design_documents/design-document-09_namespaces-packages-and-imports.md`: source-level names, packages, imports, and visibility.
- `documents/design_documents/design-document-10_small-miscelaneous.md`: low-priority null and equality feature discussion.
- `documents/design_documents/design-document-11_compilation-libraries-and-host-integration.md`: project compilation, libraries, and Java interop.
- `documents/design_documents/design-document-12_intrinsics-and-external-bindings.md`: compiler intrinsic registry and external declaration design.
- `documents/design_documents/design-document-13_typed-raised-effects.md`: typed raised effects, runtime behavior, and open design questions.
- `documents/design_documents/design-document-14_sum-product-types-and-newtypes.md`: exploratory design for algebraic sums, products, and value-class-like newtypes.
- `documents/design_documents/design-document-15_type-class-witnesses-and-generic-bounds.md`: type-class witnesses, applied contract bounds, and generic bounds.
- `documents/design_documents/design-document-16_patterns-and-pattern-matching.md`: pattern syntax, resolution, exhaustiveness, and compilation.
- `documents/design_documents/design-document-17_structural-equality-and-hashing.md`: structural equality, hashing, `Eq<T>`, and contract composition.
- `documents/design_documents/design-document-18_member-discovery-and-stabilization.md`: proposed public member-discovery API and bounded stabilization milestone.
