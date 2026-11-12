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
// read "first of T"
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

    public apply<R>(converter: Converter<T, R>): R = 
        converter.convert(this.item);
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
    public fn fold<R>(initial: R, combiner: Combiner<T, R>): R {
        let mut result = combiner.start(initial);
        for (let item in this) result = combiner.combine(item);
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
[generic type-system design](../documents/design_documents/design-document-19_generic-type-system.md).

## Type erasure

Zeron checks generic types while building a program, but the JVM does not keep every generic type
argument as a separate runtime class. This is called **type erasure**. For example, `ArrayList<Int>` and
`ArrayList<String>` are both represented at runtime by the same `ArrayList` class; `Array<T>` is represented
as an array of JVM references.

As a result, generic code cannot ask at runtime which concrete type was used for `T` unless the
declaration opts in with the `reified` marker. The type parameter preserves type relationships
while Zeron checks the program, but it does not give the program a runtime type value to inspect.
Also, writing `T` alone does not say which operations are available on values of that type; a
generic function can only use operations justified by its declaration. Later chapters explain how
to describe such requirements.

## Star views and anonymous existentials

A star view is a read-only view of an unknown type argument. `Box<*>` is exactly
`Box<out Any?>`, so it adds no new type variable and supports no exact type test. The class must
admit the `Any?` projection argument, just as it would for the explicit projection:

```zeron
public class Box<T: nullable> {
    public property value: T;
    public constructor new;
}

fn read(box: Box<*>): Any? = box.value;
```

When a parameter needs a specific, unknown argument with inherited contract evidence, write an
anonymous existential with `<: ...>`:

```zeron
public contract Render {
    render(): String;
}

fn describe(box: Box<: Render>): String = box.value.render();
```

At the call site the compiler implicitly packs the argument and infers the hidden type. The hidden
type does not appear in the source signature and does not consume an explicit call-site type
argument. Since `describe` chooses a different hidden type for each call, its second `Box`
parameter would be unrelated unless it shares a named type parameter: `fn compare(a: Box<: Render>,
ordered: Box<: Render>)` does not say the two arguments contain the same type.

Anonymous existentials are limited to direct arguments of the outermost applied parameter type of
a declared function, method, or contract method. They are rejected in variables, fields, results,
receivers, arrays, nested arguments, lambda parameters, and function-type parameters. `Array<*>` is
also rejected because arrays do not accept projections.

Add `reified` when the hidden argument needs a runtime descriptor. Reified type parameters and
hidden arguments support exact applied-type tests:

```zeron
fn isIntBox<T: reified>(value: T): Boolean = value is Box<Int>;
fn isReifiedIntBox(box: Box<: reified>): Boolean = box is Box<Int>;
```

An exact test compares applied type descriptors, including array elements and nullable or mutable
markers. A null source is false. The test is available only when the source actually carries a
descriptor; an erased or projected source keeps the existing static generic test rules. Stored and
result-position existentials and runtime contract-bound tests are separate proposals.

## Opening an anonymous argument

A star view or anonymous existential hides a type argument. To use that argument by name inside a
branch, open it with `let` in a direct `if` or `while` type test:

```zeron
public fn readSize(box: Box<*>): Int {
    if (box is Box<let T>) {
        let value: T = box.value;
        return sizeOf(box);
    }
    return 0;
}
```

`T` is a fresh branch-scoped type, not a recovered runtime type. On the true path the tested
binding has type `Box<T>`, and `T` can appear in branch-local annotations and generic calls. The
binder inherits what the source already carries: a bounded anonymous existential contributes its
bound and contract evidence, a reified anonymous existential contributes its descriptor, and a
known application such as `Box<Int>` makes `T` that argument. A star view or a raw `Any` test does
not invent evidence or a descriptor.

When the source is already the opened application, the pattern is total and the false branch is
unreachable. Otherwise the compiler performs the ordinary raw class test and both branches remain
possible.

The first version requires the opening test to be the direct condition, with every target argument
written as `let`. Mixed patterns such as `Pair<Int, let V>`, nested openings, composed conditions,
and `in` projections are rejected. The binder cannot escape the branch: returning it or assigning a
type that mentions it to an outer binding is an error. Remaining opening forms, escape contexts,
result-position existentials, and variance are tracked in ZEP-0116.

## Runtime type descriptors

A type parameter marked `reified` receives a runtime descriptor for its type argument. A reified
parameter can be forwarded to another declaration that requires runtime identity:

```zeron
public fn identity<T: reified>(value: T): T = value;
public fn relay<T: reified>(value: T): T = identity(value);
```

The marker is part of the declaration's ABI. Exported declarations must state it explicitly, so a
signature always determines which hidden descriptor arguments its implementation receives.
Private and local declarations infer the marker transitively from calls that require a descriptor.
At a call site with concrete types the compiler passes an interned constant; when the argument is
another reified type parameter it forwards the descriptor directly. Nested applications, arrays,
and nullability or mutability markers compose into a descriptor at the call site.

Class and contract type parameters cannot be reified, because instances and contract dispatch do
not carry those descriptors in this version. Effects also remain non-generic; generic effect
payloads are a separate proposal. Exact applied-type tests use the descriptor of a reified value or
type parameter, as described above.

## Naming conventions

Type parameter names are ordinary names chosen by the programmer. `T` is common when a
declaration has one simple type parameter; when there are several, names such as `E` for an
element type and `C` for a collection can make their different roles easier to follow.
