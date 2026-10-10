# Sum, Product, and Newtype Types: Design Exploration

## Status and scope

This document records an exploratory direction for algebraic data types in Zeron. It is not a
language specification and does not authorize implementation choices where questions remain open.
It distinguishes three related but different ideas:

- **Sum types** represent one alternative from a closed set of cases.
- **Product types** represent a value containing all of a set of components.
- **Newtypes** give an underlying value a distinct nominal identity and domain-specific API.

The proposed sum declaration syntax is the current discussion's concrete starting point. Product
type syntax and newtype syntax have not been selected. Transparent type aliases are not proposed
here; a distinct wrapper type is preferred when a domain concept must not be interchangeable with
its representation.

## Motivation and existing facilities

Zeron already has nominal classes and contracts, sealed contracts, generic `Option<T>` and
`Result<T, E>` library types, and exhaustive matching over sealed contracts. These facilities can
model many alternatives using a sealed contract and one implementing class per case. A native sum
declaration could make the closed alternatives explicit and let the compiler own construction,
case coverage, and generated representations.

Classes already represent named objects with fields and methods, and so can be used for product-like
data. Standalone tuples, anonymous records, and native sum declarations are not currently
implemented. Do not infer that a future product type must be structural: anonymous records have
previously been excluded to avoid introducing structural typing.

## Sum types

### Proposed declaration form

Use a `type` declaration whose right-hand side is a `|`-separated list of cases:

```zeron
public type Option<T> = Some(value: T) | None;
public type Coordinate = North | East | South | West;
```

Cases with payloads use named fields; cases without payloads are written by name alone. This form
covers both payload-bearing alternatives and enum-like sets of named constants. It avoids a separate
`sum` keyword while clearly distinguishing a sum from a single-type alias by its case-list form.

The declaration is intended to introduce a **nominal, closed type**, not a transparent alias or a
structural union. A type's identity is its declared name and package, regardless of whether another
sum has identical cases.

### Case names and construction

The preferred direction is to qualify cases with the sum name at use sites, for example:

```zeron
let missing: Option<Int> = Option.None;
let present = Option.Some(value: 42);
let direction = Coordinate.North;
```

Qualification avoids package-level collisions between common names such as `None`, `Some`, and
`North`, and provides a natural spelling for both construction and patterns. Whether constructor
arguments use labels, positional arguments, or support both remains undecided. Generic arguments
should be inferable from an expected type where possible; inference for standalone cases such as
`Option.None` requires particular attention because that case has no payload from which to infer
`T`.

Cases are fixed by the declaration. A consumer cannot add cases or implement the sum to extend its
alternatives. Visibility should be coherent across the sum and its cases: exposing a public sum
should expose enough case information for clients to construct and exhaustively match it.

### Matching and exhaustiveness

Native sums should compose with `match`. A case-only pattern can be the initial supported pattern
form:

```zeron
match (option) {
    case Option.Some -> ...;
    case Option.None -> ...;
}
```

Payload binding and nested destructuring can be added as pattern capabilities are designed. Cases
with named payloads should leave room for patterns that bind selected fields. Matching should reject
unknown or duplicate cases and require coverage of every alternative unless an unguarded wildcard
covers the remainder. Guarded cases should not count toward exhaustiveness because their guards may
fail. Matching nullable values remains distinct from matching the sum's cases: the scrutinee must
first be proven non-null or handled by separately specified nullable-pattern behavior.

The intended benefit over encoding every sum as an ordinary sealed contract is that the compiler has
the exact finite case set directly from the declaration, enabling reliable exhaustive checking and
case-specific construction without user-authored implementing classes.

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
nullability, overload resolution, and API compatibility independently of the chosen JVM layout.

## Compilation strategies to evaluate

### First implementation candidate for sums

A straightforward JVM lowering would generate a common parent interface or abstract type for each
sum and a final implementation class for each case. Payload fields live on their case class.
Matching can dispatch on generated case identity using `instanceof`, then read payloads after static
case checking. Zero-payload cases could be singleton instances, although allocation and identity
semantics need to be specified.

This strategy resembles the existing sealed-contract representation and is a conservative starting
point. A tagged representation with a discriminator and shared payload storage could reduce object
overhead, but adds layout complexity and is not required to establish source semantics. Source type
identity and exhaustive coverage must not depend on generated JVM names or layout.

### Product and newtype representation

Named product types could lower to ordinary objects, like classes, or to tuples/records with a
specialized representation if such a feature is justified. Newtypes could lower directly to their
underlying JVM representation in eligible contexts and use generated wrappers or bridges where
boxing is needed. Any representation optimization must preserve nominal checks, validated
construction, generic behavior, and stable public library metadata.

## Open questions

1. Is `type Name = Case | Case` the final syntax, and what syntax, if any, should transparent aliases
   use? Import aliases do not resolve this distinction because they only rename references.
2. Are sum cases accessed only as `Sum.Case`, or should imports permit unqualified case names?
3. Are case payloads positional, labeled, or both? Can fields have defaults or visibility modifiers?
4. How are generic arguments inferred for zero-payload cases and overloaded case names?
5. What payload destructuring patterns are in scope initially, and how do guards and nullable values
   interact with case coverage?
6. Should zero-payload cases have singleton identity, value equality, or unspecified object identity?
7. Do product types need a standalone tuple/record form in addition to classes and named case payloads?
   If so, are they nominal or structural?
8. What exact syntax declares a newtype, and where can validation run without allowing invariant
   bypass? Can methods and multiple constructors be declared inline?
9. In which JVM contexts may a newtype be unboxed, and what boxing, equality, overload, reflection,
   and library-ABI rules are required?
10. How are sums and newtypes represented in compiled-library metadata, and which declaration
    changes constitute source or binary API changes?

## Suggested sequencing

1. Specify native sum identity, case visibility and construction, generic inference, and exhaustive
   case-only matching. Reconcile this with existing sealed-contract matching rather than silently
   changing its behavior.
2. Implement sums with generated nominal case classes first; defer representation optimization and
   advanced nested patterns.
3. Evaluate product types separately against the capabilities of classes and named case payloads.
4. Specify newtype construction invariants and source semantics before selecting erased/boxed lowering.
   Treat representation and ABI as part of the design, not as an invisible optimization.
