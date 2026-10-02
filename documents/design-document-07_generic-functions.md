# Generic Functions: Implementation and Roadmap

## Status

Zeron has an implemented first slice of generic named functions. Calls support inference and
explicit type arguments, generic signatures are substituted during resolution, and the compiler
erases type variables to JVM reference types. Callback parameters and callback return values,
including nested callbacks, nullable callback values, and mutable function views, are adapted through
generated bridge helpers.

Invariant generic classes/contracts and callback adaptation across their erased nominal boundaries
are also implemented; see [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md)
and [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md). Bounds, variance,
overloads, and first-class generic function values remain deferred, so this is not a complete generic
type system.

## Language Contract

A generic function declares its type parameters after its name and must state its return type:

```zeron
fn identity<T>(value: T): T = value;

fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);

identity(42);                  // infer T as Int
identity<String>("zeron");    // explicit type argument
```

Type parameters are scoped to one declaration. The current syntax permits them recursively in
parameters, results, function signatures, nullable and reference types, and the built-in
`Array<T>` descriptor. Function type annotations accept zero or multiple parameters.

At a direct generic call, the resolver seeds substitutions from explicit type arguments, if given,
then resolves non-lambda arguments and structurally matches their types against the generic
signature. Known substitutions are applied to expected lambda parameter types before the lambda
body is resolved; its result may supply remaining substitutions. Every declared type parameter must
be resolved or the call reports that an explicit type argument is required. Conflicting inferences
are rejected. The resulting arguments are checked against the fully substituted signature.

Type variables are opaque inside a generic body. Values can be passed, stored, returned, and used in
supported structural positions, but unary and binary operators are rejected when they require
constraints. There is no bounds or trait/contract constraint syntax. Lambdas remain monomorphic;
generic function values and implicit specialization of a function name are not supported.

## Implementation Details

### Parsing and type identity

The parser recognizes `fn name<T, R>(...)` and `name<Int>(...)`. A generic declaration without an
explicit return annotation is rejected. Call type arguments are retained on the call AST node for
resolution. Type-parameter descriptors include a declaration-scope identity so unrelated `T`
parameters do not compare equal merely because they share a spelling.

`FunctionDescriptor` retains the generic parameter list. `TypeSubstitution` recursively substitutes
and erases type variables through nullable, reference, array, generic-descriptor, and function
descriptors. `Array<T>` has its existing dedicated invariant descriptor and lowers to `Object[]`.
User-defined invariant generic classes and contracts retain parameterized source identities and erase
to their raw JVM class or interface; see [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

### Resolution

Generic call inference is structural and intentionally bounded. It handles direct type variables,
function signatures, arrays (including a mutable array literal projected to a read-only parameter),
nullable/reference wrappers, and existing generic descriptors. It is not a general subtype solver:
there are no constraints, variance rules, overload selection, or inference from a desired result
type alone. If a type parameter appears only in an unconstrained lambda parameter, the caller must
provide enough information elsewhere or pass an explicit type argument.

Lambdas passed as arguments are resolved against the partially substituted function signature.
Lambdas returned from a function are resolved against that function's declared return signature.
The lambda itself does not acquire the enclosing function's polymorphism.

### JVM lowering

Generic functions compile once; the compiler does not specialize a method for every type argument.
Type variables map to `java.lang.Object` in method descriptors. Primitive values are boxed when
passed through an erased parameter and cast/unboxed when returned to a concrete call-site type.
Reference values are cast at the corresponding boundary. Source-level type checking remains based
on descriptors, not these JVM representations.

A direct callback type containing type variables, such as `(T) -> R`, lowers to a generated SAM
shape based on its erased signature. When a concrete lambda value crosses into that erased callback
parameter, or an erased callback result crosses back to a concrete shape, the compiler emits a
private static bridge method in the generated program class. The bridge performs the required
casts, boxing, and unboxing; `LambdaMetafactory` creates the adapter object. Concrete function
shapes and ordinary non-generic lambda lowering remain unchanged.

Adapter discovery recursively follows function parameters and results through nullable and
reference-view wrappers. Nested adapters are registered in the direction required by the outer
bridge, and nullable callback values pass through a null-preserving helper before a non-null adapter
is created. Generic function references are still outside this implementation. The primitive and
reference shape matrix and adapter reuse need broader tests before this boundary should be treated
as fully characterized.

## Implementation Roadmap

1. **Stabilize the direct-call slice.** Keep inference, explicit arguments, return annotations,
   opaque type-variable behavior, arrays, primitive boxing, and direct callback parameter/result
   bridges covered by resolver and generated-code tests.
2. **Callback-shape adaptation: implemented baseline.** Nullable callback values, nested callback
  parameters/results, and mutable function views are adapted recursively without changing
  source-level function-shape identity. Expand tests across all primitive/reference combinations
  and bridge reuse.
3. **Design generic function values.** Define explicit specialization or expected-function-type
   instantiation for passing generic named functions as values. Resolve capture and overload
   interactions before implementation.
4. **Invariant generic classes and contracts implemented.** Constructor and member substitution,
   declaration-site conformance, invariant identity, raw JVM erasure, nominal callback adapters, and
   erased contract bridges are covered. Broader shape combinations and adapter reuse remain follow-up
   coverage.
5. **Add constraints only with a coherent member model.** Specify constraint declarations, checking,
  dispatch, and how constrained operations lower before permitting operators or members on `T`.
  Bounds, variance, overloads, and broader inference remain deferred.

## Acceptance Criteria for the Current Slice

- Generic declarations require explicit return types and keep type parameters scoped to the
  declaration.
- Calls infer consistently from values and contextual lambdas, accept explicit type arguments, and
  diagnose conflicts or unresolved parameters.
- Type-specific operations on unconstrained type variables fail during resolution rather than
  producing invalid bytecode.
- Erased method descriptors, primitive boxing/unboxing, generic array access, and recursive callback
  bridges agree with runtime behavior for inline, stored, returned, nested, nullable, and mutable-view
  callbacks.
- Generic lowering does not change canonical identities for ordinary concrete function shapes.

Update this note whenever parser, resolver, or backend support changes the accepted generic-function contract.
