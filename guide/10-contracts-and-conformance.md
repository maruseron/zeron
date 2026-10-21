# Contracts and conformance

Imagine a program that can work with many kinds of shapes. Each shape stores different
information: a rectangle has a width and height, while a circle has a radius. But the program
may only need to ask each shape for its area. How can one piece of code use both kinds of objects
without needing a separate version for every class?

A contract describes operations that a type promises to provide. A class lists the contracts it
conforms to in its declaration. Conforming means that the class agrees to provide the operations
the contract requires.

Contracts are types, so their names follow the same **PascalCase** convention as class names:
`Measurable`, `Named`, and `TokenFactory`. Function and member names such as `area` and `name`
use **camelCase**.

## Required methods

A contract can start by requiring a method. A method declaration ending in `;` names an operation
and its result type, but does not provide its implementation. We call these methods abstract:

```zeron
contract Measurable {
    area(): Float;
}
```

A class that conforms to `Measurable` must provide a matching public method:

```zeron
class Rectangle is Measurable {
    width: Float;
    height: Float;

    public area(): Float = this.width * this.height;
}
```

Code can then accept any value that conforms to `Measurable` and call the required operation:

```zeron
fn measure(shape: Measurable): Float = shape.area();
```

`measure` can be used with a `Rectangle` or another class that conforms to `Measurable`. The
function only needs to know that the object provides `area`; it does not need to know how that
class stores its data or calculates the result. Conformance is written on the class declaration,
so a class does not conform just because it happens to have a method with the same name.
This limitation can be bridged, as we'll see in next lessons.

## Required properties

Contracts can also require properties. A read-only property requirement promises that code can
read a value; a `mut property` promises that it can also be changed through a mutable reference:

```zeron
contract Named {
    property name: String;
}

class Person is Named {
    public property name: String;
}
```

Here the automatic property on `Person` supplies the required `name`. A class can also meet a
property requirement with custom accessors. A writable requirement needs a writable property;
a read-only requirement can be met by either a read-only or writable property.

Contracts describe what an object lets other code do, not how it stores its information. They
cannot add fields to a class.

## Default methods

Sometimes every conforming class should share the same implementation of an operation. A contract
can provide that implementation with `default`:

```zeron
contract Labelled {
    property name: String;

    default label(): String = "Name: " + this.name;
}
```

A class conforming to `Labelled` must provide `name`, but it can use the contract's `label`
implementation without writing that method itself. This isn't authoritative: a class may choose to
provide its own `label` method instead. Code using a `Labelled` value can call `label()` either way.

## Combining contracts

Contracts can include other contracts with `requires`. This lets one contract describe a larger
set of capabilities by combining smaller ones:

```zeron
contract Printable {
    text(): String;
}

contract Labelled {
    label(): String;
}

contract Described requires Printable + Labelled {
    summary(): String;
}
```

A class that conforms to `Described` must provide the required operations from all three
contracts. It also conforms to `Printable` and `Labelled`, so code that needs only one of those
smaller capabilities can use the same object.

A class can conform to several contracts directly as well. Contracts do not make one class inherit
another class; they let different classes promise the same useful operations.
