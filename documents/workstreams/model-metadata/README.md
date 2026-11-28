# Model Metadata and Mapping

| Field | Value |
| --- | --- |
| Status | Active |
| ZEPs | [ZEP-0091](../../zeps/ZEP-0091.md), [ZEP-0097](../../zeps/ZEP-0097.md), [ZEP-0101](../../zeps/ZEP-0101.md), [ZEP-0102](../../zeps/ZEP-0102.md), [ZEP-0103](../../zeps/ZEP-0103.md) |

## Mission

Complete the declarative model surface so model declarations can carry compiler-recognized
metadata, map to external names, adapt field representations, and describe generic and closed-sum
models without generating one-off serialization code.

The shipped foundation is model declarations, generated serialization operations, unified property
identity, and annotation targets ([ZEP-0079](../../zeps/ZEP-0079.md),
[ZEP-0092](../../zeps/ZEP-0092.md), [ZEP-0093](../../zeps/ZEP-0093.md), and
[ZEP-0098](../../zeps/ZEP-0098.md)). This workstream covers the remaining metadata and mapping
design, not the already shipped runtime operations.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0091](../../zeps/ZEP-0091.md) | Compiler-recognized annotation consumption | — | Accepted | — |
| 2 | [ZEP-0097](../../zeps/ZEP-0097.md) | `MAPPING` annotation target for model mapping clauses | 1 | Accepted | — |
| 3 | [ZEP-0101](../../zeps/ZEP-0101.md) | Field representation adapters for model mappings | — | Accepted | — |
| 4 | [ZEP-0102](../../zeps/ZEP-0102.md) | Generic models through model factories | 3 | Draft | — |
| 5 | [ZEP-0103](../../zeps/ZEP-0103.md) | Polymorphic models for closed sum types | 4 | Draft | — |

## Sequencing

Part 1 lands the annotation model that part 2 consumes. Part 3 no longer depends on part 1 for its
core: `via` names values rather than annotations, and only reusing adapters in serialization
derivation waits for the annotation model. Parts 4 and 5 are later layers; they remain `Draft` until
their designs are accepted.

Parts 1–3 are candidates in the active [release plan](../../releases/current.md). Candidacy is
visibility, not a version assignment; the parts become eligible for selection only when their status
is `Ready` and the release plan freezes scope.

## Out of scope

- Generated serialization operations, which shipped with [ZEP-0092](../../zeps/ZEP-0092.md).
- External names as a general annotation facility; that is part 1's consumption model, not a new
  naming feature.
- Scoped resource values and cleanup syntax, which belong to the
  [scoped resources](../scoped-resources/README.md) workstream.
