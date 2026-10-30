# Pattern matching

Programs often need to make a decision based on what kind of value they have. Zeron provides
type-test operators for simple checks, and `match` expressions for describing several possible
cases in one place.

## Testing a value's type

Use `is` to test whether a value has a particular type:

```zeron
fn incrementIfInt(value: Any): Int = if (value is Int) then value + 1 else 0;
```

When the test succeeds, Zeron knows that `value` has the tested type in that branch. `is` is useful
for a single check, but a series of different checks quickly becomes repetitive: each test needs
the value again, and each branch needs its own expression.

For a sum type, `matches` can test one case and make its payloads available when the test succeeds:

```zeron
fn circleRadius(shape: Shape): Float =
    if (shape matches Shape.Circle(radius)) then radius else 0.0;
```

`matches` is currently supported only as a direct `if` or `while` condition. It cannot be combined
with `and`, `or`, or `not`, or used as an ordinary expression, because a payload binding is only
valid on the path where that case matched. When several alternatives or more involved conditions
are needed, use a `match` expression instead.

## Choosing a case with `match`

A `match` expression lists the cases to consider. It checks them from top to bottom, selects the
first one that matches, and produces that case's result:

```zeron
fn describe(value: Int): String = match (value) {
    case 0 -> "zero";
    case _ -> "number";
};
```

The first case matches an integer literal; the final `case _` is a wildcard for the rest of the
integer values. The `Any` type is useful with `is`, but it is not a valid `match` root. Likewise,
a wildcard does not make a non-sealed contract or an open class type a valid root.
An ordinary concrete class root currently requires a supported literal test; the planned public-
pattern opt-in for direct class roots is described below.

The value being matched is evaluated once, and only the selected result is evaluated. Every case
must produce a compatible result type. Sealed contracts and sum types have a known set of
alternatives; products have one constructor; and literal matching is available for supported
concrete types. These root rules let the compiler check that a match is complete.

## Matching literals and products

Integer, Boolean, and String literals can be matched directly. Integer and String matches need a
wildcard or total pattern because their value domains are unbounded. Both Boolean literals can
cover the Boolean domain:

```zeron
fn describeFlag(value: Boolean): String = match (value) {
    case true -> "enabled";
    case false -> "disabled";
};

fn describeWord(value: String): String = match (value) {
    case "map" -> "transform";
    case _ -> "another operation";
};
```

Floating-point literal patterns are not supported. Literal patterns compare values directly; they
do not evaluate arbitrary expressions or call an `Eq<T>` witness.

Product types can be destructured positionally in declaration order. A component can itself be a
binding, `_`, a literal, or another supported nested pattern:

```zeron
type Point = (x: Int, y: Int);

fn location(point: Point): String = match (point) {
    case Point(0, 0) -> "origin";
    case Point(x, y) -> "another point";
};
```

A product case covers the root only if all its component patterns are total. Thus `Point(0, 0)`
alone is not exhaustive; a total pattern such as `Point(x, y)` or a wildcard is needed. Product
patterns can also be nested inside sum payloads and class-pattern outputs.

## Ignoring a part of a value

When a pattern can extract part of a value, use `_` for a part that does not matter. It means “match
this part, but do not give it a name.” For example, a disk match can ignore its radius:

```zeron
sealed contract DiskLike permits Disk {}

class Disk is DiskLike {
    public property radius: Float;
}

fn ignoreRadius(disk: DiskLike): Float = match (disk) {
    case Disk.radius(_) -> 0.0;
};
```

The meaning depends on where `_` appears: `case _` matches any remaining value, while `_` inside a
pattern ignores just that one extracted part. `Disk.radius` reads the public `radius` property;
property patterns are explained below. Named pattern bindings, such as `radius`, are immutable and
available only in their case.

## Adding a guard

Sometimes the value's shape is not enough to choose a result. Add an `if` guard after a pattern to
check an additional condition:

```zeron
fn positiveRadius(disk: DiskLike): Float = match (disk) {
    case Disk.radius(radius) if radius > 0.0 -> radius;
    case Disk.radius(_) -> 0.0;
};
```

The guard runs after the pattern matches and its bindings are available. If the guard is false,
`match` tries the next case. Guards must produce `Boolean`, and a guarded case does not count toward
complete coverage because its condition might fail.

## Matching public properties

Classes can be matched through their public properties. The property name identifies what to read,
and the pattern in parentheses describes the value to match or bind:

```zeron
fn radius(disk: DiskLike): Float = match (disk) {
    case Disk.radius(value) -> value;
};
```

The property must be public, and this form extracts one property at a time. A binding reads its
value without changing it; a nested pattern can describe a more specific shape. Property matching
uses the class's public interface rather than exposing its private fields. Pattern names and
property names must be distinct; a collision is rejected. Otherwise, a declared named pattern is
used when present, with the legacy single-property form retained as a compatibility fallback.

## Sealed contracts and permitted classes

A sealed contract has a closed set of conforming classes, listed in its `permits` clause. Each
permitted class must directly conform to the contract. Because the list is closed, a match can
handle each permitted class and let Zeron check that none has been missed:

```zeron
sealed contract Drawable permits Orb, Tile {}

class Orb is Drawable {
    public property radius: Float;
}

class Tile is Drawable {
    public property side: Float;
}

fn size(shape: Drawable): Float = match (shape) {
    case Orb.radius(radius) -> radius;
    case Tile.side(side) -> side;
};
```

For this check, a case counts only when it is unguarded and its pattern cannot fail. A final
unguarded wildcard can cover all remaining classes. A non-sealed contract, `Any`, or open class
type is not a valid match root, even with a wildcard.

## Matching sum types

A sum type already declares its complete set of cases. Match each case to handle the alternatives;
payload patterns bind the values carried by a case:

```zeron
fn label(shape: Shape): String = match (shape) {
    case Shape.Circle(radius) -> "circle";
    case Shape.Square(side) -> "square";
    case Shape.Point() -> "point";
};
```

Use `_` for payloads that are not needed, such as `Shape.Circle(_)`. A case without payloads is
written with empty parentheses. The compiler checks that every sum case is covered unless an
unguarded wildcard handles the remaining cases. Pattern bindings are immutable, and sum payloads
cannot be reassigned.

## Defining custom patterns

When a class needs to offer a useful way to break apart its value that is not just reading one
property, it can declare a named pattern. Its outputs specify the names and types that a match can
bind:

```zeron
sealed contract PairLike permits Pair {}

class Pair is PairLike {
    left: Int;
    right: Int;

    public pattern values(first: Int, second: Int) {
        first = this.left;
        second = this.right;
    }
}

fn total(pair: PairLike): Int = match (pair) {
    case Pair.values(first, second) -> first + second;
};
```

The class computes each output in the pattern body. A pattern may also have a `when` condition,
making it refutable: when the condition is false, that case does not match and the next case is
tried. Outputs, like other pattern bindings, are immutable in the matching case.

Patterns can be nested to describe values inside extracted outputs. Use a public-property pattern
when a property already expresses the data to inspect; define a custom pattern when the class
should provide a named, possibly multi-value or conditional way to expose its structure.

## Opting a class into root matching

Direct matching on a concrete class is planned as an opt-in. A class will opt in by declaring at
least one `public pattern`; no separate `matchable` modifier is planned. This enables matching on
that class's static type without requiring a literal test:

```zeron
fn total(pair: Pair): Int = match (pair) {
    case Pair.values(first, second) -> first + second;
};
```

This direct-class-root form is not yet supported by the compiler. Until it is implemented, match
class values through a sealed contract or use the currently supported literal-matching rules.
When implemented, only an unguarded total pattern or wildcard will establish complete coverage.
A pattern with `when`, a nested refutable pattern, or an arm guard will not count as total.
Declaring a public pattern will not make `Any`, non-sealed contracts, or open class roots valid;
classes are not treated as closed families of runtime alternatives.

Nullable scrutinees are not currently supported.

Use `is` for a single type check, `matches` for a simple sum-case test with payloads in an `if` or
`while` condition, and `match` when several alternatives need clear, ordered handling. See the
[pattern-matching design document](../documents/design_documents/design-document-16_patterns-and-pattern-matching.md)
for the current rules and limitations.
