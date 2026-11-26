# Scoped Resources

| Field | Value |
| --- | --- |
| Status | Active |
| ZEPs | [ZEP-0158](../../zeps/ZEP-0158.md), [ZEP-0160](../../zeps/ZEP-0160.md), [ZEP-0161](../../zeps/ZEP-0161.md), [ZEP-0162](../../zeps/ZEP-0162.md) |

## Mission

Add non-escaping scoped values and expression-valued resource blocks without introducing lifetime
annotations, manual close calls, or an ownership system. Scoped values must compose with the
response-typed effects, structured concurrency, and continuation semantics shipped in `0.9.0`.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0160](../../zeps/ZEP-0160.md) | `scoped T` projection, origin, escape checking, and canonical form | — | Draft | — |
| 2 | [ZEP-0161](../../zeps/ZEP-0161.md) | `scoped fn` / `scoped mut fn`, `Self` propagation, and contract conformance | 1 | Draft | — |
| 3 | [ZEP-0158](../../zeps/ZEP-0158.md) | Expression-valued resource blocks with `scoped &File` receivers | 1, 2 | Draft | — |
| 4 | [ZEP-0162](../../zeps/ZEP-0162.md) | Generic admissibility for scoped values beyond the first slice | 1, 2, 3 | Draft | — |

## Sequencing

The first slice is parts 1–3: the projection and escape checker, the receiver/method surface, and the
resource block that consumes them. Part 4 records the deliberate rejection of scoped type arguments
and container scoping in the first slice; it is a later design and is not required for parts 1–3.

All four parts are `Draft` and are not assigned to a release. Part 3 cannot become `Ready` before
parts 1 and 2 are implemented, and its cleanup rules must agree with the shipped concurrency and
resumption semantics.

## Out of scope

- General ownership or lifetime annotations.
- Escaping resource handles or storing them in containers.
- Automatic disposal of arbitrary values without a scoped receiver.
