# Generic Functions: Implementation and Roadmap

## Status

Zeron has an implemented first slice of generic named functions. Calls support inference and
explicit type arguments, generic signatures are substituted during resolution, and the compiler
erases type variables to JVM reference types. Callback parameters and callback return values,
including nested callbacks, nullable callback values, and mutable function views, are adapted through
generated bridge helpers.

Generic methods on classes and contracts support inferred or explicit type arguments at member calls,
including multiple contract bounds on method type parameters. Contract
implementations match generic method signatures up to renaming of method type parameters. The
method parameters are erased in JVM descriptors; the Zeron library index retains them for
Zeron-to-Zeron consumers, and class/method `Signature` attributes expose the corresponding generic
metadata to JVM tools.

Invariant generic classes/contracts and callback adaptation across their erased nominal boundaries
are also implemented; see [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md)
and [design-document-03_lambda-lowering.md](design-document-03_lambda-lowering.md). Generic named
functions can be specialized as monomorphic function values. Immutable let-bound lambdas support a
limited rank-1 scheme slice. A first slice of multiple contract bounds on generic function and
generic method type parameters is implemented. Variance, nested schemes, explicit
`forall`, and polymorphic values stored in mutable bindings remain deferred. This is not a complete
generic type system.

Generic class type arguments are inferred at canonical and named-constructor calls. Inference uses
constructor arguments, including each element passed to a variadic named constructor, and an expected
constructed type when one is available. Lambda argument results can contribute constraints after
other arguments and expected types have partially instantiated the constructor signature. This does
not infer standalone class references or use later statements as constraints.

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

Type parameters are scoped to one declaration. Generic functions and generic methods may give each
parameter one or more contract bounds, including applied generic bounds such as `C: Box<E>`. Each
bound exposes its non-mutating methods in the generic body and every applied bound is checked
against inferred or explicit type arguments. Class bounds, bounds on generic classes/contracts, and
mutating bound methods remain unsupported. Generic functions and methods may also call class-side
factory requirements through bounds; the compiler selects a compatible public named constructor,
honoring explicit witness remapping, and passes its method handle as hidden evidence. Witness
metadata is preserved in API-index v18 for separately compiled libraries. Full generic-pattern
overlap analysis remains future work. See
[design-document-15_type-class-witnesses-and-generic-bounds.md](design-document-15_type-class-witnesses-and-generic-bounds.md).
Type parameters may appear recursively in parameters, results, function signatures, nullable and
reference types, and the built-in `Array<T>` descriptor. Function type annotations accept zero or
multiple parameters.

Class factory calls use the same bounded structural unification rules for class type parameters.
`List.of(1, 2)` infers `List<Int>`, while `let items: List<Int> = List.empty()` obtains its type
argument from the expected type. Expected types only fill unresolved parameters; they cannot override
conflicting constructor-argument inferences. No common supertype is inferred, and a class type
parameter absent from both argument constraints and the expected type requires an explicit class
type argument. Constructor calls that collide with a namespace function are ambiguous when both
candidates accept the call.

Let-bound lambda generalization remains limited to unresolved identity and constant-result forms.
It does not infer contract bounds from member calls or carry implicit constraints into a scheme;
expanding it requires a separate sound constraint-inference rule. Operator requirements remain
deferred to the operator-witness design.

At a direct generic call, the resolver seeds substitutions from explicit type arguments, if given,
then resolves non-lambda arguments and structurally matches their types against the generic
signature. Known substitutions are applied to expected lambda parameter types before the lambda
body is resolved; its result may supply remaining substitutions. Every declared type parameter must
be resolved or the call reports that an explicit type argument is required. Conflicting inferences
are rejected. The resulting arguments are checked against the fully substituted signature.

Unbounded type variables remain opaque inside a generic body. Values can be passed, stored, returned,
and used in supported structural positions, but unary and binary operators are rejected when they
require constraints. A bounded type variable exposes the non-mutating methods declared by any of its
contract bounds.

Contract named constructors are class-side factory requirements, not instance methods; an erased
bounded value alone cannot select their implementation. Generic functions may call a required
factory through the type parameter, such as `C.empty()`. Concrete call sites select a compatible
public constructor from declared conformance and any explicit witness mapping, while generic callers
forward hidden evidence. Applied contract arguments remain available to source-level checking even
though the evidence ABI uses erased method handles. Complete overlap checking for distinct generic
witness patterns remains future work; see
[design-document-15_type-class-witnesses-and-generic-bounds.md](design-document-15_type-class-witnesses-and-generic-bounds.md).

Lambdas with an expected function type remain monomorphic. An immutable let-bound lambda with safe
unresolved parameters may generalize them into a rank-1 scheme, instantiated freshly at each use.
The first slice handles direct parameter identity and constant results. Mutable bindings, nested
schemes, explicit `forall`, and unresolved types used by operators are not generalized. Generic
function names still require full explicit specialization (`name::<T>`) or an expected function type;
those named-function references are monomorphic values.

## Implementation Details

### Parsing and type identity

The parser recognizes `fn name<T, R>(...)`, `fn display<T: Named + Encodable>(...)`, direct calls such as
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
nullable/reference wrappers, and existing generic descriptors. It is not a general subtype solver: there are no variance rules or inference from a desired result
type alone. Overloaded calls infer each generic candidate independently, discard inapplicable
candidates, and select the unique most-specific applicable parameter signature. Non-generic
candidates win only as a final tie-break; ambiguous candidates require a more specific argument or
explicit type arguments.
Contract bounds may authorize readonly contract methods on generic function and method type
parameters; all bounds are checked independently.
If a type parameter appears only in an unconstrained lambda parameter, the caller must
provide enough information elsewhere or pass an explicit type argument.

Lambdas and bare function names passed as arguments are resolved against the partially substituted
function signature. Immutable let-bound lambda schemes instantiate freshly at calls and expected
function types; the binding's scheme is not mutated by an individual use. Let-bound lambda
generalization remains limited to identity and constant results: it does not invent contract bounds
or operator constraints, and the lambda syntax has no explicit bounded-parameter form. Operator-constrained
lambda parameters remain monomorphic and may use later call context. Generic function references validate
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
  `LambdaMetafactory`. Polymorphic function values and partial type-argument lists remain deferred.
  Overloaded function references require an expected function type that selects one signature.
4. **Invariant generic classes and contracts implemented.** Constructor and member substitution,
   declaration-site conformance, invariant identity, raw JVM erasure, nominal callback adapters, and
   erased contract bridges are covered. Broader shape combinations and adapter reuse remain follow-up
   coverage.
5. **Multiple contract bounds on generic functions and methods: first slice implemented.** Each
  function or method type parameter may have multiple contract bounds. Call sites validate inferred
  and explicit type arguments against every bound; generic bodies may call a bound's non-mutating
  methods through a cast and interface dispatch. Bounds on generic classes/contracts, class bounds,
  mutating methods, operator constraints, variance, and broader inference remain deferred.
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
- Multiple contract-bounded generic function and method calls validate inferred and explicit type
  arguments against each bound; only readonly methods declared by a bound are callable on `T`.
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
