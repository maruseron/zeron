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
bound its type constructor by `Collector<C<_>>`:

```zeron
import zeron.collections.Collector;

fn collectInto<C<_>: Collector<C>, E: nullable + mut>(
    source: Iterable<E>
): &C<E> {
    let result = C.empty<E>();
    for (let item in source) C.add(result, item);
    return result;
}
```

`Collector` is a higher-kinded contract: it describes operations on a whole family of types such as
`List<T>`. A `Sink<T>` bound remains useful when code only needs to add to an existing value. See
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

A witness must implement the contract's abstract instance methods, unless the contract supplies
defaults. Its methods are read-only in the current model: `mut` requirements are unsupported,
`this` is immutable, and implementations may access only public members of the target. This does
not guarantee that a witness method is free of other effects.

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
contract, must be implemented through a witness rather than a class's `is` clause. A witness for
such a contract implements all of its abstract requirements: declare static implementations with
`static fn` and instance implementations with `fn`. Contracts without static requirements may
still use nominal class conformance; that conformance remains authoritative for its instance
methods, and a witness cannot replace them. Factories are expressed separately: use a class's
named constructor directly, pass a factory value for one concrete type, or use an HKT contract for
a generic type family.

## Selecting witnesses

For a concrete target type and fully applied contract, Zeron must find exactly one applicable
witness in the active scope. If it finds none, the bound cannot be satisfied; if it finds more
than one, the selection is ambiguous. The compiler does not choose a “closest” witness.

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
importing both makes the selection ambiguous.

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
extensions. If more than one witness exposes the same method, the call is ambiguous. A generic
helper with an explicit bound is often a clearer way to state which contract behavior the code
requires.

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
- A witness cannot override the instance methods of a nominally conforming class.
- Generic arguments are erased at runtime. An ordinary erased Java-callable descriptor has no
  witness context and throws `UnsupportedOperationException`; Zeron-generated calls use the
  evidence-bearing descriptor.
- Overlap checking rejects patterns it can identify as overlapping, but it is not a complete
  theorem prover for every complex generic pattern.

For the full rules and implementation details, see the
[type-class witnesses and generic bounds design document](../documents/design_documents/design-document-15_type-class-witnesses-and-generic-bounds.md).
