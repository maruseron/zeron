# Generic bounds and witnesses

A generic type parameter lets a function accept many types, but inside the function a type like
`T` may not be specific enough. The function can pass a `T` around, but it cannot assume that every
possible `T` has a particular method or operation. A **bound** narrows the types that can be used
for a type parameter and states which contract those types must provide.

## Bounds: require the operations a function needs

Suppose a function wants to display any value. It needs more than an arbitrary `T`: it needs a
type that provides `Display`:

```zeron
import zeron.lang.Display;

fn show<T: Display>(value: T): String = value.toString();
```

The bound `T: Display` means that `T` must have evidence for the `Display` contract. Within `show`,
the bound makes the contract's non-mutating methods available. A call
is valid only when Zeron can establish that the chosen type meets the bound. For example,
`show(42)` uses the compiler-provided `Display` implementation for `Int`.

Bounds can include generic contract arguments, not just a contract name. For example,
`Sink<Int>` and `Sink<String>` are different requirements. This lets a function say exactly which
operations and related types it expects, rather than treating every generic type as if it had the
same capabilities.

Applied contract bounds describe behavior on existing values. A mutating method can be called
through a bound when the receiver has a mutable reference and the type arguments satisfy the
contract's admissibility requirements. The JVM erases generic type arguments, but Zeron still checks
the applied arguments during compilation.

Contracts no longer declare class-side factories. To create and populate a generic collection,
bound its type constructor by `CollectionBuilder<C<_>>`:

```zeron
import zeron.collections.CollectionBuilder;

fn collectInto<C<_>: CollectionBuilder<C>, E: nullable + mut>(
    source: Iterable<E>
): &C<E> {
    let result = C.empty<E>();
    for (let item in source) C.add(result, item);
    return result;
}
```

`CollectionBuilder` is a higher-kinded contract: it describes operations on a whole family of types
such as `List<T>`. A `Sink<T>` bound remains useful when code only needs to add to an existing value,
and `Collector<T, R>` describes a value that accumulates items and returns one result. See
[Higher-kinded abstractions](30-higher-kinded-abstractions.md) for constructor bounds and dispatch.

## When a type does not declare the required contract

A bound works when a type already conforms to the required contract, or when the compiler provides
the necessary evidence. But sometimes the type you want to use does not declare that conformance.
You may not own the type, or its existing declaration may not be the right place to add a
particular behavior. A **witness** supplies an implementation of a contract for a target type
without changing that type's declaration.

For example, define a contract for values that can provide a label, then provide a witness for
`Int`:

```zeron
public contract Label<T> {
    label(): String;
}

public witness IntLabel: Label<Int> for Int {
    fn label(): String = "integer";
}
```

The witness allows generic code to state and use the corresponding bound:

```zeron
fn label<T: Label<T>>(value: T): String = value.label();

let description = label(42);
```

This is useful when adding behavior to built-in or third-party types, keeping an implementation
local to a package, or giving a type a contract implementation without modifying its nominal
conformance. A witness does not change the target type itself: it provides compile-time evidence
that can satisfy a bound.

A witness implements the abstract instance methods declared directly by its own contract, unless
that contract supplies defaults. Methods inherited from a prerequisite contract are not part of the
witness: each prerequisite is separate evidence with its own provider. With the strict rule in
effect, declaring an inherited operation in a child witness is an error that names the contract
that owns the operation. Witness methods are read-only in the current model: `mut` requirements are
unsupported, `this` is immutable, and implementations may access only public members of the target.
This does not guarantee that a witness method is free of other effects.

When a target class already has compatible public instance methods, a declaration can opt into
forwarding those methods instead of repeating trivial witness bodies:

```zeron
delegating witness Addable<Vector> for Vector {}
```

For a delegating witness, explicit witness methods take precedence, defaults remain defaults, and
other abstract, non-mutating instance requirements are forwarded only when a compatible public
method is declared directly on the target class. Unmatched requirements still need
implementations. Static requirements are never delegated and must be implemented explicitly.
Delegation does not infer a witness: the witness declaration remains explicit.

Contracts with static method requirements, including requirements inherited from another
contract, may be implemented through a class's `is` clause. A public class-scoped `static fn`
satisfies a matching static requirement directly. When the requirement needs the class's type
parameters, or when several entries need to be grouped, add a first-order `implementation` block
for the contract and use `static fn` entries:

```zeron
contract Factory {
    static create(): Int;
}

class Box<T> is Factory {
    implementation Factory {
        static fn create(): Int = 1;
    }
}
```

Explicit block entries take precedence over class static functions, and contract defaults fill any
remaining static requirement. A witness for such a contract implements its own abstract
requirements: declare static implementations with `static fn` and instance implementations with
`fn`. Requirements inherited from a prerequisite are supplied by that prerequisite's evidence.
Nominal conformance remains authoritative for the whole contract, including its static
requirements, so a witness cannot replace it. Factories are expressed separately: use a class's
named constructor directly, pass a factory value for one concrete type, or use an HKT contract for
a generic type family.

The compiler-owned entry contract `Main<A>` is an example of a static requirement supplied by a
witness. `Main.run` consumes the value returned by `main`; a user type opts in with a normal
witness, and the compiler selects exactly one applicable `Main<A>` evidence value when building
the launcher:

```zeron
public witness JobMain: Main<Job> for Job {
    static fn run(value: Job): Unit = value.start();
}
```

`Unit`, `Int`, and `Result<Unit, E>` have standard witnesses in `zeron.lang`. A library can add
entry behavior for another type with its own witness.

## Provision and requirement evidence

A witness provides exactly the contract named in its declaration. If that contract has a
`requires` clause, each prerequisite is a separate requirement: Zeron resolves it to its own
canonical provider instead of treating the child witness as a provider of every contract in its
closure. A canonical `default` witness wins; when no default exists, a named witness is used only
if exactly one applies. Two independent providers for the same prerequisite are still ambiguous.

This lets a contract hierarchy share evidence without duplicating methods. For example, an
`Applicative<F>` witness provides `pure` and `ap`, while its `Functor<F>` prerequisite has the
`map` witness. A `Monad<F>` witness provides `flatMap` and requires both, but does not provide
`map`, `pure`, or `ap` itself. A concrete type therefore has one provider per contract in the
chain, and `Monad<Option<_>>` can share the default `Applicative<Option<_>>` and
`Functor<Option<_>>` witnesses with `Alternative<Option<_>>`.

Operations are dispatched by their declaring contract. `map` resolves through `Functor`, `pure`
and `ap` through `Applicative`, `flatMap` through `Monad`, and `empty` and `orElse` through
`Alternative`, even when the receiver is a concrete type that implements all of them. Calling an
operation through a child bound such as `F<_>: Monad<F>` still permits inherited operations: the
compiler projects them from the bound's prerequisite evidence.

The strict rule applies to every witness, including class `implementation` blocks: a block may
declare only operations owned by its own contract. Split a chain into one implementation block per
contract, and do not repeat inherited operations in a child block. The compiler rejects an
inherited operation with an error that names the contract that declares it.

## Selecting witnesses

For a concrete target type and fully applied contract, Zeron must find exactly one applicable
provider in the active scope. If it finds none, the bound cannot be satisfied; if it finds more
than one, the selection is ambiguous unless an explicit selection chooses a provider. The compiler
does not choose a “closest” witness, and a witness for a child contract is not a provider for its
prerequisites.

Witnesses in the current package are active automatically, and a star import such as
`import labels.*;` activates every witness exported by that package. A named public witness can
be activated on its own with `import witness labels.IntLabel;` or `import witness labels.*;`. An
alias, as in `import witness labels.IntLabel as LocalLabel;`, changes only the name used in
diagnostics. Importing a single type, function, or value does not activate that package's
witnesses. Imports can therefore affect which evidence is available, and adding a star import can
make a previously unique selection ambiguous.

Witness names occupy their own declaration namespace: a witness name may match a type or function
name, and a witness is never used as a value or a type. A named witness without `public` is
package-local. Anonymous witnesses have no exportable identity, are deprecated during the
migration release, and are removed one release after the standard library migrates.

A `default` witness is canonical evidence declared in the same file as the type it targets:

```zeron
public class Box<T: mut> {
    public constructor new(value: T);
}

default witness<T: Display> Display for Box<T> {
    fn toString(): String = render(this);
}
```

A default witness is unnamed and is not marked `public`; its identity is the pair (contract, target
pattern), and the compiler derives an internal index key from it. Its visibility follows the target
type. It becomes active when the target type is in scope through the current package, an explicit
type import, or a package star import. Intrinsic targets are always in scope. `import witness` never
activates a default, and writing a fully qualified type name does not import the type, so it does not
activate its defaults. At most one default may apply to a fully applied contract and target,
overlapping defaults are declaration errors, and a default has no priority over an ordinary witness:
importing both makes the selection ambiguous unless an explicit selection disambiguates it.

A default witness of a public target must use a public contract, because it becomes public
canonical evidence for that type.

A class-scoped `implementation Contract<T<_>>` block is canonical evidence for its owner type. It
activates when that owner type is in scope through the current package, an explicit type import, or
a package star import, so importing `Stream` is enough to use `Stream`'s `Monad` implementation;
the whole `zeron.collections` package does not need to be imported. A class implementation block is
not source syntax for a `default witness`, but it follows the same owner-scoped activation rule and
is exported with the default marker for separately compiled consumers.

The standard library declares canonical evidence for nominal types as unnamed defaults, such as
`Integral<Integer>` and `Display` for `Integer`, and `Monad<Option<_>>` and `Foldable<Option<_>>` for
`Option`. Their
evidence travels with those types. All intrinsic types are canonically owned by `zeron.lang`, and
primitive evidence such as `Integral<Int>`, `Integral<Long>`, `Floating<Float>`, and the primitive
codecs is available as intrinsic defaults. An intrinsic default is always active, so a generic bound
such as `Floating<Float>` needs no witness import.

Not every intrinsic witness is a default. `Array` keeps its `Foldable<Array<_>>` evidence as the
named public witness `zeron.collections.ArrayFoldable`; it is imported explicitly with
`import witness zeron.collections.ArrayFoldable;` when a generic bound needs it. This keeps the cost
of evidence handling visible instead of activating it for every array user.

Declaration placement is unrestricted. A witness may be declared in any package, including one
that owns neither the contract nor the target type; there is no orphan rule. Only a `default`
stays beside its target type, because canonical evidence travels with the type. When multiple
generic witness declarations could apply, their patterns must not overlap in active scope; the
current overlap checks reject overlaps they can identify.

Witness methods participate in member lookup after ordinary instance methods and applicable
extensions. An operation exposed by a witness is selected from the provider for the contract that
declares it; the compiler does not choose among sibling witnesses that happen to reach it through
their prerequisites. If two providers compete for the same declaring contract, the call is
ambiguous. A generic helper with an explicit bound is often a clearer way to state which contract
behavior the code requires.

## Witness bundles

Some contracts build on each other. `Monoid<T>` requires `Semigroup<T>`, and a witness for the
monoid is only usable when a compatible semigroup is selected too. A **witness bundle** groups
witnesses for one target type under a single name so they can be selected together.

```zeron
public witness bundle Multiplication for Int {
    witness Semigroup<Int> {
        fn combine(other: Int): Int = this * other;
    }

    witness Monoid<Int> {
        static fn empty(): Int = 1;
    }
}
```

A bundle is a declaration, not a runtime object. It directly provides the contracts named by its
members, and selecting it activates those members together; they cannot be selected individually
through the bundle name. Its closure also activates the recorded canonical defaults for member
prerequisites that no member supplies. A generic bundle such as
`public witness bundle ListMonoid<T> for List<T>` declares members that refer to the bundle's own
type parameters.

A bundle marked `default` is active wherever its target type is used, just like a default witness:

```zeron
default witness bundle for Int {
    // canonical members
}
```

A default bundle is unnamed, cannot be marked `public`, and must appear beside the type it targets,
just like a default witness. Unlike a standalone witness, a named (non-default) bundle becomes active
only through a selection; importing its name does not activate its members. This is deliberate: it
avoids an import silently introducing several overlapping witnesses. Use a bundle when the members
form a coherent set, and a standalone witness when a file needs exactly one piece of evidence.

## Selecting a bundle or witness explicitly

A `using witness` block selects providers for the enclosed statements. The name may be a bundle or a
standalone named witness:

```zeron
using witness (Multiplication) {
    let total = combineAll(3, 4);
}
```

Several providers can be selected in one clause, and a qualified name is allowed:

```zeron
using witness (HexDisplay, zeron.collections.DescendingComparable) {
    let hex = describe(255);
    let ordered = sort(values);
}
```

A selection is local to its block. It does not leak into later code or nested declarations, and
two selections in the same scope that provide the same contract for the same type are an error
rather than a silent choice. Selecting a provider that is already active is allowed and has no
extra effect. A selection cannot override evidence a class already implements nominally, structural
`Eq`/`Hashable` evidence, derived serialization evidence, or intrinsic arithmetic lowering.

A single call can also select a provider for that invocation only:

```zeron
let additive = combineAll(3, 4);
let product = combineAll(3, 4) with witness (Multiplication);
let additiveAgain = combineAll(3, 4);
```

The call-site clause is considered before an enclosing `using witness` block, which is considered
before ambient defaults and nominal conformance. It applies only to calls that still have hidden
evidence parameters to fill; selecting a witness for a call that already fixed its evidence inside a
non-generic body is an error. Function values, class constructors, operator invocations, and `do`
operations do not accept the call-site form, and a function value captures its evidence when it is
created.

Explicit selection is for resolving a choice the compiler cannot make on its own. Generic code that
always needs the same contract is usually clearer with a bound, such as
`fn sum<T: Monoid<T>>(...)`, because the requirement then appears in the signature instead of at
selected call sites.

## Runtime representation and current limits

Bounds and witnesses are selected statically. Generic type arguments are erased by the JVM, so
runtime code cannot inspect `T` to discover which implementation to use. Instead, Zeron passes
hidden `MethodHandle` values as evidence to generated bounded code. Witness implementations are
emitted as static helpers, not as runtime dictionary objects. Applied type arguments remain
available for Zeron's compile-time checking and separate compilation, and library indexes
preserve witness metadata for consumers.

The current implementation has several boundaries:

- Applied contract bounds are supported on generic functions, methods, classes, and contracts.
  Class-bound evidence is selected during construction and carried by instances; bounded contract
  defaults use evidence carried by implementing instances.
- A read-only receiver cannot call mutating methods through a bound; use a mutable reference.
  Mutating witness requirements remain unsupported.
- A witness cannot implement a contract already implemented nominally by a class, whether its
  requirements are static, instance, or both.
- Generic arguments are erased at runtime. An ordinary erased Java-callable descriptor has no
  witness context and throws `UnsupportedOperationException`; Zeron-generated calls use the
  evidence-bearing descriptor.
- Overlap checking rejects patterns it can identify as overlapping, but it is not a complete
  theorem prover for every complex generic pattern.

For the full rules and implementation details, see the
[type-class witnesses and generic bounds design document](../documents/design_documents/design-document-15_type-class-witnesses-and-generic-bounds.md).
