# Public Member Discovery and Stabilization

## Status

The public member discovery API described here is implemented following accepted ZEP-0009. A
protocol-independent LSP direction is separately accepted in ZEP-0021; its implementation and
release timing are not committed by this document. The stabilization milestone below remains a
proposed, bounded next step; its scope and timebox should be agreed before work begins.

## Implemented API

`com.maruseron.zeron.api.PublicMemberCatalog` is the immutable, editor-independent query boundary.
Create it with `fromSource(ResolutionResult)` or `fromLibrary(ZeronLibraryIndex)`, then query a
qualified type with `findType(name, appliedArguments)`. Returned records contain source-level
signatures, parameter names/default and variadic metadata, generic substitutions, mutability,
effects, constructors, optional source provenance, direct members, available contract
requirements/defaults, and separately listed matching extensions with their explicit activation
import. Source ranges use zero-based UTF-16 coordinates. Compiled declarations have no source
provenance. Queries cover public classes, contracts, sums, products, and newtypes. The API exposes
neither AST nodes nor index records.

The index schema was advanced only for facts missing from its existing wire model: package-qualified
nominal type identities and callable parameter names. Those facts are required for source/library
identity and signature parity.

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

Types and members need source-level declaration identities that distinguish overloads and do not
depend on AST object identity, source offsets, binary-index order, or generated JVM names. For
source-backed declarations, optionally attach provenance with a source URI and range. Compiled
libraries without source files still return the same semantic declarations, with provenance absent.
The public range contract must state its offset and line/column bases and coordinate encoding; an
editor adapter can convert it to the position encoding negotiated with its client.

The first version should answer type-level discovery questions. It need not answer which members are
in scope at an arbitrary cursor. Contextual accessibility and completion ranking belong to a later
query layer.

## Source and compiled-library parity

Source declarations and declarations loaded from a compiled library must produce equivalent public
semantic member records for equivalent APIs. The source path should use resolved declarations; the
compiled path should use validated `ZeronLibraryIndex` data. Neither path should derive source
signatures from JVM reflection or generated bytecode. Provenance is supplementary: compare semantic
signatures and declaration identities for parity, and compare locations only when available.

Before extending the binary index, compare its current exported metadata with the proposed public
model. Add index fields only where a required source-level fact is genuinely absent. Any wire-format
change must use the centralized schema version; any incompatible bundled-library contract change
must use the separate standard-library API version. Keep serialization compatibility decisions in
the library-artifact design, not in the public query API.

Parity tests should build a representative library from source, load the compiled artifact, query
both forms, and compare normalized public semantic member records, including declaration identities.
Compare provenance only for source-backed declarations; its absence in a compiled artifact must not
alter semantic identity or visibility. Include generic class and contract signatures, properties,
mutating methods, raised effects, constructors, and explicitly imported extensions. Do not require
private declarations or implementation bodies to match because they are not part of the public API.

## Future editor integration

A later editor service can compose this catalog with document analysis:

1. Parse and resolve the current project and imports.
2. Determine the expression type and lexical scope at a source position.
3. Query members and applicable extensions using the applied receiver type.
4. Filter by access context and resolution rules, then return candidates with source-level
   signatures and diagnostics.

That work requires source-position mappings for relevant AST nodes, useful behavior on incomplete
input, and a document/project lifecycle. It should not be smuggled into the type-level discovery
API. The catalog may expose optional declaration provenance, but should not depend on LSP types,
cursor state, open-document versions, incomplete-source recovery, or completion ranking. A future
LSP-facing layer can compose it with an immutable project/document analysis snapshot, use source maps
for cursor and diagnostic ranges, then apply lexical scope, imports, accessibility, and resolution
precedence. Do not expose resolver tokens, AST object identity, or mutable workspace state through
the public catalog.

## Proposed bounded stabilization milestone

### Intent

Establish a known-good, documented release baseline for the current feature set. This is a quality
and release milestone, not a promise that every feature is finished or that the language is fully
stable.

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

At the end of the cycle, review the remaining issue list and select the next bounded milestone.
The type-level member discovery API is already implemented. LSP implementation remains a separate
workstream under accepted ZEP-0021; it is not part of this catalog stabilization milestone.
