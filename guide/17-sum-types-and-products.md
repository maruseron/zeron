# Products, data classes, and sum types

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

The standard library defines streaming `Encoder`/`Decoder` protocols and the effect-aware
`Encode<T, E>`, `Decode<T, E>`, `Codec<T, E>`, and `Shape<T>` contracts. Products, sums, and
transparent data classes derive the capability when their components support it, and derivation
also generates the streaming operations (see [Contract derivation](./38-contract-derivation.md)
for the derivation rules): a type that derives `Encode, Decode` round-trips through
the free `encode(value, encoder)` and `decode<{}, T>(decoder)` entry points without building an
intermediate value tree. A failure names the path of components that led to it, unknown fields are
skipped, and an unknown sum case is a decode error. The `zeron.serialization.json` package provides
JSON parsing, a JSON value model, and stringification. `Json` is a configuration value whose
`indent` component sets the number of spaces per nesting level: `Json.compact()` and the companion
functions `Json.encodeToString` and `Json.decodeFromString` use the default compact form, while
`Json.pretty(2).encodeToString(value)` indents each level by two spaces.
`encodeToStringWith(model, value) { field(User::name); }` encodes the fields that a model maps and
the block selects, because a model erases the mapped value types. `zeron.serialization.json.typed`
adds `parseAs<T>`, `stringifyAs<T>`, `decodeValue<T>`, and `encodeValue<T>`, which drive the derived
or hand-written `Encode`/`Decode` operations directly over a JSON value. A tagged alternative is a
single-member object whose member value is the payload, and a payload-less case is JSON null. Maps
are not supported, because JSON object keys are strings and the protocol has no map-key operation.

## Two serialization entry points

Zeron exposes two related but distinct ways to turn values into an external representation:

- **Streaming codecs** use the `Encoder` and `Decoder` protocols and `Encode<T, E>`,
  `Decode<T, E>`, or `Codec<T, E>` evidence, either derived or hand-written. The entry points
  (`encode`, `decode`, and the JSON helpers `encodeValue`, `decodeValue`, `parseAs`, and
  `stringifyAs`) read and write the type's declared shape: product components, sum cases, and
  data-class components under their declared names. Derivation covers products, sums, and
  transparent data classes; ordinary classes cannot derive, and no model is involved.
- **Models** use a `Model<T>` value that maps a nominal type's properties to external names and
  carries format metadata declared by the model's contract. A consumer such as the JSON model
  builder reads those mappings and encodes the fields it selects. Models also describe ordinary
  classes, partial mappings, and non-JSON formats such as SQL tables, and they never change the
  type or construct it.

The two are layered rather than interchangeable. A model supplies names and field selection, but
each selected value is still written with its `Encode` evidence; conversely, derived codecs never
consult a model. The JSON API reflects this: `Json.encodeToString(value)` needs only `Encode`,
while `Json.encodeToStringWith(model, value) { field(User::name); }` needs `Encode` for the selected
fields' values and a block that supplies the typed reads, because `AnyField` erases the mapped
value type.

| | Streaming codecs | Models |
| --- | --- | --- |
| Capability | `Encode<T, E>`, `Decode<T, E>`, or `Codec<T, E>` | A `Model<T>` mapping value |
| Names | Declared component and case names | External names from the model |
| Shape | The whole declared value | Only the mapped fields a consumer selects |
| Types | Products, sums, and transparent data classes | Any nominal type with readable properties, including ordinary classes |
| Use when | A canonical, derivable representation is enough | External names, a partial view, or a non-codec format are needed |

Model-based decoding is not part of the current JSON API: `jsonModel` and
`Json.encodeToStringWith` encode only. A consumer can read a mapped property with `Field.get`, or
write it through `Field.writer` when the model exposes one.

## Model declarations

A model maps a nominal type's properties to names in an external representation without changing the
type. Declare a contract that requires `zeron.lang.Model<T>` and adds its own properties, then
declare a `model` for a target type:

```zeron
import zeron.lang.Model;

public contract SqlModel<T: nullable + mut> requires Model<T> {
    property tableName: String;
}

public model UserSql is SqlModel<User> {
    tableName = "users";

    name -> "user_name";
    age  -> "user_age";
}
```

The left side of `->` names a property of `User`, resolved as `User::name` would be; the right side
is the external name. `property = value` assigns a property required by the parent contract, and the
initializer must be a constant. A model is a named singleton value, not a type, so it can be passed
where `SqlModel<User>` is expected while the target type stays independent of the mapping.

`model.fields` enumerates every mapping as an `AnyField<T>`, `model.field(User::age)` returns the
typed `Field<User, Int>`, and `model.fieldOf(id)` finds a mapping by property identity. `Field.get`
reads the property, and `Field.writer` is present when the property has an accessible setter: it is
an `Option<(&T, V) -> Unit>` that writes the property. Read-only properties have no writer.

A model can be passed where its contract or the core `Model<T>` is expected, including through an
intermediate contract such as `SqlModel<User>`; a generic consumer infers `T` from the model's
contract. One type may have several models, and a model may map only some properties; unknown or
inaccessible properties, duplicate mappings, and missing or non-constant contract assignments are
compile errors.
Models do not construct the target type, change its representation, or enable reflection over
unmapped types; meaning specific to a format belongs to the consumer.

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
