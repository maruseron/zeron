# For-expressions

A for-expression visits values from one or more sources and produces a result. Its clauses are
written in evaluation order: `for` introduces a generator, `let` names an intermediate value, and
`if` filters out paths that do not match. End with `yield` for a lazy stream:

```zeron
let values = for number in [1, 2, 3, 4, 5]
    if number > 2
    yield number * 2;
```

The expression produces a lazy `Stream<Int>`. Constructing it evaluates the outer source expression,
but traversal and per-item clause expressions are deferred until values are requested.

## Generators and local bindings

Each `for name in source` clause introduces a nested generator. Later generators are evaluated for
each path that reaches them, and traversed completely before the earlier generator advances:

```zeron
import zeron.collections.List;

let expanded = for number in [1, 2]
    for result in List<Int>.of(number, number * 10)
    yield result;
```

The resulting stream yields `1`, `10`, `2`, and `20` in that order.

Use `let` to compute an intermediate value once for each path reaching that clause. Later clauses
and the result expression can use the binding:

```zeron
let labels = for number in [1, 2, 3]
    let doubled = number * 2
    if doubled > 2
    yield "value: " + doubled.toString();
```

A guard applies at its position. A false `if` skips the rest of the current path, including any later
generators or result expression.

## Lazy streams and stream operations

`yield expression` is the final clause of a lazy for-expression. It selects the value produced for
each path and returns a `Stream<T>`; it does not consume that stream. The ordinary `Stream<T>`
methods remain available for transformations and limits:

```zeron
let middle = (for number in [1, 2, 3, 4, 5, 6]
    yield number)
    .drop(2)
    .take(3);
```

Here `middle` is a stream of `3`, `4`, and `5`. Operations such as `map`, `filter`, `flatMap`,
`dropWhile`, and `takeWhile` are library methods, not for-expression clauses. They can be composed
with a yielded stream like any other stream.

Each traversal requests iterators from its sources as needed. A for-expression does not promise that
those sources are replayable or will return the same values on each traversal.

## Collecting into a sink

Use `collect expression into sink` as the final clause when the values should be added to a mutable
sink immediately:

```zeron
import zeron.collections.List;

let evens = for number in [1, 2, 3, 4, 5, 6]
    if number %% 2 == 0
    collect number * 10 into List<Int>.empty();
```

The expression returns the same sink after adding each projected value, so `evens` is a
`List<Int>`. The sink is evaluated once before traversal. If a source, clause, projection, or sink
addition fails, evaluation stops; earlier effects and sink mutations are not rolled back.

`yield` and `collect` are alternatives, and exactly one result clause is required at the end. Use
`yield` when you want to keep composing a lazy stream; use `collect` when you need the values stored
in a sink.

For a plain loop with no transformed result, use the `for` statement from
[expressions and control flow](05-expressions-and-control-flow.md).
