# Intrinsic Arrays and Reference Views

## Status

The initial reference-view and fixed-size array slice is implemented. It uses a dedicated invariant
`Array<T>` descriptor, preserves `&T` in resolved types, and supports non-empty array literals,
indexed reads and writes, and `.length` through the compiler backend. It is an explicit-AST
implementation, not yet the intrinsic registry described below.

The construction syntax is a non-empty literal, `[value, ...]`. Every element is initialized before
the literal produces a value; `null` combines with a single concrete element type to infer a nullable
element type. A fresh literal produces `&Array<T>` so its slots may be updated; assigning it to
`Array<T>` projects that view to read-only. `array[index]` reads a slot, `array[index] = value` writes
through `&Array<T>`, and `array.length` returns the fixed length. Indexes are zero-based; compiled
programs use `Objects.checkIndex` and throw `IndexOutOfBoundsException` for an invalid index.
Each literal creates a distinct backing array. Empty literals and allocation by length are not
implemented.

## Purpose

Arrays are useful as a basic indexed collection and as a bridge to JVM APIs. They should not,
however, introduce a second set of rules for generic type identity or collapse binding reassignment
into permission to mutate array contents.

This proposal builds on the binding/reference distinction in
[design-document-04_mutability.md](design-document-04_mutability.md) and the class and contract
roadmap in [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

## Current Foundation

- `let` and `let mut` already distinguish immutable and reassignable bindings. This is independent
  of whether the value referenced by a binding can be mutated.
- `&T` is preserved as a resolved reference-view qualifier, including when it qualifies a function
  type. Mutable views project implicitly to their read-only base type; the reverse conversion is
  rejected. This does not imply exclusive ownership or borrow checking.
- Invariant generic classes and contracts support member substitution and raw JVM lowering, including
  callback adaptation across erased nominal boundaries. The broader adapter-shape matrix remains
  follow-up coverage; see [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md).
- `Array<T>` has a dedicated invariant descriptor, and indexed reads, writes, and length are
  represented by explicit AST operations lowered by the compiler.

The first array slice implements these type checks and operations directly. The registry and
declaration model below remain future work; they are intended to replace the current built-in
special cases, not prerequisites for the slice already in use.

## Recommended Model

### Source-level type identity

Expose arrays as a parameterized type, written `Array<T>`. To the type checker, `Array<Int>` is a
type constructor applied to an element type, with ordinary type identity and equality. It is
invariant in `T`: `Array<Dog>` is not assignable to `Array<Animal>` merely because `Dog` is an
`Animal`. A mutable array view could otherwise write an `Animal` that is not a `Dog` into the
array.

The compiler recognizes `Array` as a built-in type constructor and lowers it to `Object[]` in this
initial slice, boxing primitive elements. This implementation detail does not affect array
assignment, inference, or equality. The dedicated array descriptor preserves source-level element
identity and invariance independently of this JVM representation.

Nullability is independent: `Array<T>?` means a nullable reference to an array. It does not make
the array's elements nullable; that is written `Array<T?>`.

### Binding reassignment and reference capability

Keep these permissions separate:

| Type or declaration | Permission |
| --- | --- |
| `let values: Array<T>` | The name cannot be rebound; the array is viewed as read-only. |
| `let mut values: Array<T>` | The name can be rebound; the array is still viewed as read-only. |
| `let values: &Array<T>` | The name cannot be rebound; this view may update array slots. |
| `let mut values: &Array<T>` | The name can be rebound, and the current view may update array slots. |

The ordinary `Array<T>` view is read-only. A mutable `&Array<T>` may be projected to a read-only
`Array<T>` view without copying the array. This is a one-way implicit conversion: a read-only view
cannot be converted back to `&Array<T>` without an explicit operation that establishes mutation
capability. In the initial design, do not provide a cast that simply asserts this capability.

Read-only is a permission of a particular view, not a guarantee that no alias can mutate the
underlying array. This matches the proposed `&T` model: mutable aliases may coexist, and no
Rust-style exclusivity or data-race guarantee is implied.

### Indexed access and projections

An ordinary indexed read, `values[index]`, returns an element viewed as `T`. Index assignment is
allowed only when the receiver has mutable array capability:

```zeron
fn first(values: Array<Int>): Int = values[0];

fn replaceFirst(values: &Array<Int>): Unit {
    values[0] = 42;
}
```

The mutable capability applies to the array's slots. It does not recursively grant permission to
mutate objects stored in those slots. For example, a writable `&Array<Person>` permits replacing a
slot with another `Person`; mutating a `Person` through a slot requires the element itself to carry
mutable reference capability, such as an element type `&Person`.

Do not initially define `&values[index]` as a general mutable reference projection. A slot update
can be represented as an indexed assignment checked against `&Array<T>`, without exposing a
first-class reference to the slot. If first-class projected references are added later, specify
their lifetime and aliasing behavior separately; the current language has no borrow checker to
enforce non-escape or exclusivity.

### Minimal user-visible contract

The implemented first slice settles the basic collection contract as follows:

- Arrays have fixed length; resizing and structural mutation are deferred.
- `.length` returns the number of slots.
- Indexes are zero-based; invalid indexes throw `IndexOutOfBoundsException` in compiled programs.
- Reads work through `Array<T>`; writes require `&Array<T>`.
- Non-empty literals initialize every slot before exposing the array. Allocation by length and
  uninitialized slots are unavailable.
- Each literal allocates a distinct array. Structural equality, slicing, multidimensional syntax,
  and integration with the user-defined iterator protocol remain deferred. `for` loops over arrays
  are implemented with dedicated index-based lowering and do not require the intrinsic registry or
  an iterator protocol.

Empty literals and contextual element typing remain open. Allocation by length alone must not
expose JVM zero-initialization as if it were a language guarantee.

## Intrinsic Implementation

An intrinsic is a source-level operation or type whose implementation is supplied by the compiler
or runtime rather than by an ordinary Zeron class body. Its source signature and type rules should
remain visible to the resolver; only its execution is special.

### Avoid scattered special cases

### Current implementation boundary

- `ArrayDescriptor` carries the element type and is invariant by descriptor equality.
- The parser recognizes the built-in `Array` constructor and the `length` property.
- Reads, writes, and literals have distinct AST operations. `.length` is represented as a property
  expression and resolved as the built-in array length operation when its receiver is an array.
- The resolver checks element compatibility, requires `Int` indexes, and enforces mutable views on
  writes. Compiled operations use `Objects.checkIndex`.
- The compiler lowers all arrays to boxed `Object[]`.
- There is not yet a registry of stable intrinsic IDs or a source declaration form for intrinsic
  signatures.

### Target architecture: registered intrinsics

The current parser and resolver spelling checks are a small initial implementation, not the intended
long-term dispatch model. Avoid spreading more behavior-specific checks across the
parser, resolver, and compiler. That couples syntax, type identity, and JVM lowering,
and makes user-defined types with the same name hazardous.

Instead, introduce a small intrinsic registry with stable internal identifiers and declared
signatures. A conceptual catalogue might contain:

```text
array.allocate<T>(length: Int) -> Array<T>
array.length<T>(array: Array<T>) -> Int
array.read<T>(array: Array<T>, index: Int) -> T
array.write<T>(array: &Array<T>, index: Int, value: T) -> Unit
```

This is an implementation sketch, not proposed callable source syntax. The source language may
expose length as a property and reads/writes as indexing. The resolver must still check element
types, index types, and mutable capability before lowering an operation.

### Registry responsibilities

- Give each intrinsic a stable identity independent of a user-visible spelling.
- Associate it with a source-level signature and any required type-parameter substitution.
- Let resolution produce a typed intrinsic operation or another explicit resolved representation.
- Let each execution backend implement that identity without redoing type checking.
- Reject duplicate or conflicting intrinsic registrations during compiler initialization.

Begin with an internal registry or built-in catalogue; do not add an `intrinsic` keyword to user
syntax solely for arrays. If external or standard-library intrinsic declarations become useful,
design a source annotation that binds a declaration to a registered intrinsic identity. Keep the
declaration's type signature as the authority for checking calls; never dispatch solely on a
stringly typed method name.

One future direction is a signature-only, class-like declaration (sometimes described as an
"expected class") that states which members an intrinsic type promises, with the registry binding
those signatures to stable intrinsic IDs and backend implementations. The term and syntax are not
defined in this repository; an intrinsic contract or catalogue may be a better fit. In either form,
`.length` should resolve to a generic intrinsic operation such as `array.length`, rather than
  requiring a spelling-specific array check in property resolution. The same mechanism could later bind indexed get/set
operations and host-provided implementations.

The initial parser represents literals, indexed reads, indexed writes, and properties; the resolver
recognizes array `.length`, records operation types, and checks capabilities. The compiler lowers them to `Object[]`
operations and `Objects.checkIndex` bounds checks. As the intrinsic registry is introduced,
resolution should produce a typed intrinsic operation with a stable ID, and the compiler should
dispatch on that ID. This would replace spelling-based intrinsic recognition and keep operation
identity out of parser spelling and backend type-checking.

### JVM representation choices

The current compiler lowers all elements to boxed values in JVM `Object[]`; nullable primitives and
function values therefore fit the same carrier. Specialized primitive/reference arrays may be a
future optimization, but must preserve the current source-level invariance and nullability behavior.

Keep source type equality independent of this layout. Centralize the element-to-array descriptor
mapping in the type-lowering layer; do not let JVM descriptors define variance or source-level
assignability. Decide how Java array covariance and Java-originated values are handled before
interoperability is exposed. A source-level invariant type check is required even if the JVM array
representation has its own runtime store checks.

## Implementation Roadmap

1. **Freeze the implemented contract.** Non-empty literals, fixed length, `.length`, zero-based
  indexing, checked bounds, initialized slots, fresh-array identity, and specialized `for` iteration
  are the current choices. Empty literals, allocation by length, resizing, slicing, structural
  equality, and custom iterator integration remain deferred.
2. **Represent `Array<T>`.** Implemented with a dedicated element-aware descriptor, invariant
  equality, and composition with nullability, reference views, and function types.
3. **Represent reference capability.** Implemented as `&T`, including function types, with
  mutable-to-read-only projection and no implicit reverse conversion. `let mut` remains only a
  binding-reassignment permission.
4. **Add syntax and typed AST operations.** Implemented for non-empty literals, indexed reads,
  indexed assignment, and `.length`, represented through the general property expression.
5. **Add resolver checks and intrinsic identities.** Element and index checks and mutable-write
  enforcement are implemented. Stable intrinsic IDs and signature registration remain future work.
6. **Compiler execution: implemented.** The compiler backend uses `Object[]`, boxed primitive
  elements, and `Objects.checkIndex`. A reference interpreter was removed; specialized layouts and
  Java interoperability remain future work.
7. **Test the contract end to end.** Tests cover projection, invariance, nullable slots, primitive
  boxing/unboxing, aliasing, bounds failures, and generated-code execution. Broader interoperability,
  all reference/function element combinations, and descriptor inspection remain follow-up coverage.
8. **Replace spelling-based intrinsic handling.** Design a stable intrinsic registry/catalogue,
  decide whether intrinsic contracts should have source declarations (possibly an expected-class
  form), then lower `.length` and indexed operations to generic resolved intrinsic operations.

## Acceptance Criteria

- `Array<T>` behaves as one invariant source-level type constructor with element-aware type identity.
- Read-only views permit reading but reject slot replacement; mutable views permit slot replacement.
- A mutable view can be passed to read-only code without granting that code write capability.
- `let mut` affects only rebinding; it does not silently make an array mutable.
- Slot mutability is shallow and does not grant mutation of a referenced element object.
- Array operations have explicit typed AST nodes and matching resolver and compiler behavior. Stable
  intrinsic identities are a future architectural goal, not yet implemented.
- Source-level type rules do not depend on JVM array descriptors or runtime class names.

## Deferred Questions

- Should `Array<T>` interoperate directly with Java arrays, or should an intrinsic runtime wrapper
  mediate Java's covariant array behavior?
- Should support be extended beyond non-empty literals to fixed-size allocation with an initializer?
- Should arrays later conform to the standard `Iterable<T>` contract, or continue to use only their
  dedicated index-based loop lowering?
- If first-class element-slot references are eventually added, what lifetime and escape rules govern
  them without exclusive borrowing?
- Should the intrinsic registry remain compiler-internal, or become a general source-level extension
  mechanism for standard-library and host-provided operations? Should source declarations resemble
  intrinsic contracts, expected classes, or another signature-only construct?
