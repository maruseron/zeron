# Classes and constructors

A class describes a kind of object. It says what information each object remembers and what
operations can be performed on it. For example, a program that works with counters can make a
separate `Counter` object for each count, rather than keeping the counter's data as an unrelated
number elsewhere.

```zeron
public class Counter {
    count: Int;
}
```

This declaration describes the `Counter` type; it does not create a counter by itself. `public`
means code outside this class can use the class. Create an object by calling its constructor:

```zeron
let counter = Counter.new(0);
```

This creates one counter whose `count` starts at zero. A second call to `Counter.new(0)` creates
a separate object with its own count. Classes are useful when a program needs several things of
the same kind, each with its own data and behavior.

By convention, class and other type names use **PascalCase**, like `Counter` and `Greeter`.
Fields, properties, methods, and variables use **camelCase**, like `count`, `current`, and
`counter`.

## Fields and starting values

A field is information stored in each object. In the example, `count` is a field of type `Int`.
Every counter has its own `count`, initialized when the counter is created. The field is private:
code outside `Counter` cannot read or change it directly. Later in this chapter, we will add a
public way to read it and an operation that updates it.

A field can also have an initializer: an expression that supplies its starting value whenever a
new object is made:

```zeron
public class Greeter {
    greeting: String = "Hello";
    name: String;
}
```

An initializer is written after `=` on the field declaration. It gives every new object the same
starting value for that field. Fields without initializers instead get their starting values from
the constructor.

Here, `greeting` starts as `"Hello"` and does not need to be supplied when constructing a
`Greeter`. `name` has no initializer, so construction must provide it. Initializers can use simple
expressions and earlier fields, but they are not a place for arbitrary setup code.

## The main constructor

Every class has one main constructor, called the canonical constructor. It initializes all fields
that do not have initializers, in the order they are declared. When no constructor is declared,
Zeron supplies a public canonical constructor automatically. Omitting the canonical constructor
is the usual Zeron convention when the constructor should be public.
Write `public constructor new;` only when you want to state that choice explicitly.

For `Counter`, the canonical constructor takes the `count` field as its argument:

```zeron
let counter = Counter.new(0);
```

If a field has an initializer, it is left out of these arguments. There is no separate body for
the canonical constructor: Zeron creates and initializes the object from its fields and their
initial values. A class can declare `private constructor new;` to prevent other code from calling
the canonical constructor directly.

The word “canonical” just distinguishes this one main constructor from any extra named
constructors. It is the constructor that actually creates and initializes the object.

## Named constructors

All constructors in Zeron have a name, and a class can have any amount of constructors. While the
canonical constructor *must* be called `new`, all other constructors are free to use any name
of your liking, and are called named constructors. It allows you to use a descriptive name to create
objects and may choose the arguments to pass to the canonical constructor:

```zeron
public class Counter {
    count: Int;
    private constructor new;

    public constructor zero() = Counter.new(0);
    public constructor startingAt(value: Int) = Counter.new(value);
}
```

Other code can now write `Counter.zero()` or `Counter.startingAt(10)`, but cannot call
`Counter.new(...)` directly. Each named constructor delegates actual object creation to the
canonical constructor; it does not allocate a different kind of object. Use named constructors
when their names make common starting choices easier to understand or when they need to prepare
values before construction.

## Methods and `this`

A method is an operation declared inside a class. It can use that object's fields and can be
called with dot syntax. Add a method that reads the counter's private field:

```zeron
public class Counter {
    count: Int;
    public current(): Int = this.count;
}
```

Now `counter.current()` gives the value of the counter without exposing the field itself.
Within a method, `this` means the particular object on which the method was called. Here,
`this.count` is the `count` field belonging to that counter. Methods can calculate and return a
value, as `current` does, or perform an action.

## Public and private members

`public` means other code may use the class member; `private` means it is reserved for use inside
the class. Fields are private automatically, so they do not have a visibility modifier. Methods,
properties, and constructors state their visibility explicitly. The class itself can also be
declared `public` when it should be usable from elsewhere.

These choices let a class hide details that callers should not depend on. A public class can be
created and used from outside; its private fields remain internal, while its public members form
the set of operations available to other code.

## Methods that can change an object

By default, a method can read the object's fields but cannot change them. Mark a method `mut`
when its job is to update the object:

```zeron
public mut increment(): Unit {
    this.count += 1;
}
```

Calling a `mut` method requires a mutable reference to the object. For example,
`Counter.new(0)` produces a reference that can call `increment()`. A read-only reference can
still call a method that does not change the object, such as `current()`, but cannot call
`increment()`.

This permission belongs to the reference, not to the variable name. `let mut counter = ...`
allows the name `counter` to be assigned a different reference; it does not by itself let the
current reference change its object. Conversely, an ordinary `let counter = Counter.new(0)`
binding cannot be reassigned, but it can still call `mut` methods because the constructed
reference is mutable. The difference between these two permissions, including how references may
be shared, is explained in [references and mutation](08-references-and-mutation.md).

## Properties

A property lets callers read or update information using member syntax, such as
`counter.current`, rather than calling a method like `counter.current()`. A property declaration
can spell out exactly what should happen on a read and on a write:

```zeron
public class Label {
    labelText: String;

    public constructor new;

    public mut property label: String {
        get = this.labelText;
        set(value) {
            this.labelText = value;
        }
    }
}
```

The `get` block runs when code reads `item.label`; the `set(value)` block runs when code assigns
to it, such as `item.label = "Welcome"`. `value` is the new value being assigned. This property
uses the private `labelText` field to store its value, so the getter and setter decide how outside
code accesses that field. Because the property can be assigned, it is declared `mut`, and
assigning to it requires a mutable reference to the object, just as calling a `mut` method does.

These are custom accessors: Zeron runs the code in the getter or setter and does not create
separate storage for the property. If the property needs to remember a value, the class declares
a field for that purpose, as `labelText` does above.

### Automatic properties

For a simple stored value, you do not need to write the getter and setter yourself. An automatic
property omits the accessor blocks, and Zeron supplies private storage and a getter. A `public`
property acquires an automatic getter, and adding `mut` to it supplies an automatic setter as well:

```zeron
public property name: String;
public mut property label: String = "new";
```

The first property can be read but not assigned to by callers. The second can be read and
assigned, and its initializer gives it the starting value `"new"`. An automatic property without
an initializer is supplied to the canonical constructor after uninitialized fields, in property
declaration order. A property with an initializer already has a starting value and is omitted
from those constructor arguments.

### Computed properties

A computed property calculates its value when it is read instead of storing a separate value.
Write a getter that uses other class data:

```zeron
public property current: Int {
    get = this.count;
}
```

Each time code reads `counter.current`, the getter evaluates `this.count` again. There is no
separate stored value for `current`, and it does not add a constructor argument. The property is
useful when a value should be derived from the object's current state rather than saved
separately.

A computed property can also define a setter, but it must declare `mut` and provide that setter
logic itself. In this case, the getter computes the value on each read, and the setter decides
how an assigned value changes the object's stored fields. Choose an automatic property for a
simple stored value, and a computed property when reads or writes need custom behavior.

Classes give related data and behavior one name and one place to maintain. They are especially
useful when a program needs multiple objects of the same kind, each with its own state and
operations. Classes do not inherit from other classes; they can instead conform to contracts,
introduced in [contracts and conformance](10-contracts-and-conformance.md).
