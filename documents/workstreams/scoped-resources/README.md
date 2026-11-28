# Scoped Resources

| Field | Value |
| --- | --- |
| Status | Active |
| ZEPs | [ZEP-0184](../../zeps/ZEP-0184.md), [ZEP-0160](../../zeps/ZEP-0160.md), [ZEP-0161](../../zeps/ZEP-0161.md), [ZEP-0185](../../zeps/ZEP-0185.md), [ZEP-0158](../../zeps/ZEP-0158.md), [ZEP-0162](../../zeps/ZEP-0162.md) |

## Mission

Add non-escaping scoped values and expression-valued resource blocks without introducing lifetime
annotations, manual close calls, or an ownership system. Scoped values must compose with the
response-typed effects, structured concurrency, and continuation semantics shipped in `0.9.0`.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0184](../../zeps/ZEP-0184.md) | Nominal `Self` in class bodies and contract results | — | Accepted | — |
| 2 | [ZEP-0160](../../zeps/ZEP-0160.md) | `scoped` non-escape attribute, origin tokens, sealing, and escape checking | 1 | Accepted | — |
| 3 | [ZEP-0161](../../zeps/ZEP-0161.md) | Class `scoped fn` / `scoped mut fn`, explicit `scoped Self` results, and sealing call rules | 1, 2 | Accepted | — |
| 4 | [ZEP-0185](../../zeps/ZEP-0185.md) | Scoped contract requirements and defaults, overrides, and extension methods | 1, 2, 3 | Accepted | — |
| 5 | [ZEP-0158](../../zeps/ZEP-0158.md) | Expression-valued resource blocks with scoped receivers | 1, 2, 3 | Draft | — |
| 6 | [ZEP-0162](../../zeps/ZEP-0162.md) | Generic admissibility for scoped values beyond the first slice | 2, 3, 5 | Draft | — |

## Sequencing

Parts 1–4 are the accepted language foundation. Part 1 supplies the receiver name; part 2 supplies
the non-escape attribute and the escape checker; part 3 supplies class receiver methods and
receiver-tied results; part 4 extends the same semantics to contracts, defaults, overrides, and
extensions. Each is independently reviewable and testable. Part 1 is generally useful outside this
workstream, but it is sequenced here because the receiver surface depends on it.

Part 5 is the first user-visible consumer: an expression-valued resource block whose receiver is a
scoped `&File`. It cannot become `Ready` before parts 1–3 are implemented; part 4 extends the same
semantics to contracts and extensions but is not required by the block. Its cleanup rules must agree
with the shipped concurrency and resumption semantics.

Part 6 records the deliberate rejection of scoped type arguments and container scoping in the first
slice. It is a later design and is not required for parts 1–5. Promoting scopedness from an
attribute to a type-level qualifier is the prerequisite for that work.

## Out of scope

- General ownership or lifetime annotations.
- Escaping resource handles or storing them in containers.
- Automatic disposal of arbitrary values without a scoped receiver.
