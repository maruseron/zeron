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

See [NAVIGATION.md](./NAVIGATION.md) for the compiler pipeline and resolver/compiler module
responsibilities.

## Feature Roadmap and Shipping

- For feature work requiring a proposal, draft and resolve a ZEP before implementation. Follow
  `documents/zeps/ZEP-0000.md` and `ZEP-0001.md`, and register it in `documents/zeps/ZEP.md`.
- Implement the accepted design, then update `CHANGELOG.md`, relevant design references, and the
  ZEP proposal status. Keep the tracker synchronized whenever proposal status or title changes.
- Update `README.md` and/or relevant `guide/` chapters to document user-facing behavior.

## Release Cycle and Process

- Follow `documents/zeps/ZEP-0012.md` for artifact versioning. Bump the project version in
  `pom.xml`, then run `powershell -ExecutionPolicy Bypass -File scripts/release.ps1` from the
  repository root.
- `API_INDEX_SCHEMA_VERSION` and `STANDARD_LIBRARY_API_VERSION` in `ZeronVersions.java` can each
  be bumped only once per release cycle. Consolidate required API and schema changes before
  incrementing either version.

## Validation

- For compiler or resolver changes, run `mvn test` (or compile all Java sources using the command above when Maven is unavailable).
- For function-shape identity changes, run the canonical key tests and compile `src/main/resources/test.zn`; inspect generated interface names and method descriptors with `javap` when bytecode identity matters.
- For lambda-lowering changes, compile `src/main/resources/test.zn`, invoke the generated program, and inspect `test` plus generated shape interfaces with `javap -classpath dist` when bytecode shape matters.
- Keep changes scoped to the behavior being changed. Maintain the stable Java 25 target and do not change generated outputs as incidental cleanup.

## Design References

- For changes covered by the proposal process, follow `documents/zeps/ZEP-0000.md` and use the
  template in `documents/zeps/ZEP-0001.md`. Check `documents/zeps/ZEP.md` for the next available
  number; assign sequential zero-padded IDs, never reuse an ID, and add every new ZEP to the tracker.
  Keep the tracker in numerical order with links, titles, and current statuses, and update it whenever
  a ZEP's status or title changes. Maintain release state and release/bundle reference only in that tracker,
  separately from proposal status; update them as a ZEP becomes a candidate, is planned, deferred, or
  shipped. Routine fixes and contained changes do not need a ZEP.
- `documents/zeps/ZEP.md`: numerical tracker of Zeron Enhancement Proposals and canonical release
  candidacy/bundle state.
- `documents/zeps/ZEP-0000.md`: ZEP scope, numbering, lifecycle, and decision process.
- `documents/zeps/ZEP-0001.md`: ZEP authoring template.
- `documents/zeps/ZEP-0012.md`: canonical artifact and public release-series versioning policy.
- `documents/design-document-01_grammar.cfgr`: implemented source grammar.
- Retired, archive only: `documents/design-document-02_type-grammar.cfgr`. Its encoded descriptor grammar is not authoritative for current source syntax or type representation.
- `documents/design-document-00.md`: language overview and intended features.
- `documents/design-document-03_lambda-lowering.md`: current lambda lowering, known gaps, and roadmap.
- `documents/design-document-04_mutability.md`: mutability semantics and implementation.
- `documents/design-document-05_classes-and-contracts.md`: classes and contracts.
- `documents/design-document-06_intrinsic-arrays.md`: intrinsic array types and operations.
- `documents/design-document-07_generic-functions.md`: generic functions.
- `documents/design-document-08_flow-typing-type-tests-and-casts.md`: flow typing, type tests, and casts.
- `documents/design-document-09_namespaces-packages-and-imports.md`: source-level names, packages, imports, and visibility.
- `documents/design-document-10_small-miscelaneous.md`: low-priority null and equality feature discussion.
- `documents/design-document-11_compilation-libraries-and-host-integration.md`: project compilation, libraries, and Java interop.
- `documents/design-document-12_intrinsics-and-external-bindings.md`: compiler intrinsic registry and external declaration design.
- `documents/design-document-13_typed-raised-effects.md`: typed raised effects, runtime behavior, and open design questions.
- `documents/design-document-14_sum-product-types-and-newtypes.md`: exploratory design for algebraic sums, products, and value-class-like newtypes.
- `documents/design-document-15_type-class-witnesses-and-generic-bounds.md`: type-class witnesses, applied contract bounds, and generic bounds.
- `documents/design-document-16_patterns-and-pattern-matching.md`: pattern syntax, resolution, exhaustiveness, and compilation.
- `documents/design-document-17_structural-equality-and-hashing.md`: structural equality, hashing, `Eq<T>`, and contract composition.
- `documents/design-document-18_member-discovery-and-stabilization.md`: proposed public member-discovery API and bounded stabilization milestone.
