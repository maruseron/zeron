# Type-Class Witnesses and Generic Bounds

## Status

This document records the agreed direction and implementation status for applied contract bounds
and class-side constructor evidence. Generic functions and methods retain applied contract bounds
and instance calls through those bounds. Class-side factory calls use hidden `MethodHandle` evidence
parameters; concrete callers select a compatible public named constructor from a class's declared
conformance, while generic callers forward compatible evidence. Explicit `witness` declarations
can remap factory requirements, including generic class patterns. These hidden parameters use erased
JVM descriptors, while selection uses source-level applied types.

The API index preserves witness mappings for separately compiled libraries. The current coherence
check rejects witnesses sharing the same nominal contract and target; a general applied-pattern
overlap solver remains future work. See [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md)
and [design-document-07_generic-functions.md](design-document-07_generic-functions.md) for the
conformance and generic-bound foundations.

The feature should make evidence relationships visible as a language concept, in the direction of
type classes, while using compiler-passed witnesses as the runtime representation. The initial
extension is intentionally restricted: a class's declared contract conformance provides evidence
for its instance methods, and an explicit witness may map class-side factory requirements to named
constructors. General non-nominal instances and witness-dispatched instance methods are future work.

## Applied contract bounds

A generic bound denotes a complete applied contract type, not merely a raw contract name. For
example, `Sink<Int>` and `Sink<String>` are distinct constraints even though both erase to the same
JVM interface. Substitution and overload selection retain all nested contract arguments at source
level. The initial generic model remains invariant; adding variance is independent work.

Generic functions and methods may bind a type parameter to an applied contract:

```zeron
fn collectInto<E, C: Sink<E>>(source: Iterable<E>): C {
    let result = C.empty();
    for (let item in source) result.add(item);
    return result;
}
```

Applied bounds such as `C: Sink<E>` retain their type arguments during resolution and conformance
checking, even though those arguments erase in JVM descriptors. Bounds expose non-mutating instance
operations under the current mutability rules; class-side operations are selected through hidden
factory evidence, not through a value receiver. Generic functions support the illustrated factory
call. Generic methods, like generic functions, can call class-side factories through bounds.

## Conformance and witness declarations

An existing class declaration remains the source of nominal instance conformance:

```zeron
class IntList is Sink<Int> {
    public constructor new;
    public constructor createEmpty() = IntList.new();
    public mut add(value: Int): Unit { }
}
```

That conformance automatically supplies evidence for `Sink<Int>` instance-method use. A standalone
witness does not create a second instance or replace the conformance. It completes the class-side
factory mapping:

```zeron
witness Sink<Int> for IntList {
    empty = IntList.createEmpty;
}
```

The spelling is a proposed design. A generic declaration should be expressible without reducing
applied types to raw names:

```zeron
witness<T> Sink<T> for Buffer<T> {
    empty = Buffer<T>.empty;
}
```

This schematic form quantifies over `T`; it is valid only when the corresponding
`Buffer<T> is Sink<T>` conformance and every mapped factory requirement are valid for the full type
pattern.

The witness target is a constructor reference, not a call expression. It records which static
factory should be invoked later and does not evaluate or allocate anything when the witness is
selected. In the initial slice, entries map only class-side factory requirements. Each entry names
the contract requirement on the left and a public named constructor on the implementing class on
the right. A contract factory requirement implicitly returns `&Self`; under a bound on type `C`,
`C.empty()` therefore has type `&C`.

If no explicit witness mapping is written, a requirement uses the same-name constructor when it is
compatible. An explicit mapping replaces that conventional mapping for that requirement and allows
the class factory to have a different name. It does not add another witness candidate. The target
must match the requirement's parameter types after substitution, generic arity, variadic shape,
visibility, and implicit `&Self` result. Constructor selection remains static; no virtual constructor
slot or runtime class-name lookup is introduced.

## Coherence and selection

Witness selection is global and independent of imports, local scope, and call site. For any concrete
type and fully applied contract, there is at most one applicable witness. Missing evidence is a
compile-time error; multiple applicable witnesses are a compile-time ambiguity, never resolved by
choosing the nearest scope or most recent import.

The compiler checks the applied contract against the class's declared conformance after substituting
the witness's type parameters. A witness cannot claim an unrelated conformance. Generic witness
patterns must not overlap in a way that creates multiple applicable witnesses. The initial placement
rule is settled: a witness must be declared in the same package as either its contract or its target
class. Compiled libraries must preserve enough metadata to enforce coherence across compilation
boundaries.

## Generic body and call-site resolution

When compiling a generic function, each bound that declares class-side factories contributes hidden
evidence parameters to its implementation signature. A class-side call such as `C.empty()` is
resolved against that bound and emitted as an invocation on its evidence parameter. It does not
inspect an erased `C`, infer a class from an instance value, or perform dynamic lookup. The current
implementation reserves evidence for every factory on those bounds, even if the function does not
use each factory. Instance methods continue to dispatch through the nominal contract interface.

At a direct generic call, ordinary inference first determines the type arguments. The resolver then
substitutes them into each bound, selects the unique witness for every resulting applied contract,
and supplies those witnesses as hidden arguments. When generic code calls another generic function requiring the same class-side operation, it forwards
the evidence it already received for a compatible bound rather than resolving it again. This
supports separate compilation while keeping source-level type checking precise.

For example, after inference establishes `C = List<Int>` and `E = Int`, a call conceptually lowers to:

```java
newSink(methodHandle(List::empty));
```

where the source-level helper is conceptually `fn newSink<C: Sink<Int>>(): C = C.empty();`.

The implementation uses direct method-handle constants for selected constructors rather than
generated witness classes. Explicit mappings and generic witness patterns participate in the same
selection process.

## JVM representation and library metadata

The witness is the runtime evidence; the type argument itself is not reified. The implementation
passes a `java.lang.invoke.MethodHandle` as hidden evidence and invokes it using an erased
signature-polymorphic call. Generic function descriptors in the API index retain their source-level
bounds; explicit witness and mapping metadata are also serialized.
Generic signatures and factory results use erased JVM descriptors, with bridges or casts where needed.
For example, a `Sink<E>` factory's source result is `&Self`; in a generic body it erases according
to `C`, and a concrete call may cast the returned reference to the inferred concrete class type.
`E` remains available to compile-time resolution and is not required as a runtime class token.

The Zeron API index preserves source-level applied bounds, function signatures, witness patterns,
and factory mappings. Schema version 18 accounts for the hidden-evidence ABI and witness metadata,
allowing mapped witnesses to be consumed across compiled-library boundaries.

## Type classes: intended scope and limits

The witness model provides a foundation for a type-class feature, but it does not immediately turn
every contract into dictionary dispatch. In the first extension:

- Nominal `class C is Contract<Args>` conformance continues to provide instance-method dispatch
  through the existing JVM interface implementation.
- Witnesses provide evidence for class-side factory requirements, including explicit constructor
  remapping.
- Generic bounds may retain applied contract arguments and pass their evidence implicitly.
- General witnesses for types that do not implement the nominal contract are not allowed.
- Instance methods are not redirected through witness dictionaries.

A broader type-class design could later permit non-nominal instances and dispatch instance methods
through witnesses. That would require defining method dictionaries, default method ownership,
overlap and orphan rules, visibility, and how witness evidence composes when an implementation
itself has generic bounds. It should be designed as an explicit expansion, not smuggled into the
initial factory-witness feature.

## Decisions and remaining design questions

Agreed direction:

1. Applied contract arguments are preserved in bound and witness identity.
2. Declared nominal conformance automatically supplies instance-method evidence.
3. Explicit `witness Contract<Args> for Type` declarations provide class-side factory mappings and
   may remap a requirement to a differently named public constructor.
4. A contract factory returns `&Self`; through a bound on `C`, its result is `&C`.
5. Witness selection is globally coherent and compile-time; class-side evidence is passed implicitly
   to generic implementations when required.
6. Witnesses do not reify generic type arguments, and no virtual constructor dispatch is introduced.
7. Initial scope excludes non-nominal instances and witness-dispatched instance methods.

Remaining work includes a complete overlap solver for distinct generic patterns and expanding
separate-compilation and negative diagnostics coverage. The default same-name behavior, constructor
mapping intent, placement rule, and hidden-evidence direction are settled above.
