# Operator overloading

Zeron supports a small set of user-defined and generic operators through explicit algebraic
contracts. A type does not gain operator behavior merely because it has a similarly named method:
the required contract must be in its conformance or available as witness evidence.

## Algebraic capabilities

The standard library defines four contracts in `zeron.algebra`:

- `Add<T>` provides `add(other)` and the additive identity `zero()`.
- `Addition<T>` requires `Add<T>` and adds `negate()`.
- `Mult<T>` provides `multiply(other)` and the multiplicative identity `one()`.
- `Multiplication<T>` requires `Mult<T>` and adds `reciprocal()`.

The initial operator mapping is:

| Operator | Required contract | Operation |
| -------- | ---------------- | --------- |
| `+` | `Add<T>` | Add the right value to the left. |
| `*` | `Mult<T>` | Multiply the values. |
| unary `-` | `Addition<T>` | Negate the value. |
| binary `-` | `Addition<T>` | Add the negation of the right value. |

For example, a generic addition function states its requirement explicitly:

```zeron
import zeron.algebra.Add;

fn add<T: mut + Add<T>>(left: T, right: T): T = left + right;
```

The same function can use the compiler-provided evidence for supported numeric types or an
applicable implementation for a user-defined type. `Int` and `Long` support additive and
multiplicative operations but do not provide `Multiplication`, because zero has no multiplicative
inverse. `Float` supports all four contracts.

## Built-in behavior and limits

Concrete arithmetic on `Int`, `Long`, and `Float` keeps its intrinsic behavior. `String + T` keeps
its existing `Display<T>` behavior and takes precedence over algebraic `Add<T>` evidence. For
generic code, operator requirements are not inferred: declare the corresponding contract bound.
Operands and results in this initial operator set use the same type; mixed-type promotion is not
inferred.

Although `Multiplication<T>` provides `reciprocal`, `/` is not overloaded. The compiler checks
required methods and types, but it does not prove associativity, identities, inverses, commutativity,
or any other algebraic law. The
[operator-overloading proposal](../documents/zeps/ZEP-0036.md) records the complete evidence matrix
and compatibility rules.
