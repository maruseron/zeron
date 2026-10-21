# Generic bounds and witnesses

A generic type parameter lets a function accept many types, but inside the function a type like
`T` may not be specific enough. The function can pass a `T` around, but it cannot assume that every
possible `T` has a particular method or operation. A **bound** narrows the types that can be used
for a type parameter and states which contract those types must provide.

## Bounds: require the operations a function needs

Suppose a function wants to display any value. It needs more than an arbitrary `T`: it needs a
type that provides `Display<T>`:

```zeron
import zeron.lang.Display;

fn show<T: Display<T>>(value: T): String = value.toString();
```

The bound `T: Display<T>` means that `T` must have evidence for the fully applied contract
`Display<T>`. Within `show`, the bound makes the contract's non-mutating methods available. A call
is valid only when Zeron can establish that the chosen type meets the bound. For example,
`show(42)` uses the compiler-provided `Display<Int>` implementation.

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
contract Label<T> {
    label(): String;
}

witness Label<Int> for Int {
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

If a class already declares nominal conformance, that conformance remains authoritative for its
instance methods; a witness cannot replace them. Factories are expressed separately: use a class's
named constructor directly, pass a factory value for one concrete type, or use an HKT contract for
a generic type family.

## Selecting witnesses

For a concrete target type and fully applied contract, Zeron must find exactly one applicable
witness in the active scope. If it finds none, the bound cannot be satisfied; if it finds more
than one, the selection is ambiguous. The compiler does not choose a “closest” witness.

Witnesses in the current package are active automatically. A star import such as
`import labels.*;` activates witnesses from that package. An explicit import of a single symbol
does not activate that package's witnesses. This means imports can affect which evidence is
available, and adding a star import can make a previously unique selection ambiguous.

Witnesses also follow an **orphan rule**: the witness must be declared in the package of either its
contract or its target type. This keeps witness definitions near at least one of the types they
connect. When multiple generic witness declarations could apply, their patterns must not overlap
in active scope; the current overlap checks reject overlaps they can identify.

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
[type-class witnesses and generic bounds design document](../documents/design-document-15_type-class-witnesses-and-generic-bounds.md).
