# Operator overloading

Arithmetic operators use numeric contract evidence; a similarly named method alone does not
enable an operator. Generic code must state the required numeric bound explicitly:

```zeron
import zeron.numeric.Numeric;
import zeron.numeric.Fractional;

fn add<T: Numeric<T>>(left: T, right: T): T = left + right;
fn divide<T: Fractional<T>>(left: T, right: T): T = left / right;
```

`Numeric<T>` provides `+`, `-`, unary `-`, and `*`. `/` requires `Fractional<T>`. Built-in
`Int`, `Long`, and `Float` operations retain their intrinsic behavior; numeric evidence enables the
same syntax for types such as `Integer` and `Rational`. `String + T` keeps its existing display
behavior. Operands and results use the same type, and the compiler does not infer numeric bounds or
mixed-type promotions.

Numeric operators do not imply associativity, identity, inverse, commutativity, or other algebraic
laws. General algebraic abstractions can remain independent of the numeric hierarchy.

For the complete hierarchy, exact literal conversion, and built-in numeric types, see
[Numeric hierarchy and exact literals](33-numeric-hierarchy-and-exact-literals.md). The historical
[ZEP-0036](../documents/zeps/ZEP-0036.md) describes the original algebraic-operator design; its
operator mapping was superseded by [ZEP-0071](../documents/zeps/ZEP-0071.md).
