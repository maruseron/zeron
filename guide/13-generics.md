# Generics

Suppose you want a function that returns the first item in an array. You could write one version
for integers and another for strings:

```zeron
fn firstInt(values: Array<Int>): Int = values[0];
fn firstString(values: Array<String>): String = values[0];
```

Both functions do the same job. If you wrote a separate version for every type, you would repeat
the same logic many times. Generics let one declaration work with different types while keeping
track of which type is used.

## A function that works with different types

Write a type parameter in angle brackets after the function name. Use it in the parameter and
result types wherever those types should match:

```zeron
fn first<T>(values: Array<T>): T = values[0];
```

Here, `T` is a name for the type chosen at each use. If `values` is an `Array<Int>`, the result is
an `Int`. If it is an `Array<String>`, the result is a `String`. The same function body works for
both without losing the connection between the array's element type and the result type.

When you call `first`, Zeron can usually work out `T` from the argument:

```zeron
let numbers = [10, 20, 30];
let firstNumber = first(numbers);
```

Here, `T` is `Int`, so `firstNumber` is an `Int`. You can also write the type argument yourself
when it helps make the choice clear:

```zeron
let firstWord = first<String>(["hello", "world"]);
```

These `first` examples assume the array has at least one item.

## Types that keep track of their contents

Classes and contracts can also have type parameters. This is useful when the objects they describe
store or provide values of a type chosen by the code using them:

```zeron
contract Source<T> {
    value(): T;
}

class Box<T> is Source<T> {
    item: T;

    public value(): T = this.item;
}
```

`Box<T>` stores one value of type `T`, and its `value` method returns that same type. Its contract
also uses `T`, so a class implementing `Source<Int>` must provide an `Int` value, while one
implementing `Source<String>` must provide a `String`.

The type argument is part of the type you use:

```zeron
let count: Box<Int> = Box<Int>.new(3);
let title: Box<String> = Box<String>.new("Guide");
```

The first box contains an integer and the second contains a string. Their types help prevent
accidentally using the wrong kind of value.

## Extensions can be generic too

An extension can name a type parameter and use it in the type it extends. This lets one extension
work with arrays of many different element types:

```zeron
public extension<T> Array<T> {
    public fn firstOr(fallback: T): T {
        if (this.length == 0) return fallback;
        return this[0];
    }
}
```

The `T` in `Array<T>` is the element type, and the fallback must have that same type. This
extension can be used with an integer array and an integer fallback, or a string array and a string
fallback.

## Functions inside generic declarations

A method can use the type parameter of its class. It can also introduce another type parameter of
its own:

```zeron
class Box<T> {
    item: T;

    public apply<R>(transform: (T) -> R): R = transform(this.item);
}
```

`T` comes from `Box<T>` and describes the value stored in the box. `R` belongs to `apply` and
describes the result of the supplied function. For example, a `Box<Int>` can use `apply` with a
function that turns its integer into a string, giving a `String` result. The type parameters
describe two different roles without limiting the method to one particular pair of types.

An extension method can do the same. For example, an array extension may use `T` for the element
type and introduce `R` for a result type:

```zeron
public extension<T> Array<T> {
    public fn fold<R>(initial: R, combine: (R, T) -> R): R {
        let mut result = initial;
        for (let item in this) result = combine(result, item);
        return result;
    }
}
```

The array provides values of type `T`, while `R` is the type of the running result. An integer
array, for example, can be folded into a string by choosing `R` as `String`.

## Mutable-reference type arguments

Generic parameters admit read-only, non-null types by default. A mutable reference such as `&Box`
is rejected unless the parameter opts in with `mut`:

```zeron
fn inspect<T>(value: T): Unit = ();
fn preserveMutableView<T: mut>(value: T): T = value;
```

The `mut` marker controls which type arguments are admissible; it does not give the generic
function permission to mutate its value. Mutation still requires a mutable reference and an
operation that supports mutation. The `nullable` marker independently permits nullable arguments,
so `T: nullable + mut` accepts both nullable types and mutable-reference types.

Effect-kinded parameters use the exclusive `effect` marker to accept effect types for generic
propagation. For the marker rules and nested generic boundaries, see the
[generic type-system design](../documents/design-document-19_generic-type-system.md).

## Type erasure

Zeron checks generic types while building a program, but the JVM does not keep every generic type
argument as a separate runtime class. This is called **type erasure**. For example, `List<Int>` and
`List<String>` are both represented at runtime by the same `List` class; `Array<T>` is represented
as an array of JVM references.

As a result, generic code cannot ask at runtime which concrete type was used for `T`. The type
parameter preserves type relationships while Zeron checks the program, but it does not give the
program a runtime type value to inspect. Also, writing `T` alone does not say which operations are
available on values of that type; a generic function can only use operations justified by its
declaration. Later chapters explain how to describe such requirements.

## Naming conventions

Type parameter names are ordinary names chosen by the programmer. `T` is common when a
declaration has one simple type parameter; when there are several, names such as `E` for an
element type and `C` for a collection can make their different roles easier to follow.
