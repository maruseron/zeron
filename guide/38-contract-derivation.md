# Contract derivation

Some Zeron behavior is generated from a type's declaration rather than written by hand. This is
called **derivation**. It comes in two forms:

- **Intrinsic structural equality.** Products, sums, and transparent data classes get conditional
  `Eq` behavior from their declared components without any clause. This is always part of the type.
- **Opt-in derivation.** A declaration can add a `deriving` clause to request a compiler-generated
  contract witness for capabilities such as `Hashable` or `Encode`.

Derivation reads the type's source-declared shape. It does not use JVM reflection, inspect generated
representation details, or invent evidence at a use site. The requested capability belongs to the
type declaration, so it is stable for every consumer, including separately compiled libraries.

## The `deriving` clause

`deriving` is a soft keyword. It appears in a declaration header:

```zeron
import zeron.lang.Hashable;

public type Position deriving Hashable = (x: Int, y: Int);

public type Shape deriving Hashable = Circle(radius: Float) | Dot;

public data class Point(x: Int, y: Int) deriving Hashable {
    coordinateSum: Int = x + y;
}

public class Record deriving Eq, Hashable {
    id: Int;
    private label: String = "unnamed";
}

public newtype UserId deriving Eq, Hashable = Int;
```

For products, sums, and newtypes the clause follows the declared name and type parameters and comes
before `=`. For classes it follows any `is` contract list and comes before the body. A clause can
list several contracts, separated by commas.

Derivation creates a compiler-generated contract witness. It does not add nominal `is` conformance
to a class and does not add source-level methods to the type. Values use the capability through
`Eq<T>`, `Hashable<T>`, `Encode<T, E>`, and the other contracts described below.

## What each declaration can derive

| Declaration | Intrinsic structural `Eq` | Can request with `deriving` |
| --- | --- | --- |
| Product type | Yes | `Hashable`, `Encode`, `Decode`, `Codec`, `Shape` |
| Sum type | Yes | `Hashable`, `Encode`, `Decode`, `Codec`, `Shape` |
| Data class | Yes, from canonical components | `Hashable`, `Encode`, `Decode`, `Codec`, `Shape` |
| Ordinary class | No | `Eq`, `Hashable` |
| Newtype | No | `Eq`, `Hashable` |

The restrictions matter:

- `Eq` on a product, sum, or data class is always intrinsic, so listing it in `deriving` is rejected
  as redundant. A data class reports that it has intrinsic structural `Eq`; a product or sum reports
  that it already has structural `Eq` evidence.
- `Hashable` on an ordinary class or newtype requires `deriving Eq` in the same clause. Products,
  sums, and data classes already have the required equality.
- Serialization contracts (`Encode`, `Decode`, `Codec`, and `Shape`) are available only to products,
  sums, and transparent data classes. Ordinary classes and newtypes use a hand-written codec.
- A type with a custom `Eq` implementation cannot derive `Eq` or `Hashable`; provide a matching
  explicit `Hashable` implementation instead.

## Intrinsic structural equality

Products, sums, and data classes compare by value out of the box. Their `Eq` evidence is conditional
on the components' own evidence:

- a product compares all of its declared components, in declaration order;
- a sum first compares the case, then the payloads of that case; different cases are unequal
  without comparing payloads;
- a data class compares only its canonical header components. Auxiliary fields, initializer state,
  computed properties, and methods do not participate.

This is why `Point.new(1, 2) == Point.new(1, 2)` works without a `deriving` clause, while an
ordinary class still needs `===` for identity or explicit evidence for value equality.

The evidence is conditional for generic types. A `Box<T>` can be compared only when `T` has `Eq`
evidence:

```zeron
public class Box<T> deriving Eq, Hashable {
    value: T;
}

fn sameBox<T: Eq<T>>(left: Box<T>, right: Box<T>): Boolean = left == right;
```

Constructing and matching a `Box<Opaque>` remains possible even when `Opaque` has no equality. The
limitation appears only where equality is used. Nullable values lift the base evidence: two nulls are
equal, null differs from a non-null value, and two non-null values use the component's `Eq`.

## Derived `Eq` and `Hashable`

An ordinary class or newtype derives `Eq` from its stored state:

```zeron
import zeron.lang.Eq;
import zeron.lang.Hashable;

public class Record deriving Eq, Hashable {
    id: Int;
    private label: String = "unnamed";
}

public newtype UserId deriving Eq, Hashable = Int;
```

For a class, every source-declared stored field and backing property participates in declaration
order, including private fields and initialized fields. Computed properties and compiler-generated
bookkeeping do not. For a newtype, the underlying value is the only component. A data class uses its
canonical components, just like its intrinsic equality.

`Hashable<T>` requires `Eq<T>`, and its central law is:

> If `a == b`, then `hash(a) == hash(b)`.

Derived hashing is computed from the same components as derived equality, so the two stay
consistent. Products combine all components in order. Sums also mix in the case identity, so
different cases are not treated as interchangeable payloads. Data classes hash their canonical
components only. Derived hashes are 64-bit values.

For a generic type, the compiler records the component requirements behind the derived witness. A
`Box<T> deriving Eq, Hashable` therefore works for `Box<Int>` and becomes unavailable for
`Box<Opaque>` unless `Opaque` supplies the needed evidence. The requirements also travel through
the compiled-library API index, so a consumer of a separately compiled library selects the same
evidence without seeing private component names.

Direct self-recursion is supported, including a nullable self-recursive component such as a linked
list node. Mutually recursive declaration groups are not synthesized as one recursive evidence
group, and there is no runtime cycle detection. Equality and hashing of mutable component state is
shallow: if a component's equality-relevant state changes while the value is used as a `Map` key or
`Set` element, the entry may become unreachable.

Derived hash values are not a serialized representation and are not promised to be stable across
processes. Do not persist them as identifiers.

## Serialization derivation

The `zeron.serialization` package defines streaming codecs. `Encode<T, E>` writes a value,
`Decode<T, E>` reads one, and `Codec<T, E>` combines both. The `E` parameter is an effect row, so a
codec whose body raises effects declares them in the contract rather than hiding them.

`deriving Codec` is equivalent to `deriving Encode, Decode`. Listing `Codec` fills in whichever
direction is not listed explicitly, and a type that derives both directions also receives the
combined `Codec` evidence. `Shape<T>` is a separate, effect-free capability and is not implied by
`Codec`.

```zeron
import zeron.serialization.Codec;
import zeron.serialization.Decode;
import zeron.serialization.Encode;
import zeron.serialization.json.parseAs;
import zeron.serialization.json.stringifyAs;

public type Point deriving Encode, Decode = (x: Int, y: Int);
public type Tag deriving Codec = Empty | Number(value: Int);

fn pointText(): String =
    stringifyAs(Point.new(1, 2)).fold((text) -> text, (error) -> error.message);

fn pointValue(text: String): Int =
    parseAs<Point>(text).fold((point) -> point.x + point.y, (error) -> -1);
```

The generated mapping follows the declared shape:

- **Products and data classes** encode as records. Each declared component becomes a named field, in
  declaration order. Data-class decoding invokes the canonical constructor, so initializer blocks
  run and invariants are not bypassed. Auxiliary data-class state is neither written nor read.
- **Sums** encode as alternatives: the case name is the tag, followed by its payloads. The JSON
  adapter represents a payload-carrying case as a single-member object such as `{"Number": 7}`, and
  a payload-less case as JSON `null`.
- **Nullable components** use lifted evidence. A missing nullable component decodes as `null`.
  A missing non-nullable component is an error. Null is accepted only where the component's type and
  codec accept it; defaults are not inferred.
- **Unknown record fields** are skipped. An unknown sum case is a decoding error.
- **Errors** from nested values carry the path of component names that led to the failure.

Encoding a product `Point.new(1, 2)` with the JSON adapter produces `{"x":1,"y":2}`. Decoding
accepts the fields in any order and skips fields the type does not declare.

The effect row is part of the derived witness. `Encode` uses the union of its components' `Encode`
rows. `Decode` for a product or sum uses the union of its components' `Decode` rows. A data class
also includes the effects declared by its initializer blocks, because decoding runs the canonical
constructor. `Shape` is always effect-free. The JSON convenience functions such as
`Json.encodeToString`, `parseAs`, and `stringifyAs` require the empty row; a type with an effectful
codec uses the streaming `encode` and `decode` entry points and handles the effects at the call
site.

`Shape<T>` exposes the static kind of a value before one exists. It reports `RecordKind` for
products and data classes, `AlternativeKind` for sums, and whether the type admits a null
representation. Schema-oriented code can require a `Shape<T>` bound instead of inspecting a value.

Only products, sums, and data classes can derive serialization evidence. An ordinary class or
newtype needs a hand-written codec, or a mapping to a data aggregate through a model. Serialization
derivation is opt-in even when every component is already serializable; the compiler does not add a
codec just because a use site requests one.

## Rejected derivation requests

The compiler reports a declaration-site error for:

- `deriving Eq` on a product, sum, or data class, because structural equality is already intrinsic;
- `deriving Hashable` on an ordinary class or newtype without also deriving `Eq`;
- `deriving Encode`, `Decode`, `Codec`, or `Shape` on an ordinary class or newtype;
- deriving `Eq` or `Hashable` for a type that defines its own `Eq`;
- listing the same contract more than once;
- a contract name the compiler cannot derive; and
- a component that lacks the evidence the derived capability requires.

A component failure names the component, so a missing `Hashable` on one field points directly at the
field rather than at the enclosing declaration.

## Relation to contracts and separate compilation

Derivation is a way to obtain a contract witness; it is not a second kind of contract. The witness
is selected like any other evidence, obeys the same ambiguity rules, and can be used through generic
bounds. Because the request is attached to the type, code in another compilation unit sees the same
capability after importing the type, without re-deriving it or discovering private components.

For more on the underlying contracts, see [Equality and ordering](./21-equality-and-ordering.md),
[Generic bounds and witnesses](./20-generic-bounds-and-witnesses.md), and
[Products, data classes, and sum types](./17-sum-types-and-products.md). The design record is in
[structural equality and hashing](../documents/design_documents/design-document-17_structural-equality-and-hashing.md)
and [ZEP-0008](../documents/zeps/ZEP-0008.md).
