# Language Design Notes 00: Zeron design features overview

## Contents

- [Types](#types)
- [Variables](#variables)
- [Functions](#functions)
- [Simplest program](#sidetrack-simplest-zeron-program)
- [Higher order functions and Lambdas](#higher-order-functions-and-lambdas)
- [Control flow](#control-flow)
- [Ranges and Iterables](#ranges-and-iterables)
- [Classes](#classes)

## Languages to review for alternatives

Java, Kotlin, Scala, Haskell, OCaml, Swift, Rust, Zig, Haxe, Julia, CoffeeScript (see until!), F#

### Wishlist for this bitch ass language

#### Implemented foundations

- Core scalar types, `Unit`, function declarations, and type inference for ordinary bindings and
    function results.
- Higher-order function calls and lambda values, including zero- and multi-parameter lambdas.
- Binding reassignment as a declaration property: `let` bindings are immutable and `let mut`
    bindings may be reassigned.
- Basic nullable types: a non-null value can be widened to `T?`, and `null` can initialize an
    explicitly nullable binding or parameter.
- Single-expression function bodies and range-literal syntax.

#### Prototypes and partial support

- Lambda parameter inference is deferred and contextual: unresolved parameters can be fixed by a
    later call or expected function type. This is an implementation prototype, not polymorphic
    lambda typing.
- Lambdas lower to generated function-shape interfaces. Named function declarations can be called,
    but are not yet general first-class function values.
- Nullable descriptors and the basic `T`/`T?` assignments exist. Null-check flow refinement and
    nullable collection support do not.
- Generic-looking type syntax and descriptors exist, but generic substitution, type checking, and
    JVM lowering are incomplete.
- Range and `for` syntax exist, but iterable validation and general iteration semantics remain
    incomplete.

#### Planned language features

- Nominal classes and contracts, including private fields, construction, accessors, and receiver
    mutation rules. Inheritance and contract composition need separate design decisions.
- Method delegation: allow a wrapper method to forward its parameters and result to a receiver method
    reference, such as `get(index: Int): T` delegating to `contents::get`. Define this as a distinct
    forwarding form, not as a function-reference value or a change to `=` expression bodies; syntax,
    generic substitution, overload resolution, and receiver mutability rules remain to be designed.
- Intrinsic arrays with read-only and mutable views ([design](design-document-06_intrinsic-arrays.md));
    fully supported generics, algebraic data types, discriminated unions, and structural or
    nominal tuples.
- Immutable collection types, list comprehensions, and explicit resource management.
- Reference mutation capability (`&T`) and extension methods.
- First-class effect handling and any monadic syntax; the semantics and surface syntax are open.

---

### Types

Every expression and binding is assigned a `TypeDescriptor` during resolution. A descriptor is the
language's static type identity; it is separate from the JVM representation chosen by the compiler.
Some descriptors are source types, while others (`Infer` and `Null`) are internal tools and cannot
be written as ordinary declared types.

#### Built-in types

| Type | Meaning | Current status |
| --- | --- | --- |
| `Never` | Bottom type: an expression that does not produce a value or return normally. | Descriptor exists; throw expressions and complete control-flow integration are not implemented. |
| `Unit` | The single unit value, used when a computation has no useful result. | Supported as a type and literal. It is distinct from `Never`. |
| `Int` | Integer values. | Supported. |
| `Float` | Floating-point values. | Supported. |
| `Boolean` | Truth values. | Supported. |
| `String` | Text values. | Supported. |

#### Internal and nominal types

- `Infer` marks a type that resolution has not determined yet. It is not a user-declarable type.
- `Null` is the internal type of the `null` literal, not a type users can declare. It is assignable
  to a nullable type, but a binding initialized only with `null` needs an explicit nullable
  annotation, such as `let value: Int? = null;`.
- A nominal descriptor currently stores a type name. Class declarations and full nominal type
  resolution are future work, so a name descriptor alone does not imply that a usable class exists.

#### Function types

Function types are structural signatures consisting of an ordered parameter list and a return type:

```zeron
(Int, String) -> Boolean
() -> Unit
```

The function's source name is not part of its structural shape. Lambdas can be stored in bindings
and passed to functions; parameter inference is currently contextual and remains a prototype. The
compiler lowers function shapes to generated JVM interfaces, but those generated names are not the
language-level type identity.

#### Nullable types

`T?` describes values of `T` together with `null`. Nullability wraps one non-nullable base type;
applying `?` to an already-nullable type or to the internal `Null` type is not allowed.

The current assignment rules allow `T` to widen to `T?`, and allow `null` to initialize `T?`. They
do not allow a nullable value to flow to `T` without a check. Nullable operands cannot be used with
ordinary operators until null-check flow refinement is designed and implemented. Nullable primitive
types have boxed JVM representations, while their source-level identity remains distinct from the
non-nullable type.

#### Type modifiers and compound or structural types

The following is a mix of current syntax, partial representations, and future design sketches; it
is not a statement that every form is fully implemented.

```text
Nullable type:       Type?
Function type:       (Type, ...) -> Type
Generic type:        Type<Argument, ...>
Array type:          Type[]
Mutable reference:   &Type
Discriminated union: type Type = A | B
```

Nullable and function types have working language-level representations. Generic syntax and
descriptors are partial: type-parameter binding, substitution, and backend lowering are not
complete. The sample spelling `Array<T>` currently goes through this generic-looking syntax; it
does not mean arrays are implemented as a collection type. The `Type[]` spelling, mutable-reference
capability, and discriminated unions remain design proposals. In particular, the parser currently
does not preserve `&Type` as a resolved type qualifier.

---

### Variables

In Zeron, a variable is a binding from a name to a value. A binding may omit
its initializer only when it has an explicit nullable type.

#### Variable declaration

Current candidate for a Zeron variable declaration:

```zeron
let mut accumulator = 0;
let ITERATION_LIMIT = 16;
```

#### Variable type inference

Zeron can infer types for variable declarations whenever the initialization
expression is resolvable to a well-formed type. If this is not possible,
the types should be explicitly stated:

```zeron
let mut currentHighest: Person? = null;
```

#### Binding declaration grammar

```text
BindingDeclaration ::= "let" [Mutability] Identifier [TypeAnnotation] [Initializer] ";"
Mutability         ::= "mut"
TypeAnnotation     ::= ":" Type
Initializer        ::= "=" Expression
```

If a type annotation is omitted, the initializer must provide an inferable type; a bare `null`
initializer therefore requires an explicit nullable annotation. If the initializer is omitted, the
type annotation must be nullable. `mut` makes the binding reassignable; it does not modify the type.

---

### Functions

In Zeron, functions are arbitrarily invocable pieces of logic bound to
a name.

#### Function declaration

Current candidate for function declaration is:

```zeron
fn multiply(a: Int, b: Int): Int {
    return a * b;
}

fn multiply(a: Int, b: Int): Int = a * b;

// inferred as (Int, Int) -> Int
fn multiply(a: Int, b: Int) = a * b;
```

#### Invocation

In Zeron, functions invocations follow the C syntax convention:

```zeron
multiply(a, b);
```

#### Function type inference

Zeron can infer the return types for functions, given the returned
expression can be inferred as well:

```zeron
// can be inferred to (String) -> String
fn greeting(name: String) {
    return "Hello, " + name;
}
```

#### Syntax

```text
FunctionDeclaration ::= "fn" Identifier "(" [ParameterList] ")" [ReturnAnnotation] FunctionBody
ParameterList       ::= Parameter ("," Parameter)*
Parameter           ::= Identifier ":" Type
ReturnAnnotation    ::= ":" Type
FunctionBody        ::= "=" Expression ";" | Block
Block               ::= "{" Statement* "}"
```

Every parameter has an explicit type annotation. The return annotation is optional: an expression
body without one infers its return type, while a block body without one has type `Unit`. When a
return annotation is present, the resolver checks an expression body against that type. The `=`
form is the single-expression body syntax; `->` is used in function types and lambdas, not as an
alternative named-function body delimiter.

---

### Sidetrack: simplest Zeron program

Given the syntax so far, the simplest Zeron program possible would become the following:

```zeron
fn main() = print("Hello world");
```

Or for those who would prefer the longer version, the full method, including the explicit
Unit return:

```zeron
let main(): Unit {
    return print("Hello world");
}
```

---

### Higher order functions and Lambdas

Zeron supports higher order functions by allowing functions
to be sent as arguments to others:

```zeron
fn doSomething(number: Int, action: (Int) -> Unit) {
    action(number);
}

doSomething(5, print);
```

For logic that hasn't been previously defined, one can
instead use a lambda function for brevity:

```zeron
doSomething(5, (item) -> {
    if (item % 2 == 0) print(item);
});
```

Or even, if the last argument in a parameter list is a
single argument function:

```zeron
doSomething(number) {
    // 'it' is the default name for a single argument lambda
    print(it * 2); 
};
```

#### Lambda syntax

```text
Full form:
    "("{OPTIONAL ARGUMENT LIST}")" "->" {FUNCTION BODY}
    
Short form:
    "{" [ {NAME[":" {TYPE} ] } "->" ] {FUNCTION BODY} "}"
    
(OPTIONAL ARGUMENT LIST: ARGUMENT LIST | NO ARGUMENT LIST)
    (ARGUMENT LIST: ARGUMENT separated by commas )
        (ARGUMENT: {NAME}":" {TYPE} )
    (NO ARGUMENT LIST: TYPELESS ARGUMENT separated by commas )
        (TYPELESS ARGUMENT: {NAME} )
(TYPE: {NAME}{NULL | ARRAY} )
(FUNCTION BODY: {SHORT BODY} | {LONG BODY})
    (SHORT BODY: {EXPRESSION} )
    (LONG BODY: {STATEMENTS} )
```

### Control Flow

In Zeron, there are several control flow mechanisms available.
For conditional flow, the traditional 'if' statement is
available:

```zeron
if (isMalformed(uri)) {
    return Response.error("URI is malformed: " + uri);
} else {
    return Response.success(Json.from(uri));
}
```

If is also an expression:

```zeron
let x = if (n < 0) then 0 else n;

let x =      if (n <  0) then "negative" 
        else if (n == 0) then "zero"
        else if (n >  0) then "positive";
```

The traditional while loop:

```zeron
while (iterator.hasNext()) {
    let value = iterator.next();
    consume(value);
}
```

For inverted conditions, the until keyword provides better
clarity:

```zeron
let i = 0;
until (i == 5) {
    print(i);
    i += 1;
}
```

For indefinite iteration, the loop keyword:

```zeron
loop {
    let value = waitForRequest();
    consume(value);
}
```

### Ranges and Iterables

By default, two things are iterable in Zeron: Arrays and
*Ranges*. Ranges are objects that encode a sequence of
numbers.

#### Range syntax

You can create a Range with the `..`  operator:

```zeron
let oneThroughTen = 1..10;
```

#### Iterables

In Zeron, ranges, arrays and iterables can participate in
the iteration constructs provided by the language:

```zeron
let mut accumulator = 0;
for (i in 1..10) {
    accumulator += i;
}
```

#### Making an iterable

In Zeron, any custom type is eligible to become an iterable
by implementing the Iterable contract:

```zeron
contract Iterable {
    iterator(): Iterator;
}

// declaration site
class PersonList is Iterable {
    array: Person[];
    
    iterator(): Iterator { ... }
}

//use site
class PersonList {
    array: Person[];
}

implement Iterable for PersonList {
    iterator(): Iterator { ... }
}
```

### Classes

Zeron supports the creation of custom types through the
class keyword:

```zeron
class Person {
    name: String;
    age: String;

    // the new constructor is reserved: it requires has all
    // fields in the class as parameters
    constructor new;
}

let p = Person.new("John", 37);
```

This class has a particular caveat - all fields are private
in Zeron. This means that trying to access name on our friend
John will throw an error:

```zeron
print("This person's name is: " + p.name);
                                // ^ error: no getter has
                                //   been defined for 'name'
```

To expose the fields in John, you can add getters and setters:

```zeron
class Person {
    name: String;
    get -> name;
    set -> name = it;
```

The getters we're defining here are just returning and modifying
the value, respectively. Writing this for every field would be a
lot of boilerplate, so Zeron lets you opt into default getters
and setters by adding the `public` keyword in front of it:

```zeron
class Person {
    public name: String;
```

You can even mix and match. The following:

```zeron
class Person {
    public name: String;
    set -> name = sanitize(it);
```

Will provide a default getter with a custom setter.

#### Mutability

Since all fields are private by default, they're also freely
mutable within the context of a class, with one caveat:
methods that mutate them must declare they do so with `mut`:

```zeron
class Person {
    public name: String;
    public age: String;
    
    constructor new;
    
    // defining the grow method without mut would be
    // a semantic error: age cannot be mutated without
    // outside a mutation context
    mut grow(): Unit -> age += 1;
}
```

By default, variables of type `Person` do not have access to
mutable methods (including setters), which means this remains
an error for our mutable-by-design class Person:

```zeron
let p = Person.new("John", 37);
p.grow();
   // ^ semantic error: mutable method cannot be called
   //   on an immutable reference
```

To solve this, we must obtain a mutable reference to person:

```zeron
let p = mut Person.new("John", 37);
p.grow();
print(p.age); // 38!
```
