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
does not affect assignability or type identity. Component names must be unique. Product type
parameters accept the same contract bounds and explicit `out` variance as classes and contracts,
for example `type Pair<out T: Eq<T>> = (left: T, right: T);`. Bounds are checked on the
compiler-generated product class, and covariance is checked against the read-only components, so
both apply uniformly to construction, component access, inference, and separate compilation.

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

The initial product feature omits methods, defaults, custom accessors, mutable slots, and
`Hashable`/`Stable` derivation; positional product destructuring is covered in
[design-document-16](design-document-16_patterns-and-pattern-matching.md). Products lower to final
JVM classes with private-final component storage and generated accessors. The generated
constructor and accessors preserve declaration order and generic erasure rules.

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
Canonical components are readable by any declaration that can name the data class, regardless of
whether the data class itself is `public`; the type's visibility gates nameability, not structural
access. Positional patterns use the components in declaration order and exclude auxiliary state.
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

Sum type parameters accept the same contract bounds and explicit `out` variance as classes,
contracts, and products. Payloads are read-only, so a covariant parameter may occur in a case
payload. `Option`, `Result`, and `Pair` use covariant parameters, and a covariant sum widens
through nested applications. Writable references, arrays, mutable or consuming positions, and
invariant derived contracts still reject an `out` occurrence.

There are no transparent type aliases. A declaration such as `type MyInt = Int;` is a one-case sum
whose case is named `Int`; it constructs as `MyInt.Int()` and is neither assignable to nor from
`Int`. Use a newtype for a single-value nominal representation and a product with at least two
components for a grouped value. A one-component product is deliberately not provided
([ZEP-0129](../zeps/ZEP-0129.md)): it would carry product equality and positional destructuring
without providing structural information beyond the single component, while a newtype expresses the
same shape with explicit construction and nominal identity. This nominal behavior is intentional,
but it is a common source of "alias" surprises.

### Cases, payloads, construction, and visibility

Qualification remains the unambiguous form, and [ZEP-0128](../zeps/ZEP-0128.md) accepts
context-resolved unqualified case names as an ergonomic alternative:

```zeron
let missing: Option<Int> = None();
let present: Option<Int> = Some(42);
let qualified = Option.Some(42);
let direction = North();
```

An unqualified case name resolves through the expected sum type or the match scrutinee; if two
visible sums expose the same case name and context does not choose one, the name must be qualified.
Qualification avoids package-level collisions between common names such as `None`, `Some`, and
`North`, and remains available for construction and patterns. Payloads are declared as ordered named
fields, are immutable, and are positional at construction and match sites. Names are
metadata/documentation only; named arguments and payload defaults are not in the MVP.

All cases have the visibility of their sum. Public sums expose every case and payload type in their
API; payload types must themselves be publicly nameable. Per-case visibility is excluded. Case-local
type parameters are implemented by [ZEP-0128](../zeps/ZEP-0128.md). A case may declare its own
marker-bounded parameters, which are existential in the sum type:

```zeron
public type IO<A: mut> =
      Pure(value: A)
    | Bind<B: mut>(source: IO<A>, transform: (A) -> IO<B>);
```

`IO<A>` still has one type argument; `Bind` chooses `B` at construction and matching treats it as
opaque. Contract bounds on case-local parameters remain deferred. Adding, removing, or changing a
public case or payload is a public API change.

Every zero-payload case is semantically a singleton: constructing it repeatedly yields the same
value, and reference identity comparisons observe that stable identity. Payload-bearing cases
construct fresh values. This is a source semantic guarantee, not merely a code-generation shortcut.

Generic sum parameters are invariant, following existing generic nominal types. Constructor
inference uses payload argument types and a contextual expected sum type. For a payload-less case,
context must determine the sum's type arguments or the caller must supply them explicitly. If
arguments plus context do not uniquely determine all parameters, resolution fails rather than
guessing. Sum parameters accept the same marker and contract bounds as classes and contracts, so a
declaration such as `type Sorted<A: Comparable<A>> = Empty | Cons(head: A, tail: Sorted<A>);`
checks its bound at every application. Case-local arguments are inferred from payloads and the
expected sum, or supplied completely after the case name, as in `IO.Bind<B>(source, transform)`.

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

Case-local parameters may be opened by a branch-local binder when matching:

```zeron
match (io) {
    case IO.Pure(value) -> value;
    case IO.Bind<let B>(source, transform) -> ...;
}
```

The binder is opaque and cannot escape the branch. A concrete case-local argument in a pattern is
rejected because case identity is erased.

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

## Annotations

Native products, sums, and newtypes accept annotations using the same syntax and validation rules
as classes. The declaration targets are `PRODUCT`, `SUM`, and `NEWTYPE`; product components use
`PRODUCT_COMPONENT`, and sum cases use `SUM_CASE`. Applications precede the annotated element:

```zeron
@Versioned(2)
public type Pair<out A, out B> = (@SerialName("first") left: A, right: B);
@Versioned(2)
public type Result<out T, out E> = @SerialName("ok") Ok(value: T) | Err(error: E);
```

Annotations are metadata only: they do not change construction, matching, structural `Eq`,
derivation, or representation. Public declarations export their applications and their
components' or cases' applications through the library index; non-public declarations keep them
in source metadata only. Annotation consumption remains a separate feature.

## Newtypes and nominal single-value views

### Declaration, representation, and construction

A newtype declares a distinct nominal type over one statically known representation, including
type parameters, nominal classes, products, sums, function types, and explicitly declared Java
external classes. [ZEP-0070](../zeps/ZEP-0070.md) implements generic and nominal representations
for `0.8.0`, and [ZEP-0129](../zeps/ZEP-0129.md) applies them to single-value shapes such as a
function-shaped `IO`.
For example:

```zeron
public newtype Meters = Float;
public newtype UserId = String where value != "";
public newtype IO<A: mut> = () -> A;
```

`IO` is a function-shaped newtype: construction takes the function value, a newtype-owned extension
sees that function as `this`, and an explicit `run()` operation invokes it. A parameter typed as the
newtype inside such a body is likewise represented by its carrier, so a higher-order operation can
accept `A -> IO<B>` and treat the returned value as its function carrier.

The first declaration exposes `Meters.new(value)`; the second exposes only
`UserId.tryNew(value): Option<UserId>`. The validator introduces an immutable local named `value`
of the underlying type. It must resolve to `Boolean`, is evaluated once after evaluating the
argument once, and `false` produces `Option.None`; `true` produces `Option.Some` containing the new
value. A validator has no declared raised effects. Zeron does not promise general purity or
determinism for validator expressions; invariant validity is established at construction time, and
the built-in representation restriction ensures that the wrapped value cannot later mutate.

Validated newtypes have no source-accessible unchecked constructor. Unvalidated newtypes use the
ordinary `.new(value)` constructor. There is no `.value` property: the representation is the value.
A newtype-owned extension, witness, or namespace body is a representation context, so `this` and
newtype-typed parameters have the carrier type directly. Ordinary callers use an explicit extension
or namespace accessor when they need the representation.

Newtypes do not implicitly inherit `Eq`, `Comparable`, or any other evidence from the underlying
type. Equality and other behavior require explicit ordinary witnesses or extensions. JVM identity
comparison (`===`) is not defined for newtypes; use value equality only when the corresponding
evidence is available.

The implementation uses a direct carrier: concrete fields, parameters, and returns use the
representation, while generic, nullable, `Any`, array, and other erased positions may box. The
source-level distinction is preserved by nominal checking; no wrapper object or generated class
exists for the newtype. Boxing and adaptation are defined at nullable, generic, contract, function,
Java, and library ABI boundaries.

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

Compiled-library metadata must export the sum declaration, type parameters and their bounds,
visibility, ordered cases, each case's local parameters and marker bounds, and each payload's name
and type. Consumer compilation uses that metadata for construction, type inference, pattern opening,
and exhaustive matching; it must not reconstruct source semantics from class files. Changing the
metadata schema is distinct from changing the standard-library API compatibility number.

### Product and newtype representation

Products lower to final nominal classes with private-final component storage, a canonical constructor,
and generated read-only accessors. Newtypes lower directly to their representation; concrete fields,
parameters, and returns use the carrier, while erased positions may box. Nominal checks, validated
construction, generic behavior, and public library metadata remain stable across that boundary.

## Deferred design questions

Newtype deserialization and host interop must not bypass validated construction. Guarded reference
newtypes remain deferred and guarded newtypes remain limited to immutable scalar and `String`
representations. [ZEP-0070](../zeps/ZEP-0070.md) implements generic and nominal backing
representations over direct carriers.

## Suggested sequencing

1. Sum MVP implementation is complete, including generated nominal case classes and singleton
   nullary cases. Product MVP implementation is complete; mutual-recursion evidence between
   declarations remains a separate extension.
2. Newtypes use direct carriers and guarded `tryNew` construction. The wrapper and `.value` ABI were
   removed by ZEP-0070; that is an explicit ABI change, not an invisible optimization.
