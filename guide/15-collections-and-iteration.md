# Collections and iteration

Arrays are useful when a program needs to keep several values of the same type. You can visit
each item in an array with a `for` loop:

```zeron
let scores = [8, 10, 9];
let mut total = 0;

for (let score in scores) {
    total += score;
}
```

The loop runs once for each array element, with `score` holding the current item. You do not have
to manage an index or write the stopping condition yourself.

Arrays can also be passed anywhere an `Iterable<T>` is expected:

```zeron
import zeron.collections.Iterable;

fn sum(values: Iterable<Int>): Int {
    let mut total = 0;
    for (let value in values) {
        total += value;
    }
    return total;
}

let result = sum([8, 10, 9]);
```

The compiler creates a lightweight adapter for each conversion. It keeps the original array rather
than copying its elements, so later writes through a mutable alias remain visible. A `for` loop
directly over an array still uses intrinsic array iteration and does not create an adapter.

## Letting other types work with `for`

Arrays are not the only values that can be visited one item at a time. A list, a range of numbers,
or another type can also work in a `for` loop if it follows Zeron's **iteration protocol**: a
small set of operations that describes how to get items one by one.

The standard protocol is defined by two contracts in the `zeron.collections` package:

```zeron
public type Step<T admits nullable> =
      Yield(value: T)
    | Done;

public contract Iterable<T admits mutable> {
    iterator(): &Iterator<T>;
}

public contract Iterator<T admits nullable + mutable> {
    mut next(): Step<T>;
    default mut close(): Unit = ();
}
```

An `Iterable<T>` can make an iterator. The iterator's `next()` method returns a `Step<T>`:
`Step.Yield(item)` when there is another item, or `Step<T>.Done()` when it has reached the end.
This iterator-specific result keeps exhaustion separate from `Option<T>`, which represents the
presence or absence of a value and is explained in [Option and Result](19-option-and-result.md).
The loop takes care of asking for items until there are no more.

An iterator is mutable because it remembers its position and moves forward each time `next()` is
called. If you define a type that conforms to `Iterable<T>` and returns an appropriate iterator,
your type can be used in `for` loops too.

`close` releases a cursor's resources. Its default does nothing, so ordinary iterators need no
implementation. `for` calls it on the cursor it owns after exhaustion, `break`, `return`, and an
exceptional exit, and the `Stream` adapters forward it to their upstream iterator. A resource-backed
iterator should make `close` idempotent.

## Pull generators

`generator<T>` turns a body that performs `Yield<T>` into a replayable source:

```zeron
import zeron.collections.generator;
import zeron.lang.Yield;

let body: () -> Unit performs Yield<Int> = () -> {
    perform Yield<Int>(0);
    perform Yield<Int>(1);
    perform Yield<Int>(2);
};
let numbers = generator<Int>(body);

for (let number in numbers) println(number);
```

The body starts on the first `next()` and stays suspended between pulls. Each `iterator()` call
starts a fresh body, so the source can be traversed repeatedly; the returned `Generator<T>` is a
one-shot cursor with `cancel()` and `isDone()`. `cancel()` reports cleanup failure as
`DisposeFailure`; `close()`, which `for` uses, does not. The body may leave only `Yield<T>` and the
`DisposeFailure` that `using` cleanup can raise unhandled, because `next()` has no effect row. The
signature also accepts a trailing lambda: `generator<Int> { perform Yield<Int>(1); }`.

## Ranges of integers

Zeron's integer range syntax is a convenient way to visit a series of integers:

```zeron
let mut total = 0;
for (let number in 1..5) {
    total += number;
}
```

The `..` range includes both endpoints, so this loop visits `1`, `2`, `3`, `4`, and `5`. A range
can count down as well:

```zeron
for (let number in 3..1) {
    // visits 3, then 2, then 1
}
```

Both endpoints are ordinary `Int` expressions, evaluated once when the range is created, so a
range can be sized from the data it walks:

```zeron
let values: Array<Int> = [4, 7, 9];
for (let index in 0..values.length - 1) {
    println(values[index].toString());
}
```

`..` binds more loosely than arithmetic and groups to the right, so `0..values.length - 1` is
`0..(values.length - 1)`. Parenthesize a range before applying an operator to it.

Ranges are iterable, so they use the same `for` loop behavior without needing to create an array
of all the numbers first. The `IntRange` type is provided in `zeron.ranges`.

## Lists and streams

The `zeron.collections` package also provides `ArrayList<T>`, `HashSet<T>`, `HashMap<K, V>`, and
`Stream<T>`. All of them can be visited with a `for` loop, but they are useful for different jobs.

`List<T>`, `Set<T>`, and `Map<K, V>` are contracts, so a function can accept any implementation.
The standard library ships one implementation of each: `ArrayList<T>` keeps its elements in order
and can be indexed, `HashSet<T>` holds distinct values, and `HashMap<K, V>` associates keys with
values. Build a collection through its class:

```zeron
import zeron.collections.ArrayList;

let mut names: &ArrayList<String> = ArrayList<String>.empty();
names.add("Ada");
names.add("Grace");
let firstName = names.at(0);
```

The list keeps its items so you can add, remove, or look them up later. Use it when you need to
build up a collection or keep one around for repeated access.

A read-only parameter names the contract, so every implementation can be passed:

```zeron
import zeron.collections.List;

fn total(values: List<Int>): Int {
    let mut sum = 0;
    for (let value in values) sum = sum + value;
    return sum;
}
```

Every `Iterable` also offers the terminal operations `forEach`, `fold`, `any`, `all`, `count`, and
`isEmpty`. The callback-taking ones are effect-row-polymorphic: their callback runs during the
call, so its effects become part of the caller's row, and `any`/`all` stop at the first decisive
element.

Mutation is expressed by capability contracts rather than by one mutable collection contract. A
function that adds elements takes `Sink<T>`, one that edits by position takes
`PositionalEditor<T>`, one that writes by key takes `KeyedSink<K, V>`, and one that drops a value
takes `Removable<T>`. `Sized`, `RandomAccess`, `Reversible`, `Sortable`, and `Clearable` describe
the remaining optional capabilities.

The standard library also ships baseline alternative implementations, which show that the contracts
do not prescribe storage:

| Type | Representation | Notable capabilities |
| --- | --- | --- |
| `LinkedList<T>` | mutable doubly linked list | `List`, `Sequenced`, `Deque`, `Sink`, `Clearable` |
| `ArrayDeque<T>` | mutable circular array | `List`, `Sequenced`, `Deque`, `RandomAccess`, `Sink`, `Clearable` |
| `ConsList<T>` | persistent singly linked list | `List`, `Sequenced`; `prepend` returns a new list |
| `IdentityHashMap<K, V>` | hash table keyed by reference identity | `Map`, `KeyedSink`, `Sized`, `Clearable` |

`Deque<T>` is a capability contract requiring `List<T> + Sequenced<T>`, with `addFirst`, `addLast`,
`removeFirst`, and `removeLast`. `LinkedList` and `ArrayDeque` implement it; `ConsList` does not,
because a persistent list cannot mutate its ends. A function that only needs those operations can
take `&Deque<Int>`.

Sorting is split the same way. `Sortable<C<_>>` supplies a sorted copy through `sorted` and
`sortedBy(compare)`, while `InPlaceSortable<C<_>>` supplies `sort` and `sortBy(compare)` for
collections that can reorder themselves. The standard library provides `Sortable` for `ArrayList`,
`LinkedList`, and the persistent `ConsList`, and both capabilities for the mutable `ArrayList` and
`LinkedList`. `sorted` and `sort` require the element type to implement `Comparable`, while
`sortedBy` and `sortBy` accept any comparator. The standard implementations use an unstable sort,
so equal elements may change relative order.

```zeron
import zeron.collections.ArrayList;

let ordered = names.sorted();
let mut values = ArrayList.of(3, 1, 2);
values.sortBy((a, b) -> b.compareTo(a)); // 3, 2, 1
``` `IdentityHashMap` compares keys with `===`, so its keys need neither `Eq` nor
`Hashable`, and two structurally equal objects are distinct keys.

```zeron
import zeron.collections.ArrayDeque;

let mut deque = ArrayDeque<Int>.empty();
deque.addFirst(1);
deque.addLast(3);
let first = deque.removeFirst();
```

A `Stream<T>` is useful when you want to describe steps for producing or transforming values. Its
operations include `map`, `filter`, `flatMap`, `take`, `drop`, `takeWhile`, and `dropWhile`:

```zeron
import zeron.collections.Stream;

let doubled = Stream<Int>.fromArray([1, 2, 3])
    .map(value -> value * 2)
    .filter(value -> value > 2);
let results = doubled.toList();
```

The stream describes the work to do; `map` and `filter` do not immediately build a new list.
Values are requested when an operation consumes the stream, such as `toList()`, `count()`, or a
`for` loop. `sortedBy(compare)` is a stateful intermediate operation: the first traversal buffers
every element, sorts the buffer, and then yields the elements in order, so it costs `O(n)` extra
space per traversal.

```zeron
let byLength = Stream<String>.fromArray(["ccc", "a", "bb"])
    .sortedBy((a, b) -> a.length().compareTo(b.length()))
    .toList();
``` Use a stream to express a sequence of transformations, and use a list when you need to
store the resulting values and change or revisit them.

`collect` reduces a stream through the operations that build a collector: an initial accumulator, a
step that combines the accumulator with an element, and a conversion of the final accumulator.

```zeron
let total = numbers.collect(0, (sum, value) -> sum + value, (sum) -> sum * 2);
```

`collectWith` takes a collector that is already built: a value that adds items with `add` and
returns its result from `finish`. The collector owns how items accumulate, so the call site passes
no functions at all:

```zeron
let csv = names.collectWith(StringJoiner.withSeparator(", "));
```

A collector is an ordinary value: `add` returns the updated collector instead of changing the old
one, so the same collector can start as many accumulations as you like.

The `Collector` namespace also holds the collectors for the concrete collections and for numeric
summaries, so a stream is gathered without naming the target class at the call site:

```zeron
import zeron.collections.Collector;

let list = values.collectWith(Collector.toArrayList<Int>());
let queue = values.collectWith(Collector.toLinkedList<Int>());
let chain = values.collectWith(Collector.toConsList<Int>());
let set = values.collectWith(Collector.toHashSet<Int>());
let map = entries.collectWith(Collector.toHashMap<String, Int>());
let sum = values.collectWith(Collector.summing<Int>());
```

`toArray`-style collectors fill a mutable collection and hand back a mutable reference to it, so
the result can be read and extended in place. `toHashMap` needs `Hashable` keys, `toHashSet` needs
`Hashable` elements, and `summing` works for any `Numeric<T>`, starting from its zero.

These are the canonical `Collector.of` implementation wherever the accumulation allows it, and
`Collector.of` also builds a collector from an initial accumulator and two functions when no
dedicated implementation exists:

```zeron
let bracket = Collector.of<String, Int, String>("",
    (text, value) -> text + value.toString(), (text) -> "[" + text + "]");
let text = numbers.collectWith(bracket);
```

## Collections of anonymous existential elements

A collection element type can be a stored anonymous existential. Each element then keeps its own
hidden contract evidence or `reified` descriptor:

```zeron
import zeron.collections.ArrayList;

public contract Render {
    render(): String;
}

public class Text is Render {
    public constructor new;
    public render(): String = "text";
}

public class Box<T> {
    public property value: T;
    public constructor new;
}

let mut boxes: &ArrayList<Box<: Render>> =
        ArrayList.of(Box.new(Text.new()));
boxes.add(Box.new(Text.new()));
for (let box in boxes) {
    let rendered = box.value.render();
}
```

`ArrayList` stores one holder per element; construction, `add`, `at`, iteration, nested
collections, and separately compiled collection fields pack and unpack those holders. A
marker-only element existential keeps the erased payload. Callback operations such as `map` and
`filter` are not part of this first slice because their callback parameter would itself carry a
stored existential.

Both are designed to be used with iteration, but they do not have the same purpose: a list is
storage, while a stream describes a way to produce values. The next chapter introduces
[for-expressions](16-for-expressions.md), which combine iteration with transformations and
collection.
