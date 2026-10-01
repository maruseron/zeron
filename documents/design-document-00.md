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

The following is a mix of current syntax, partial representations, and future design sketches; it is
not a statement that every form is fully implemented.

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
does not mean arrays are implemented as a collection type. The `Type[]` spelling and discriminated
unions remain design proposals. Mutable-reference capability (`&Type`) is preserved in resolved
types and enforced for array-slot writes and mutable-to-read-only projections, including function
types; enforcement for class members awaits class support.

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

#### Function declaration grammar

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
LambdaExpression             ::= SingleParameterLambda | ParenthesizedLambda
SingleParameterLambda        ::= Identifier "->" LambdaBody
ParenthesizedLambda          ::= "(" [LambdaParameterList] ")" "->" LambdaBody
LambdaParameterList          ::= Identifier ("," Identifier)*
LambdaBody                   ::= Expression | Block
Block                        ::= "{" Statement* "}"
```

Lambda parameters are identifiers without annotations; their types are inferred from the body or
an expected function type. The bare form is available only for a single parameter. Parentheses are
required for zero or multiple parameters. An expression body consists of one expression; a block
body contains statements and uses `return` when it returns a value.

### Control Flow

An `if` statement evaluates a Boolean condition and executes exactly one branch. Its `else` branch
is optional. `return` inside a selected branch exits the current function immediately. Names such as
`isMalformed`, `Response.error`, and `Json.from` in this example are illustrative APIs, not built-in
language features.

```zeron
if (isMalformed(uri)) {
    return Response.error("URI is malformed: " + uri);
} else {
    return Response.success(Json.from(uri));
}
```

`if` is also an expression. In this form, each branch produces a value, and the resolver requires
the branch types to agree. The selected branch supplies the expression's value; nested `else if`
expressions can represent a multi-way choice.

```zeron
let x = if (n < 0) then 0 else n;

let x =      if (n <  0) then "negative" 
        else if (n == 0) then "zero"
        else if (n >  0) then "positive";
```

`while` is a pre-test loop: it checks the condition before each iteration, so the body may run zero
times. The condition must have type `Boolean`.

```zeron
while (iterator.hasNext()) {
    let value = iterator.next();
    consume(value);
}
```

`until` expresses the inverse stopping condition. The body runs while its condition is false and
stops once that condition becomes true; like `while`, it may run zero times.

```zeron
let i = 0;
until (i == 5) {
    print(i);
    i += 1;
}
```

`loop` has no condition and therefore continues indefinitely unless control leaves the loop, for
example with `break;` or by returning from the function. `break;` exits the innermost enclosing
`while`, `until`, or `loop`; it cannot escape across a function or lambda boundary. These constructs
are compiled to JVM control flow, and loop conditions must have type `Boolean`.

```zeron
loop {
    let value = waitForRequest();
    consume(value);
}
```

### Error handling

Zeron defers source-level error handling, including exception handlers and typed result values, until
its generic and sum-type foundations are established. The syntax and semantics can then be designed
together with error propagation, cleanup, and behavior across generated-function and Java boundaries.

For now, scanner/parser and resolver failures are compile-time diagnostics that stop code generation.
Runtime exceptions from generated JVM code propagate to the host caller; Zeron does not catch or
translate them. This is the current runtime boundary behavior, not a source-level recovery mechanism.
Nullable values represent absence only when that is the intended meaning, not general errors. Values
such as `Response.error(...)` remain ordinary API-level values rather than built-in error handling.

### Ranges and Iterables

Range literals and arrays are intended to be iterable. Range literals currently have a generic
descriptor, but array iteration is not integrated with the dedicated `Array<T>` descriptor, and the
compiler does not yet lower `for` loops. Treat the following as intended syntax rather than a
runnable example.

#### Range syntax

You can create a Range with the `..`  operator:

```zeron
let oneThroughTen = 1..10;
```

#### Iterables

The intended `for` form binds an immutable name for each value in the iterable:

```zeron
let mut accumulator = 0;
for (let i in 1..10) {
    accumulator += i;
}
```

The parser requires `let` in the loop header. The resolver's iterable handling is still partial,
and bytecode generation for `for` is not implemented.

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

Classes are planned as nominal reference types with object identity. The initial constructor form
requires every field to be initialized before the instance becomes observable:

```zeron
class Person {
    name: String;
    age: Int;
    constructor new;
}

let person = Person.new("Ada", 37);
```

The initial constructor must initialize each field exactly once; additional constructors,
delegation, and inheritance are deferred. Fields are private by default, and access to a private
field outside its permitted scope is a compile-time error. Public fields are intended to expose
generated accessors, while custom getters and setters may be declared explicitly. Their exact
grammar and interaction with generated accessors remain to be specified.

```zeron
class Person {
    public name: String;
    set -> name = sanitize(it);
}
```

Contracts describe required member signatures and are checked statically. Start with declaration-site
conformance and a single contract per class; separate implementation declarations, default methods,
and multiple conformance are deferred.

```zeron
class PersonList is Iterable { ... }
```

#### Mutability

Binding mutability and reference mutation capability are independent. `let mut` allows a name to be
rebound; it does not grant permission to mutate the referenced object. A plain `T` is a read-only
view, while `&T` is a mutable view. An immutable binding may hold an `&T`, and a reassignable binding
may hold a read-only `T`.

Methods that mutate their receiver are marked `mut`; calling them, or a setter that mutates state,
requires a mutable receiver view. `&T` does not imply exclusive access or borrow checking: aliases may
observe mutations. How code obtains a mutable view, including from a newly constructed object, remains
to be specified.

```zeron
class Person {
    age: Int;
    mut grow(): Unit -> age += 1;
}

fn growPerson(person: &Person): Unit {
    person.grow();
}
```
