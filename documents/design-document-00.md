## Language Design Notes 00: Zeron design features overview
- [Types](#types)
- [Variables](#variables)
- [Functions](#functions)
- [Simplest program](#sidetrack-simplest-zeron-program)
- [Higher order functions and Lambdas](#higher-order-functions-and-lambdas)
- [Control flow](#control-flow)
- [Ranges and Iterables](#ranges-and-iterables)

### Languages to review for alternatives
Java, Kotlin, Scala, Haskell, OCaml, Swift, Rust, Zig, 
Haxe, Julia, CoffeeScript (see until!), F#

### Wishlist for this bitch ass language:
- Bottom type, Unit type and Type inference: done, except for lambda expressions, which need a 
  receiver type
- Free functions: but not directly as types
- ~~Implicit returns?~~ support for single expression bodies
- Lambdas
- Ranges and iteration
- Classes (small feature duh)
- - fields can only be private, use getters and setters
- - only one level of inheritance (abstract class)
- - contracts are specified by interfaces/traits/type classes
- - TODO: explore only private methods and contract upcasting for implementation detail hiding.
this would necessitate intersection types (e.g `Iterable[T] & Autoclosable`)
- Generics
- Explicit resource management
- First class nullable type widening (array and mutable types too)
- ADTs
- Discriminated unions?
- Immutable data structures
- List comprehensions
- Structural tuples? (BIG maybe, nominal sure)
- TODO: explore first class monad support in syntax
- EFFECTS!!!!!!!!!!! I WANT THIS SHIT SO BAD
- Receiver methods as extensions

---

### Types
#### Theoretical
```
Never (Bottom type): throw expressions
Void (Unit type): the unit
```
#### Built In
```
Int, Double, String, Boolean
```
#### Type modifiers and compound or structural types
```
// Type modifiers
Mutable types:
    &Type

Nullable types:
    Type?

Array types:
    Type[]

// Structural types
Function types:
    (Type...) -> Type
    
Discriminated unions:
    type Type = A | B
    
Generic types:
    Type<T>
```
#### Precedence
For the three type modifiers, precedence is mutable = nullable > array.
- `&Person?` is a mutable nullable reference to a `Person`.
`&Person?[]` is an array of mutable references.

---

### Variables
In Zeron, a variable is a bind from a name to a value. It is illegal to 
declare a variable without an initialization expression at its right
hand side.
#### Declaration
Current candidate for a Zeron variable declaration:
```
let mut accumulator = 0;
let ITERATION_LIMIT = 16;
```
#### Type inference
Zeron can infer types for variable declarations whenever the initialization
expression is resolvable to a well-formed type. If this is not possible,
the types should be explicitly stated:
```
let mut currentHighest: Person? = null;
```
#### Syntax candidates
```
constant declaration options:
    implicit bind, implicitly constant:
    {NAME} = {EXPRESSION}
    
    explicit bind, implicitly constant: 
    {BIND} {NAME} = {EXPRESSION}
    
    
    explicit bind, explicitly constant:
    {CONSTANT} {BIND} {NAME} = {EXPRESSION}
    
    (EXPRESSION: any valid expression )
    (NAME: any valid identifier )
    (BIND: const | val | let | var | etc )
    (CONSTANT: const | final | etc )
    
variable declaration 
    implicit bind, implicitly variable:
    {NAME} = {EXPRESSION}
    
    explicit bind, implicitly variable:
    {BIND} {NAME} = {EXPRESSION}
    
    explicit bind, explicitly variable:
    {BIND} {MUTABLE} {NAME} = {EXPRESSION}
    
    (EXPRESSION: any valid expression )
    (NAME: any valid identifier )
    (BIND: let | var | etc )
    (MUTABLE: mut | var | etc )
```

---

### Functions
In Zeron, functions are arbitrarily invocable pieces of logic bound to
a name.
#### Declaration
Current candidate for function declaration is:
```
let multiply(a: Int, b: Int): Int {
    return a * b;
}

let multiply(a: Int, b: Int): Int -> a * b;
let multiply(a: Int, b: Int): Int = Int::times;

// inferred as (Int, Int) -> Int
let multiply(a: Int, b: Int) -> a * b;
```
#### Invocation
In Zeron, functions invocations follow the C syntax convention:
```
multiply(a, b);
```
#### Type inference
Zeron can infer the return types for functions, given the returned
expression can be inferred as well:
```
// can be inferred to (String) -> String
let greeting(name: String) {
    return "Hello, " + name;
}
```
#### Syntax
```
{BIND} {NAME}{ARGUMENT LIST}[":" {TYPE}] {FUNCTION BODY}

(BIND: let )
(NAME: any valid identifier )
(ARGUMENT LIST: ARGUMENT separated by commas )
    (ARGUMENT: {NAME}":" {TYPE} )
(TYPE: {NAME}{NULL | ARRAY} )
(FUNCTION BODY: {SHORT BODY} | {LONG BODY})
    (SHORT BODY: ("=" | "->") {EXPRESSION} ";" )
    (LONG BODY: "{" {STATEMENTS} "}" )
```

---

### Sidetrack: simplest Zeron program
Given the syntax so far, the simplest Zeron program possible would become the following:
```
let main() -> print("Hello world");
```
Or for those who would prefer the longer version, the full method, including the explicit
Unit return:
```
let main(): Unit {
    return print("Hello world");
}
```

---

### Higher order functions and Lambdas
Zeron supports higher order functions by allowing functions
to be sent as arguments to others:
```
let doSomething(number: Int, action: (Int) -> Unit) {
    action(number);
}

doSomething(5, print);
```
For logic that hasn't been previously defined, one can
instead use a lambda function for brevity:
```
doSomething(5, (item) -> {
    if (item % 2 == 0) print(item);
});
```
Or even, if the last argument in a parameter list is a 
single argument function:
```
doSomething(number) {
    // 'it' is the default name for a single argument lambda
    print(it * 2); 
};
```
#### Lambda syntax
```
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
```
if (isMalformed(uri)) {
    return Response.error("URI is malformed: " + uri);
} else {
    return Response.success(Json.from(uri));
}
```
If is also an expression:
```
let x = if (n < 0) then 0 else n;

let x =      if (n <  0) then "negative" 
        else if (n == 0) then "zero"
        else if (n >  0) then "positive";
```
The traditional while loop:
```
while (iterator.hasNext()) {
    let value = iterator.next();
    consume(value);
}
```
For inverted conditions, the until keyword provides better 
clarity:
```
let i = 0;
until (i == 5) {
    print(i);
    i += 1;
}
```
For indefinite iteration, the loop keyword:
```
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
```
let oneThroughTen = 1..10;
```
#### Iterables
In Zeron, ranges, arrays and iterables can participate in 
the iteration constructs provided by the language:
```
let mut accumulator = 0;
for (i in 1..10) {
    accumulator += i;
}
```
#### Making an iterable
In Zeron, any custom type is eligible to become an iterable
by implementing the Iterable contract:
```
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
```
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
```
print("This person's name is: " + p.name);
                                // ^ error: no getter has
                                //   been defined for 'name'
```
To expose the fields in John, you can add getters and setters:
```
class Person {
    name: String;
    get -> name;
    set -> name = it;
```
The getters we're defining here are just returning and modifying
the value, respectively. Writing this for every field would be a
lot of boilerplate, so Zeron lets you opt into default getters 
and setters by adding the `public` keyword in front of it:
```
class Person {
    public name: String;
```
You can even mix and match. The following:
```
class Person {
    public name: String;
    set -> name = sanitize(it);
```
Will provide a default getter with a custom setter.
#### Mutability
Since all fields are private by default, they're also freely
mutable within the context of a class, with one caveat:
methods that mutate them must declare they do so with `mut`:
```
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
```
let p = Person.new("John", 37);
p.grow();
   // ^ semantic error: mutable method cannot be called
   //   on an immutable reference
```
To solve this, we must obtain a mutable reference to person:
```
let p = mut Person.new("John", 37);
p.grow();
print(p.age); // 38!
```