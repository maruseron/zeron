# Mutability: Bindings and Values

## Purpose

Zeron currently uses mutability to describe two different permissions:

1. Whether a name can be rebound to a different value.
2. Whether a reference can be used to mutate the value it points to.

These permissions are independent and remain separate in the syntax, type model, resolver, and compiler. This document records the current semantics and implementation status. It does not propose a borrow checker or ownership system.

## Current State

The language overview in `design-document-00.md` already shows both ideas:

- `let mut accumulator = 0` makes a binding reassignable.
- `&Person` and a `mut` method/receiver sketch describe permission to mutate an object.

Binding reassignment is implemented as a separate policy. The parser records `let mut` as `BindingMutability.REASSIGNABLE` and ordinary `let` as `BindingMutability.IMMUTABLE`. The resolver rejects writes to immutable bindings, and the compiler emits local/global assignment stores. Scoped symbols restore shadowed bindings when a nested scope ends. Zeron uses the compiler backend; there is no reference interpreter or runtime assignment backstop.

Reference-view capability is preserved in resolved types and enforced for array-slot writes,
function-view projection, and class/contract member access. Class fields are private; writes are
allowed only inside the declaring class through a mutable receiver. Mutating methods require a
mutable receiver, and contract conformance checks receiver mutability.

## Binding Reassignment

Binding mutability answers: **Can this name be assigned another value?** It does not answer whether the current value can be changed internally.

Keep the existing declaration syntax:

```zeron
let count = 0;       // binding cannot be reassigned
let mut count = 0;   // binding can be reassigned
```

Represent this as binding metadata, not as a type modifier. Prefer a positively named concept such as `BindingMutability` with `IMMUTABLE` and `REASSIGNABLE`, rather than storing the inverse `isFinal` boolean throughout the compiler. The declaration AST and resolved binding should carry that policy.

The resolver rejects assignment to an immutable binding. The compiler lowers the same policy for local variables and global fields; JVM `final` is useful for fields, but it is not a substitute for source-level validation of every assignment.

Reassignment should preserve the declared type. For example, `let mut n: Int? = 1; n = null;` changes the binding's value but does not change its static type from `Int?`.

## Mutation Capability

Mutation capability answers: **May code using this reference invoke operations that mutate the referenced value?** It is a property of the reference/view, not of the name holding it.

`&T` is the mutable-capability view, and plain `T` is a read-only view. A mutable `&T` may be
projected to read-only `T`; a read-only view cannot be upgraded. Do not make a binding reassignable
merely because its type is `&T`.

Illustrative separation:

```zeron
let mut current: Person = first; // may rebind `current`
let fixed: Person = first;       // may not rebind `fixed`
```

For class member calls, capability controls whether an operation may mutate the receiver:

```zeron
class Person {
    displayName: String;
    public constructor new;

    public name(): String = this.displayName;
    public mut rename(name: String): Unit {
        this.displayName = name;
    }
}

fn readName(person: Person): String = person.name();
fn renamePerson(person: &Person): Unit {
    person.rename("Grace");
}
```

Class mutation follows these rules:

- A class method is mutating only when explicitly marked `mut`. The marker is independent of
    `public` or `private`; there is no inferred mutation effect.
- Calling a `mut` method requires a mutable receiver view (`&Class`). Non-`mut` methods may be
    called through either mutable or read-only views.
- Fields remain private and have no visibility modifier or generated accessor. Field reads
    are available inside the declaring class; field writes are allowed only there and require a
    mutable receiver. Field writes use direct assignment.
- A property declared `mut` has a setter; assigning through it requires a mutable receiver.
    Compound property assignment evaluates its receiver once, then calls the getter, computes the
    new value, and calls the setter. This read-compute-write sequence is not atomic.
- Contracts may require read-only or writable properties. A writable requirement needs a setter;
    a read-only requirement accepts either kind of implementation.
- Contracts declare whether a required method is `mut`; a conforming public class method must match
    that receiver capability exactly.
- Methods cannot overload by receiver capability. Fields, properties, and methods share one member
    namespace, and duplicate member names are rejected.

These rules do not change binding reassignment: rebinding the local name still separately requires
`let mut`.

For arrays, distinguish changing the array binding from changing its contents. `let mut values: Array<Int>` allows assigning another array to `values`; mutating an element requires `&Array<Int>`. This avoids accidentally making every collection mutable just because its variable is reassignable.

### Aliasing and Scope of the Guarantee

Treat `&T` as permission to mutate, not as proof of exclusive access. Multiple references may alias, and mutation through one is visible through the others unless a later ownership design says otherwise. Do not promise Rust-like borrow checking, uniqueness, or data-race safety from this marker alone.

## Type and Compiler Model

Keep binding policy outside `TypeDescriptor`. Mutation capability is represented as a distinct
reference/view qualifier over a base type, separate from nullability. A nullable mutable view
combines `&` capability with `?` nullability without encoding either concept into the nominal
type's display name.

On the JVM, these permissions normally need no new runtime class representation: both views can use the same reference descriptor. The resolver enforces the capability; the compiler emits ordinary field or method calls after the check. This keeps a language-level permission from leaking into structural type equality or generated JVM class names.

## Implementation Milestones

1. **Binding reassignment baseline: implemented.** The AST and bindings use an explicit policy; resolver and compiler handle reassignment, with sample coverage for local/global writes and shadow restoration.
2. **Preserve `&T` in the type model: implemented.** Reference capability is resolved and enforced for arrays, function views, classes, and contracts. Mutable-to-read-only projection is allowed; the reverse is rejected.
3. **Specify class mutation operations: implemented.** Explicit `mut` methods require mutable receivers; visibility is independent. Fields are private and may be assigned only inside their declaring class through a mutable receiver. Method overloads by receiver capability are unsupported.
4. **Extend capability checks to class members: implemented.** The resolver checks mutable receivers for mutating class and contract methods and writable property assignment, and checks mutable receivers and declaring-class access for field writes.
5. **Expand tests around the distinction: implemented baseline.** Resolver and runtime tests cover binding reassignment, class mutation, read-only rejection, field privacy, and array/function projections. Extend these tests when additional member forms are designed.

## Design Decision

Keep binding reassignment as an independent declaration property. Use `&T` capability for mutations
through class, contract, and collection views; enforce it in the resolver and lower the validated
operations to ordinary JVM references. Do not combine either concept with nullability or
type-parameter identity.
