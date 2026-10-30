# Sum, Product, and Newtype Types

## Status and scope

This document specifies nominal sums, products, newtypes, and transparent data classes. It
distinguishes related but different ideas:

- **Sum types** represent one alternative from a closed set of cases.
- **Product types** represent a value containing all of a set of components.
- **Transparent data classes** are nominal classes whose declared components define their canonical
  data representation, while allowing behavior and separate auxiliary state.
- **Newtypes** give an underlying value a distinct nominal identity and domain-specific API.

Product and newtype declarations are nominal, not transparent aliases or structural tuples.

## Motivation and existing facilities

Zeron already has nominal classes and contracts, sealed contracts, a generic `Result<T, E>` library
type, and exhaustive matching over sealed contracts. These facilities can model alternatives using
a sealed contract and one implementing class per case. Native sums make the closed alternatives
explicit and let the compiler own construction, case coverage, and generated representations.
`Option<T>` is now the bundled example of a native generic sum.

Classes remain appropriate for named objects with methods and custom behavior. Product declarations
provide a compact form for shallowly immutable, structurally comparable data. Anonymous structural
records are not introduced: product identity remains nominal.

## Nominal product types

### Declaration and construction

A product is declared with a named, ordered list of at least two components:

```zeron
public type Pair<A, B> = (left: A, right: B);
```

The declaration creates a nominal type identified by its package and declared name. Product shape
does not affect assignability or type identity. Component names must be unique. Generic parameters
follow the existing invariant nominal-type rules.

Construction uses the ordinary canonical nominal constructor:

```zeron
let pair: Pair<Int, String> = Pair<Int, String>.new(42, "answer");
```

Arguments are evaluated and stored in declaration order. Normal generic argument inference and
contextual typing apply. Product constructors expose no alternate shorthand spelling.

### Components and equality

Each component is a generated read-only property, visible with the product declaration's visibility.
There are no component-level visibility or mutability modifiers, setters, defaults, or custom
accessors. Immutability is shallow: a component may refer to mutable state, which remains mutable
through its own API.

Every product receives compiler-synthesized structural `Eq<Product<...>>` evidence when all
components have applicable `Eq` evidence. Equality compares corresponding components in declaration
order, left-to-right, short-circuiting at the first unequal component. It is not JVM
`Object.equals`; ordinary Java identity behavior is retained. A user-defined `Eq` witness for a
product conflicts with this intrinsic derivation. Product equality does not imply `Hashable` or
`Stable`.

The initial product feature omits destructuring patterns, methods, defaults, custom accessors,
mutable slots, and `Hashable`/`Stable` derivation. Products lower to final JVM classes with
private-final component storage and generated accessors. The generated constructor and accessors
preserve declaration order and generic erasure rules.

Compiled-library metadata records product identity, generic parameters, and ordered component names
and types. A consumer uses that metadata for construction, property typing, and conditional equality;
it does not infer product semantics from JVM class files.
The evidence rules and the distinction between value equality and JVM identity are shared with sums;
see [design-document-17_structural-equality-and-hashing.md](design-document-17_structural-equality-and-hashing.md).

## Transparent data classes

Data classes combine an explicitly declared product shape with the methods, contracts, and auxiliary
state of a nominal class:

```zeron
public data class Point(x: Int, y: Int) {
    coordinateSum: Int = x + y;

    public squaredDistance(): Int = this.x * this.x + this.y * this.y;
}
```

Components are immutable, read-only properties and form the canonical constructor arguments.
Components are initialized first, then direct field initializers run in source order, followed by
initializer blocks in source order. Auxiliary state is excluded from structural equality and is not
part of the canonical component representation. Direct initializers may call only effect-free
functions; initializer blocks may declare effects, which become effects of canonical construction.
Data classes are final and cannot declare alternate named constructors or component defaults.

Every data class receives conditional structural `Eq` evidence over its components; explicitly
deriving `Eq` is redundant and rejected. `Hashable` remains opt-in through `deriving Hashable` and
requires suitable component evidence. Equality and hashing semantics are shared with products and
sums; see [design-document-17_structural-equality-and-hashing.md](design-document-17_structural-equality-and-hashing.md).

Source parsing, initialization, equality, hashing, and compiled-library metadata are implemented.
Serialization through the canonical model remains pending implementation of the `Encode`/`Decode`
capabilities from ZEP-0068; the data-class declaration alone does not currently enable serialization.

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
extended, implemented, or subclassed by consumers. A sum has no user-declared methods or additional
members in the MVP. Declaration-site contract conformance remains unsupported; compiler-synthesized
structural `Eq` support for sums is described in
[design-document-17_structural-equality-and-hashing.md](design-document-17_structural-equality-and-hashing.md).

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

Structural `Eq<T>` evidence for sums is implemented when every non-recursive payload has applicable
equality evidence. Direct self-recursive payloads are supported; mutually recursive sum declarations
are not yet synthesized as a recursive evidence group. See
[design-document-17_structural-equality-and-hashing.md](design-document-17_structural-equality-and-hashing.md).

## Newtypes and value-class-like wrappers

### Declaration, representation, and construction

A newtype declares a distinct nominal type over one concrete, non-generic immutable built-in
representation. The initial supported representations are `Int`, `Float`, `Boolean`, and `String`.
For example:

```zeron
public newtype Meters = Float;
public newtype UserId = String where value != "";
```

The first declaration exposes `Meters.new(value)`; the second exposes only
`UserId.tryNew(value): Option<UserId>`. The validator introduces an immutable local named `value`
of the underlying type. It must resolve to `Boolean`, is evaluated once after evaluating the
argument once, and `false` produces `Option.None`; `true` produces `Option.Some` containing the new
value. A validator has no declared raised effects. Zeron does not promise general purity or
determinism for validator expressions; invariant validity is established at construction time, and
the built-in representation restriction ensures that the wrapped value cannot later mutate.

Validated newtypes have no source-accessible unchecked constructor. Unvalidated newtypes use the
ordinary `.new(value)` constructor. Both forms expose a generated read-only `.value` property with
the visibility of the declaration. Reading `.value` reveals the representation but does not permit
forging a validated newtype.

Newtypes do not implicitly inherit `Eq`, `Comparable`, or any other evidence from the underlying
type. Equality and other behavior require explicit ordinary witnesses or extensions. JVM identity
comparison (`===`) is not defined for newtypes; use value equality only when the corresponding
evidence is available.

The initial implementation uses a final JVM wrapper class. This preserves nominal identity and gives
nullable and generic uses a conventional reference representation, but allocates on construction.
Direct-carrier lowering is a future optimization, not a property of this MVP. Any later erasure must
preserve the source-level distinction and define boxing/adaptation at nullable, generic, contract,
function, Java, and library ABI boundaries.

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

Products lower to final nominal classes with private-final component storage, a canonical constructor,
and generated read-only accessors. Newtypes initially lower to final wrapper classes with private-final
representation storage and a generated read-only accessor. Any future representation optimization
must preserve nominal checks, validated construction, generic behavior, and stable public library
metadata.

## Deferred design questions

Newtype deserialization and host interop must not bypass validated construction. Direct-carrier
lowering, generic newtypes, mutable or user-defined representations, derived equality, and
identity-sensitive operations remain deferred. [ZEP-0070](../zeps/ZEP-0070.md) explores generic
newtypes and nominal backing representations; its representation and invariant questions are not
settled.

## Suggested sequencing

1. Sum MVP implementation is complete, including generated nominal case classes and singleton
   nullary cases. Product MVP implementation is complete; mutual-recursion evidence between
   declarations remains a separate extension.
2. The concrete newtype MVP is implemented using wrapper classes and guarded `tryNew` construction.
   Treat direct-carrier lowering and its ABI as a later representation change, not as an invisible
   optimization.
