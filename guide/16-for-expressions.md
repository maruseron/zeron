# For-expressions

A `for` expression starts with a source to visit, then applies a series of stages to its values.
It uses the same iteration support as a `for` loop, but each stage transforms, skips, limits, or
collects values:

```zeron
let values = for [1, 2, 3, 4, 5]
    map number -> number * 2
    filter number -> number > 4;
```

Stages run from top to bottom. This example doubles each number first, then keeps the doubled
values greater than four. Because there is no `collect` stage, the expression produces a lazy
`Stream<Int>`: it describes the work, and values are produced as the stream is traversed.

## Transforming values

`map` changes each value. Its expression becomes the value passed to the next stage:

```zeron
map number -> number * 2
```

`flatMap` changes each value into an iterable and visits each item from that iterable before
moving on to the next source value. It is useful when each input can produce zero, one, or several
outputs:

```zeron
import zeron.collections.List;

let expanded = for [1, 2]
    flatMap number -> List<Int>.of(number, number * 10);
```

This produces `1`, `10`, `2`, and `20`, in that order.

## Filtering and limiting values

`filter` keeps a value only when its condition is true. `dropWhile` skips values at the beginning
while its condition is true, then passes that value and all remaining values through. `takeWhile`
passes values until its condition first becomes false, then ends the traversal:

```zeron
let selected = for [1, 2, 3, 4, 5, 6]
    filter number -> number %% 2 == 0
    dropWhile number -> number < 4
    takeWhile number -> number < 7;
```

This leaves `4` and `6`: the filter first keeps even numbers, then `dropWhile` skips `2`, and
`takeWhile` continues through the remaining values because both are less than `7`.

Use `drop count` to skip a number of values, or `take count` to stop after that many values:

```zeron
let middle = for [1, 2, 3, 4, 5, 6]
    drop 2
    take 3;
```

`middle` contains `3`, `4`, and `5`. Counts are evaluated when the pipeline is created. Negative
counts behave like zero. These stages apply to the values at their position in the pipeline, so
after a `flatMap` they count the flattened values.

## Collecting into a sink

If the result should be stored in a collection, end the pipeline with `collect` and provide a
mutable sink:

```zeron
import zeron.collections.List;

let evens = for [1, 2, 3, 4, 5, 6]
    filter number -> number %% 2 == 0
    map number -> number * 10
    collect List<Int>.empty();
```

The expression returns the same sink after adding each produced value, so `evens` is a
`List<Int>`. A sink is mutable because collecting calls its `add` operation. `collect` must be the
final stage.

`collect` is the only terminal stage in a for-expression. To count or fold the results instead,
leave off `collect` and use the resulting stream's operations, such as `count()` or `fold(...)`.
For a plain loop with no transformation stages, use the `for` statement from
[expressions and control flow](05-expressions-and-control-flow.md).
