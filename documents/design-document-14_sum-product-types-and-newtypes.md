# Sum, Product, and Newtype Types

## Status and scope

This document specifies the nominal-sum MVP and keeps product and newtype design separate. It
distinguishes three related but different ideas:

- **Sum types** represent one alternative from a closed set of cases.
- **Product types** represent a value containing all of a set of components.
- **Newtypes** give an underlying value a distinct nominal identity and domain-specific API.

Product type syntax and newtype syntax have not been selected. Transparent type aliases are not
introduced by the sum declaration.

## Motivation and existing facilities

Zeron already has nominal classes and contracts, sealed contracts, a generic `Result<T, E>` library
type, and exhaustive matching over sealed contracts. These facilities can model alternatives using
a sealed contract and one implementing class per case. Native sums make the closed alternatives
explicit and let the compiler own construction, case coverage, and generated representations.
`Option<T>` is now the bundled example of a native generic sum.

Classes already represent named objects with fields and methods, and so can be used for product-like
data. Standalone tuples, anonymous records, and native sum declarations are not currently
implemented. Do not infer that a future product type must be structural: anonymous records have
previously been excluded to avoid introducing structural typing.

## Sum types

### Bundled `Option<T>`

The bundled `zeron.lang.Option<T>` is implemented as `Some(value: T) | None`. Its case constructors
are `Option.Some(value)` and `Option<T>.None()`. Operations including `isSome`, `getOrElse`, `fold`,
`map`, and `filter` are public extensions over the sum, so they use ordinary static extension
dispatch rather than generated case-class methods. Iterators and other standard-library APIs
continue to use `Option<T>` as their result type.

### Declaration and identity

Use a `type` declaration whose right-hand side is a `|`-separated list of cases:

```zeron
public type Option<T> = Some(value: T) | None;
public type Coordinate = North | East | South | West;
```

Cases with payloads use named fields; cases without payloads are written by name alone. This form
covers both payload-bearing alternatives and enum-like sets of named constants. It avoids a separate
`sum` keyword while clearly distinguishing a sum from a single-type alias by its case-list form.

The MVP declaration introduces a **nominal, closed type**, not a transparent alias or a structural
union. A type's identity is its declared name and package, regardless of whether another sum has
identical cases. A declaration must contain at least one uniquely named case. Cases cannot be
extended, implemented, or subclassed by consumers. A sum has no user-declared methods, contract
conformance, or additional members in the MVP.

### Cases, payloads, construction, and visibility

The preferred direction is to qualify cases with the sum name at use sites, for example:

```zeron
let missing: Option<Int> = Option<Int>.None();
let present = Option.Some(42);
let direction = Coordinate.North();
```

Qualification avoids package-level collisions between common names such as `None`, `Some`, and
`North`, and provides one spelling for construction and patterns. Every case is constructed by a
qualified call, including payload-less cases: `Option.None()` or `Option<Int>.None()`. Payloads are
declared as ordered named fields, are immutable, and are positional at construction and match sites.
Names are metadata/documentation only; named arguments and payload defaults are not in the MVP.

All cases have the visibility of their sum. Public sums expose every case and payload type in their
API; payload types must themselves be publicly nameable. Per-case visibility and case-specific type
parameters are excluded. Adding, removing, or changing a public case or payload is a public API
change.

Every zero-payload case is semantically a singleton: constructing it repeatedly yields the same
value, and reference identity comparisons observe that stable identity. Payload-bearing cases
construct fresh values. This is a source semantic guarantee, not merely a code-generation shortcut.

Generic sum parameters are invariant, following existing generic nominal types. Constructor
inference uses payload argument types and a contextual expected sum type. For a payload-less case,
context must determine the sum's type arguments or the caller must supply them explicitly. If
arguments plus context do not uniquely determine all parameters, resolution fails rather than
guessing.

### Matching and exhaustiveness

Sums compose with `match` using qualified case patterns:

```zeron
match (option) {
    case Option.Some(value) if value > 0 -> value;
    case Option.Some(_) -> 0;
    case Option.None() -> -1;
}
```

The initial payload pattern requires exactly the declared arity; each position binds an immutable
local or uses `_`. Nested patterns and OR-patterns are deferred for sums. A sum match is over a
non-null sum value, consistent with existing match behavior; nullable matching is separate. Unknown
and duplicate case patterns are errors. A guarded case is non-covering. An unguarded wildcard may
cover only remaining cases and must be final. Otherwise every declared case must be covered by an
unguarded case. Existing Boolean guard evaluation, result type joining, and single scrutinee
evaluation apply.

Case type arguments must match the scrutinee's resolved sum arguments. Generic runtime dispatch uses
erased case identity; generic payload types are validated statically. This sum facility is a distinct
source declaration, even if the first implementation reuses sealed-contract match machinery.

### Case refinement and payload access

A known case can be tested without destructuring through `match`:

```zeron
if (option is Option.Some) then option.value else 0;
```

The test narrows the value to that generated case on the true control-flow path. For a generic sum,
the case inherits type arguments from the tested sum value; case runtime checks use the erased
generated class identity. A named payload may be read as a property only while flow analysis proves
that the receiver has been refined to its declaring case. Payload reads preserve the declared
generic payload type. Payloads are immutable and are never assignable.

`matches` combines the test with positional extraction:

```zeron
if (option matches Option.Some(value)) then value else 0;
```

It is initially supported only as a direct `if` or `while` condition. Payload names are immutable
locals available only on the successful branch/body path; `_` ignores a payload. The pattern must
name a case of the tested sum and provide exactly its payload arity. Logical composition, negation,
and other expression contexts are rejected for now, avoiding bindings on paths where the case test
may not have succeeded. Use `match` when composing tests or handling several cases.

### Extensions and witnesses

Extensions may target the nominal sum type, including an applied generic pattern such as
`extension<T> Option<T>`. They are ordinary statically dispatched extensions: they add no members
to generated case classes, introduce no virtual dispatch, and may inspect payloads only through
the sum's `match` and refinement rules. A sum-target extension is not a witness and does not imply
contract conformance.

A contract witness may target the sum type once, including a complete generic pattern such as
`witness<T> Describable for Option<T>`. Its implementation receives the sum value, so it can use
exhaustive `match` to provide behavior across cases. This witness does not establish conformance
for individual generated case classes, and no case-specific witnesses are synthesized. Factory
mappings remain unsupported for sum witnesses; they are class-side constructor mappings and sums
have case factories instead.

## Product types

A product combines all of its components into one value. A case payload such as
`Some(value: T)` is itself a one-field product; multiple named payload fields would form a larger
product:

```zeron
type Event = Moved(from: Coordinate, to: Coordinate) | Closed;
```

Zeron classes already cover nominal products with methods, visibility, and ordinary object identity.
The design question is whether the language also needs a lighter tuple or record form, and if so,
whether that form is nominal or structural.

No syntax or semantics for standalone product values are selected here. In particular, questions
remain about positional versus named fields, field mutability, equality, destructuring, labels,
generic inference, and whether product shape contributes to type identity. Anonymous structural
records would affect assignability and generic type identity and should not be introduced merely as
an implementation convenience for sum payloads. A named nominal product or tuple type could be
considered separately if there is a demonstrated use case beyond classes and named sum-case payloads.

## Newtypes and value-class-like wrappers

A newtype represents one underlying value but has a distinct nominal source type. For example,
`Meters` could wrap `Float` while preventing accidental interchange with arbitrary `Float` values.
This differs from a transparent type alias, which would preserve interchangeability, and from a sum,
which chooses among multiple cases.

A newtype-like type should be able to expose methods and constructors that enforce domain rules.
Illustratively, a `Meters` API might provide a validating constructor and operations that return
`Meters` rather than `Float`. The exact declaration and constructor syntax remain open. The design
should ensure that an invariant cannot be bypassed through an implicit raw constructor; visibility,
deserialization, Java interop, and other host boundaries need explicit rules.

The desired representation is value-class-like: avoid allocating a wrapper where the compiler can
safely represent the value as its underlying type, while retaining distinct nominal typing in source.
This is a representation goal, not yet a settled guarantee that wrappers are erased in every
context. Generics, nullable values, contracts/interfaces, function values, identity-sensitive
operations, and ABI boundaries may require boxing or adaptation. The language must specify equality,
nullability, and API compatibility independently of the chosen JVM layout.

## Compilation strategies to evaluate

### Sum lowering

A straightforward JVM lowering generates a sealed common parent interface and one final
implementation class per case. Payload fields live on their case class. Matching dispatches on case
identity, then reads payloads after static case checking. Each zero-payload case has one cached,
publicly accessible singleton instance and its case factory returns that instance. If every case is
payload-less, a generated JVM enum is also a representation candidate; it must preserve the same
singleton identity, exhaustiveness, and source-level nominal semantics. Enum lowering is an
optimization choice, not exposure of Java enum operations or ABI.

This strategy resembles the existing sealed-contract representation and is a conservative starting
point. A tagged representation with a discriminator and shared payload storage could reduce object
overhead, but adds layout complexity and is not required to establish source semantics. Source type
identity and exhaustive coverage must not depend on generated JVM names or layout.

Compiled-library metadata must export the sum declaration, type parameters, visibility, ordered
cases, and each payload's name and type. Consumer compilation uses that metadata for construction,
type inference, and exhaustive matching; it must not reconstruct source semantics from class files.
Changing the metadata schema is distinct from changing the standard-library API compatibility number.

### Product and newtype representation

Named product types could lower to ordinary objects, like classes, or to tuples/records with a
specialized representation if such a feature is justified. Newtypes could lower directly to their
underlying JVM representation in eligible contexts and use generated wrappers or bridges where
boxing is needed. Any representation optimization must preserve nominal checks, validated
construction, generic behavior, and stable public library metadata.

## Deferred design questions

Product types remain separate from sum payload products. Classes already provide nominal
product-like objects; a standalone tuple/record needs a demonstrated use case and decisions about
identity, mutability, labels, equality, destructuring, and generic type identity.

Newtypes need their own declaration syntax, validated-construction boundary, visibility and
deserialization rules, equality/nullability semantics, and boxing/ABI contract before implementation.
They must not be represented by an implicit alias or smuggled into this sum feature.

## Suggested sequencing

1. Implement the specified sum MVP with generated nominal case classes and singleton nullary cases.
   Keep enum lowering, advanced patterns, product syntax, and newtypes deferred.
2. Evaluate product types separately against the capabilities of classes and named case payloads.
3. Specify newtype construction invariants and source semantics before selecting erased/boxed lowering.
   Treat representation and ABI as part of the design, not as an invisible optimization.
