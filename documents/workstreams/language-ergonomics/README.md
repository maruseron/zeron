# Language Ergonomics

| Field | Value |
| --- | --- |
| Status | Active |
| ZEPs | [ZEP-0085](../../zeps/ZEP-0085.md), [ZEP-0163](../../zeps/ZEP-0163.md), [ZEP-0167](../../zeps/ZEP-0167.md), [ZEP-0168](../../zeps/ZEP-0168.md), [ZEP-0170](../../zeps/ZEP-0170.md), [ZEP-0186](../../zeps/ZEP-0186.md) |

## Mission

Remove small sources of friction in type checking, inference, and contract inheritance without
introducing a new subsystem. Each part should be independently understandable, independently
shippable, and justified by a concrete program that currently requires an avoidable workaround.

This workstream is for contained language smoothing. Larger programs such as static verification,
resource scoping, and bytecode representation changes have their own workstreams.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0163](../../zeps/ZEP-0163.md) | Admit mutable-reference receivers for inherited read-only generic method binders without changing the element type | — | Accepted | — |
| 2 | [ZEP-0167](../../zeps/ZEP-0167.md) | Separate capability requirements from allowed instantiations in generic type arguments | — | Rejected | — |
| 3 | [ZEP-0168](../../zeps/ZEP-0168.md) | Allow covariant return refinement in a contract redeclaration and reuse the class-side bridge | — | Accepted | — |
| 4 | [ZEP-0085](../../zeps/ZEP-0085.md) | Default inferred bindings to read-only projections and add explicit `mut` preservation of a mutable view | — | Rejected | — |
| 5 | [ZEP-0186](../../zeps/ZEP-0186.md) | Add apply, mapping, filtering, and bind pipe operators with a scoped subject placeholder, zero-argument extension references, and argument-bearing extension calls; syntax-only, with no new runtime or type-system subsystem | — | Accepted | — |
| 6 | [ZEP-0170](../../zeps/ZEP-0170.md) | Warn when a comprehension-shaped `do` block targets concrete `Stream<_>` where a `for` expression is clearer; advisory only | — | Accepted | — |

## Sequencing

The parts were designed independently. Parts 1, 3, 5, and 6 are `Accepted`; parts 2 and 4 are
`Rejected`. Parts 1, 3, 5, and 6 are candidates in the
[0.10.0 release plan](../../releases/current.md); candidacy is not a release assignment.

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
