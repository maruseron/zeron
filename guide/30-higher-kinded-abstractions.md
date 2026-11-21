# Higher-kinded abstractions

Generic functions can abstract over values such as `T`, but sometimes an operation should work over
different type constructors: for example, mapping a transformation over both `List<T>` and
`Stream<T>`. A higher-kinded contract describes that shared operation without choosing one concrete
container.

## A constructor parameter

`F<_>` names a type constructor with one changing type slot. A `Functor` contract can describe a
`map` operation for any constructor that implements it:

```zeron
contract Mapper<F<_>> {
    map<A admits mutable, B admits mutable>(this: F<A>, transform: (A) -> B): F<B>;
}
```

The underscore marks the slot that will vary. An implementation declares the applied constructor
and supplies the required operation:

```zeron
class Box<T admits mutable> is Mapper<Box<_>> {
    value: T;

    public constructor new;

    implementation Mapper<Box<_>> {
        fn map<A admits mutable, B admits mutable>(
            this: Box<A>,
            transform: (A) -> B
        ): Box<B> = Box.new(transform(this.value));
    }
}
```

The implementation block belongs to the class. Its method is statically dispatched through
higher-kinded evidence; it does not add an ordinary virtual method to `Box`. The block is optional:
when it is omitted, public instance methods implement receiver-marked operations and public class
static functions implement receiverless operations. Explicit blocks remain authoritative. If the
class already provides a compatible public method for a receiver-based operation, the block can opt
into forwarding it:

```zeron
class Box<T> is Mapper<Box<_>> {
    public map<R admits mutable>(transform: (T) -> R): Box<R> =
        Box<R>.new(transform(this.value));

    delegating implementation Mapper<Box<_>> {}
}
```

Explicit methods in the block take precedence. Contract defaults fill any remaining operation, and
receiverless operations are satisfied by public class static functions. The old
`is Mapper<Box<_>>` form with no block now means the same structural matching; add an explicit
block when you need to override a member, keep the implementation in one place, or provide an
operation that has no matching public member. Mutating operations can delegate when the receiver
and target method both allow mutation.

An implementation block supplies exactly its own contract. When a contract requires another one,
declare a separate block for the prerequisite; do not repeat inherited operations in the child
block. In the standard library, `Stream` therefore has separate `Functor`, `Applicative`, `Monad`,
and `Foldable` implementations, and `Option` and `Result` split their chains the same way. A child
block that declares `map` is rejected because `Functor` owns `map`.

The target method does not need to repeat the contract signature exactly. Its parameters may accept
broader types and its return type may be narrower, provided the method remains assignable to the
required operation and does not perform effects outside those allowed by the contract.

## Abstract over a type family

A generic parameter can bind the constructor and require the corresponding higher-kinded
capability:

```zeron
fn mapValues<F<_>: Mapper<F>, A, B>(
    source: F<A>,
    transform: (A) -> B
): F<B> = F.map(source, transform);

let box = Box<Int>.new(41);
let incremented = mapValues<Box<_>, Int, Int>(box, value -> value + 1);
```

Here `F.map(...)` is an explicit static call through the constructor bound. The same type parameter
and bound syntax appears in the standard library's `Functor<F<_>>`, `Applicative<F<_>>`,
`Monad<F<_>>`, and `Foldable<F<_>>` contracts. `Stream` implements `Functor`, `Applicative`,
`Monad`, and `Foldable`; `List` implements `Foldable` and `CollectionBuilder`.

Higher-kinded capabilities also describe construction and mutation for a generic family:

```zeron
import zeron.collections.CollectionBuilder;

fn emptyCollection<C<_>: CollectionBuilder<C>, T admits nullable + mutable>(): &C<T> =
    C.empty<T>();
```

`CollectionBuilder<C<_>>` combines an `empty` factory with an `add` operation. `Sink<T>` remains a
behavioral contract for an existing sink. These contracts can both constrain a type family; when
their receiver operations share a name, a behavioral contract wins receiver lookup and the HKT
operation remains available through explicit static syntax such as `C.add(values, item)`.

## Dispatch and limits

HKT operations are statically dispatched. For receiver syntax, concrete instance methods take
precedence, followed by behavioral contract methods, then HKT receiver operations. A type or
argument error in a behavioral contract method does not silently fall through to an HKT method.
Use the explicit constructor-qualified call when you need to select HKT evidence directly.

The implemented kind system is intentionally bounded: a constructor reference has one changing
slot, while other type arguments may be fixed (for example, a constructor family based on
`Result<_, E>`). It does not provide arbitrary type-level computation or higher-order constructor
composition. HKT contracts can require other HKT contracts, but behavioral contracts and HKT
contracts cannot declare requirements on each other.

For the underlying evidence and separate-compilation model, see
[Higher-Kinded Abstractions and Declarative Contracts](../documents/zeps/ZEP-0019.md) and
[Removing Contract Constructor Requirements](../documents/zeps/ZEP-0059.md).
