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

`charAt` and `at` read one character by UTF-16 code-unit index and return `Option<Char>`, so an
out-of-range index is `None` rather than a failure. `toLowerCase` and `toUpperCase` ignore the host
locale, `trim`, `trimStart`, and `trimEnd` remove surrounding whitespace, `isBlank` reports text
that is empty or only whitespace, and `replace` replaces literal text:

```zeron
let initial = title.charAt(0).getOrElse('?');
let normalized = "  Zeron Guide  ".trim().toLowerCase();
let noSpaces = "a b c".replace(" ", "");
```

`toInt` and `toFloat` parse the whole string and return `Option`, so invalid or out-of-range text
is `None`:

```zeron
let limit = "42".toInt().getOrElse(0);
```

## Splitting, lines, and joining

Operations that produce several strings return a `Stream<String>`, so they compose with map,
filter, and the other stream operations without building a temporary array:

```zeron
let fields = "a,b,".split(",");   // "a", "b", ""
let words = "a b c".split(" ");   // one-character separator
let rows = text.lines();
```

`split` takes literal text, not a pattern, and keeps empty fields, including a leading, repeated,
or trailing one. A single-character separator is written as that text. An empty separator splits
into individual characters. `lines` ends a line at `\n`, `\r\n`, or `\r`, and a
terminal line break does not add an empty line. Neither `split` nor `lines` consumes the stream, so
`count`, `map`, or a `for` loop decides how much work happens.

`splitRegex` takes a regular expression and reports an invalid one instead of failing:

```zeron
match (text.splitRegex("[0-9]+")) {
    case Result.Ok(parts) -> println(parts.count());
    case Result.Err(RegexError.InvalidPattern(message)) -> println(message);
}
```

`String.join` puts values back together, and `StringJoiner` is the reusable collector behind it:

```zeron
let csv = String.join(", ", headers);
let path = StringJoiner.withSeparator("/").join(segments);
```

A joiner is a `Collector`, so a stream can be joined directly; see
[collections and iteration](15-collections-and-iteration.md) for `collect`.

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
