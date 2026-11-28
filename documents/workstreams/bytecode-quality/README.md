# Bytecode Quality and Optimization

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0095](../../zeps/ZEP-0095.md), [ZEP-0096](../../zeps/ZEP-0096.md), [ZEP-0180](../../zeps/ZEP-0180.md), [ZEP-0181](../../zeps/ZEP-0181.md), [ZEP-0182](../../zeps/ZEP-0182.md), [ZEP-0183](../../zeps/ZEP-0183.md) |

## Mission

Reduce generated class count, class size, allocation, and dispatch overhead without changing Zeron
source semantics, separate compilation, Java interop, or deterministic output. The workstream owns
the compiler's emission-quality program and its measurable quality baseline.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0095](../../zeps/ZEP-0095.md) | Bytecode quality and evidence inlining: `Unit` lowering, direct carriers, lambda and shape emission, evidence inlining, equality/hashing, initialization, and local peepholes | — | Draft | — |
| 2 | [ZEP-0096](../../zeps/ZEP-0096.md) | Primitive specialization: primitive arrays, specialized entry points, collection storage, and compact evidence | 1 | Draft | — |
| 3 | [ZEP-0180](../../zeps/ZEP-0180.md) | Witness-interface evidence passing: witness interfaces, witness classes, evidence dictionaries, HKT and derived-witness migration, and the evidence ABI | 1; coordinates with 2 | Draft | — |
| 4 | [ZEP-0181](../../zeps/ZEP-0181.md) | Bootstrapped constants and shared bytecode runtime: condy witness constants, indy witness factories, bootstrap ABI, and the shared hidden-class bootstrap | 3 | Draft | — |
| 5 | [ZEP-0182](../../zeps/ZEP-0182.md) | Hidden-class anonymous constants: object literals, handler values, named handler declarations, class-byte embedding, and named/lambda fallbacks | 4 | Draft | — |
| 6 | [ZEP-0183](../../zeps/ZEP-0183.md) | Switch-bootstrapped match dispatch: `SwitchBootstraps.typeSwitch` top-level labels, restart protocol, selected-pattern emission, and chain fallback | 1; coordinates with 5 | Draft | — |

## Sequencing

Part 1 is one design with five independently shippable implementation stages: local peepholes and
initialization; evidence inlining; lambda and shape placement; `Unit` boundaries; and direct-carrier
newtypes. The stages are not separate workstream parts unless a later decision splits them into
independently reviewable ZEPs.

Part 2 is deliberately separate because primitive-specialized representations and entry points are
ABI-changing. It follows part 1's quality baseline and measurement harness so specialization is
compared against the improved erased output. Neither part is assigned to a release.

Part 3 changes the erased evidence carrier from per-slot `MethodHandle` parameters to witness
interfaces and dictionaries. It follows the part 1 baseline and resolves the compact-evidence
representation left open by part 2. Part 3 and part 2 both affect the library index; their schema
changes must be consolidated under one `API_INDEX_SCHEMA_VERSION` and
`STANDARD_LIBRARY_API_VERSION` review in the same release cycle.

Part 4 owns the shared condy/indy bootstrap runtime for both witness linking and hidden-class
definition. It follows part 3 because witness constants are its first consumer, and it must not
define a second class-definition mechanism for part 5.

Part 5 lowers eligible object literals and handler values to hidden classes. It follows part 4 for
the bootstrap ABI and class-byte hooks. Object and handler lowering may land in separate stages, but
the named synthetic class and `LambdaMetafactory` remain the fallbacks throughout.

Part 6 is an emission-only match-dispatch change. It follows part 1's quality baseline and
measurement harness and coordinates with part 5 because hidden anonymous classes cannot be bootstrap
labels. It does not depend on parts 3–4 and does not extend the shared bootstrap runtime. String
roots, primitive roots, and nested decision trees remain separate future parts. No part is assigned
to a release.

## Boundaries

- Source behavior, evaluation order, exceptions, null checks, identity, mutability, and contract
  selection must not change.
- Compiled-library compatibility and separate compilation are part of every part's validation.
- This workstream does not add language features or library APIs; it changes how existing programs
  are emitted.
- Witness selection remains static. Evidence dictionaries and witness objects are passive carriers,
  not runtime lookup tables.
- Hidden classes are used only for anonymous values whose generated type does not escape into a
  method descriptor, field descriptor, stack-map frame, exported API, reified descriptor, or
  serialization metadata. The named synthetic class remains the fallback.
- Object literal instances and handler literal values are never shared across evaluations. Only a
  named handler declaration, which denotes one immutable value, may use a condy singleton.
- Match dispatch may use the JDK's `SwitchBootstraps.typeSwitch`, but a bootstrap label must be a
  named class. Match semantics, evaluation order, null and no-match behavior, and the no-match
  exception must not change.
- Quality thresholds and representation defaults remain open questions in the ZEPs.

## References

- [Class-file quality inventory](../../developer_guides/classfile-quality-inventory.md) — measured
  baseline and itemized opportunities.
- [ZEP-0012](../../zeps/ZEP-0012.md) — artifact and public release-series versioning.
