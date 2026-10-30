# Numeric hierarchy and exact literals

Zeron's arithmetic operators use numeric evidence rather than general algebraic identities. The
standard library defines four contracts in `zeron.numeric`:

- `Numeric<T>` provides addition, subtraction, multiplication, negation, absolute value, signum,
  and conversion from an exact `Integer`.
- `Integral<T>` extends `Numeric<T>` with truncating `quot`/`rem`, floor-rounded `div`/`mod`, and
  conversion to `Integer`.
- `Fractional<T>` extends `Numeric<T>` with division, reciprocal, and conversion from an exact
  `Rational`.
- `Floating<T>` extends `Fractional<T>` with square root, powers, logarithms, and trigonometric
  operations.

Arithmetic operators require the corresponding evidence: `+`, `-`, unary `-`, and `*` use
`Numeric<T>`; `/` uses `Fractional<T>`. Generic functions declare these bounds explicitly:

```zeron
import zeron.numeric.Numeric;
import zeron.numeric.Fractional;

fn sum<T: Numeric<T>>(left: T, right: T): T = left + right;
fn quotient<T: Fractional<T>>(left: T, right: T): T = left / right;
```

The numeric hierarchy is separate from general algebraic abstractions; numeric operators do not
imply associativity, identities, inverses, or other algebraic laws. Built-in primitive arithmetic
keeps its intrinsic implementation. Operator operands and results have the same type; there is no
implicit promotion between different numeric types.

## Exact integer and rational types

`zeron.numeric.Integer` is an arbitrary-precision integer backed by Java's `java.math.BigInteger`.
The Zeron type is distinct from Java's boxed `java.lang.Integer`; use a qualified name or import
alias when both are needed. Its `n` suffix is explicitly imported:

```zeron
import zeron.numeric.Integer;
import suffix zeron.numeric.Integer.n;

let accountId: Integer = 123456789012345678901234567890n;
let nextId = accountId + 1n;
```

`zeron.numeric.Rational` represents a reduced exact fraction with a positive, nonzero denominator.
Zero is represented as `0/1`. Its `Ratio<Integer>` interface exposes `numerator()` and
`denominator()`, and arithmetic remains exact:

```zeron
import zeron.numeric.Rational;

let third: Rational = 1 / 3;
let oneTenth: Rational = 0.1;
let exactSum: Rational = 0.1 + 0.2;
```

The last expression is exactly `3/10`, not the binary floating-point approximation normally
associated with the decimals `0.1` and `0.2`. Division or reciprocal of zero propagates the host
`ArithmeticException`.

Both types provide `Display`, `Eq`, `Comparable`, and `Hashable` evidence. Rational display uses
`numerator/denominator` for non-integers and the integer text when the denominator is one.
Comparison is exact, and equal values have matching hashes.

## Context-directed literals

Unsuffixed integer literals retain their exact arbitrary-precision value until the compiler knows
the expected type. Integer values are converted through `Numeric<T>.fromInteger`; fractional values
are converted through `Fractional<T>.fromRational`. Decimal text is interpreted as an exact ratio
before conversion, without an intermediate `Float`. Radix prefixes and digit separators are
preserved in the integer's mathematical value.

When there is no expected numeric type, the existing defaults remain: integer literals default to
`Int`, and fractional literals to `Float`. Values outside the default `Int` range or an explicitly
expected `Int` or `Long` range are rejected at compile time. Conversions of already-existing
`Integer` values use checked conversions and raise `ArithmeticException` if out of range. Conversion
from `Rational` to `Float` rounds to the nearest binary64 value, ties to even.

The expected type must provide the relevant numeric evidence. Zeron does not silently widen
operands or guess a conversion for a type that does not conform to the required contract.
