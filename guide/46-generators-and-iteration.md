# Generators and the iteration protocol

[Collections and iteration](15-collections-and-iteration.md) introduced Zeron's iteration protocol:
an `Iterable<T>` makes an `Iterator<T>`, and `next()` returns a `Step<T>` that is either
`Step.Yield(value)` or `Step<T>.Done()`. A `for` loop pulls from that iterator until it is done and
then closes it.

A generator is another way to implement that same protocol. Instead of writing an iterator class
with a stored position, you write an ordinary body that emits values with `perform Yield<T>(value)`.
The compiler turns the body into a cursor that suspends after each yield and resumes on the next
pull. This chapter describes the generator side of the protocol and how the two interact.

## The protocol, restated for generators

The names line up with the protocol one to one:

| Protocol role | Ordinary implementation | Generator implementation |
| --- | --- | --- |
| Factory | `Iterable<T>` | `GeneratorSource<T>` |
| Cursor | `Iterator<T>` | `Generator<T>` |
| One pull | `next(): Step<T>` | `next(): Step<T>` |
| End of input | `Step<T>.Done()` | `Step<T>.Done()` |
| Cleanup | `close()` | `close()` or `cancel()` |

`generator<T>(body)` takes a body of type `() -> Unit performs Yield<T>, DisposeFailure` and returns
a `GeneratorSource<T>`. Because the source is an `Iterable<T>`, it works anywhere an iterable works,
including `for` loops and `Stream.from`.

```zeron
import zeron.collections.generator;
import zeron.collections.Step;
import zeron.collections.Stream;
import zeron.lang.Yield;

let numbers = generator<Int> {
    perform Yield<Int>(0);
    perform Yield<Int>(1);
    perform Yield<Int>(2);
};

let mut total = 0;
for (let number in numbers) total = total + number; // 3

let firstTwo = Stream<Int>.from(numbers).take(2).toList(); // 2 elements
```

Each `iterator()` call on the source starts a fresh body, so the same source can be traversed again.
The cursor it returns is `Generator<T>`, which is a one-shot `Iterator<T>`: once it is done or
cancelled, it stays done. A cursor is deliberately not an `Iterable<T>`, so it cannot be used
directly in a `for` loop; ask the source for an iterator instead.

## Pulling by hand

A `for` loop is the usual consumer, but the cursor is an ordinary iterator, so you can pull it
manually and inspect each `Step`:

```zeron
let mut cursor = generator<Int> {
    perform Yield<Int>(4);
    perform Yield<Int>(5);
}.iterator();

let mut total = 0;
let mut finished = false;
until (finished) {
    let step = cursor.next();
    if (step matches Step.Yield(value)) {
        total = total + value;
    } else {
        finished = true;
    }
}
```

The body starts on the first `next()`. Creating a source does not run the body at all, so an
unconsumed generator does no work and raises no effects.

## Effects inside a generator body

The body may leave only `Yield<T>` and the `DisposeFailure` that `using` cleanup can raise.
Everything else must be handled inside the body, because `next()` has no effect row. A body that
reads a file therefore handles `IoFailure` itself:

```zeron
import zeron.collections.generator;
import zeron.fs.Path;
import zeron.io.IoFailure;
import zeron.io.text.readText;
import zeron.lang.Yield;

let lines = generator<String> {
    let text = handle (readText(Path.from("notes.txt"))) with {
        case IoFailure.cause(_) -> "";
    };
    for (let line in text.lines()) perform Yield<String>(line);
};
```

The generator is not lazy about that read when the body uses it this way: the read happens on the
first pull, not at creation, which is still later than eager code but earlier than the first
`Yield`. Put expensive setup after the first `perform` when it should be deferred further.

## Cleanup and cancellation

A body can own a resource with `using`. The resource is disposed whenever the cursor stops: when the
body finishes normally, when the consumer stops early, or when the cursor is cancelled. A `for`
loop closes the cursor it owns after normal exhaustion, `break`, `return`, and an exceptional exit,
so cleanup inside the body runs on every one of those paths.

`close()` performs that cleanup without reporting its failure. `cancel()` does the same but reports
cleanup failure as `DisposeFailure`, which is why a function that calls `cancel()` declares the
effect:

```zeron
let mut cursor = generator<Int> {
    perform Yield<Int>(1);
    perform Yield<Int>(2);
}.iterator();
let first = cursor.next();
cursor.cancel(); // `performs DisposeFailure` when cleanup fails
```

Abandoning a cursor without closing it also cancels it, which is what makes an early `for` exit
safe. A cursor that has already finished ignores later `close()` and `cancel()` calls, so cleanup
runs at most once.

## Composing generators

A generator body is ordinary code, so it can iterate another iterable and yield transformed values.
This is the generator counterpart of `Stream.map`:

```zeron
import zeron.collections.Iterable;
import zeron.collections.generator;
import zeron.lang.Yield;

fn doubled(values: Iterable<Int>): Iterable<Int> =
    generator<Int> {
        for (let value in values) perform Yield<Int>(value * 2);
    };
```

`doubled([1, 2, 3])` produces `2`, `4`, and `6` when consumed. The returned type is named as the
protocol, `Iterable<Int>`, so callers do not depend on the generator implementation. Nesting bodies
works the same way: an inner `for` over a generator pulls that cursor and closes it when the outer
body finishes or is cancelled.

## Generators and streams

`Stream.from(source)` wraps any `Iterable`, including a `GeneratorSource`, and the stream's operators
stay lazy. The stream asks the generator for one value at a time, and `take`, `takeWhile`, `any`, and
`all` stop pulling when they have their answer. When the stream finishes or is abandoned, it closes
the generator cursor it created, so body cleanup still runs.

The difference is what each layer owns:

- A generator body describes one pull sequence and owns its local resources.
- A stream describes a transformation pipeline over a source and owns the iterator it created.
- A `for` loop owns the iterator it created and is responsible for closing it.

Do not share one `Generator<T>` cursor between two consumers. Each consumer should ask the
`GeneratorSource<T>` for its own cursor, because a cursor's position and completion are part of its
value.

## Related

- [Collections and iteration](15-collections-and-iteration.md)
- [For-expressions](16-for-expressions.md)
- [Raised effects](22-raised-effects.md)
- [Resource management](26-resource-management.md)
