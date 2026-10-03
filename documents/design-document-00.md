# Language Design Notes 00: Zeron design features overview

## Contents

- [Types](#types)
- [Variables](#variables)
- [Functions](#functions)
- [Generic functions](#generic-functions-first-slice)
- [Simplest program](#sidetrack-simplest-zeron-program)
- [Higher order functions and Lambdas](#higher-order-functions-and-lambdas)
- [Control flow](#control-flow)
- [Ranges and Iterables](#ranges-and-iterables)
- [Classes](#classes)
- [Names, Packages, and Imports](#names-packages-and-imports)

## Languages to review for alternatives

Java, Kotlin, Scala, Haskell, OCaml, Swift, Rust, Zig, Haxe, Julia, CoffeeScript (see until!), F#

### Wishlist

#### Implemented foundations

- Core scalar types, `Unit`, function declarations, and type inference for ordinary bindings and
    function results.
- Remainder for `Int` and `Float`, 32-bit `Int` bitwise and shift operators, and their variable-only
    compound assignments. `++`/`--` are not part of the language; use `+= 1` or `-= 1`.
- Built-in `Any` as a non-null top type: non-null values widen to `Any`, nullable values and `null`
    widen to `Any?`; supported type tests and checked/safe casts are implemented.
- Generic top-level functions with explicit return types, call-site type inference or explicit type
    arguments, recursive substitution, and erased JVM lowering.
- Generic functions accept and return callback values involving type parameters; generated bridge
    methods adapt concrete lambda shapes to and from erased callback interfaces.
- Higher-order function calls and lambda values, including zero- and multi-parameter lambdas.
- Nominal classes and contracts with private fields, canonical construction, methods, static
    conformance checking, and contract dispatch.
- Invariant generic classes and contracts with explicit construction arguments, member substitution,
    declaration-site conformance, raw JVM erasure, erased-signature bridges, and callback adaptation
    across erased nominal fields and members.
- Fixed-size `Array<T>` values with non-empty literals, indexed reads and writes, and a `length`
    property; writes require a mutable reference view.
- `for` loops over arrays, inline integer ranges, and user-defined types conforming to the bundled
    `Iterable<T>` and `Iterator<T>` contracts. Arrays retain specialized lowering; ranges are
    ordinary `zeron.ranges.IntRange` values and use protocol dispatch.
- Callable `zeron.io.print` and `zeron.io.println` standard-library functions, lowered through
    registered intrinsic IDs. The former print statement syntax has been removed.
- Initial Java class-directory interop for public constructors and methods, including expanded
    varargs calls with supported component types. JARs, JDK module discovery, fields, and ordinary
    Java array signatures remain unsupported.
- Binding reassignment as a declaration property: `let` bindings are immutable and `let mut`
    bindings may be reassigned.
- Reference mutation capability (`&T`) is distinct from binding reassignment and is enforced for
    arrays, functions, and class/contract views.
- Basic nullable types: a non-null value can be widened to `T?`, and `null` can initialize an
    explicitly nullable binding or parameter.
- Single-expression function bodies and range-literal syntax.

#### Prototypes and partial support

- Safe immutable let-bound lambdas with identity or constant-result bodies generalize unresolved
    parameters into rank-1 schemes; each call or expected function type instantiates them separately.
    Operator-constrained lambdas remain on the contextual monomorphic inference path. Mutable
    bindings and nested schemes are not generalized.
- Lambdas are reusable first-class values and lower to generated function-shape interfaces. Named
    functions can be used as values when an expected function type is available; generic named
    functions require explicit or contextual specialization. Lambdas capture immutable bindings by
    value, but cannot implicitly capture and mutate reassignable local bindings.
- Nullable descriptors and basic `T`/`T?` assignments exist. Flow refinement for direct local and
    parameter null/type tests in statement branches and `if` expressions is implemented, including
    short-circuit Boolean conditions. Checked and safe casts are implemented for supported targets.
    Loop exits join condition-false and `break` paths; loop-written bindings are conservatively
    invalidated at assignments, and loop-header facts converge across backedges; `for` retains its
    zero-iteration path. See
    [design-document-08](design-document-08_flow-typing-type-tests-and-casts.md).
    Arrays support nullable element types, while broader nullable collection behavior remains limited.
- Generic functions support one contract bound per type parameter. Generic function references can
    be specialized explicitly with `name::<Type>` or inferred from an expected function type; these
    are monomorphic values, not polymorphic lambdas. Variance, overloads, generic class/contract
    bounds, and first-class generic function values without specialization remain unsupported.
    Generic callback adaptation works across top-level functions, specialized function references,
    and nominal members; broader shape coverage and adapter reuse remain.

#### Planned language features

- Method delegation: allow a wrapper method to forward its parameters and result to a receiver method
    reference, such as `get(index: Int): T` delegating to `contents::get`. Define this as a distinct
    forwarding form, not as a function-reference value or a change to `=` expression bodies; syntax,
    generic substitution, overload resolution, and receiver mutability rules remain to be designed.
- Signature-only external/expected declarations remain to be designed; see the
    [intrinsic and external binding roadmap](design-document-12_intrinsics-and-external-bindings.md).
    Algebraic data types, discriminated unions, and structural or nominal tuples are also future work.
- Immutable collection types, list comprehensions, and explicit resource management.
- Null-aware navigation (`?.`), null fallback (`??`/`??=`), and explicit structural/referential
    equality semantics; see the [small language features discussion](design-document-10_small-miscelaneous.md).
- Extension methods.
- First-class effect handling and any monadic syntax; the semantics and surface syntax are open.

---

### Names, Packages, and Imports

Package headers, qualified nominal identities, selective imports, aliases, and public/package
visibility are implemented. Packages are source namespaces; imports are compile-time name bindings,
not runtime loading. Project discovery, library artifacts, and Java interop are covered in
[design-document-11](design-document-11_compilation-libraries-and-host-integration.md); intrinsic
bindings are covered in [design-document-12](design-document-12_intrinsics-and-external-bindings.md).
Source-level name resolution is specified in
[design-document-09](design-document-09_namespaces-packages-and-imports.md).

### Types

Every expression and binding is assigned a `TypeDescriptor` during resolution. A descriptor is the
language's static type identity; it is separate from the JVM representation chosen by the compiler.
Some descriptors are source types, while others (`Infer` and `Null`) are internal tools and cannot
be written as ordinary declared types.

#### Built-in types

| Type | Meaning | Current status |
| --- | --- | --- |
| `Never` | Bottom type: an expression that does not produce a value or return normally. | Descriptor exists; throw expressions and complete control-flow integration are not implemented. |
| `Any` | Top type for all non-null values. | Implicit widening is implemented; it lowers to JVM `Object`. `Any?` also accepts nullable values and `null`. `is`-based narrowing and checked/safe casts are implemented for supported targets. |
| `Unit` | The single unit value, used when a computation has no useful result. | Supported as a type and literal. It is distinct from `Never` and has a shared non-null runtime singleton. |
| `Int` | Integer values. | Supported. |
| `Float` | Floating-point values. | Supported. |
| `Boolean` | Truth values. | Supported. |
| `String` | Text values. | Supported. |

#### Internal and nominal types

- `Infer` marks a type that resolution has not determined yet. It is not a user-declarable type.
- `Null` is the internal type of the `null` literal, not a type users can declare. It is assignable
  to a nullable type, but a binding initialized only with `null` needs an explicit nullable
  annotation, such as `let value: Int? = null;`.
- Class and contract declarations resolve to package-qualified nominal types across the current
    compilation set. Module-qualified identity remains future work.

#### Function types

Function types are structural signatures consisting of an ordered parameter list and a return type:

```zeron
(Int, String) -> Boolean
() -> Unit
```

The function's source name is not part of its structural shape. Lambdas can be stored in bindings
and passed to or returned from functions; parameter inference is currently contextual and remains a
prototype. Lambdas may capture immutable bindings by value, including references to shared heap
objects, but may not implicitly capture reassignable local bindings. The compiler lowers function
shapes to generated JVM interfaces, but those generated names are not the language-level type
identity.

#### Nullable types

`T?` describes values of `T` together with `null`. Nullability wraps one non-nullable base type;
applying `?` to an already-nullable type or to the internal `Null` type is not allowed.

The current assignment rules allow `T` to widen to `T?`, and allow `null` to initialize `T?`. A
nullable local or parameter can be used as non-null after a proven null check; an `Any` value can be
refined by a supported `is` test in statement branches and `if` expressions. Refinement does not
apply to properties, array slots, or globals, and facts after an `if` expression reflect the join of
both branches. Nullable operands still require proof before ordinary use; see
the [flow-typing, type-test, and cast design](design-document-08_flow-typing-type-tests-and-casts.md).
Nullable primitive types have boxed JVM representations, while their source-level identity remains
distinct from the non-nullable type.

`Any` is the non-null top type and accepts implicit widening from every non-null source type,
including `Unit`; scalar values are boxed as needed. `Any?` accepts all source types, including
nullable types and `null`, while `Any?` cannot implicitly narrow to `Any` or another specific type.
All Unit values use a shared generated `UnitValue` singleton, so widening Unit to `Any` preserves the
non-null distinction from `null`. Conditional `is` tests are implemented for supported runtime
types. `as T` performs a checked cast whose failure propagates as a JVM runtime exception; `as? T`
returns null on mismatch or null. Casts to erased generic, array-element, and function-shape types
are not supported.

#### Type modifiers and compound or structural types

The following is a mix of current syntax, partial representations, and future design sketches; it is
not a statement that every form is fully implemented.

```text
Nullable type:       Type?
Function type:       (Type, ...) -> Type
Generic type:        Type<Argument, ...>
Array type:          Array<Type>
Mutable reference:   &Type
Discriminated union: type Type = A | B
```

Nullable and function types have working language-level representations. Generic functions and
invariant generic classes/contracts support type parameters, substitution, and erased JVM lowering.
Generic top-level function callbacks have bridge adaptation. Callback values crossing generic nominal
member boundaries are also adapted through generated helpers and erased contract bridges; broader
shape coverage remains follow-up work. `Array<T>` is an implemented built-in invariant type
constructor with its own descriptor, not a user-defined generic class; non-empty literals, indexing,
and `.length` are supported. The `Type[]` spelling and discriminated unions remain design proposals.
Mutable-reference capability (`&Type`) is preserved in resolved
types and enforced for array-slot writes, function-view projection, class member mutation, and
mutable-to-read-only projections.

---

### Variables

In Zeron, a variable is a binding from a name to a value. A binding may omit
its initializer only when it has an explicit nullable type.

#### Variable declaration

Current variable declaration syntax:

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

Function declarations use `fn` with either a block body or an expression body:

```zeron
fn multiply(a: Int, b: Int): Int {
    return a * b;
}

fn product(a: Int, b: Int): Int = a * b;
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

#### Generic functions (first slice)

See the [generic-function implementation note and roadmap](design-document-07_generic-functions.md) for
the current resolver and JVM lowering details.

Generic functions declare type parameters after the name and require an explicit return type:

```zeron
fn identity<T>(value: T): T = value;
fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);

identity(42);                  // infer T as Int
identity<String>("zeron");    // explicit type argument
```

Type parameters are scoped to the declaration and may appear recursively in parameter, return,
array, nullable, reference, and function types. Direct calls infer substitutions from arguments;
explicit type arguments are available when inference is underconstrained. Lambdas receive their
contextual parameter types after known substitutions are applied, and their body results can infer
remaining type variables. Generic functions require annotated returns so their schemes are known
before body resolution.

Unbounded type parameters are opaque in function bodies. A contract-bounded parameter can call that
contract's non-mutating methods; operators and other type-specific operations still need future
constraints. Contextually typed lambdas remain monomorphic; safe immutable let-bound lambdas have a
limited rank-1 generalization slice. Generic named functions can be referenced as monomorphic
function values by explicit specialization or expected function type. At runtime, type parameters
erase to `Object`; direct callback parameters/results and specialized function references crossing
between erased and concrete function shapes use generated bridge helpers. Nested callbacks, nullable
callback values, and mutable function views are adapted recursively; broader primitive/reference
combinations and bridge-reuse coverage remain. Invariant generic classes/contracts and callback
adaptation across their erased member boundaries are implemented. Single contract bounds on generic
functions are implemented; variance and generic class/contract bounds remain deferred.

---

### Sidetrack: simplest Zeron program

A minimal program using the standard output function is:

```zeron
import zeron.io.println;

fn main(): Unit {
    println("Hello world");
}
```

---

### Higher order functions and Lambdas

Zeron supports higher-order functions that receive function values. Lambdas and named functions can
be passed directly when an expected function type is available. Generic named functions may also be
specialized explicitly:

```zeron
import zeron.io.println;

fn doSomething(number: Int, action: (Int) -> Unit) {
    action(number);
}

doSomething(5, item -> {
    if (item % 2 == 0) println(item);
});

fn identity<T>(value: T): T = value;
let intIdentity: (Int) -> Int = identity::<Int>;
let stringIdentity: (String) -> String = identity;
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
body contains statements and uses `return` when it returns a value. Lambdas can be stored and
returned as values and capture immutable bindings by value; capturing a reassignable local binding
is rejected. Shared mutation can be performed through a captured mutable reference to a heap object.

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
import zeron.io.println;

let i = 0;
until (i == 5) {
    println(i);
    i += 1;
}
```

`loop` has no condition and therefore continues indefinitely unless control leaves the loop, for
example with `break;` or by returning from the function. `break;` exits the innermost enclosing
loop. `continue;` starts the next iteration of the innermost loop: it rechecks the condition for
`while`, `until`, and `loop`, or advances the element/range before checking again for `for`. Neither
statement can cross a function or lambda boundary. These constructs are compiled to JVM control
flow, and loop conditions must have type `Boolean`.

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

`for` supports `Array<T>` values, `zeron.ranges.IntRange` values, and values conforming to the bundled
`Iterable<T>` contract. The loop evaluates its iterable expression once, binds an immutable element
name, and supports `break` and `continue`. Arrays use dedicated index-based lowering. Integer range
literals construct ordinary `IntRange` values, whose `iterator()`, `hasNext()`, and `next()` methods
use contract dispatch. Ranges are inclusive, choose an ascending or descending unit step from their
endpoints, and do not increment after yielding the final endpoint, avoiding integer overflow.

#### Range syntax

You can create a Range with the `..`  operator:

```zeron
let oneThroughTen = 1..10;
```

#### Iterables

The `for` form binds an immutable name for each value:

```zeron
let mut accumulator = 0;
for (let i in 1..10) {
    accumulator += i;
}
```

The parser requires `let` in the loop header. Arrays must currently be non-empty because empty array
literals are not implemented. Range values can be stored and iterated later like any other iterable.

#### Making an iterable

`Iterator<T>` and `Iterable<T>` are ordinary generic contracts in package `zeron.collections`,
defined in `src/main/resources/stdlib/iteration.zn`. The CLI loads this file as a separate source
unit. A custom iterable imports and implements these public contracts through ordinary class
conformance; arrays retain specialized lowering and ranges use ordinary protocol dispatch.

```zeron
package geometry;

import zeron.collections.Iterable;

public class PersonList is Iterable<Person> {
    people: Array<Person>;
    public constructor new;
    public iterator(): &Iterator<Person> = ...;
}
```

### Classes

Classes are nominal reference types with object identity. A class has private fields, one canonical
constructor that initializes every field in declaration order, and methods with explicit visibility
and return types. The canonical constructor is public by default; an explicit private declaration
can restrict direct construction. Implemented named constructors are static factory methods with
expression or block bodies and an implicit `&Class` result:

```zeron
class Person {
    name: String;
    age: Int;
    private constructor new;
    public readAge(): Int = this.age;
    public constructor fromName(name: String) = Person.new(name, 0);
    public mut birthday(): Unit {
        this.age = this.age + 1;
    }
}

let person = Person.fromName("Ada");
```

Construction initializes every field exactly once. Omitting a canonical declaration synthesizes a
public constructor; `private constructor new;` restricts direct construction. Fields are private and
there are no public fields, generated accessors, properties, or user-written canonical-constructor
bodies. Named factories have no `this`; block bodies must return `&Class` on every normal path, and
all object allocation still goes through canonical `new`. Methods provide the class API. Contracts declare required method signatures; the
source design permits multiple declaration-site conformances, checked statically, and calls through
contract-typed references use interface dispatch, including when a class conforms to multiple
contracts. Separate `implement` declarations, default methods, contract inheritance, and class
inheritance remain deferred.

```zeron
class PersonList is Iterable<Person> { ... }
```

#### Mutability

Binding mutability and reference mutation capability are independent. `let mut` allows a name to be
rebound; it does not grant permission to mutate the referenced object. A plain `T` is a read-only
view, while `&T` is a mutable view. An immutable binding may hold an `&T`, and a reassignable binding
may hold a read-only `T`.

Methods that mutate their receiver are marked `mut`; calling them, or a setter that mutates state,
requires a mutable receiver view. A newly constructed object has a mutable reference view (`&T`),
which may be projected to read-only `T`; the reverse conversion is not implicit. `&T` does not imply
exclusive access or borrow checking: aliases may observe mutations.

```zeron
class Person {
    age: Int;
    public constructor new;
    public mut grow(): Unit {
        this.age = this.age + 1;
    }
}

fn growPerson(person: &Person): Unit {
    person.grow();
}
```
