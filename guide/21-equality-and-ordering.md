# Equality and ordering

Most Zeron types cannot use comparison operators automatically. A class does not become
comparable just because it has fields, and the compiler does not guess how two values should be
compared. Scalar types have a few built-in operations: `Int` and `Float` can be compared for
equality and order, while `Boolean` and `String` support equality. `String` has other useful
operations, such as `startsWith` and `contains`, but it does not get the relational operators
`<`, `<=`, `>`, and `>=` by default.

For other types, Zeron uses contracts to make comparison behavior explicit. The standard library
defines `Eq<T>` for equality and `Comparable<T>` for ordering. These capabilities are separate:
equality does not imply an ordering, and ordering does not imply equality.

`Float.NaN` names the IEEE not-a-number value. `Float.isNaN()` and `Float.isFinite()` classify
floating-point values. NaN compares equal to NaN under Zeron's value-equality rule, but every
relational comparison involving NaN is false. `Float.sign` returns `Option<Int>` and is `None` for
NaN; positive and negative zero both have sign `Some(0)`.

## Equality with `Eq<T>`

`Eq<T>` describes how to compare two values of the same type for value equality. Its single method
is `eq(other: T): Boolean`. The operators `==` and `!=` use this evidence; `!=` is the negation of
the same equality comparison.

Built-in equality is available for `Int`, `Float`, `Boolean`, `String`, and `Unit`. Other types
need an applicable `Eq<T>` implementation, provided by nominal conformance, a witness, or
structural equality for supported product and sum types. For example, a generic function must
declare that requirement:

```zeron
import zeron.lang.Eq;

fn same<T: Eq<T>>(left: T, right: T): Boolean = left == right;
```

The bound lets the function compare its `T` values without knowing which concrete type was chosen.
Without applicable evidence, equality on that type is a compile-time error. Zeron does not fall
back to Java's `Object.equals` or to object identity. Use `===` when the question is whether two
references point to the same object; it is distinct from value equality and is not available for
scalar values.

Nullable values use lifted equality: two nulls are equal, one null and one non-null value are not,
and two non-null values are compared using `Eq<T>` for their underlying type. Structural equality
for products compares their components; for sums, values must first be from the same case, then
their payloads are compared. Hashing is a separate capability: `Hashable<T>` requires `Eq<T>`.
The standard library supports it for scalar types and nullable values, and generic code can require
it with a bound. Products, sums, classes, and newtypes can opt into derived `Eq` and `Hashable`
implementations; derivation is explicit, not automatic. `Map` and `Set` use `Hashable` keys or
elements, which must not be mutated in ways that change their equality or hash while stored.

## Ordering with `Comparable<T>`

The relational operators `<`, `<=`, `>`, and `>=` express ordering. `Int` and `Float` use their
built-in numeric comparisons. A custom type can opt in by providing `Comparable<T>`, whose
`compareTo(other: T): Ordering` method returns one of `Ordering.LT`, `Ordering.EQ`, or
`Ordering.GT`.

For example, a `Rank` compares itself with another `Rank` by comparing its numeric value:

```zeron
import zeron.lang.Comparable;
import zeron.lang.Ordering;

class Rank is Comparable<Rank> {
    public property value: Int;
    public constructor new;

    public compareTo(other: Rank): Ordering =
        if (this.value < other.value) then Ordering.LT()
        else if (this.value > other.value) then Ordering.GT()
        else Ordering.EQ();
}
```

Once this implementation is in scope, code can use `Rank` values with the relational operators.
`<` is true for `LT`; `<=` is true for `LT` or `EQ`; `>` is true for `GT`; and `>=` is true for
`GT` or `EQ`. A generic function using these operators must state the same capability as a bound:

```zeron
fn comesBefore<T: Comparable<T>>(left: T, right: T): Boolean = left < right;
```

Equality and ordering use evidence selected according to the same scope and ambiguity rules as
other witnesses. The
[equality and hashing design](../documents/design-document-17_structural-equality-and-hashing.md)
describes the current evidence rules, supported types, and limitations.
