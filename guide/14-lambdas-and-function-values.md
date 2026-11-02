# Lambdas and function values

Sometimes a function needs to do its main job while letting the caller choose one small part of
the work. For example, a program may need to apply different calculations to a number. Writing a
separate version of the main operation for each calculation would repeat code.

One solution is to pass the calculation itself to a function. In Zeron, functions can be used as
values: you can store one in a variable, pass it to another function, or return it from a
function.

## Passing a function as a value

A function type describes the inputs and result of a callable value. `(Int) -> Int` means a
function that takes an `Int` and produces an `Int`; the unary shorthand `Int -> Int` means the same
thing. A named function with that shape can be stored in a variable:

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
input types. It also supplies input types to lambdas in both branches of a conditional expression:

```zeron
fn choose(flag: Boolean): (Int) -> Int {
    return if (flag) then (value -> value + 1) else (value -> value - 1);
}
```

When there is no clear expected type, add a type annotation so Zeron can determine the lambda's
inputs and result.

Lambda parameters may be annotated explicitly when that makes their types clearer:

```zeron
let increment: Int -> Int = (value: Int) -> value + 1;
```

Function-type arrows associate to the right, so `Int -> Int -> Int` means a function from `Int` to
another function from `Int` to `Int`. A function with two inputs is still written `(Int, Int) -> Int`.

When a function parameter is the final argument, its lambda may be written after the ordinary call
arguments, or by itself when it is the only argument:

```zeron
let result = apply(21) { value -> value * 2 };
```

This is equivalent to passing the same lambda inside the call parentheses. For a unary function
parameter, the trailing lambda may omit its parameter list and use the implicit name `it`:

```zeron
let result = apply(21) { it * 2 };
```

The expected function type determines whether an arrowless trailing block has one parameter or
zero. Zero-parameter callbacks keep their existing block form; callbacks with multiple parameters
must name them explicitly.

## Receiver functions

A receiver function adds a typed receiver context to a function value. Write the receiver type
before the explicit parameter list:

```zeron
Configuration.() -> Unit
Transaction.(Request) -> Result
```

The receiver is separate from the explicit parameters and is supplied when the function is called.
An expected receiver function type contextually types an ordinary lambda literal:

```zeron
contract Counter {
    value(): Int;
}

fn evaluate(counter: Counter, body: Counter.() -> Int): Int =
    counter.body();

fn incremented(counter: Counter): Int =
    evaluate(counter, () -> value() + 1);
```

Inside that lambda, unqualified members resolve against the receiver. Parameters and local
declarations shadow receiver members; `this` refers to the current receiver. In nested receiver
lambdas, qualify an enclosing receiver as `EnclosingType.this`.

Receiver functions remain first-class values: they can be stored, passed, and returned. Invoke a
receiver function with receiver-call syntax, `receiver.callback(arguments)`. A compatible
receiver-function value in scope takes precedence over ordinary member or extension lookup. The
receiver's reference capability is preserved, and receiver functions are distinct from ordinary
functions that happen to take the receiver as their first explicit parameter; there is no implicit
conversion between the two types.

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

## Inspecting a predicate with `CodeModel`

Some APIs need to inspect a predicate instead of executing it. For example, a database library
can translate a predicate into a parameterized SQL condition. Import `CodeModel` and annotate the
parameter that should receive the model:

```zeron
import zeron.compiler.CodeModel;

fn where(
    @CodeModel predicate: CodeModel<(User) -> Boolean>
): Query<User> { ... }

query.where(user -> user.age >= minimumAge);
```

The argument must be an inline lambda. The compiler resolves and type-checks it normally, then
builds an immutable model with typed nodes for parameter references, literals, property access,
comparisons, Boolean operators, null tests, and immutable captures. Captured values are evaluated
once when the call is made and are available separately from the expression tree, so a query
library can bind them as SQL parameters rather than embedding them in generated SQL.

The initial model expression subset does not include arithmetic, method calls, mutation, or
control flow; unsupported expressions are rejected rather than executed. A `CodeModel<F>` is not
an executable function. If an API needs both the model and a callback, declare separate parameters
for them.

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

Receiver functions have a distinct generated shape. Their receiver is the first runtime argument,
followed by their explicit parameters.

These generated names and interfaces are implementation details. In Zeron source, a function type
is described by its inputs and result, not by the name of a generated runtime interface.
