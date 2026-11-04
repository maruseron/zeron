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
public type Step<T: nullable> =
      Yield(value: T)
    | Done;

public contract Iterable<T: nullable> {
    iterator(): &Iterator<T>;
}

public contract Iterator<T: nullable> {
    mut next(): Step<T>;
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
take `&Deque<Int>`. `IdentityHashMap` compares keys with `===`, so its keys need neither `Eq` nor
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
`for` loop. Use a stream to express a sequence of transformations, and use a list when you need to
store the resulting values and change or revisit them.

Both are designed to be used with iteration, but they do not have the same purpose: a list is
storage, while a stream describes a way to produce values. The next chapter introduces
[for-expressions](16-for-expressions.md), which combine iteration with transformations and
collection.
