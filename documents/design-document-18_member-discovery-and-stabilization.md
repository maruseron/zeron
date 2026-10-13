# Public Member Discovery and Stabilization

## Status

Proposed design. This document does not commit the project to an editor integration or a release
date. The stabilization milestone below is a proposed, bounded next step; its scope and timebox
should be agreed before work begins.

## Motivation

Third-party tools need a supported way to discover Zeron's public API. Documentation generators,
API browsers, build integrations, and eventually editors should not have to inspect compiler
internals or parse generated JVM classes to learn which members a Zeron type exposes.

Member discovery is a semantic capability, not syntax coloring. The initial objective is a stable
query over public type and library metadata. Cursor-aware completion, diagnostics while typing, and
other editor services are separate work.

## Existing foundations

The compiler already has two useful forms of semantic information:

- `ResolutionService` and `ResolutionResult` resolve declarations, imports, and types for source
  compilation. The result is compilation-oriented and does not currently provide a general public
  query from an applied type to its available members, or from every source expression to its
  resolved type.
- `ZeronLibraryIndex` is the portable boundary for compiled Zeron libraries. It already records
  class and contract members, properties, constructors, generic signatures and bounds, mutability,
  raised effects, and explicit extension metadata. Its schema and the standard-library API version
  are centrally versioned.

This is a strong metadata base, but the binary index is not itself a suitable third-party query API.
Consumers should not need to decode its wire format or depend on AST objects reconstructed from it.
See [design-document-11](./design-document-11_compilation-libraries-and-host-integration.md) for
library artifacts and [design-document-09](./design-document-09_namespaces-packages-and-imports.md)
for source-level names, imports, visibility, and lookup.

## Goals

1. Provide a stable, read-only public API for discovering public Zeron type members.
2. Give source-backed and compiled-library-backed declarations the same semantic representation.
3. Preserve language-level signature information needed for accurate display and later tooling.
4. Keep declaration discovery distinct from context-sensitive completion.
5. Make the API useful without an LSP, while leaving a clean path for one.

## Non-goals for the first increment

- Implementing an LSP, VS Code semantic extension, or editor-specific protocol.
- Querying arbitrary source positions, recovering completion candidates from incomplete syntax, or
  incrementally reanalyzing changed documents.
- Reflectively discovering arbitrary JVM members. Java interop remains limited to the compiler's
  declared classpath and facade rules; inherited Java-member lookup is not implied.
- Exporting private implementation details, method bodies, generated JVM names as source semantics,
  or source documentation comments unless a separate documentation-metadata design is agreed.
- Defining a stable compatibility promise for all compiler internals or for the binary index beyond
  its existing version checks.

## Proposed public model

Expose an immutable semantic catalog through a compiler-owned API rather than exposing
`ResolutionContext`, resolver maps, AST nodes, or binary-index records directly. Exact Java type and
package names should be selected during implementation; this design specifies behavior, not a class
layout.

A type query should accept a fully qualified type identity and, where relevant, its applied type
arguments. Results should contain public source-level declarations and enough metadata to render
their signatures:

- member name and kind (method, property, constructor, or contract requirement);
- declaring type and origin, distinguishing directly declared members, contract-provided/default
  members, and extension methods;
- callable type parameters and bounds, named parameters and types, default/variadic information
  where applicable, and return type;
- property type and readable/writable capability;
- receiver mutability, static/instance status where the language supports it, and declared raised
  effects;
- applied generic substitutions for the queried type, without erasing the original declaration
  signature.

The API should return overloads and same-named declarations as distinct entries when the language
model permits them. It must not merge members solely by name or present ambiguous members as though
one had been selected.

Member origin matters. A consumer may want the methods declared by a class, the requirements and
defaults contributed by its applied contracts, or applicable extension methods. Keep these
categories explicit so a future completion service can apply import scope, visibility, and
resolution precedence rather than treating every discovered declaration as equally callable.

The first version should answer type-level discovery questions. It need not answer which members are
in scope at an arbitrary cursor. Contextual accessibility and completion ranking belong to a later
query layer.

## Source and compiled-library parity

Source declarations and declarations loaded from a compiled library must produce equivalent public
member records for equivalent APIs. The source path should use resolved declarations; the compiled
path should use validated `ZeronLibraryIndex` data. Neither path should derive source signatures
from JVM reflection or generated bytecode.

Before extending the binary index, compare its current exported metadata with the proposed public
model. Add index fields only where a required source-level fact is genuinely absent. Any wire-format
change must use the centralized schema version; any incompatible bundled-library contract change
must use the separate standard-library API version. Keep serialization compatibility decisions in
the library-artifact design, not in the public query API.

Parity tests should build a representative library from source, load the compiled artifact, query
both forms, and compare normalized public member records. Include generic class and contract
signatures, properties, mutating methods, raised effects, constructors, and explicitly imported
extensions. Do not require private declarations or implementation bodies to match because they are
not part of the public API.

## Future editor integration

A later editor service can compose this catalog with document analysis:

1. Parse and resolve the current project and imports.
2. Determine the expression type and lexical scope at a source position.
3. Query members and applicable extensions using the applied receiver type.
4. Filter by access context and resolution rules, then return candidates with source-level
   signatures and diagnostics.

That work requires source-position mappings for relevant AST nodes, useful behavior on incomplete
input, and a document/project lifecycle. It should not be smuggled into the type-level discovery
API. The API should avoid preventing these additions, but should not carry editor-specific concepts
before an actual client needs them.

## Proposed bounded stabilization milestone

### Intent

Establish a known-good, documented release baseline for the current feature set before adding the
member-discovery API. This is a quality and release milestone, not a promise that every feature is
finished or that the language is fully stable.

The repository has recently completed a full 378-test run and a release-script run that exercised
both bundled-source and compiled-standard-library project builds. Treat those as the current green
baseline; repeat them for the milestone release rather than reopening already-passing work without
a failure signal.

### In scope

- Freeze new language semantics and standard-library API additions for this milestone, except where
  a targeted fix is necessary to resolve a release-blocking defect.
- Triage known defects in existing implemented features. Fix defects that cause incorrect
  compilation or runtime behavior, corrupt library/API compatibility, or prevent the documented
  release workflow. Record accepted limitations rather than broadening scope to speculative
  cleanup.
- Keep the full Maven test suite green under the project's supported JDK and preview configuration.
- Keep the release script green, including diagnostics/explanations and project build-and-run smoke
  coverage for both bundled-source and compiled-standard-library modes.
- Audit the user-facing documentation touched by the current feature set for contradictions with
  implemented behavior. Update only inaccurate or missing guidance needed to use the release and
  its supported features.
- Produce one release candidate/archive and verify its checksum and the two standard-library modes.

### Exit criteria

The milestone is complete when:

1. There are no known release-blocking correctness, packaging, or API-index compatibility defects.
2. The complete test suite passes with zero failures.
3. The release script succeeds from a clean build and creates the compiler archive and checksum.
4. Both source-mode and compiled-mode smoke projects compile and run through the packaged compiler.
5. Any remaining known limitations relevant to the release are documented or explicitly deferred.

### Explicitly out of scope

- New language features, new standard-library surface, and opportunistic refactors.
- A claim of source or binary compatibility beyond the project's existing versioning policy.
- An LSP, editor plugin, cursor-sensitive completion, incremental compilation, or automated
  quick-fixes.
- Fixing every low-priority issue or expanding test coverage without a specific risk or defect.

### Suggested timebox and review

Timebox this as one focused stabilization cycle, provisionally two to three weeks. If the exit
criteria are not met by the end of the cycle, report the remaining blockers and decide whether to
extend the cycle once or reduce the release scope; do not silently turn it into indefinite feature
freeze. The duration is a planning proposal, not a release-date commitment.

At the end of the cycle, review the remaining issue list and decide whether to begin the member
discovery API as the next bounded milestone. The first implementation should start with the
type-level catalog and source/compiled parity tests; LSP work remains a separate decision.
