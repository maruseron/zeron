# Classes and Contracts: Design and Roadmap

## Purpose

This document proposes an incremental path for adding nominal classes and contracts to Zeron. It builds on the type overview in [design-document-00.md](design-document-00.md), the binding/reference distinction in [design-document-04_mutability.md](design-document-04_mutability.md), and the current function representation in [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md).

The goal is to establish useful object semantics before adding inheritance, generics, or advanced contract composition. Classes should also provide explicit shared heap state for programs and closures without changing the rule that lambdas cannot implicitly capture mutable local bindings.

## Current Foundation

- `NominalDescriptor` currently identifies a type by a string name. There is no class declaration AST, member lookup, or object allocation yet.
- The type grammar sketches nullable types, mutable-reference views (`&T`), arrays, functions, and generics. The parser currently does not preserve mutable-reference capability as part of a resolved type.
- Binding reassignment is implemented independently from reference mutation. `let mut x` permits rebinding; it does not by itself describe whether an object referenced by `x` may be mutated.
- Lambda lowering captures immutable values through `invokedynamic`. A captured reference value can preserve object identity, but the rules for capturing and using a future mutable-reference view must be enforced by the class and reference-capability type system.
- The language overview sketches private fields, constructors, accessors, mutating methods, and contracts. It also contains two possible contract-conformance forms; one should be selected before that syntax is implemented.

## Recommended Semantic Model

### Nominal identity

A class declaration introduces a nominal type. Equality and assignability are based on the language-level type identity, not on a JVM binary name or generated artifact path. Keep this identity separate from the compiler's JVM representation, just as function-shape identity is separate from generated SAM interface names.

For the initial single-program compiler, class names can be unique within the program's type namespace. The type identity representation should be extendable to include module and package identity when those features exist. Do not make generated names part of semantic equality.

### Objects, fields, and construction

Class instances are reference-identity heap objects. Fields are private by default. A class declaration may expose field access through generated or custom getters and setters rather than making backing storage public.

Construction must initialize every field before the new object becomes observable. The current overview proposes a constructor that accepts all fields:

```zeron
class Person {
    name: String;
    age: Int;
    constructor new;
}

let person = Person.new("Ada", 37);
```

The first implementation should support one well-defined construction path and reject missing or duplicate initialization. Additional constructors, delegation, and inheritance can follow later.

### Mutation and references

Preserve three separate questions:

1. Can a local name be rebound? This is controlled by binding mutability, such as `let` versus `let mut`.
2. Can code mutate the object reached through a reference? This is controlled by reference capability, proposed as read-only `T` versus mutable `&T`.
3. Which class operations mutate the receiver? Mutating methods and setters require mutable receiver capability.

An immutable binding may hold a mutable reference, and a reassignable binding may hold a read-only reference. These are not interchangeable permissions. `&T` should not imply exclusive ownership or Rust-style borrow checking; aliases may observe the same mutation unless a separate ownership model is designed.

For example, a closure may capture an immutable binding by value. If that binding contains an explicitly mutable reference to a heap object, the captured reference may provide shared mutation according to the reference-capability rules. This does not permit implicitly capturing a reassignable local binding.

### Contracts

A contract describes required member signatures and permits a value of a concrete class to be used through that abstraction. Conformance should be checked statically: every required member must exist with compatible parameter, return, and receiver-capability types.

Begin with named contracts and explicit class conformance. Support calls through a contract-typed reference and a single-contract upcast. Contract values may lower to JVM interfaces, but source contract identity and conformance remain language-level concepts; compiler-generated JVM names are not semantic identities.

Defer default implementations, associated types, generic contracts, multiple inheritance, and intersection types. These features depend on a working base model and should not be prerequisites for ordinary classes or simple contracts.

## Syntax Decisions

The overview currently sketches both declaration-site conformance:

```zeron
class PersonList is Iterable { ... }
```

and a separate implementation declaration:

```zeron
implement Iterable for PersonList { ... }
```

Choose one initial form before parser work. Prefer declaration-site conformance for the first implementation because it keeps the class's contract obligations visible with its members. Keep separate implementations as a possible later extension if orphan rules, coherence, and multiple implementations are specified.

Also settle the exact grammar for fields, constructors, methods, visibility, and accessors. The prose examples in the overview are sketches, not a complete grammar. In particular, define whether a constructor is a reserved `new` member, whether field initializers are supported, and how inferred member types interact with explicit signatures.

## Implementation Roadmap

1. **Freeze the minimal surface.** Choose the initial conformance syntax, constructor form, visibility defaults, and whether field initializers exist. Specify duplicate declarations, forward references, and whether classes can refer to themselves or other classes declared later.
2. **Add nominal declarations and resolution.** Add class and contract AST nodes, parser support, declaration collection, and type-name resolution. Reject duplicate type names and unknown types. Keep semantic identity independent from JVM names and reserve a path to module-qualified identity.
3. **Implement basic objects.** Add backing fields, allocation, and the initial constructor. Enforce field types and definite initialization before exposing the instance. Add tests for construction, field storage, and invalid initializers.
4. **Implement member access and privacy.** Add member lookup and access expressions. Keep storage private by default; implement generated public accessors and explicit custom getters/setters only after visibility and receiver rules are enforced.
5. **Implement methods and mutation capability.** Add instance methods and receiver dispatch. Preserve the distinction between binding reassignment and mutable-reference capability. Require mutable receiver capability to call methods or setters that mutate state; reject such calls through read-only views.
6. **Implement basic contracts.** Add contract declarations, class conformance checks, and contract-typed calls. Test missing members, incompatible signatures, receiver mutability mismatches, and valid upcasts. Start with one contract per declaration if multiple conformance remains undecided.
7. **Integrate with lambdas and runtime behavior.** Test capturing immutable scalar values, capturing object references, and mutation through explicitly shared mutable references. Verify that ordinary mutable locals remain rejected as captures and that SAM shapes do not acquire hidden capture parameters.
8. **Expand the type-system surface.** Add inheritance, abstract classes, multiple contracts, intersection types, generics, and module-qualified type identity only as separate designs with their own conformance rules and tests.

## Acceptance Criteria

The initial class-and-contract milestone is complete when:

- Class names resolve to stable semantic nominal identities independent of JVM names.
- Construction either initializes every required field or fails at compile time.
- Backing fields are inaccessible outside their permitted scope; accessors and methods follow the selected visibility rules.
- Rebinding a variable and mutating an object through a reference are checked independently.
- Mutating methods cannot be invoked through a read-only reference.
- Contract conformance and calls through a contract type are statically checked.
- A class instance can be captured by a lambda according to the explicit reference-capability rules, while mutable local bindings remain uncaptured.
- Runtime tests and generated bytecode agree on object identity, method dispatch, and field access.

## Explicitly Deferred

Do not bundle the following into the first vertical slice: inheritance, abstract classes, generic classes/contracts, default contract methods, intersection types, overload resolution, extension methods, package/module loading, serialization, or ownership/borrow checking. Each adds semantic rules that should build on tested nominal identity, construction, access control, and receiver capability rather than being inferred from JVM behavior.

Revisit these deferrals after the initial object and contract model meets the acceptance criteria above.
