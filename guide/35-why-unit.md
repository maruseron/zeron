# Why unit?

If you're familiar with a language like C, Java, or C#, then you are also most likely used to the
type `void` as the "this function returns nothing" type. While this is undoubtedly useful, there
are certain asymmetries to `void` that are better addressed by unit, which will be explored in
this chapter.

## 1. `void` isn't really a type

`void` is, in general terms, a pseudo-type. More than a real feature of the type system, `void` is
a syntactic marker:

- You cannot declare a variable of type void: `void v = ...`. This makes sense: if a type indicates
  the shape of the value contained, what is the shape of nothingness?
- You often cannot use it as a generic type argument: `List<void>` is illegal in both Java and C#.
  Java solves this issue by having `Void`: a standard library class with a private constructor and
  no instances, effectively only allowing null. If you think about it, that's the `Unit` type.
- `void` cannot be stored in a data structure or returned from a lambda uniformly, which leads to
  all kinds of tricks to circumvent this shortcoming.

This asymmetry is deeply inconvenient: an operation like `stream.map(println)` can never work if the
return type of `println` is `void`. This hole in the type system is pervasive, and shows up in all
kinds of places when you try to design an API.

## 2. What `Unit` is

`Unit` is a type with exactly one inhabitant, or value. Different languages call it and represent
it in different ways, but Zeron follows the functional programming tradition: `Unit` is the type,
and `()` is the single value it can hold: the unit.

```zeron
fn log(msg: String): Unit = println(msg); // returns ()
```

In algebraic terms, `Unit` is the 1 type: its cardinality is 1. Contrast it with:

| Type | Inhabitants | Cardinality |
| - | - | - |
| `Never`/`Nothing`/`void` | none | 0 |
| `Unit` | one | 1 |
| `Boolean` | two | 2 |

The table above reveals an additional weakness of `void`: it has no values, like `Never`, yet it is
used for functions that return normally, like `Unit`. Since it is not a real type, it cannot
express either concept properly, and the type system loses the ability to distinguish "returns
nothing useful" from "never returns". While more sophisticated type systems separate this concern
across `Never` and `Unit`, `void` is stuck doing double duty, which inevitably leads to weaker
function contract guarantees.

## 3. How it solves the problem

### 3.1 Uniformity

With `Unit`, every function has the shape `A -> B`. There is no need for a special case:

```zeron
// No special case: println is an ordinary value of type Any? -> Unit
let printer: Any? -> Unit = println;

// ...so it can be passed anywhere a function is expected
let results: List<Unit> = Stream.of(1, 2, 3).map(printer).toList();
// prints 1, 2, 3; results is [(), (), ()]
```

With `void`, you need separate rules for functions that return vs. those that don't at all levels
of semantic analysis: type checking, inference, subtyping and code generation.

### 3.2 Generics

`void` often forces duplication in generic code. The textbook example of it is the
`java.util.function` package:

```java
interface Function<A, B> { ... } // A -> B
interface Consumer<A>    { ... } // A -> void
interface Supplier<B>    { ... } //   -> B
interface Runnable       { ... } //   -> void
```

In Zeron, however, this is unnecessary. All of these shapes can be expressed with the same function
type pattern `A -> B`:

```zeron
A  -> B    // function
A  -> Unit // consumer
() -> A    // supplier
() -> Unit // runnable
```

### 3.3 Composition

Because functions are uniform values, they can compose without special cases:

```zeron
fn run<T>(f: () -> T): T = f();

run(() -> println("hello")); // T = Unit
run(() -> 42);               // T = Int
```

A single definition of `run` covers both cases. With `void`, you'd need overloads or a dedicated
variant.

### 3.4 The Categorical Interpretation

In category theory, `Unit` is the terminal object: for every type `A` there is exactly one function
`A -> Unit` (the one that discards the value). This isn't just theoretical decoration; it has
concrete consequences:

- It gives `const`, `ignore`, and `discard` a natural definition.
- A value of type `A` is equivalent to a function `Unit -> A` (a "point" of the type), which
  underpins the idea of thunks and lazy evaluation.
- In the algebra of types, `A × Unit ≅ A` and `Either<A, Unit> ≅ Option<A>`: `Unit` is the identity
  element of the product, and it gives the canonical definition of Option.

### 3.5 Effects and Monads

In functional languages, `Unit` cleanly expresses "this action matters only for its effect":

```zeron
fn mapM_<A, M<_>: Monad<M>>(f: A -> M<Unit>, xs: Array<A>): M<Unit>;
fn when<M<_>: Monad<M>>(p: Boolean, action: M<Unit>): M<Unit>;
```

Without `Unit`, you’d have to invent a separate version of `Monad` for actions with no result, or
return an arbitrary, meaningless value.

## 4. Costs and Caveats

`Unit` isn't free. Some points to consider:

- **Runtime representation**. A one-inhabitant type needs no bits, but naive implementations
  (like Java's `Void`) can imply a reference or an allocation. Rust guarantees that `()` is
  zero-sized (a ZST) and optimizes it away entirely; on the JVM, Kotlin elides `Unit` in many
  cases, but not all (for instance, using it as a generic argument materializes the `Unit` object).
  Zeron's current implementation is naive: `Unit` is a real value, and `Unit`-returning functions
  always materialize it. A ZEP for optimizing `Unit` allocation is underway.
- **Confusion with null**. In Python, `None` serves as `Unit` but also as "absence", which conflates
  two distinct concepts. A good design separates `Unit` (a meaningful value: "done, with no
  result") from `Option::None` (absence of a value).

## Summary

In conclusion, `Unit` isn't a particularly sophisticated concept. If anything, it's quite the
opposite: it follows the same rules as every other type, and formalizes nothing-valued returns
into the type system.
