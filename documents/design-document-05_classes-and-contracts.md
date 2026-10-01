# Classes and Contracts: Design and Roadmap

## Purpose

This document records the initial class-and-contract feature set for Zeron and its later roadmap. It builds on the type overview in [design-document-00.md](design-document-00.md), the binding/reference distinction in [design-document-04_mutability.md](design-document-04_mutability.md), and the current function representation in [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md).

The goal is to establish useful object semantics before adding inheritance, generics, or advanced contract composition. Classes should also provide explicit shared heap state for programs and closures without changing the rule that lambdas cannot implicitly capture mutable local bindings.

## Implemented Foundation

- Class and contract names are collected before member resolution, so signatures can refer to types declared later in the same program. Names are unique in the program type namespace and remain source-level nominal identities.
- Classes lower to generated JVM classes, and contracts lower to JVM interfaces. Generated binary names do not determine source-level assignability.
- Binding reassignment and reference mutation remain independent: `let mut x` permits rebinding, while `&T` permits mutation through the reference.
- Lambdas can capture immutable class or contract references by value, including mutable reference views. Reassignable local bindings remain uncapturable.

## Recommended Semantic Model

### Nominal identity

A class declaration introduces a nominal type. Equality and assignability are based on the language-level type identity, not on a JVM binary name or generated artifact path. Keep this identity separate from the compiler's JVM representation, just as function-shape identity is separate from generated SAM interface names.

For the initial single-program compiler, class names can be unique within the program's type namespace. The type identity representation should be extendable to include module and package identity when those features exist. Do not make generated names part of semantic equality.

### Objects, fields, and construction

Class instances are reference-identity heap objects. Every field is private; fields cannot declare visibility, and v1 generates no accessors. Public methods define the class API and preserve control over invariants.

Construction must initialize every field before the new object becomes observable. Each class declares exactly one canonical constructor, which accepts one argument per field in field-declaration order:

```zeron
class Person {
    name: String;
    age: Int;

    public constructor new;
}

let person = Person.new("Ada", 37);
```

The compiler generates the JVM `<init>` for `new`; the source call allocates the object and invokes that constructor. The constructor's `public` or `private` visibility controls who may call it. There are no field initializers, user-written constructor bodies, or other named constructors in v1. Named constructors can later lower to static factories that delegate to the canonical initialization path.

### Mutation and references

Preserve three separate questions:

1. Can a local name be rebound? This is controlled by binding mutability, such as `let` versus `let mut`.
2. Can code mutate the object reached through a reference? This is controlled by reference capability, proposed as read-only `T` versus mutable `&T`.
3. Which class operations mutate the receiver? Mutating methods require mutable receiver capability.

An immutable binding may hold a mutable reference, and a reassignable binding may hold a read-only reference. These are not interchangeable permissions. A fresh `Class.new(...)` expression has type `&Class`; it can be projected to a read-only `Class` view, but a read-only view cannot be upgraded. `&T` does not imply exclusive ownership or borrow checking; aliases may observe the same mutation.

Every class method declares visibility explicitly with `public` or `private`. `mut` is independent of visibility and marks a method that requires a mutable receiver. Method return types are explicit in v1. Fields are private without a modifier. There are no public fields, generated accessors, properties, or compound field assignments.

```zeron
class Person {
    name: String;
    age: Int;
    public constructor new;

    public name(): String = this.name;
    public mut birthday(): Unit {
        this.grow();
    }
    private mut grow(): Unit {
        this.age = this.age + 1;
    }
}
```

For example, a closure may capture an immutable binding by value. If that binding contains an explicitly mutable reference to a heap object, the captured reference may provide shared mutation according to the reference-capability rules. This does not permit implicitly capturing a reassignable local binding.

### Contracts

A contract describes required member signatures and permits a value of a concrete class to be used through that abstraction. Conformance should be checked statically: every required member must exist with compatible parameter, return, and receiver-capability types.

Contracts contain required method signatures with explicit return types and no visibility modifier; those requirements are public by definition. A class conforms with `class Name is Contract`, and only a compatible public class method satisfies a requirement. Parameter types, return types, and receiver mutability must match. Calls through contract-typed references use interface dispatch. V1 permits one contract per class and supports a statically checked class-to-contract projection, preserving mutability capability.

Defer default implementations, associated types, generic contracts, multiple inheritance, and intersection types. These features depend on a working base model and should not be prerequisites for ordinary classes or simple contracts.

## V1 Syntax

V1 uses declaration-site conformance and does not support separate `implement` declarations. `fn` remains the introducer for top-level functions; class and contract methods omit it because their declaration forms are distinguished by visibility or contract context.

```zeron
contract Named {
    name(): String;
}

class Person is Named {
    name: String;
    public constructor new;
    public name(): String = this.name;
}
```

The grammar is:

```text
ClassDeclaration     ::= "class" Identifier ["is" Identifier] "{" ClassMember* "}"
ClassMember          ::= Field | Constructor | Method
Field                ::= Identifier ":" Type ";"
Constructor          ::= Visibility "constructor" "new" ";"
Method               ::= Visibility ["mut"] Identifier "(" [ParameterList] ")" ":" Type MethodBody
ContractDeclaration  ::= "contract" Identifier "{" ContractMethod* "}"
ContractMethod       ::= ["mut"] Identifier "(" [ParameterList] ")" ":" Type ";"
Visibility           ::= "public" | "private"
MethodBody           ::= "=" Expression ";" | Block
```

Fields and methods share one member namespace; duplicate fields or methods are errors, and overloads are not supported. The canonical constructor is required even for a fieldless class. Method calls use `receiver.method(...)`; field reads and writes use `receiver.field` and `receiver.field = value`, with writes allowed only through a mutable view from within the declaring class. `this` names the current receiver.

## Implementation Roadmap

1. **V1 implemented.** The parser, resolver, JVM class/interface lowering, constructor calls, fields, methods, single-contract conformance, receiver mutability, and immutable-reference lambda capture are implemented and tested.
2. **Add named constructors.** Lower non-canonical named constructors to static factory methods that return fully initialized instances through the canonical constructor.
3. **Expand the type-system surface.** Consider inheritance, abstract classes, multiple contracts, intersection types, generics, and module-qualified type identity only as separate designs with their own conformance rules and tests.

## Acceptance Criteria

The initial class-and-contract milestone is complete when:

- Class names resolve to stable semantic nominal identities independent of JVM names.
- Construction either initializes every required field or fails at compile time.
- Backing fields are inaccessible outside their declaring class; method and constructor visibility is explicit.
- Rebinding a variable and mutating an object through a reference are checked independently.
- Mutating methods cannot be invoked through a read-only reference.
- Contract conformance and calls through a contract type are statically checked.
- A class instance can be captured by a lambda according to the explicit reference-capability rules, while mutable local bindings remain uncaptured.
- Runtime tests and generated bytecode agree on object identity, method dispatch, and field access.

## Explicitly Deferred

V1 defers named constructors, inheritance, abstract classes, generic classes/contracts, default contract methods, intersection types, overload resolution, extension methods, package/module loading, serialization, public fields, generated accessors, and ownership/borrow checking. Each adds semantic rules that should build on tested nominal identity, construction, access control, and receiver capability rather than being inferred from JVM behavior.

Revisit these deferrals after the initial object and contract model meets the acceptance criteria above.
