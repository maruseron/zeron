# Language Ergonomics

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0085](../../zeps/ZEP-0085.md), [ZEP-0163](../../zeps/ZEP-0163.md), [ZEP-0167](../../zeps/ZEP-0167.md), [ZEP-0168](../../zeps/ZEP-0168.md) |

## Mission

Remove small sources of friction in type checking, inference, and contract inheritance without
introducing a new subsystem. Each part should be independently understandable, independently
shippable, and justified by a concrete program that currently requires an avoidable workaround.

This workstream is for contained language smoothing. Larger programs such as static verification,
resource scoping, and bytecode representation changes have their own workstreams.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0163](../../zeps/ZEP-0163.md) | Widen mutable-projection receivers to read-only bases when solving inherited read-only generic method bounds | — | Draft | — |
| 2 | [ZEP-0167](../../zeps/ZEP-0167.md) | Separate capability requirements from allowed instantiations in generic type arguments | — | Draft | — |
| 3 | [ZEP-0168](../../zeps/ZEP-0168.md) | Allow covariant return refinement in contract inheritance and emit the required bridge | — | Draft | — |
| 4 | [ZEP-0085](../../zeps/ZEP-0085.md) | Default inferred bindings to read-only projections and add explicit `mut` preservation of a mutable view | — | Draft | — |

## Sequencing

The parts are independent. Part 3 interacts with capability handling from part 2, but it records no
hard dependency and can be designed separately. Part 4 is a contained binding-inference rule and
does not depend on the generic-bound or capability parts. Each part is `Draft` and none is assigned
to a release.

Additional small proposals can join this workstream when they share the same test: the change is
independently shippable, has a concrete ergonomics benefit, and does not introduce a new language
subsystem.

## Boundaries

- Not a general redesign of generics, contracts, or inference.
- Not a place for effects, scoped resources, or static verification; those concerns have their own
  workstreams.
- Numeric literal, unit, suffix, and operator design belongs to the
  [numeric surface](../numeric-surface/README.md) workstream.
- No part should be treated as `Ready` until its design is accepted and its implementation and
  separate-compilation checks are complete.
