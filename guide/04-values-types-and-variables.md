# Values, types, and variables

In the [last chapter](02-your-first-program.md), you wrote this line:

```zeron
println("Hello, world!");
```

The text `"Hello, world!"` is a _value_: a piece of data your program can use. In that example, you
handed it to `println`, which displayed it. Most of what a program does follows that pattern: it
creates values, keeps track of them, and does something with them. There are all kinds of other
values, like the number `3` or the logic value `true`.

Not every value can be treated the same way. You may add two numbers (`1 + 2` is `3`), but adding
two pieces of text is a different matter (`"chocolate" + " cake"` is `"chocolate cake"`!), and
there's no way to confidently assert what adding `true` and `false` means. A _type_ describes what
kind of value something is and which operations make sense for it. `Int` is the type of whole
numbers (integers), `String` is the type of text, and `Boolean` is the type of `true` and `false`.

Finally, a program usually needs to remember a value so it can use it again later. You can do that
by giving the value a name. In programming, this is called a variable, and in Zeron you declare one
of those with `let`:

```zeron
let count: Int = 3;
let greeting: String = "hello";
let isReady: Boolean = true;
```

A `let` declaration is also called is also called a _binding_, as it binds a name to a value.
The part after each name's colon states its type. Zeron uses that information to check that later
uses make sense. For example, adding two `Int` values produces another `Int`, while using a
`String` where an `Int` is required is an error: types do not match. The part after the equals sign
is the value the variable is meant to hold, called an _initializer_.

## Letting the compiler work out a type

The incredibly smart Zeron compiler is able to tell the types of variables whose initializers are
present, most of the time:

```zeron
let count = 3;
let greeting = "hello";
```

Here, Zeron infers that `count` is an `Int` and `greeting` is a `String`. This is called type
inference. Omitting the type does _not_ mean that the variable doesn't have one, or that it can
change. It still has a specific type - the compiler is just saving you from the work of having to
state it explicitly. You should still add a type annotation when it makes your intention clearer,
or when the initial value does not provide enough information. For example:

```zeron
let x = null; // error! what type is this?
```

To fix this error, adding the type annotation back should do the trick:

```zeron
let x: Int? = null; // works!
```

## Names that can be reassigned

A `let` binding cannot be assigned a new value after it is created. Different languages call this
idea by different names: constants, values, immutable variables, etc. If you ever need to change the value of a name later on, a `let mut` ("mut" as in "mutable") binding lets you do that:

```zeron
let mut total = 0;
total = total + 3;
total += 2; // shorthand for total = total + 2!
```

The type of `total` is still inferred as `Int`; `mut` allows the value to be updated, not changed
to an unrelated type. Use `let` when a name should continue to refer to its original value, and
`let mut` when the calculation needs to keep track of changing state. This is intentional: most
variables you'll ever declare won't need to be reassigned, so you explicitly mark those that will.

This section is about reassigning the value of a name; how values themselves can be modified is a
separate subject in [references and mutation](08-references-and-mutation.md).

## Values that may not be there

Most types describe values that must be present. Note the word most: this is not always the case.
Tag a type with `?` to say that a value may instead be `null`. `String?` means either a string or
nothing. The nullable and non-null forms are different types:

```zeron
let name: String = "Ada";
let optionalName: String? = null;
let anotherOptionalName: String? = "Grace";

let wrong: String = null; // error!
```

Use a nullable type when absence is a meaningful possibility, such as an optional field for a form.
A non-null `String` cannot be `null`; code that may have no string instead must say `String?` so
that possibility is visible in its type. The ways to check and safely use a nullable value are
covered later in the guide.

## Naming conventions

Languages usually have tacit rules about how to name things. In Zeron, variable names such as
`count`, `greeting`, and `isReady` follow _camelCase_: the first word starts with a lowercase
letter, and each following word starts with a capital letter. Types, on the other hand, follow
_PascalCase_, also known as _UpperCamelCase_, where every world starts with a capital letter,
including the first one. Type names such as `Int`, `String`, and `Boolean` follow this rule by
default (not like they have a choice) but, as we'll later see, user-defined types such as
`AbstractFinalDeserializedModelObject` should follow the rule as well. Thankfully, it doesn't
exist in Zeron, but you can learn about user-defined types in the chapters for
[classes](09-classes-and-constructors.md) and
[other very equally important data types](17-sum-types-and-products.md).

## When an operation has no useful result: `Unit`

Some operations are useful for what they do rather than for a value they produce. Printing text is
an example: the important result is the visible output, not a new piece of data to store. Zeron
uses the type `Unit` for this kind of no-useful-result outcome. Its single value is written `()`.

`Unit` gives action-only operations a consistent type, just as `Int` describes whole numbers.
You will see it on declarations that perform an action but do not return information for the
caller to use. Functions and their result types are introduced in a later chapter.

If you're familiar with C-like languages, you might be wondering why use `Unit` instead of `void`.
That is covered in [this chapter](35-why-unit.md).
