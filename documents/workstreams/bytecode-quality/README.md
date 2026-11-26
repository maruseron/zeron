# Bytecode Quality and Optimization

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0095](../../zeps/ZEP-0095.md), [ZEP-0096](../../zeps/ZEP-0096.md) |

## Mission

Reduce generated class count, class size, allocation, and dispatch overhead without changing Zeron
source semantics, separate compilation, Java interop, or deterministic output. The workstream owns
the compiler's emission-quality program and its measurable quality baseline.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0095](../../zeps/ZEP-0095.md) | Bytecode quality and evidence inlining: `Unit` lowering, direct carriers, lambda and shape emission, evidence inlining, equality/hashing, initialization, and local peepholes | — | Draft | — |
| 2 | [ZEP-0096](../../zeps/ZEP-0096.md) | Primitive specialization: primitive arrays, specialized entry points, collection storage, and compact evidence | 1 | Draft | — |

## Sequencing

Part 1 is one design with five independently shippable implementation stages: local peepholes and
initialization; evidence inlining; lambda and shape placement; `Unit` boundaries; and direct-carrier
newtypes. The stages are not separate workstream parts unless a later decision splits them into
independently reviewable ZEPs.

Part 2 is deliberately separate because primitive-specialized representations and entry points are
ABI-changing. It follows part 1's quality baseline and measurement harness so specialization is
compared against the improved erased output. Neither part is assigned to a release.

## Boundaries

- Source behavior, evaluation order, exceptions, null checks, and contract selection must not change.
- Compiled-library compatibility and separate compilation are part of every part's validation.
- This workstream does not add language features or library APIs; it changes how existing programs
  are emitted.
- Quality thresholds and representation defaults remain open questions in the ZEPs.

## References

- [Class-file quality inventory](../../developer_guides/classfile-quality-inventory.md) — measured
  baseline and itemized opportunities.
- [ZEP-0012](../../zeps/ZEP-0012.md) — artifact and public release-series versioning.
