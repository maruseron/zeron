# Option and Result

Some functions cannot produce a useful value for every input. A search might not find what it is
looking for, or a calculation might fail for certain values. The function still needs to tell its
caller what happened.

If there is a sensible fallback, the function can return that value. Otherwise, Zeron's standard
library provides `Option<T>` for a value that may be absent and `Result<T, E>` for an operation
that can either succeed or report an error. When a function needs to stop normal work and transfer
control to a handler, typed raised effects provide another option; they are introduced in
[Raised effects](22-raised-effects.md).

## Option: a value or no value

Use `Option<T>` when absence is an ordinary possibility and the caller does not need a reason for
it. An `Option<T>` is either `Some(value)`, containing a `T`, or `None`, containing no value:

```zeron
fn findName(id: Int): Option<String> {
    if (id == 1) return Option.Some("Ada");
    return Option<String>.None();
}
```

The return type makes it clear that a name might not be found. A caller can use `match` to handle
both possibilities:

```zeron
let label = match (findName(1)) {
    case Option.Some(name) -> name;
    case Option.None() -> "unknown";
};
```

`Option.Some(name)` binds the value to `name`; `Option.None()` represents the missing value. A
payload-less generic case such as `None` may need explicit type arguments or an expected type so
Zeron can determine the contained type.

The standard `Option` extensions include `getOrElse(other)`, `map`, `filter`, and `fold`. For
example, `getOrElse` supplies a fallback when the option is `None`, while `map` transforms the
contained value when it is `Some` and leaves absence unchanged. These extensions are available
from `zeron.lang.Option`.

## Result: a value or an error

Use `Result<T, E>` when a function can succeed or fail and callers should be able to learn why.
`T` is the success value's type; `E` is the error value's type. A result is either `Ok(value)` or
`Err(error)`:

```zeron
fn findName(id: Int): Result<String, String> {
    if (id == 1) return Result.Ok<String, String>("Ada");
    return Result.Err<String, String>("No name exists for that id");
}
```

The caller can inspect either case and decide what to do:

```zeron
let label = match (findName(1)) {
    case Ok(name) -> name;
    case Err(message) -> "lookup failed: " + message;
};
```

`Ok` carries the successful value, and `Err` carries the error. Both are ordinary values, so the
function's caller chooses whether to handle the error, return it, or transform it. The standard
operations include `map` for transforming a success, `mapError` for transforming an error, and
`flatMap` for chaining operations that can also fail.

Choose `Option<T>` when the only distinction that matters is whether a value exists. Choose
`Result<T, E>` when a failure reason is useful information that should travel with the returned
value.

## Chaining steps with `do`

A chain of two or three fallible steps written as nested `match` expressions is hard to read. A `do`
block lists the steps in order and yields the value to produce when every step succeeds:

```zeron
fn total(first: Int, second: Int): Result<Int, String> = do {
    let left <- positive(first);
    let right <- positive(second);
    yield left + right;
};
```

`let name <- expression` binds the value of a successful step. If a step is `None` or `Err`, the
rest of the block is skipped and that value becomes the block's result, so a later step never runs
on a missing value. The final `yield` produces the success value. The same shape works for
`Option`, `Result<_, E>`, `Stream`, and any library constructor with a `Monad` implementation.

A plain `let name = expression` clause computes a local for the clauses that follow it. It must
appear after the first bind:

```zeron
fn labelled(): Option<String> = do {
    let value <- Option.Some(21);
    let doubled = value * 2;
    yield "value: " + doubled.toString();
};
```

The constructor comes from the first bind, so `Option.Some(...)` makes the block an `Option` and a
`Result` case makes it a `Result` with that error type. Steps dispatch through that constructor's
`Monad` witness, not through instance methods with the same name, so a type may keep an instance
`flatMap` with a different signature — `Stream.flatMap` accepts `(T) -> Iterable<R>`, for example —
without changing what a `do` block means. Every bind must use the same constructor,
and `Result` binds must agree on `E`; adapt a single expression with `mapError` when its error type
differs. Write `do<Option<_>>` or `do<Result<_, E>>` to state the constructor explicitly; the
declared constructor must match the chain's inferred one. Because the constructor is not chosen
from the surrounding expected type, an empty or ambiguous chain is an error rather than a guess.

There are no `do` guards and no `collect` clause: put an `if` expression in a bind when a step is
conditional, and use a [for-expression](16-for-expressions.md) when you want to collect values.

## When a function must stop normal work

Sometimes a function cannot continue normally: for example, a required user record is missing and
the current operation must be abandoned. Returning an `Option` or `Result` keeps the outcome as a
value that callers inspect. Typed raised effects instead transfer control to a matching handler,
or require the function to declare that the effect may escape.

Raised effects and `Result` are separate choices: Zeron does not automatically convert one into
the other. Learn how to declare, propagate, and handle raised effects in
[chapter 22](22-raised-effects.md).
