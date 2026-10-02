# Classes and Contracts: Design and Roadmap

## Purpose

This document records the initial class-and-contract feature set for Zeron and its later roadmap. It builds on the type overview in [design-document-00.md](design-document-00.md), the binding/reference distinction in [design-document-04_mutability.md](design-document-04_mutability.md), and the current function representation in [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md).

The goal is to establish useful object semantics before adding inheritance or advanced contract composition. Classes should also provide explicit shared heap state for programs and closures without changing the rule that lambdas cannot implicitly capture mutable local bindings.

## Implemented Foundation

- Class and contract names are collected before member resolution, so signatures can refer to types declared later in the same program. Names are unique in the program type namespace and remain source-level nominal identities.
- Classes conform to multiple contracts through declaration-site conformance and lower to generated JVM classes implementing each generated contract interface. Generated binary names do not determine source-level assignability.
- Binding reassignment and reference mutation remain independent: `let mut x` permits rebinding, while `&T` permits mutation through the reference.
- Lambdas can capture immutable class or contract references by value, including mutable reference views. Reassignable local bindings remain uncapturable.
- Classes and contracts support invariant type parameters. Fields, methods, construction, and declaration-site conformance are checked after substitution.
- Parameterized nominal types retain source-level identity but erase to one raw JVM class or interface per declaration. Generic contract bridges adapt differing erased signatures.
- Named constructors are static factories with expression or block bodies and an implicit mutable class-reference result. Omitted canonical declarations synthesize public construction; `private constructor new;` restricts it.

## Recommended Semantic Model

### Nominal identity

A class declaration introduces a nominal type. Equality and assignability are based on the language-level type identity, not on a JVM binary name or generated artifact path. Keep this identity separate from the compiler's JVM representation, just as function-shape identity is separate from generated SAM interface names.

For the initial single-program compiler, class names can be unique within the program's type namespace. The type identity representation should be extendable to include module and package identity when those features exist. Do not make generated names part of semantic equality.

### Objects, fields, and construction

Class instances are reference-identity heap objects. Every field is private; fields cannot declare visibility, and v1 generates no accessors. Public methods define the class API and preserve control over invariants.

Construction must initialize every field before the new object becomes observable. Each class has one
canonical constructor, which accepts one argument per field in field-declaration order and lowers to
the JVM `<init>`. If a class omits a canonical-constructor declaration, the compiler synthesizes a
public one. A class may explicitly declare `private constructor new;` to restrict direct construction;
an explicit `public constructor new;` remains valid but is redundant.

```zeron
class Person {
    name: String;
    age: Int;
}

let person = Person.new("Ada", 37);
```

The compiler generates the JVM `<init>` for `new`; the source call allocates the object and invokes
the canonical constructor. There are no field initializers or user-written canonical-constructor
bodies. The canonical constructor is the only object-allocation path and initializes every field
before exposing the instance.

Named constructors are additional static factory entry points. They require explicit visibility,
have a unique name in the class member namespace, and accept either an expression body or a block:

```zeron
class Person {
    name: String;
    age: Int;
    private constructor new;

    public constructor fromName(name: String) = Person.new(name, 0);

    public constructor senior(name: String) {
        let person = Person.new(name, 65);
        person.age = person.age + 1;
        return person;
    }
}
```

A named constructor has the implicit result type `&Class`; every reachable normal path in a block
must return a value of that type. It has no implicit `this` because it runs as a static factory. To
work with a constructed object, bind the result of the canonical constructor to a local and use that
receiver explicitly. Returning an expression of type `&Class` is allowed; it need not be a direct
`Class.new(...)` expression. Generic class arguments remain explicit at the call site. Named
constructors do not overload, and their names share the class member namespace with fields and
methods. They lower to static factory methods; only canonical `new` lowers to JVM `<init>`.

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

Contracts contain required method signatures with explicit return types and no visibility modifier;
those requirements are public by definition. A class may conform to multiple contracts through a
comma-separated declaration-site list. Every requirement must be satisfied by a compatible public
class method; parameter types, return types, and receiver mutability must match. Identical
requirements from several contracts can be satisfied by one method. Conflicting requirements with
the same name are rejected because overloads are not supported. Calls through contract-typed
references use interface dispatch. Each class remains final; multiple contract conformance is not
class inheritance or contract-to-contract inheritance.

Defer default implementations, associated types, multiple inheritance, and intersection types. These features depend on a working base model and should not be prerequisites for ordinary classes or simple contracts.

### Invariant generic classes and contracts

Class and contract type parameters are scoped to their declaration. Generic nominal types are invariant: `Box<Int>` is distinct from `Box<String>`, and a parameterized class or contract must be used with exactly its declared number of type arguments. Construction supplies explicit arguments on the class type before `.new`; constructor inference is not performed.

Fields, method parameters, method results, `this`, and constructor fields are substituted from the receiver or construction type. Type parameters remain opaque inside generic bodies; operations requiring constraints are rejected. Each declaration emits one JVM class or interface regardless of its source type arguments. Type variables erase to `java.lang.Object`, while parameterized nominal types erase to their raw JVM class.

Generic contracts use declaration-site conformance with explicit contract arguments. Requirements are substituted before checking public visibility, exact parameter and result types, and receiver mutability. Calls through parameterized contract types use interface dispatch. If a concrete implementation signature differs from the erased interface signature, a public synthetic bridge adapts arguments and results with casts and boxing/unboxing as needed.

Callback adaptation across erased generic nominal boundaries is implemented. Lambdas are contextually resolved against substituted constructor and member signatures; the compiler plans adapters for callback-valued fields, member arguments/results, and erased contract bridges. Generated bridge methods can call the public synthetic static adapters in the program class. Resolver, runtime, and ABI tests cover primitive/reference specializations, field reads/writes, method arguments/results, nested and nullable callbacks, mutable function views, and both contract bridge directions. Broader shape combinations and adapter reuse remain follow-up coverage.

Bounds, variance, overload resolution, constructor inference, raw generic uses, and advanced contract composition remain outside this slice.

```zeron
contract Readable<T> {
    readValue(): T;
}

class Box<T> is Readable<T> {
    value: T;
    public constructor new;
    public readValue(): T = this.value;
}

let box = Box<Int>.new(42);
```

## V1 Syntax

V1 uses declaration-site conformance and does not support separate `implement` declarations. `fn` remains the introducer for top-level functions; class and contract methods omit it because their declaration forms are distinguished by visibility or contract context.
The grammar below is implemented source syntax. A class may list multiple contracts after `is`.

```zeron
contract Named {
    name(): String;
}

contract Closeable {
    close(): Unit;
}

class Person is Named, Closeable {
    displayName: String;
    public constructor new;
    public name(): String = this.displayName;
    public close(): Unit = unit;
}
```

The grammar is:

```text
ClassDeclaration     ::= "class" Identifier [TypeParameters] ["is" ContractList] "{" ClassMember* "}"
TypeParameters       ::= "<" Identifier {"," Identifier} ">"
ContractList         ::= ContractUse {"," ContractUse}
ContractUse          ::= Identifier ["<" TypeList ">"]
TypeList             ::= Type {"," Type}
ClassMember          ::= Field | Constructor | NamedConstructor | Method
Field                ::= Identifier ":" Type ";"
Constructor          ::= Visibility "constructor" "new" ";"
NamedConstructor     ::= Visibility "constructor" Identifier "(" [ParameterList] ")" MethodBody
Method               ::= Visibility ["mut"] Identifier "(" [ParameterList] ")" ":" Type MethodBody
ContractDeclaration  ::= "contract" Identifier [TypeParameters] "{" ContractMethod* "}"
ContractMethod       ::= ["mut"] Identifier "(" [ParameterList] ")" ":" Type ";"
Visibility           ::= "public" | "private"
MethodBody           ::= "=" Expression ";" | Block
```

Fields, methods, and named constructors share one member namespace; duplicate member names and overloads are not supported. Every class has one canonical constructor, synthesized as public when omitted; only `private constructor new;` is needed to restrict direct construction. Named constructors lower to static factories, have no `this`, and return `&Class`; every reachable normal path in a block must return a class reference. Factory bodies may use locals and branches, and may return any expression assignable to `&Class`. Object allocation and initialization still happen only through the canonical constructor. Method calls use `receiver.method(...)`; field reads and writes use `receiver.field` and `receiver.field = value`, with writes allowed only through a mutable view from within the declaring class. `this` names the current receiver.

## Implementation Roadmap

1. **V1 implemented.** The parser, resolver, JVM class/interface lowering, constructor calls, fields,
    methods, multiple-contract conformance, receiver mutability, and immutable-reference lambda capture
    are implemented and tested.
2. **Invariant generic classes and contracts implemented.** Explicit construction arguments,
    member substitution, invariant identity, raw JVM erasure, substituted conformance, and erased
    signature bridges are covered by resolver and runtime tests.
3. **Named constructors implemented.** Named factories support expression and block bodies, implicit
    `&Class` returns, generic class substitutions, private canonical construction, and public static
    JVM lowering.
4. **Expand the type-system surface.** Consider class inheritance, abstract classes,
    contract-to-contract inheritance, intersection types, bounds, variance, overload resolution,
    broader inference, and module-qualified type identity as separate designs.

## Acceptance Criteria

The initial class-and-contract milestone is complete when:

- Class names resolve to stable semantic nominal identities independent of JVM names.
- Construction either initializes every required field or fails at compile time.
- Backing fields are inaccessible outside their declaring class; method and constructor visibility is explicit.
- Rebinding a variable and mutating an object through a reference are checked independently.
- Mutating methods cannot be invoked through a read-only reference.
- Contract conformance and calls through a contract type are statically checked.
- Generic class and contract arguments are invariant, substituted consistently, and erased to one
    raw JVM class/interface per declaration; bridge methods preserve generic contract dispatch.
- A class instance can be captured by a lambda according to the explicit reference-capability rules, while mutable local bindings remain uncaptured.
- Runtime tests and generated bytecode agree on object identity, method dispatch, and field access.

## Explicitly Deferred

The current roadmap defers class inheritance, abstract classes, bounds, variance,
overload resolution, broader inference, default contract methods, contract-to-contract inheritance,
intersection types, extension methods, package/module loading, serialization, public fields, generated
accessors, and ownership/borrow checking. Each deferred feature adds semantic rules that should build
on tested nominal identity, construction, access control, and receiver capability rather than being
inferred from JVM behavior.

Revisit these deferrals after the initial object and contract model meets the acceptance criteria above.
