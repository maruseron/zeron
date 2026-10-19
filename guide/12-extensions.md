# Extension methods

Sometimes an operation takes a value as its first input. For example, one function might greet a
name and another might add an exclamation mark:

```zeron
fn greet(name: String): String = "Hello, " + name;
fn exclaim(message: String): String = message + "!";

let message = exclaim(greet("Ada"));
```

This works, but when several operations are combined, each new call has to wrap the previous
result. It can take effort to see which value each operation works on.

An extension method lets you write an operation in the same style as a method on the value. It
still takes the value as its receiver, but the call reads from left to right:

```zeron
public extension String {
    public fn exclaim(): String = this + "!";
}

let message = greet("Ada").exclaim();
```

Here, `this` is the `String` value to the left of `.exclaim()`. The result of `greet("Ada")` becomes
the receiver for `exclaim()`. This style can make a sequence of steps easier to follow.

## Defining and using extensions

An extension names the type it works on. Inside its methods, `this` refers to the value the method
is called on. The type named by the extension follows the **PascalCase** convention, while the
extension methods themselves use camelCase:

```zeron
package text;

public extension String {
    public fn withPeriod(): String = this + ".";
}
```

To use an extension from another file, import it by its package, receiver type, and method name:

```zeron
package app;

import text.String.withPeriod;

fn finish(sentence: String): String = sentence.withPeriod();
```

Importing a type alone does not bring its extensions into scope. You can also use a package star
import to make its public extensions available. Extensions are especially useful when you want
readable receiver-style calls for a type whose class or contract you do not want to change.

## Extensions that change a value

An extension can be marked `mut` when its operation changes the value it is called on. For example,
this extension replaces the first element of an array:

```zeron
public extension <T> Array<T> {
    public mut fn replaceFirst(value: T): Unit {
        this[0] = value;
    }
}

let values: &Array<Int> = [1, 2, 3];
values.replaceFirst(9);
```

Because `replaceFirst` changes the array, it can only be called through a mutable reference such as
`&Array<Int>`. A read-only `Array<Int>` reference can still use extensions that only read or
calculate from the array, but not this mutating one. As with other mutable references, the `&`
grants permission to change the array; it does not mean the array has no other references. See
[references and mutation](08-references-and-mutation.md) for more about this distinction.

## Organizing extensions with namespaces

An extension and a namespace with the same name in the same package are grouped together. This
lets a type's namespace hold ordinary helper functions while its extensions provide receiver-style
operations:

```zeron
package text;

namespace String {
    public fn greeting(name: String): String = "Hello, " + name;
}

public extension String {
    public fn exclaim(): String = this + "!";
}
```

The namespace function and extension can be used together:

```zeron
import text.String.exclaim;
import text.String.greeting;

let message = greeting("Ada").exclaim();
```

This follows the convention from [packages, namespaces, and imports](11-packages-namespaces-and-imports.md)
of placing helper functions in a namespace named after the class or contract they support. Both
the namespace functions and the extension methods can be imported using that shared name.

An extension is a good fit for an operation that naturally reads as something done to a value.
When an operation needs to be available through a contract for every conforming class, declare it
as a contract method instead; see [contracts and conformance](10-contracts-and-conformance.md).
