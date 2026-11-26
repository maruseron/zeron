# Static Newtype Verification

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0138](../../zeps/ZEP-0138.md), [ZEP-0139](../../zeps/ZEP-0139.md), [ZEP-0140](../../zeps/ZEP-0140.md), [ZEP-0141](../../zeps/ZEP-0141.md), [ZEP-0142](../../zeps/ZEP-0142.md), [ZEP-0143](../../zeps/ZEP-0143.md), [ZEP-0144](../../zeps/ZEP-0144.md) |

## Mission

Make guarded newtypes and value invariants statically usable. The compiler should carry proven facts
about values, accept checked construction when a predicate is known to hold, and report a clear
failure when it is not. The workstream builds one shared predicate and fact representation rather
than adding independent ad-hoc checks.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0138](../../zeps/ZEP-0138.md) | Predicate and fact IR, normalization, three-valued decisions, constant evaluation, trust boundary, and index encoding | — | Draft | — |
| 2 | [ZEP-0139](../../zeps/ZEP-0139.md) | Checked `new` for guarded newtypes with constant proof and runtime fallback | 1 | Draft | — |
| 3 | [ZEP-0140](../../zeps/ZEP-0140.md) | Comparison facts and type-intrinsic newtype facts in flow typing | 1 | Draft | — |
| 4 | [ZEP-0141](../../zeps/ZEP-0141.md) | Predicate functions and call-site fact transfer | 1, 3 | Draft | — |
| 5 | [ZEP-0142](../../zeps/ZEP-0142.md) | Interval and difference-bound facts over stable terms | 1, 3 | Draft | — |
| 6 | [ZEP-0143](../../zeps/ZEP-0143.md) | `IntRange` bounds, half-open ranges, and loop facts | 5 | Draft | — |
| 7 | [ZEP-0144](../../zeps/ZEP-0144.md) | Parameter refinements and call-site preconditions | 3, 4, 5, 6 | Draft | — |

## Sequencing

Part 1 is the shared foundation and should be designed and accepted before the feature parts. Part 2
is the most separable first feature: it can begin with constant evaluation and a runtime fallback.
Part 3 supplies the first useful flow proofs for checked construction. Parts 4 and 5 consume the
fact IR and comparison facts; part 6 supplies range-loop facts; part 7 is the capstone and should not
become `Ready` until the fact stores, predicate metadata, interval proofs, and range facts are stable.

The parts are dependency-ordered and can ship incrementally. No part is assigned to a release, and
recording the workstream does not imply that all seven parts ship together.

## Boundaries

- This is not a general refinement-type system.
- The workstream does not promise bounds-check elimination; interval reasoning is an internal
  analysis backend.
- Runtime validation remains the fallback where a proof is unavailable.
- Predicate serialization and compiled-library metadata are part of the design, not an afterthought.

## Current focus

Review and accept the fact IR in [ZEP-0138](../../zeps/ZEP-0138.md). The later parts remain `Draft`
until that foundation is stable.
