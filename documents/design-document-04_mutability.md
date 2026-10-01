# Mutability: Bindings and Values

## Purpose

Zeron currently uses mutability to describe two different permissions:

1. Whether a name can be rebound to a different value.
2. Whether a reference can be used to mutate the value it points to.

These permissions are independent and should remain separate in the syntax, type model, resolver, and compiler. This document recommends semantics and an implementation order. It does not propose a borrow checker or ownership system.

## Current State

The language overview in `design-document-00.md` already shows both ideas:

- `let mut accumulator = 0` makes a binding reassignable.
- `&Person` and a `mut` method/receiver sketch describe permission to mutate an object.

Binding reassignment is implemented as a separate policy. The parser records `let mut` as `BindingMutability.REASSIGNABLE` and ordinary `let` as `BindingMutability.IMMUTABLE`. The resolver rejects writes to immutable bindings, and the compiler emits local/global assignment stores. Scoped symbols restore shadowed bindings when a nested scope ends. Zeron uses the compiler backend; there is no reference interpreter or runtime assignment backstop.

Reference-view capability is preserved in resolved types and enforced for array-slot writes and
mutable-to-read-only projections, including function types. Mutating class members remain
unimplemented because classes and member dispatch are not yet part of the language.

## Recommendation A: Binding Reassignment

Binding mutability answers: **Can this name be assigned another value?** It does not answer whether the current value can be changed internally.

Keep the existing declaration syntax:

```zeron
let count = 0;       // binding cannot be reassigned
let mut count = 0;   // binding can be reassigned
```

Represent this as binding metadata, not as a type modifier. Prefer a positively named concept such as `BindingMutability` with `IMMUTABLE` and `REASSIGNABLE`, rather than storing the inverse `isFinal` boolean throughout the compiler. The declaration AST and resolved binding should carry that policy.

The resolver rejects assignment to an immutable binding. The compiler lowers the same policy for local variables and global fields; JVM `final` is useful for fields, but it is not a substitute for source-level validation of every assignment.

Reassignment should preserve the declared type. For example, `let mut n: Int? = 1; n = null;` changes the binding's value but does not change its static type from `Int?`.

## Recommendation B: Mutation Capability

Mutation capability answers: **May code using this reference invoke operations that mutate the referenced value?** It is a property of the reference/view, not of the name holding it.

Retain `&T` as the proposed mutable-capability view if that syntax remains desirable, and let plain `T` denote a read-only view. A read-only view may be widened to a mutable view only through an explicit operation that proves or creates that capability; conversion from mutable `&T` to read-only `T` can be allowed. Do not make a binding reassignable merely because its type is `&T`.

Illustrative separation:

```zeron
let mut current: Person = first; // may rebind `current`
let fixed: Person = first;       // may not rebind `fixed`
```

When class members exist, capability controls calls that can mutate the receiver:

```zeron
fn rename(person: Person): Unit {
    // read-only view: cannot call a mutating method
}

fn rename(person: &Person): Unit {
    // mutable view: may call a mutating method
}
```

Whether mutation is exposed through `mut` methods, setters, mutable fields, or some combination should be decided with the class/member design. The invariant should be independent of that syntax: a mutating operation requires mutable receiver capability, while rebinding the receiver's local name separately requires `let mut`.

For arrays, distinguish changing the array binding from changing its contents. `let mut values: Int[]` could allow assigning another array to `values`; mutating an element should require the array's mutation capability. This avoids accidentally making every collection mutable just because its variable is reassignable.

### Aliasing and Scope of the Guarantee

Treat `&T` as permission to mutate, not as proof of exclusive access. Multiple references may alias, and mutation through one is visible through the others unless a later ownership design says otherwise. Do not promise Rust-like borrow checking, uniqueness, or data-race safety from this marker alone.

## Type and Compiler Model

Keep binding policy outside `TypeDescriptor`. If mutation capability becomes part of the type system, model it as a distinct reference/view qualifier over a base type, separate from nullability. For example, a nullable mutable view conceptually combines `&` capability with `?` nullability without encoding either concept into the nominal type's display name.

On the JVM, these permissions normally need no new runtime class representation: both views can use the same reference descriptor. The resolver enforces the capability; the compiler emits ordinary field or method calls after the check. This keeps a language-level permission from leaking into structural type equality or generated JVM class names.

## Recommended Implementation Order

1. **Binding reassignment baseline: implemented.** The AST and bindings use an explicit policy; resolver and compiler handle reassignment, with sample coverage for local/global writes and shadow restoration.
2. **Preserve `&T` in the type model: implemented.** Reference capability is resolved and enforced for arrays and function-view projection. Class/member mutation semantics remain future work.
3. **Specify class mutation operations.** Decide which declarations are mutating, how setters and fields participate, and whether methods overload by receiver capability.
4. **Extend capability checks to class members.** The type model and one-way projection are implemented; once classes exist, enforce receiver capability at method calls, assignments, and member access.
5. **Expand tests around the distinction.** Current array and function-view tests cover projection and mutable slot access. Add class-member cases when that feature exists, while keeping binding reassignment independent.

## Design Decision

Implement binding reassignment as an independent declaration property. Reserve reference mutation capability for class, member, and collection semantics, where the compiler and resolver can enforce it. Do not combine either concept with nullability or type-parameter identity.
