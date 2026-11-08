# Functional programming essentials

Many programs are easier to understand when they are built from small functions that take values
and return values, and from data that does not change underneath them. This style is called
**functional programming**. Zeron is not a purely functional language, so you can mix it with the
imperative style you have already seen. This chapter introduces the functional style one idea at a
time, starting with the simplest and finishing with ideas that need most of the earlier chapters.

Later sections build on earlier ones, so read them in order. The final section collects exercises.

## Pure functions

A function is **pure** when its result depends only on its arguments and it does nothing else that
the rest of the program can observe. Calling a pure function twice with the same arguments always
gives the same answer, and nothing else changes:

```zeron
fn double(value: Int): Int = value * 2;

fn greeting(name: String): String = "Hello, " + name;
```

A function that prints, writes a file, or changes a value outside itself has a **side effect**, so
it is not pure:

```zeron
import zeron.io.println;

fn announce(name: String): Unit {
    println("Hello, " + name);
}
```

Neither style is wrong. Pure functions are easier to test, because you only supply arguments and
check the result, and easier to reuse, because they cannot surprise their caller. A good habit is to
put calculations in pure functions and keep the side effects in a few places at the edge of your
program. [Functional abstractions](37-functional-abstractions.md) returns to this idea.

## Values that do not change

A `let` binding cannot be reassigned. A `let mut` binding can, and a loop that accumulates a result
needs one:

```zeron
let scores = [8, 10, 9];
let mut total = 0;

for (let score in scores) {
    total += score;
}
```

The functional style prefers to describe the result instead of building it up step by step. Later
sections show how to write the same calculation without `let mut`. A related rule from
[Lambdas and function values](14-lambdas-and-function-values.md) points the same way: a lambda
copies the values it captures and cannot change a `let mut` local from the surrounding code.
Functional code seldom needs shared changing state, and when it does, that state is placed in an
object whose reference capability says who may change it, as described in
[References and mutation](08-references-and-mutation.md).

## Functions as values

A function can be stored in a variable, passed as an argument, and returned from another function.
The function type `Int -> Int` describes a function that takes an `Int` and returns an `Int`:

```zeron
let operation: Int -> Int = double;
let result = operation(21);
```

You can write a small function directly where it is needed, without a declaration name. This is a
**lambda**:

```zeron
let triple: Int -> Int = value -> value * 3;
```

Chapter 14 covers lambda syntax in detail. The rest of this chapter uses lambdas constantly.

## Higher-order functions and closures

A function that takes a function, or returns one, is a **higher-order function**. `apply` lets the
caller choose one step of the work:

```zeron
fn apply(value: Int, transform: Int -> Int): Int = transform(value);

let a = apply(5, double);
let b = apply(5) { value -> value + 1 };
let c = apply(5) { it * 3 };
```

A function can also build and return a new function. The returned lambda remembers the values it
uses from the function that created it. This is called a **closure**:

```zeron
fn adder(offset: Int): Int -> Int = value -> value + offset;

let addTen = adder(10);
let result = addTen(5);
```

`addTen` is a function that always adds `10`, because the lambda captured `offset` when `adder(10)`
was called. Each call to `adder` makes a separate function with its own captured value.

A closure can also hold on to an object and change it. `memoize` in `zeron.function` wraps a
function and returns a new one that remembers each answer in a map it captured. Calling the new
function with the same input twice runs the original only once. From the outside it still behaves
like the pure function it wraps, but inside it keeps changing state, which is the kind of shared
state [Lambdas and function values](14-lambdas-and-function-values.md) describes. Keep that state
inside the closure and the rest of your program stays functional.

## Composition and currying

Small functions become useful when you combine them. The `zeron.function` package provides two
ways to join functions. `pipe` takes two functions and returns one that runs the first and then the
second. `compose` takes the same functions in the opposite order, as in mathematical notation, so
the function written last runs first:

```zeron
import zeron.function.compose;
import zeron.function.pipe;

fn increment(value: Int): Int = value + 1;

let doubleThenIncrement = pipe(double, increment);
let sameThing = compose(increment, double);
let result = doubleThenIncrement(5);
```

Both lines build a function that doubles first and then adds one, so `result` is `11`. Choose
`pipe` when you want the steps to read in the order they run, and `compose` when you are following
a formula such as `increment(double(x))`. Both are generic, so they work for any matching types:
the output type of the first function to run must be the input type of the second. Writing `pipe`
by hand shows there is no magic in it:

```zeron
fn pipeByHand<A, B, C>(first: A -> B, second: B -> C): A -> C =
    value -> second(first(value));
```

Function-type arrows associate to the right, so `Int -> Int -> Int` means a function that takes an
`Int` and returns another function. Writing a function this way is called **currying**, and it
lets you supply arguments one at a time:

```zeron
fn add(left: Int): Int -> Int = right -> left + right;

let incrementByOne = add(1);
let sum = incrementByOne(2);
```

The standard library's `curry` turns an existing two-parameter function into this form, and `flip`
swaps the order of a function's two parameters:

```zeron
import zeron.function.curry;

let addCurried: Int -> Int -> Int = curry<Int, Int, Int>((left, right) -> left + right);
let addTen = addCurried(10);
```

Bind the result of a call to a name before you call it, as `addTen` does here: the language does not
yet allow calling the result of a call directly. A function with two parameters is still written
`(Int, Int) -> Int`, and it is a different type from the curried form. Use whichever is clearer at
the call site.

Predicates, which are functions that return `Boolean`, have their own combiners: `negate` inverts a
predicate, and `both` and `either` join two predicates with `and` and `or`, evaluating them in order
and stopping early:

```zeron
import zeron.function.both;

let isPositive: Int -> Boolean = number -> number > 0;
let isEven: Int -> Boolean = number -> number % 2 == 0;
let isPositiveEven = both(isPositive, isEven);
```

The same package offers `identity`, which returns its argument, and `constant`, which ignores its
input and always returns one value. They are small, but they are useful as arguments to other
higher-order functions.

## Transforming collections

Most data processing is a few steps repeated over a collection: keep some values, change each one,
and combine the results. These three operations are named `filter`, `map`, and `fold`, and they are
all higher-order functions. A `Stream<T>` offers them, as introduced in
[Collections and iteration](15-collections-and-iteration.md):

```zeron
import zeron.collections.Stream;

let total = Stream.fromArray([1, 2, 3, 4, 5])
    .filter(number -> number % 2 == 1)
    .map(number -> number * number)
    .fold(0) { sum, number -> sum + number };
```

`filter` keeps the odd numbers, `map` squares each one, and `fold` combines them into one value,
starting from `0`. Each step takes the previous step's result and returns a new value; nothing is
reassigned, and there is no `let mut`. The stream is lazy, so `filter` and `map` do their work only
when `fold` asks for the values.

A for-expression, introduced in [For-expressions](16-for-expressions.md), expresses the same
pipeline as clauses:

```zeron
let squares = for number in [1, 2, 3, 4, 5]
    if number % 2 == 1
    yield number * number;
```

Use method chains when each step is a single operation, and a for-expression when you need nested
sources or intermediate `let` bindings. Both produce a lazy `Stream<T>`.

## Modeling data with sum types

Functional programs describe data as a set of alternatives and then write one function that handles
every alternative. A **sum type** lists its cases, and `match` handles each one:

```zeron
import zeron.math.PI;

type Shape =
      Circle(radius: Float)
    | Rectangle(width: Float, height: Float);

fn area(shape: Shape): Float =
    match (shape) {
        case Circle(radius) -> PI * radius * radius;
        case Rectangle(width, height) -> width * height;
    };
```

Because `match` must cover every case, adding a new case to `Shape` makes `area` stop compiling
until you handle it. The compiler points you to every place that needs updating.
[Products, data classes, and sum types](17-sum-types-and-products.md) and
[Pattern matching](18-pattern-matching.md) describe these in full.

## Absence and failure as values

A pure function cannot print an error or abandon the program when something goes wrong, so it
reports the problem in its return value. `Option<T>` stands for a value that may be missing, and
`Result<T, E>` for a success or an error that explains what went wrong. Chapter 19 introduces both.

Their operations let you work on the success path without checking the result at every step. `map`
changes the value inside, and `getOrElse` supplies a fallback:

```zeron
fn findName(id: Int): Option<String> =
    if (id == 1) 
    then Option.Some("Ada");
    else Option<String>.None();

let greeting = findName(1)
    .map(name -> "Hello, " + name)
    .getOrElse("Hello, stranger");
```

If `findName` returns `None`, `map` leaves it unchanged and `getOrElse` provides the fallback. No
step needs to test for absence.

For operations that can each fail, `andThen` chains them. If any step returns `Err`, the later steps
are skipped and that error comes out at the end:

```zeron
fn checkPositive(value: Int): Result<Int, String> =
    if (value > 0) 
    then Result<Int, String>.Ok(value)
    else Result<Int, String>.Err("not positive");

fn half(value: Int): Result<Int, String> =
    if (value % 2 == 0) 
    then Result<Int, String>.Ok(value / 2)
    else Result<Int, String>.Err("odd number");

let outcome = checkPositive(8)
    .andThen(half)
    .map(number -> number + 1);
```

Here `outcome` is `Ok(5)`. Passing `half` by name works because a function with the right shape is a
value. Replace `8` with `7` and `outcome` becomes `Err("odd number")`: `half` fails, so `map` is
never applied. `Result.fold` and `Option.fold` turn a value into one final answer once you reach
the edge of the pipeline.

Typed raised effects, in [Raised effects](22-raised-effects.md), are a third way to report failure.
They transfer control to a handler instead of returning a value, and
[Functional abstractions](37-functional-abstractions.md) shows how they combine with higher-order
functions.

## Recursion

A function can describe a calculation in terms of a smaller version of itself:

```zeron
fn factorial(number: Int): Int =
    if (number <= 1) then 1
    else number * factorial(number - 1);
```

Recursion is the natural fit for data that is itself nested, such as a tree:

```zeron
type Tree =
      Leaf(value: Int)
    | Node(left: Tree, right: Tree);

fn sum(tree: Tree): Int =
    match (tree) {
        case Leaf(value) -> value;
        case Node(left, right) -> sum(left) + sum(right);
    };
```

Zeron does not optimize tail calls. A recursive call always uses another stack frame, even when it
is the last thing the function does, so a recursion that goes very deep can overflow the stack.
Writing `factorial` with an accumulator parameter does not change that. Keep these rules in mind:

- Recurse over data whose depth is small and bounded, such as a tree you built yourself.
- To walk a long or unbounded sequence, use `fold`, a stream, or a `for` loop. They run in constant
  stack space.
- When a recursive function would be easier to read as a `fold`, prefer the `fold`.

## Practice

1. Write `twice(f: Int -> Int): Int -> Int` that applies `f` two times. Use it to build
   `addFour` from `adder(2)`, and then write the same function with `pipe`.
2. Write your own generic `flip<A, B, C>(f: (A, B) -> C): (B, A) -> C`, then compare it with the
   one in `zeron.function`.
3. Rewrite the first `for` loop of this chapter, the one that sums `scores`, with `fold`.
4. Use `filter` and `map` to produce the squares of the even numbers in `[1, 2, 3, 4, 5, 6]`.
5. Add a `Triangle(base: Float, height: Float)` case to `Shape`, and let the compiler show you what
   else must change.
6. Write `parseAge` so it returns `Result<Int, String>`, rejecting negative numbers, and chain it
   with `half` using `andThen`.
7. Write `depth(tree: Tree): Int` that returns the number of levels in a tree.
