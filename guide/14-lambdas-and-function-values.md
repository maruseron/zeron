# Lambdas and function values

Sometimes a function needs to do its main job while letting the caller choose one small part of
the work. For example, a program may need to apply different calculations to a number. Writing a
separate version of the main operation for each calculation would repeat code.

One solution is to pass the calculation itself to a function. In Zeron, functions can be used as
values: you can store one in a variable, pass it to another function, or return it from a
function.

## Passing a function as a value

A function type describes the inputs and result of a callable value. `(Int) -> Int` means a
function that takes an `Int` and produces an `Int`. A named function with that shape can be stored
in a variable:

```zeron
fn double(value: Int): Int = value * 2;

let operation: (Int) -> Int = double;
let result = operation(21);
```

Here, `operation` holds the `double` function, so calling `operation(21)` produces `42`.
Writing down the function's type makes clear what can be passed in and what comes back.

A function can also receive another function as an input:

```zeron
fn apply(value: Int, transform: (Int) -> Int): Int =
    transform(value);

let result = apply(21, double);
```

`apply` does not need to know which calculation it will receive. It calls `transform` with the
value, and the caller chooses the behavior by supplying a function. This pattern is useful when
the surrounding task stays the same but one step varies, such as transforming each item or
checking whether an item meets a condition.

## Writing a lambda where you need it

For a small piece of behavior used in one place, declaring a separate named function can feel like
extra work. A **lambda** is a function written directly where it is needed, without giving it a
declaration name:

```zeron
let result = apply(21, value -> value * 2);
```

The part before `->` names the lambda's input; the expression after it is its result. Here the
lambda does the same calculation as `double`, but it is written right in the call. The expected
function type `(Int) -> Int` tells Zeron that `value` is an `Int`.

Lambdas can have more than one input, or none:

```zeron
let add: (Int, Int) -> Int = (left, right) -> left + right;
let readValue: () -> Int = () -> 42;
```

An expected function type, such as the annotation on each variable above, usually supplies the
input types. When there is no clear expected type, add a type annotation so Zeron can determine
the lambda's inputs and result.

## Using values from the surrounding code

A lambda can also use names that were declared around it. This is called **capturing** those
values:

```zeron
fn addOffset(value: Int, offset: Int): Int {
    let adjust = item -> item + offset;
    return adjust(value);
}
```

The lambda uses `offset` even though `offset` is not one of its own inputs. It captures the value
of `offset` from the surrounding function. The captured value is copied when the lambda is
created, and the lambda cannot change the surrounding variable.

For that reason, a lambda cannot capture a `let mut` local and change or reassign it. If you need
shared changing state, put that state in an object or another explicitly shared value and capture
its reference. The object's reference capability still determines whether the lambda may change
the object; capturing a reference does not grant extra permission.

## Named functions or lambdas?

Use a named function when its behavior is useful in several places, when its name helps explain
what it does, or when it needs its own generic type parameters. Use a lambda for a short,
one-off operation that is easiest to understand next to the call that uses it. Either way, a
function value has a type that describes its inputs and result.

## How lambdas are represented

At runtime, Zeron represents each distinct function shape with a generated interface that has one
callable method. Lambdas with the same input and result types share that interface, while each
lambda keeps its own behavior. When a lambda captures values, those values are stored with the
function object when it is created.

These generated names and interfaces are implementation details. In Zeron source, a function type
is described by its inputs and result, not by the name of a generated runtime interface.
