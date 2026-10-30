# Algebraic data types

Many values are made by combining simpler pieces of data. An **algebraic data type** (ADT) gives
those combinations a name and describes which values belong to that type. Zeron's `type` keyword
declares these named types. Two common ways to combine data are products, which group components
together, and sums, which offer a choice between alternatives.

Use an ADT when the shape of a value is part of what your program needs to express: for example,
coordinates that always have two components, or a response that is either successful or failed.
The type declaration makes that shape explicit, so code can work with meaningful values instead
of unrelated fields or loosely related flags.

## Product types

A product type groups a fixed set of named components into one value. Every component is present:
the value is the combination of all of them.

```zeron
public type Position = (x: Int, y: Int);

let home = Position.new(0, 0);
let column = home.x;
```

Here, a `Position` always contains both an `x` and a `y`. Its components are read-only properties,
and its canonical constructor creates a value from them in declaration order. The type is nominal:
another product with the same component types is still a different type.

Choose a product when several pieces of information belong together and should always be available
as one value. Product immutability is shallow: a component cannot be reassigned, but if it refers
to a separately mutable object, that object may still provide mutable operations. Products can
receive structural `Eq` evidence when their components support it, but this does not give them
`Hashable` automatically.

## Data classes

A data class keeps the named components of a product while also allowing methods and auxiliary
state:

```zeron
import zeron.lang.Eq;
import zeron.lang.Hashable;

public data class Point(x: Int, y: Int) deriving Hashable {
    coordinateSum: Int = x + y;

    public squaredDistance(): Int = this.x * this.x + this.y * this.y;
}

let origin = Point.new(0, 0);
let isOrigin = origin == Point.new(0, 0);
```

The component list in the header is the data class's canonical representation and constructor
signature. Components are immutable, read-only properties. A data class is nominal, so another
class with the same components is still a different type. Components must all be supplied to
`.new`; component defaults and alternate named constructors are not supported.

The compiler provides structural `Eq` when every component supports equality, comparing only those
components. Do not write `deriving Eq`; it is intrinsic. `Hashable` is optional and must be requested
with `deriving Hashable`, as in the example. Auxiliary fields such as `coordinateSum`, methods, and
computed properties are not included in either equality or hashing. Component immutability is
shallow: a component may refer to mutable state, which can still affect equality and hash results.

Initialization assigns components first, evaluates direct field initializers in declaration order,
then runs initializer blocks in source order. Direct initializers may call only effect-free
functions. An initializer block can declare its raised effects on the data class name, for example
`Point raises InitializationFailure { ... }`; those effects become part of construction and must be
handled or propagated by the caller. See [Raised effects](./22-raised-effects.md) for the effect
model.

The standard library defines streaming `Encoder`/`Decoder` protocols and `Encode`/`Decode` capability
contracts. Products, sums, and data classes can derive capability evidence when their components
support it. The `zeron.serialization.json` package provides JSON parsing, a JSON value model, and
stringification; generated value operations and the typed JSON adapter are not implemented yet, so
values cannot yet be encoded or decoded through a generic codec API.

## Sum types

A sum type describes one value chosen from a closed set of named alternatives. Declare its cases
with `|`:

```zeron
public type Shape = Circle(radius: Float) | Square(side: Float) | Point;

let origin = Shape.Point();
let disk = Shape.Circle(2.5);
```

A `Shape` is exactly one of `Circle`, `Square`, or `Point`. Cases with payloads carry the declared
components; construction supplies them in declaration order, and the payloads are immutable. A
case without a payload, such as `Point`, is a singleton value.

Choose a sum when a value must represent one of several alternatives, such as the possible states
of a task or the outcomes of an operation. Because the set of cases is closed, a `match` can handle
each alternative and the compiler can check that the cases are covered. Adding a public case
changes the type's API and may require clients to update their exhaustive matches.

Like products, sums are nominal: a different sum with identical-looking cases remains a different
type. Products and sums can receive structural `Eq` evidence when their payloads support it, but
this does not give them `Hashable` automatically.

## Newtypes

A newtype gives a supported scalar representation a distinct nominal identity. Its value is
represented by one underlying value, but the newtype prevents accidental interchange with that
underlying type or with another newtype:

```zeron
public newtype Meters = Float;
public newtype UserId = String where value != "";

let height = Meters.new(1.75);
let id = UserId.tryNew("u-42");
```

An unguarded newtype exposes `.new(value)`. A guarded newtype exposes `.tryNew(value)`, which
returns `Option<Newtype>` and constructs a value only when the Boolean validator succeeds. The
validator sees an immutable local named `value`. The supported representations are `Int`, `Float`,
`Boolean`, and `String`; generic, mutable, and arbitrary class representations are not supported.

Use a newtype to make a domain distinction explicit—for example, separating a distance from an
ordinary `Float`, or a user identifier from an arbitrary `String`. Newtypes do not implicitly
inherit equality, ordering, or other contract evidence from their representation, so those domain
semantics remain explicit.

In short, products group components that belong together, sums represent one of several
alternatives, and newtypes distinguish a value by what it means. These forms and their current
limitations are described further in
[design document 14](../documents/design_documents/design-document-14_sum-product-types-and-newtypes.md).
