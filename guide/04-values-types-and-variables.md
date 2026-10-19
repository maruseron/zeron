# Values, types, and variables

A value is a piece of data the program can use. The number `3`, the text `"hello"`, and the
Boolean value `true` are all values. A type describes what kind of value something is and which
operations make sense for it. For example, `Int` is the type of whole numbers, `String` is the
type of text, and `Boolean` is the type of `true` and `false`.

You can give a value a name by creating a binding with `let`:

```zeron
let count: Int = 3;
let greeting: String = "hello";
let isReady: Boolean = true;
```

By convention, variable names such as `count`, `greeting`, and `isReady` use **camelCase**: the
first word starts with a lowercase letter, and each following word starts with a capital letter.
Type names such as `Int`, `String`, and `Boolean` use **PascalCase**, where every word starts with
a capital letter.

The part after each name's colon states its type. Zeron uses that information to check that later
uses make sense. For example, adding two `Int` values produces another `Int`, while using a
`String` where an `Int` is required is a type mismatch.

## Letting the compiler work out a type

When the value on the right makes its type clear, you can leave the annotation out:

```zeron
let count = 3;
let greeting = "hello";
```

Here, Zeron infers that `count` is an `Int` and `greeting` is a `String`. Inference just saves you
from repeating information the compiler can already determine. The binding still has a specific
type; the compiler checks that you use it consistently. Add an annotation when it makes your
intention clearer or when the initial value does not provide enough information.

For example, an empty array has no elements from which to infer its element type, so give the
binding a type:

```zeron
let empty: Array<Int> = [];
```

An array is a fixed-size sequence of values, all with the same type. An array literal uses square
brackets, and the type is written `Array<T>` with the element type in place of `T`:

```zeron
let scores: Array<Int> = [8, 10, 9];
let firstScore = scores[0];
```

The name `scores` now refers to an array of integers, and indexing it reads one element. Arrays
are useful when the number of elements is fixed. Their indexing and relationship to other
sequences are covered in [arrays and strings](07-arrays-and-strings.md).

## Names that can be reassigned

A `let` binding cannot be assigned a new value after it is created. Use `let mut` when the name
needs to hold a different value later:

```zeron
let mut total = 0;
total = total + 3;
total += 2;
```

The type remains `Int`; `mut` allows the name to be updated, not changed to an unrelated type.
Choose `let` when a name should continue to refer to its original value, and `let mut` when the
computation needs to keep track of changing state. These examples are about reassigning a name;
how objects can be changed is a separate subject in
[references and mutation](08-references-and-mutation.md).

## Values that may be absent

Most types describe values that must be present. Add `?` to say that a value may instead be
`null`: `String?` means either a string or no string. The nullable and non-null forms are
different types:

```zeron
let name: String = "Ada";
let optionalName: String? = null;
let anotherOptionalName: String? = "Grace";
```

Use a nullable type when absence is a meaningful possibility, such as an optional name or search
result. A non-null `String` cannot be `null`; code that may have no string should say `String?`
so that possibility is visible in its type. The ways to check and safely use a nullable value are
covered later in the guide.

## When an operation has no useful result: `Unit`

Some operations are useful for what they do rather than for a value they produce. Printing text is
an example: the important result is the visible output, not a new piece of data to store. Zeron
uses the type `Unit` for this kind of no-useful-result outcome. Its single value is written `()`.

`Unit` gives action-only operations a consistent type, just as `Int` describes whole numbers.
You will see it on declarations that perform an action but do not return information for the
caller to use. Functions and their result types are introduced in a later chapter.
