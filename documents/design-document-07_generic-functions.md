# Generic Functions: Implementation and Roadmap

## Status

Zeron has an implemented first slice of generic named functions. Calls support inference and
explicit type arguments, generic signatures are substituted during resolution, and the compiler
erases type variables to JVM reference types. Callback parameters and callback return values,
including nested callbacks, nullable callback values, and mutable function views, are adapted through
generated bridge helpers.

Generic methods on classes and contracts are implemented in the same unbounded first slice:
type arguments are inferred from arguments or supplied explicitly at member calls. Contract
implementations match generic method signatures up to renaming of method type parameters. The
method parameters are erased in JVM descriptors; the Zeron library index retains them for
Zeron-to-Zeron consumers, and class/method `Signature` attributes expose the corresponding generic
metadata to JVM tools.

Invariant generic classes/contracts and callback adaptation across their erased nominal boundaries
are also implemented; see [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md)
and [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md). Generic named
functions can be specialized as monomorphic function values. Immutable let-bound lambdas support a
limited rank-1 scheme slice. A first slice of single contract bounds on generic function type
parameters is implemented. Variance, overloads, nested schemes, explicit `forall`, and polymorphic
values stored in mutable bindings remain deferred. This is not a complete generic type system.

## Language Contract

A generic function declares its type parameters after its name and must state its return type:

```zeron
contract Named {
  name(): String;
}

fn identity<T>(value: T): T = value;
fn display<T: Named>(value: T): String = value.name();

fn apply<T, R>(value: T, transform: (T) -> R): R = transform(value);

identity(42);                  // infer T as Int
identity<String>("zeron");    // explicit type argument
let intIdentity: (Int) -> Int = identity::<Int>;
let stringIdentity: (String) -> String = identity;
```

Type parameters are scoped to one declaration. Generic functions may give each parameter one
contract bound, such as `T: Named`. The bound exposes that contract's non-mutating methods in the
generic body and is checked against inferred or explicit type arguments. Multiple bounds, class
bounds, bounds on generic classes/contracts, and mutating bound methods remain unsupported. Type
parameters may appear recursively in parameters, results, function signatures, nullable and
reference types, and the built-in `Array<T>` descriptor. Function type annotations accept zero or
multiple parameters.

At a direct generic call, the resolver seeds substitutions from explicit type arguments, if given,
then resolves non-lambda arguments and structurally matches their types against the generic
signature. Known substitutions are applied to expected lambda parameter types before the lambda
body is resolved; its result may supply remaining substitutions. Every declared type parameter must
be resolved or the call reports that an explicit type argument is required. Conflicting inferences
are rejected. The resulting arguments are checked against the fully substituted signature.

Unbounded type variables remain opaque inside a generic body. Values can be passed, stored, returned,
and used in supported structural positions, but unary and binary operators are rejected when they
require constraints. A bounded type variable exposes only its bound contract's non-mutating methods.
Lambdas with an expected function type remain monomorphic. An immutable let-bound lambda with safe
unresolved parameters may generalize them into a rank-1 scheme, instantiated freshly at each use.
The first slice handles direct parameter identity and constant results. Mutable bindings, nested
schemes, explicit `forall`, and unresolved types used by operators are not generalized. Generic
function names still require full explicit specialization (`name::<T>`) or an expected function type;
those named-function references are monomorphic values.

## Implementation Details

### Parsing and type identity

The parser recognizes `fn name<T, R>(...)`, `fn display<T: Named>(...)`, direct calls such as
`name<Int>(...)`, and function values such as `name::<Int>`. A generic declaration without an
explicit return annotation is rejected. Type arguments are retained on the call or function-value
AST node for resolution. Type-parameter descriptors include a declaration-scope identity so
unrelated `T` parameters do not compare equal merely because they share a spelling.

`FunctionDescriptor` retains the generic parameter list. `TypeSubstitution` recursively substitutes
and erases type variables through nullable, reference, array, generic-descriptor, and function
descriptors. `Array<T>` has its existing dedicated invariant descriptor and lowers to `Object[]`.
User-defined invariant generic classes and contracts retain parameterized source identities and erase
to their raw JVM class or interface; see [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

### Resolution

Generic call inference is structural and intentionally bounded. It handles direct type variables,
function signatures, arrays (including a mutable array literal projected to a read-only parameter),
nullable/reference wrappers, and existing generic descriptors. It is not a general subtype solver:
there are no variance rules, overload selection, or inference from a desired result type alone. A
single contract bound may authorize readonly contract methods on a generic function type parameter.
If a type parameter appears only in an unconstrained lambda parameter, the caller must
provide enough information elsewhere or pass an explicit type argument.

Lambdas and bare function names passed as arguments are resolved against the partially substituted
function signature. Immutable let-bound lambda schemes instantiate freshly at calls and expected
function types; the binding's scheme is not mutated by an individual use. Operator-constrained lambda
parameters remain monomorphic and may use later call context. Generic function references validate
bounds and expected function types after specialization. Neither a scheme nor a lambda acquires the
enclosing function's polymorphism.

### JVM lowering

Generic functions compile once; the compiler does not specialize a method for every type argument.
Type variables, including contract-bounded variables, map to `java.lang.Object` in method
descriptors. A bounded member call casts the erased receiver to its bound contract and invokes the
contract interface; bounds do not change the function descriptor. Primitive values are boxed when
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
reference-view wrappers. Generalized lambda values use the erased SAM shape as their runtime
representation; calls box or unbox at that boundary, and a concrete callback view uses the existing
adapter mechanism. A specialized generic function value is a zero-capture SAM instance backed
by a generated static bridge. The bridge adapts its concrete callback arguments to the generic
function's erased callback shapes, invokes the original owner, then adapts callback results back to
the specialized shape. Bridge helpers are deduplicated by qualified function identity and erased
specialized shape. Nullable callback values retain null-preserving adapters. The primitive/reference
shape matrix and broader adapter reuse still need coverage.

## Implementation Roadmap

1. **Stabilize the direct-call slice.** Keep inference, explicit arguments, return annotations,
   opaque type-variable behavior, arrays, primitive boxing, and direct callback parameter/result
   bridges covered by resolver and generated-code tests.
2. **Callback-shape adaptation: implemented baseline.** Nullable callback values, nested callback
  parameters/results, and mutable function views are adapted recursively without changing
  source-level function-shape identity. Tests cover all input/output combinations among `Int`,
  `Float`, `Boolean`, and `String`, stored and returned callbacks with captures, and deterministic
  repeated compilation. Directional adapter reuse is covered for generic top-level boundaries;
  broader reuse and shape coverage across nested and nominal boundaries remain follow-up work.
3. **Generic function values: implemented first slice.** `name::<T>` explicitly specializes a
  generic function value; an expected function type can infer the specialization when it determines
  every type parameter. Specialized values lower through deduplicated static bridges and
  `LambdaMetafactory`. Polymorphic function values, partial type-argument lists, and overload
  interactions remain deferred.
4. **Invariant generic classes and contracts implemented.** Constructor and member substitution,
   declaration-site conformance, invariant identity, raw JVM erasure, nominal callback adapters, and
   erased contract bridges are covered. Broader shape combinations and adapter reuse remain follow-up
   coverage.
5. **Single contract bound on generic functions: first slice implemented.** Each function type
  parameter may have one contract bound. Call sites validate inferred and explicit type arguments;
  generic bodies may call the bound's non-mutating methods through a cast and interface dispatch.
  Bounds on generic classes/contracts, multiple or class bounds, mutating methods, operators, variance,
  overloads, and broader inference remain deferred.
6. **Let-bound polymorphic lambdas: implemented first slice.** Immutable identity and constant-result
    lambdas generalize unresolved parameters into rank-1 schemes; each use instantiates independently.
    Mutable bindings, nested schemes, and operator constraints remain deferred.

## Acceptance Criteria for the Current Slice

- Generic declarations require explicit return types and keep type parameters scoped to the
  declaration.
- Calls infer consistently from values and contextual lambdas, accept explicit type arguments, and
  diagnose conflicts or unresolved parameters.
- Generic function values accept full explicit specialization or expected-type inference, validate
  bounds and signature compatibility, and reject unresolved specializations.
- Generic class/contract methods accept inferred or full explicit type arguments, compose method
  substitutions with receiver substitutions, and contract implementation signatures compare up to
  method type-parameter renaming.
- Generic nominal and method signatures are present in JVM class files and agree with erased method
  descriptors; the Zeron API index retains source-level method type parameters for compiled libraries.
- Contract-bounded generic function calls validate inferred and explicit type arguments; only
  readonly methods declared by the bound are callable on `T`.
- Type-specific operations on unconstrained type variables fail during resolution rather than
  producing invalid bytecode.
- Erased method descriptors, primitive boxing/unboxing, generic array access, and recursive callback
  bridges agree with runtime behavior for inline, stored, returned, nested, nullable, and mutable-view
  callbacks.
- Specialized generic function values invoke the original erased static function through generated
  bridges, adapting callback parameters/results across the concrete and erased SAM shapes.
- Generic lowering does not change canonical identities for ordinary concrete function shapes.

Update this note whenever parser, resolver, or backend support changes the accepted generic-function or
generic-method contract.
