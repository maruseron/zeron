# 6. Functions

A function is a named piece of a program that does one job. Give it a name, describe any
information it needs, and then call it whenever you need that job done. For example, this
function adds two numbers:

```zeron
fn add(left: Int, right: Int): Int = left + right;
```

The names `left` and `right` are parameters: they are the inputs the function receives each time
it is called. `Int` says what kind of value each input must be, and the `: Int` after the closing
parenthesis says that the function produces an integer. Call it by writing its name and supplying
the inputs:

```zeron
let total = add(20, 22);
```

Here, `20` and `22` are arguments: the actual values supplied for the parameters. The call
produces `42`, which is stored in `total`.

By convention, function and variable names use **camelCase**, as in `add`, `left`, and `total`.
Types use **PascalCase**, as in `Int` and `String`.

## Two ways to write a function body

When a function's work is one expression, write `=` followed by that expression:

```zeron
fn double(value: Int): Int = value * 2;
```

This is concise and makes the result direct: the expression after `=` is the value returned by
the function. Use this form for a small calculation or a simple choice.

When the work takes several steps, use a block in braces. A block can declare local values, make
decisions, and perform several actions. Use `return` to give back the result:

```zeron
import zeron.io.println;

fn reportTotal(left: Int, right: Int): Unit {
    let total = add(left, right);
    println(total);
}
```

`Unit` means the function is called for its action rather than for a useful result. Here, the
function calculates a total and prints it; a caller cannot use the call as a number. An explicit
return type may be omitted when it is `Unit`, but writing `: Unit` can make the purpose clearer
when learning or reading a library.

A function that produces a useful value should say what that value's type is and return one on
every path that finishes normally:

```zeron
fn larger(left: Int, right: Int): Int {
    if (left > right) return left;
    return right;
}
```

Choose an expression body when it makes a simple result easier to see. Choose a block body when
the function needs multiple steps, local names, or more than one return path. Both forms declare
ordinary named functions; the difference is how their work is written.

## Calling and organizing functions

Arguments are supplied in parameter order. The compiler checks that each call provides compatible
values for the parameters and that the function's returned value matches its declared result type.
When a call does not fit the declaration, Zeron reports the problem while building the program,
before running it.

Functions are useful for giving repeated work one clear name. Instead of copying the same
calculation into several places, write it once and call it wherever needed. Choose names that
describe the job, and keep each function focused enough that its inputs and result are easy to
understand.

## Default arguments

Some inputs have a useful usual value. Give a parameter a default so callers can leave it out
when that value is suitable:

```zeron
fn greet(name: String, greeting: String = "Hello"): String = greeting + ", " + name;

let first = greet("Ada");
let second = greet("Ada", "Welcome");
```

`greet("Ada")` uses `"Hello"` for `greeting`, while the second call supplies `"Welcome"`.
Parameters with defaults must come after parameters without defaults. Calls fill parameters from
left to right, so they can leave off a final group of arguments, but cannot skip an earlier one.
The default is evaluated each time the function is called; it can also use values from parameters
that came before it.

## Variadic functions

A function can accept a varying number of arguments with a variadic parameter. Mark its element
type with `...` after the type:

```zeron
fn count(values: Int...): Int = values.length;

let none = count();
let three = count(4, 7, 9);
```

The function can be called with zero or more integer arguments. Inside the function, `values` is an
array containing those arguments, so the function can use array operations such as `length`.
A variadic parameter must be the last parameter, and a function can have only one. It can follow
ordinary parameters, including ones with defaults.

This chapter uses top-level functions. A function can also be declared as part of a class, where it
works with an instance; those are called methods and are covered in
[classes and constructors](09-classes-and-constructors.md). Functions can later be passed as
values too; see [lambdas and function values](14-lambdas-and-function-values.md).
