# Numeric Surface

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0044](../../zeps/ZEP-0044.md), [ZEP-0045](../../zeps/ZEP-0045.md), [ZEP-0046](../../zeps/ZEP-0046.md), [ZEP-0074](../../zeps/ZEP-0074.md), [ZEP-0075](../../zeps/ZEP-0075.md) |

## Mission

Make numeric literals, suffixes, units, and operators compositional and predictable without weakening
exact integer/rational semantics, explicit conversion rules, or the existing numeric hierarchy. The
workstream covers the source-facing numeric surface after ZEP-0043 and ZEP-0071.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0075](../../zeps/ZEP-0075.md) | General contextual construction for unsuffixed numeric literals and expected-type propagation | — | Draft | — |
| 2 | [ZEP-0074](../../zeps/ZEP-0074.md) | Numeric literal suffixes that construct nominal newtype values | — | Draft | — |
| 3 | [ZEP-0044](../../zeps/ZEP-0044.md) | Unit literals, scaled units, dimensions, and exact conversion rules | — | Draft | — |
| 4 | [ZEP-0045](../../zeps/ZEP-0045.md) | Contract-based operators with different operand and result types | — | Draft | — |
| 5 | [ZEP-0046](../../zeps/ZEP-0046.md) | Bounded, destination-declared implicit widening for numeric expressions | — | Draft | — |

## Sequencing

Parts 1 and 2 cover literal construction and should be designed against one shared resolution model.
Part 3 builds on the shipped custom suffix mechanism in ZEP-0043 and should be coordinated with the
literal-construction parts so unit suffixes do not become a separate, inconsistent grammar.

Parts 4 and 5 share the operator design space. ZEP-0045 defines heterogeneous operator signatures;
ZEP-0046 defines widening from one operand type to another. Their relationship must be decided
before either becomes `Ready`: they may be alternatives, complementary, or only one may be adopted.
No part is assigned to a release.

## Boundaries

- This does not replace the numeric hierarchy or exact `Integer`/`Rational` semantics in ZEP-0071.
- It does not introduce unbounded implicit conversion between established numeric types.
- It does not require a standard unit package or arbitrary compile-time code for units.
- Units, suffixes, and operators remain statically resolved and separately compilable.

## Current focus

Review the literal-construction designs in ZEP-0075 and ZEP-0074, then settle the relationship
between heterogeneous operators and implicit widening in ZEP-0045/ZEP-0046.
