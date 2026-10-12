# 15. Collections and iteration

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

## Letting other types work with `for`

Arrays are not the only values that can be visited one item at a time. A list, a range of numbers,
or another type can also work in a `for` loop if it follows Zeron's **iteration protocol**: a
small set of operations that describes how to get items one by one.

The standard protocol is defined by two contracts in the `zeron.collections` package:

```zeron
public contract Iterable<T> {
    iterator(): &Iterator<T>;
}

public contract Iterator<T> {
    mut next(): Option<T>;
}
```

An `Iterable<T>` can make an iterator. The iterator's `next()` method returns an `Option<T>`:
`Some(item)` when there is another item, or `None` when it has reached the end. `Option` is a type
for a value that may or may not be present; it is explained in
[Option and Result](19-option-and-result.md). The loop takes care of asking for items until there
are no more.

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

The `zeron.collections` package also provides `List<T>` and `Stream<T>`. Both can be visited with
a `for` loop and can work with values of many types, but they are useful for different jobs.

Use `List<T>` when you want a growable collection that stores values and can be changed:

```zeron
import zeron.collections.List;

let mut names: &List<String> = List<String>.empty();
names.add("Ada");
names.add("Grace");
let firstName = names.at(0);
```

The list keeps its items so you can add, remove, or look them up later. Use it when you need to
build up a collection or keep one around for repeated access.

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
