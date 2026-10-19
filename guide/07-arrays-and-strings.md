# Strings and arrays

## Text with `String`

Text values use the type `String` and are written between double quotation marks:

```zeron
let greeting: String = "Hello, Ada!";
let language = "Zeron";
```

You can combine strings with `+`:

```zeron
let message = "Hello, " + "Ada!";
```

Strings are immutable: once a string value has been created, its text does not change in place.
An operation that combines text produces a new string value, which you can give a new name.

Strings also provide a few simple operations. `length()` gives the length of the text,
`isEmpty()` tells you whether it has any text, and `contains`, `startsWith`, and `endsWith` check
for text within or at the edges of a string:

```zeron
let title = "Zeron Guide";
let titleLength = title.length();
let hasGuide = title.contains("Guide");
let startsWithZ = title.startsWith("Z");
```

These operations let you ask basic questions about text without treating it as a number or
modifying it.

## A fixed sequence with `Array<T>`

An array stores several values of one type in a fixed-size sequence. Zeron writes its array type
as `Array<T>`, where `T` is the type of each element. For example, `Array<Int>` is an array of
integers. Write an array literal in a context that supplies its element type:

```zeron
let values: Array<Int> = [10, 20, 30];
let first = values[0];
```

Array indices start at zero. For the three-element value above, valid indices are `0`, `1`, and
`2`; use `values.length` to query its size. An array's length does not grow or shrink after it is
created.

This chapter uses arrays for reading values. The next chapter explains when an array's elements
can be changed, and the difference between a read-only and mutable view. A `for` statement can
visit an array's elements one at a time; see
[expressions and control flow](05-expressions-and-control-flow.md).

Strings are immutable text, while arrays are typed storage. Use the one that fits the
data: a `String` for text, and an array for a fixed number of same-typed elements. Neither is a
growable list; the standard-library `List<T>` is introduced in
[collections and iteration](15-collections-and-iteration.md).
