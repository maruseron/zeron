# Functional abstractions

[Functional programming essentials](36-functional-programming-essentials.md) showed how to build
programs from functions and immutable data. This chapter goes one level up. It looks at how
functions interact with typed effects, how a contract can describe a whole family of operations,
and how Zeron's standard library names the patterns that `Option`, `Result`, `Stream`, and lists
have in common.

It assumes [Raised effects](22-raised-effects.md), [Generic bounds and
witnesses](20-generic-bounds-and-witnesses.md), and [Higher-kinded
abstractions](30-higher-kinded-abstractions.md). Each section below depends only on the sections
before it.

## Functions that pass effects through

A higher-order function takes a function from its caller. If that function can raise an effect,
the higher-order function must allow it to escape, and if the function cannot, the caller should not
have to declare anything. A parameter `E: effect...` stands for whatever effects the function
argument raises, and the result carries the same set:

```zeron
fn applyTwice<E: effect...>(value: Int, transform: (Int) -> Int raises E): Int raises E =
    transform(transform(value));
```

With a pure function, `E` is empty and `applyTwice` raises nothing:

```zeron
let five = applyTwice(3, number -> number + 1);
```

With a function that raises, `E` is that function's effects, so `applyTwice` raises them too:

```zeron
public effect Overflow {
    public property value: Int;
    public constructor new;
}

fn checkedDouble(value: Int): Int raises Overflow =
    if (value > 1000) then raise Overflow.new(value)
    else value * 2;
```

`raise` has type `Never`: an expression of that type never produces a value, so it can stand in
the `then` branch where an `Int` is expected. Calling `applyTwice(100, checkedDouble)` returns
`400`, but the call must now be handled or declared, because `Overflow` may escape. A named handler,
from [Raised effects](22-raised-effects.md), handles it once and reuses the policy:

```zeron
handler ZeroOnOverflow {
    case Overflow(_) -> 0;
}

let result = handle (applyTwice(600, checkedDouble)) with ZeroOnOverflow;
```

The first call returns `1200`; the second call raises `Overflow`, and the handler turns the whole
expression into `0`. This is how the standard library defines operations such as `Stream.fold` and
`any`: their callback may raise, and the effects become part of the caller's row.

## Describing behavior with contracts

A contract names a capability, and a generic bound requires it. The standard library's
`Semigroup` and `Monoid` contracts describe values that can be combined:

```zeron
public contract Semigroup<A: mut> {
    combine(other: A): A;
}

public contract Monoid<A: mut> requires Semigroup<A> {
    static empty(): A;
}
```

A `Semigroup` has an associative `combine`; a `Monoid` also has an identity value, `empty()`, that
leaves any other value unchanged. A bound lets one function work for every type that has the
capability:

```zeron
import zeron.collections.Iterable;
import zeron.control.Monoid;

fn combineAll<A: mut + Monoid<A>>(values: Iterable<A>): A =
    values.fold(A.empty(), (total, value) -> total.combine(value));
```

The same function adds numbers, joins text, or merges any type that declares a witness for
`Monoid`. Chapter 20 explains how a type conforms to a contract, including types you do not own.
Contracts do not describe the laws; they are a promise from the implementer. The laws are documented
in the standard library and are what make generic code like `combineAll` safe to trust.

## Mapping over any container

`Option<T>`, `Result<T, E>`, `Stream<T>`, and lists all support `map`. Each time, `map` changes
the values inside the structure and leaves the structure alone. That shared behavior is the
`Functor` contract, and its `F<_>` parameter stands for any type constructor with one changing
slot, as introduced in chapter 30:

```zeron
public contract Functor<F<_>> {
    map<A: mut, B: mut>(this: F<A>, transform: A -> B): F<B>;
}
```

A function bounded by `Functor` works for every container, without knowing which one:

```zeron
import zeron.control.Functor;

fn mapAll<F<_>: Functor<F>, A, B>(source: F<A>, transform: A -> B): F<B> =
    F.map(source, transform);

let option = mapAll<Option<_>, Int, Int>(Option.Some(41), number -> number + 1);
```

Functors obey two laws: mapping the identity function changes nothing, and mapping two functions in
turn is the same as mapping their composition once. Those laws are what guarantee that `map` never
changes the shape of the container. A `Stream` never gains or loses elements through `map`, and an
`Err` never turns into an `Ok`.

## Combining independent values

`map` applies a one-argument function to one wrapped value. To combine two wrapped values, you need
an `Applicative`. It adds two operations to a functor: `pure` wraps a plain value, and `ap` applies a
wrapped function to a wrapped value. From those, the contract provides `map2`:

```zeron
import zeron.control.Applicative;

fn combineWith<F<_>: Applicative<F>, A, B, C>(
    first: F<A>,
    second: F<B>,
    combine: (A, B) -> C
): F<C> = F.map2(first, second, combine);

let sum = combineWith<Option<_>, Int, Int, Int>(
    Option.Some(1),
    Option.Some(2),
    (left, right) -> left + right
);
```

`sum` is `Some(3)`. If either argument is `None`, the result is `None`. The values are independent:
neither depends on the other. When one value must be computed from another, you need a `Monad`.

## Chaining dependent steps

`Monad` adds `flatMap`: it applies a function that itself returns a wrapped value and flattens the
result into one layer. Compare the two results of `map` and `flatMap` with a function that might
fail:

```zeron
fn half(value: Int): Option<Int> =
    if (value %% 2 == 0) 
    then Option.Some(value / 2)
    else Option<Int>.None();

let nested = Option.Some(20).map(half);        // Option<Option<Int>>
let chained = Option.Some(20).flatMap(half);   // Option<Int>
```

`map` keeps the wrapper from `half`, so the result has two layers. `flatMap` removes one, so
`chained` is `Some(10)`, and chaining again gives `Some(5)`. Once a step returns `None`, every later
step is skipped. `Result` offers the same behavior under the name `andThen`.

A monad must satisfy three laws. Wrapping a value and then chaining a function is the same as just
calling the function. Chaining `pure` changes nothing. Chaining two functions in turn is the same as
chaining their combination. The standard library's `Option`, `Result`, and `Stream` implement
`Monad`, so generic code written against it works with all three.

## Folding any structure

Reducing a structure to a single value is another shared pattern. `Foldable` captures it with one
operation, `foldLeft`, and derives `count` and `isEmpty` from it:

```zeron
import zeron.control.Foldable;

fn total<F<_>: Foldable<F>>(source: F<Int>): Int =
    F.foldLeft(source, 0) { sum, number -> sum + number };
```

`total` works on a stream, a list, an `Option<Int>`, or a `Result<Int, E>`. For an `Option`, the
fold visits at most one value.

## Functional core, imperative shell

The abstractions above give you a design for whole programs. Put the decisions and calculations in a
**functional core** made of pure functions, and keep the reading, writing, and printing in a thin
**imperative shell** around it:

```zeron
import zeron.collections.Iterable;

fn total(values: Iterable<Int>): Int =
    values.fold(0) { sum, value -> sum + value };

fn report(values: Iterable<Int>): String =
    "Total: " + total(values).toString();

// Shell: the only code that touches the outside world.
println(report([8, 10, 9]));
```

The core can be tested by calling it with values and checking what it returns. It never needs a
file or a console. Effects that must happen inside the core, because a step can fail or a callback
does real work, appear in the types of its functions, so a caller can always see them. Raised effects
and `Result` fit here too: they keep failure visible in the signature instead of hiding it in a
side channel.

## Practice

1. Write `mapTwice<E: effect...>(values: Iterable<Int>, transform: Int -> Int raises E)` that
   returns a `Stream<Int>`, and check that it needs no handler for a pure `transform`.
2. Write a `Monoid` witness for a type of your own, and use `combineAll` with it.
3. Use `mapAll` with `Option`, then with `Stream`. What does it return in each case?
4. Use `flatMap` to chain three steps that each may return `None`, then replace `Option` with
   `Result` and `andThen`.
5. Write `average<F<_>: Foldable<F>>(source: F<Int>): Option<Int>` that returns `None` when the
   structure is empty.
6. Take a program of your own that prints and calculates in the same function, and split it into a
   functional core and an imperative shell.
