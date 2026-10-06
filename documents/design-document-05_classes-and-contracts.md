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
- Sealed contracts declare a closed set of permitted classes with `sealed contract C permits A, B`.
  Permitted classes must be in the contract package, conform directly, and use matching generic
  parameters in declaration order. The compiler records permits in library metadata and the JVM
  `PermittedSubclasses` attribute. `Option<T>` is the bundled example. Exhaustive `match`
  expressions check the permitted set explicitly; sealing alone does not make arbitrary branches
  exhaustive.
- Parameterized nominal types retain source-level identity but erase to one raw JVM class or interface per declaration. Generic contract bridges adapt differing erased signatures.
- Named constructors are static factories with expression or block bodies and an implicit mutable class-reference result. Omitted canonical declarations synthesize public construction; `private constructor new;` restricts it.
- `Iterator<T>` and `Iterable<T>` are ordinary bundled contracts. `Iterator<T>.next()` returns
  `zeron.lang.Option<T>`; `None` signals exhaustion and `Some` carries a value. The `for` loop
  lowers this protocol and extracts elements only from `Some`.
  `zeron.ranges.IntRange` implements
    `Iterable<Int>` and range loops invoke its methods through interface dispatch; arrays retain
    specialized lowering.

## Recommended Semantic Model

### Sealed contracts

An ordinary contract remains an open protocol. A sealed contract closes its direct implementation
set with a non-empty `permits` clause:

```zeron
public sealed contract Option<T> permits Some<T>, None<T> {
    isSome(): Boolean;
}
```

Each permitted class must be declared in the contract's package, directly conform to the contract,
and have the same number of type parameters. For the initial generic design, the permits arguments
and the class's conformance arguments must refer to their respective declaration parameters in the
same order. Public sealed contracts may permit only public classes. Classes are final in the current
language, so a permitted class cannot open the sealed set through subclassing.

The compiler enforces the permits list during resolution and emits the JVM `PermittedSubclasses`
attribute. Compiled-library metadata carries the sealed flag and permits templates so separate
consumer compilations enforce the same rule. A permits-list change is a public API change. Sealing
does not by itself make branches exhaustive; the match resolver separately checks coverage using the
sealed permits list, including when contract metadata comes from a compiled library.

### Matching sealed contracts

`match` is an expression over a non-null sealed-contract value:

```zeron
match (option) {
    case Some<T> as some -> some.value;
    case None<T> -> defaultValue;
}
```

Each arm is an expression followed by `;`. A type case must name one of the contract's directly
permitted classes, and its generic arguments must match the scrutinee contract's arguments. `as`
introduces an immutable, arm-scoped value narrowed to that class. A `_` case is an optional final
catch-all; without it, every permitted class must appear exactly once. Duplicate cases and cases
after `_` are errors. The arms must have a common result type, and the scrutinee is evaluated once.

Nullable values must be proven non-null before matching. The initial pattern set does not include
payload destructuring, nested patterns, guards, or OR-patterns. At runtime the compiler dispatches
with `instanceof` and casts. A runtime null injected through Java interop is rejected before dispatch,
and a non-wildcard match has a defensive `IllegalStateException` fallback.

### Nominal identity

A class declaration introduces a nominal type. Equality and assignability are based on the language-level type identity, not on a JVM binary name or generated artifact path. Keep this identity separate from the compiler's JVM representation, just as function-shape identity is separate from generated SAM interface names.

Class and contract identities are package-qualified across the current compilation set. Module identity may extend this representation if modules are introduced; generated JVM names remain separate from semantic equality.

### Objects, fields, and construction

Class instances are reference-identity heap objects. Fields are private and cannot declare visibility. Visibility-marked properties provide public or private accessor APIs without exposing their backing storage.

Construction must initialize every field before the new object becomes observable. Each class has
one canonical constructor, which accepts one argument for each field without an initializer, in
field-declaration order, followed by each uninitialized auto-property in property-declaration order,
and lowers to the JVM `<init>`. If a class omits a canonical-constructor
declaration, the compiler synthesizes a public one. A class may explicitly declare
`private constructor new;` to restrict direct construction; an explicit `public constructor new;`
remains valid but is redundant.

```zeron
class Person {
    name: String;
    age: Int;
}

let person = Person.new("Ada", 37);
```

The compiler generates the JVM `<init>` for `new`; the source call allocates the object and invokes
the canonical constructor. A field initializer is a fixed class-defined initial value, not a default
constructor argument: initialized fields and auto-properties are omitted from the constructor
signature and cannot be overridden by callers. Initializers run once per construction; field
initializers run in field order followed by auto-property initializers in property order. An
initializer may use literals, operators, and reads of earlier fields, including earlier
constructor-supplied fields. It may not read itself or a later field, call functions or methods,
write state, create lambdas, or otherwise perform unsupported effects. This restricted expression
set makes initialization order explicit; named constructors can provide alternate values by
calling the canonical constructor and assigning fields explicitly.

```zeron
class Server {
    host: String;
    port: Int = 8080;
    secure: Boolean = false;
    public constructor new;
}

let server = Server.new("example.com");
```

The canonical constructor is the only object-allocation path and initializes every field and
auto-property before
exposing the instance. There are no user-written canonical-constructor bodies.

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
Named-constructor parameters may end in one variadic `T...` parameter. The factory body receives
that parameter as `Array<T>`, and direct factory calls pack all positional arguments after the
fixed prefix. Defaults and spread arguments are not supported on named constructors. Canonical
`new` parameters are still derived from fields and properties and do not support variadics.

### Mutation and references

Preserve three separate questions:

1. Can a local name be rebound? This is controlled by binding mutability, such as `let` versus `let mut`.
2. Can code mutate the object reached through a reference? This is controlled by reference capability, proposed as read-only `T` versus mutable `&T`.
3. Which class operations mutate the receiver? Mutating methods require mutable receiver capability.

An immutable binding may hold a mutable reference, and a reassignable binding may hold a read-only reference. These are not interchangeable permissions. A fresh `Class.new(...)` expression has type `&Class`; it can be projected to a read-only `Class` view, but a read-only view cannot be upgraded. `&T` does not imply exclusive ownership or borrow checking; aliases may observe the same mutation.

Every class method and property declares visibility explicitly with `public` or `private`. `mut` is independent of visibility: for methods it requires a mutable receiver; for properties it adds a setter and requires a mutable receiver for writes. Method return types are explicit in v1. Fields are private without a modifier.

Properties use the same `name: Type` member shape as fields but require visibility. Without an
accessor block, the compiler provides private backing storage and a getter; `mut` additionally
provides a setter. An optional initializer supplies the fixed initial value and removes that
auto-property from canonical constructor parameters.

Custom properties use `get` and `set(value)` accessors with expression or block bodies. The declared
property type is the getter result and setter parameter type. Custom accessors do not receive hidden
backing storage; code that needs state declares an ordinary private field and accesses it by name.
Custom properties require a getter, cannot have an initializer, and require `mut` when they define a
setter.

Reads require a visible getter. Writes require a setter and a mutable receiver (`&Class` or
`&Contract`). Compound assignment is supported on writable properties: the receiver is evaluated
once, then the getter, right-hand side/operator, and setter execute in order. It is a
read-compute-write operation, not an atomic update. `??=` remains limited to mutable local bindings.
Safe-navigation assignment is not supported.

Contracts may declare `property name: Type;` for a read-only property or
`mut property name: Type;` for a writable property. A conforming public class property must have
the same type; a writable requirement needs
a setter, while a read-only requirement accepts either read-only or writable implementations.
Auto-properties and custom accessors satisfy the same contract requirements through generated JVM
accessor methods.

```zeron
contract Meter {
    mut property value: Int;
}

class Thermostat is Meter {
    storedValue: Int;
    public mut property value: Int {
        get = this.storedValue;
        set(next) = this.storedValue = next;
    }

    public property name: String;
    public mut property target: Int = 20;
}
```

Inside an instance method, an unresolved field read or method call may use the current receiver
implicitly. Lexical locals and parameters take precedence, followed by top-level functions; only
then are class fields or methods considered. Implicit field reads and method calls use the same
visibility and receiver-mutability checks as `this.field` and `this.method(...)`. Field writes
remain explicit and must use `this.field = value`. Lambdas may use implicit members and capture
the immutable `this` binding under the ordinary lambda-capture rules. Named constructors are static
factories and do not have implicit `this`.

```zeron
class Person {
    name: String;
    age: Int;
    public constructor new;

    public name(): String = name;
    public mut birthday(): Unit {
        grow();
    }
    private mut grow(): Unit {
        this.age = age + 1;
    }
}
```

For example, a closure may capture an immutable binding by value. If that binding contains an explicitly mutable reference to a heap object, the captured reference may provide shared mutation according to the reference-capability rules. This does not permit implicitly capturing a reassignable local binding.

### Contracts

A contract describes required member signatures and permits a value of a concrete class to be used through that abstraction. Conformance should be checked statically: every required member must exist with compatible parameter, return, and receiver-capability types.

Contract methods have explicit return types and no visibility modifier; requirements and default
implementations are public by definition. `default` introduces an optional concrete method
implementation, and may precede `mut`:

```zeron
contract Named {
    property name: String;
    default label(): String = this.name;
}
```

A default method may call required methods and read required properties through `this`; mutating
defaults require `default mut` and a mutable receiver. A public class method overrides a compatible
default. One unique compatible default may satisfy an abstract requirement, including a requirement
from another contract implemented by the class. Multiple applicable defaults require an explicit
class method. Default methods have no stored state and compile as JVM interface methods; calls use
normal interface dispatch. Compiled API indexes preserve whether an exported contract method has a
default body.

A class may conform to multiple contracts through a
comma-separated declaration-site list. Every requirement must be satisfied by a compatible public
class method or applicable default method. The implementation return type may be assignable to the required return type under
the existing nominal and nullable compatibility rules; parameter types and receiver mutability must
still match exactly. Identical requirements from several contracts can be satisfied by one method.
Conflicting requirements with
the same name are rejected because overloads are not supported. Calls through contract-typed
references use interface dispatch. Each class remains final; multiple contract conformance is not
class inheritance or contract-to-contract inheritance.

Defer associated types, multiple inheritance, and intersection types. These features depend on a
working base model and should not be prerequisites for ordinary classes or simple contracts.

### Invariant generic classes and contracts

Class and contract type parameters are scoped to their declaration. Methods may declare their own unbounded type parameters after the method name; those parameters are scoped to that method and cannot shadow enclosing class or contract parameters. Member calls infer method arguments from their values and contextual lambdas, or accept explicit arguments before the call argument list. Contract implementations must match generic method signatures up to renaming of method type parameters. Generic nominal types are invariant: `Box<Int>` is distinct from `Box<String>`, and a parameterized class or contract must be used with exactly its declared number of type arguments. Construction supplies explicit arguments on the class type before `.new`; constructor inference is not performed.

Fields, method parameters, method results, `this`, and constructor fields are substituted from the receiver or construction type. Type parameters remain opaque inside generic bodies; operations requiring constraints are rejected. Each declaration emits one JVM class or interface regardless of its source type arguments. Type variables erase to `java.lang.Object`, while parameterized nominal types erase to their raw JVM class.

Generic contracts use declaration-site conformance with explicit contract arguments. Requirements are substituted before checking public visibility, exact parameter types, covariant result assignability, and receiver mutability. Calls through parameterized contract types use interface dispatch. If a concrete implementation signature differs from the erased interface signature, a public synthetic bridge adapts arguments and results with casts and boxing/unboxing as needed.

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
    public close(): Unit = ();
}
```

The grammar is:

```text
ClassDeclaration     ::= "class" Identifier [TypeParameters] ["is" ContractList] "{" ClassMember* "}"
TypeParameters       ::= "<" Identifier {"," Identifier} ">"
ContractList         ::= ContractUse {"," ContractUse}
ContractUse          ::= Identifier ["<" TypeList ">"]
TypeList             ::= Type {"," Type}
ClassMember          ::= Field | Property | Constructor | NamedConstructor | Method
Field                ::= Identifier ":" Type ["=" Expression] ";"
Property             ::= Visibility ["mut"] "property" Identifier ":" Type ["=" Expression] ";"
                     |  Visibility ["mut"] "property" Identifier ":" Type "{" PropertyAccessor+ "}"
PropertyAccessor     ::= "get" ("=" Expression ";" | Block)
                     |  "set" "(" Identifier ")" ("=" Expression ";" | Block)
Constructor          ::= Visibility "constructor" "new" ";"
NamedConstructor     ::= Visibility "constructor" Identifier "(" [ParameterList] ")" MethodBody
Method               ::= Visibility ["mut"] Identifier "(" [ParameterList] ")" ":" Type MethodBody
ContractDeclaration  ::= "contract" Identifier [TypeParameters] "{" (ContractProperty | ContractMethod)* "}"
ContractProperty     ::= ["mut"] "property" Identifier ":" Type ";"
ContractMethod       ::= ["mut"] Identifier "(" [ParameterList] ")" ":" Type ";"
Visibility           ::= "public" | "private"
MethodBody           ::= "=" Expression ";" | Block
```

Fields, properties, methods, and named constructors share one member namespace; duplicate member names and overloads are not supported. Every class has one canonical constructor, synthesized as public when omitted; only `private constructor new;` is needed to restrict direct construction. Named constructors lower to static factories, have no `this`, and return `&Class`; every reachable normal path in a block must return a class reference. Factory bodies may use locals and branches, and may return any expression assignable to `&Class`. Object allocation and initialization still happen only through the canonical constructor. Method calls use `receiver.method(...)`; property reads and writes use `receiver.property` and `receiver.property = value`. Field reads and writes use explicitly named private storage. `this` names the current receiver. Initializers are fixed values, not optional constructor parameters; only uninitialized fields and auto-properties appear in the canonical constructor signature.

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
4. **Properties implemented.** Visibility-marked auto-properties and explicit custom accessors share
    the ordinary member namespace; contract property requirements, generic ABI metadata, receiver
    mutability checks, and receiver-once compound assignment are covered by runtime tests.
5. **Expand the type-system surface.** Consider class inheritance, abstract classes,
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
- Auto-properties and custom accessors implement read-only/writable contract property requirements.
- Writable property assignment requires a mutable receiver; compound assignment evaluates the
  receiver once and follows getter-compute-setter order.
- Generic class and contract arguments are invariant, substituted consistently, and erased to one
    raw JVM class/interface per declaration; bridge methods preserve generic contract dispatch.
- A class instance can be captured by a lambda according to the explicit reference-capability rules, while mutable local bindings remain uncaptured.
- Runtime tests and generated bytecode agree on object identity, method dispatch, and field access.

## Explicitly Deferred

Intrinsic or expected classes are also deferred. This possible signature-only declaration form could
describe the types and members promised by a compiler- or runtime-provided implementation, with
those declarations bound to registered intrinsic identities. Its relationship to ordinary contracts,
conformance, visibility, and backend binding remains undecided; no source syntax is selected. The
internal array intrinsic registry does not introduce this language feature. See
[design-document-12_intrinsics-and-external-bindings.md](design-document-12_intrinsics-and-external-bindings.md)
for the current distinction between intrinsic IDs and future external/expected declarations.

Class inheritance, abstract classes, bounds, variance, source-level overload resolution, broader
inference, contract-to-contract inheritance, intersection types, extension
methods, JPMS integration, serialization, public fields, generated accessors, and ownership/borrow
checking remain deferred. Class-directory compiled-library discovery and the initial Java interop
slice are implemented; JAR discovery and broader Java platform integration remain future work. Each deferred feature adds semantic rules that should build
on tested nominal identity, construction, access control, and receiver capability rather than being
inferred from JVM behavior.

Revisit these deferrals after the initial object and contract model meets the acceptance criteria above.
