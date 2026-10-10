# Type-Class Witnesses and Generic Bounds

## Status

This document records the design for applied contract bounds, class-side constructor evidence, and
non-nominal method witnesses. Generic functions and methods retain applied contract bounds and pass
hidden `MethodHandle` evidence where needed. Witness selection uses source-level applied types;
runtime evidence uses erased JVM descriptors.

The API index preserves witness patterns and their implementation metadata across separately
compiled libraries. The current coherence validation rejects overlapping witness patterns it can
identify; a complete overlap solver for all generic patterns remains future hardening.

The witness model makes evidence relationships explicit as a language concept, in the direction of
type classes, while using compiler-passed method handles as the runtime representation. A class's
declared contract conformance remains authoritative for its instance methods. An explicit witness
may provide instance-method evidence for a target that does not declare that contract, and may map
class-side factory requirements to named constructors.

## Applied contract bounds

A generic bound denotes a complete applied contract type, not merely a raw contract name. For
example, `Sink<Int>` and `Sink<String>` are distinct constraints even though both erase to the same
JVM interface. Substitution and generic inference retain all nested contract arguments at source
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
operations under the current mutability rules; class-side operations use hidden factory evidence.
Generic methods, like generic functions, can invoke class-side factories through bounds.

## Conformance and witness declarations

An existing class declaration remains the source of nominal instance conformance:

```zeron
class IntList is Sink<Int> {
    public constructor new;
    public constructor createEmpty() = IntList.new();
    public mut add(value: Int): Unit { }
}
```

That conformance automatically supplies evidence for `Sink<Int>` instance-method use. A witness
does not create a second instance or replace a declared nominal conformance. For nominal targets a
witness may complete or remap class-side factories, but it cannot override the class's instance
methods:

```zeron
witness Sink<Int> for IntList {
    empty = IntList.createEmpty;
}
```

For a non-nominal target, witness methods provide contract instance-method evidence:

```zeron
public contract Display<T> {
    toString(): String;
}

witness Display<Int> for Int {
    fn toString(): String = intToString(this);
}
```

A non-nominal witness must implement every abstract instance-method requirement. If a method is
omitted and the contract provides a default implementation, the default is lowered as a static
witness implementation. It receives the target and any prerequisite evidence needed by contract
calls made within the default.

Witness method implementations are read-only in this initial design: `mut` requirements are
unsupported, `this` is immutable, and implementations may access only public target members.
Witness bodies are not guaranteed to be effect-free; purity is not a Zeron semantic guarantee.

Generic witness declarations express complete applied patterns and prerequisite bounds:

```zeron
witness<T: Display<T>> Display<Array<T>> for Buffer<T> {
    fn toString(): String = render(this);
}
```

Prerequisite bounds must be resolved recursively when selecting the witness. Generic contract
methods are supported. The target does not need to declare nominal conformance when witness methods
supply the required behavior.

The witness target for a factory mapping is a constructor reference, not a call expression. It
records which static factory should be invoked later and does not evaluate or allocate anything when
the witness is selected. Each mapping names the contract requirement on the left and a public named
constructor on the implementing class on the right. A contract factory requirement implicitly
returns `&Self`; under a bound on type `C`, `C.empty()` therefore has type `&C`.

If no explicit factory mapping is written, a requirement uses the same-name constructor when it is
compatible. An explicit mapping replaces that conventional mapping for that requirement. The target
must match parameter types after substitution, generic arity, variadic shape, visibility, and the
implicit `&Self` result. Constructor selection remains static; no virtual constructor slot or
runtime class-name lookup is introduced.

## Coherence, imports, and selection

Witness candidates are import-scoped. Witnesses declared in the current package are implicitly
active; a `package.*` import activates witnesses from that package. Explicit symbol imports do not
activate witnesses. Separate packages may each define a witness, but importing both can make a
lookup ambiguous.

For a concrete type and fully applied contract, there must be at most one applicable witness among
the active candidates. Missing evidence is a compile-time error, and multiple applicable witnesses
are a compile-time ambiguity; resolution does not prefer the nearest scope or most recent import.
Overlapping patterns within an active witness scope are rejected. Witnesses must obey the orphan
rule: a witness is declared in the package of its contract or target type. Library metadata must
preserve enough information to validate coherence across compilation boundaries.

The compiler validates method signatures against the fully applied contract and checks declared
nominal conformance where present. A witness cannot claim an unrelated contract/type pattern.
Generic prerequisite bounds are part of witness applicability.

## Member lookup and generic body resolution

Direct calls on concrete receivers preserve this precedence:

1. Ordinary instance methods (including nominal contract dispatch).
2. Existing local, explicit-import, and star-import extension lookup tiers.
3. Witness methods in the active witness scope.

An applicable extension owns lookup even when its arguments are invalid; resolution reports the
extension error instead of silently falling back to a witness. If multiple applicable witnesses
expose the same unqualified method, the call is ambiguous. A helper with an explicit generic
contract bound is the intended disambiguation mechanism.

When compiling a generic function, each bound that declares class-side factories or instance
methods contributes hidden evidence parameters to its implementation signature. A call through the
bound dispatches using the corresponding method/factory evidence; it does not inspect an erased type
parameter or perform runtime witness lookup. Generic callers forward compatible evidence to other
bounded functions. Direct callers infer source type arguments, recursively select the unique witness
for each substituted bound, and pass its hidden method handles.

Default contract methods selected for non-nominal targets are statically lowered as witness
implementations. Calls from those defaults to other contract requirements use the target's witness
evidence and any prerequisite evidence. Nominal class conformance continues to own instance-method
behavior; a standalone witness cannot change it.

## Display and string concatenation

Stringification uses the `Display` contract and its `toString` method. `String + T` requires
`Display<T>` evidence. There is no fallback to host/JVM `Object.toString()`. Generic function bodies
must declare an explicit `Display<T>` bound; the compiler does not infer hidden bounds from an
operator use.

For nullable `T?`, stringification is built-in nullable lifting: null renders as `"null"` and a
non-null value uses `Display<T>`. No `Display<T?>` witness is needed. Compiler-provided built-in
`Display` evidence covers `Int`, `Float`, `Boolean`, `String`, and `Unit`. `Array<T>` and `Any` are
deferred. The existing `==` and `!=` behavior is unchanged; any future `Eq<T>` integration with
operators is a separate design.

## JVM representation and library metadata

The witness is runtime evidence; generic type arguments are not reified. The implementation passes
`java.lang.invoke.MethodHandle` values as hidden evidence and invokes them with erased signatures.
The API index preserves source-level applied bounds, function signatures, witness patterns, factory
mappings, method signatures, helper owners/names, and prerequisite bounds. Its schema version must
be advanced when the witness metadata format changes.

Witness methods are emitted as static helpers rather than generated dictionary classes. Generic
signatures and method results use erased JVM descriptors, with casts where required. Applied types
remain available for compile-time resolution and separate compilation.

For reflection compatibility, bounded generic declarations also expose their ordinary erased
descriptor. That overload has no witness context and therefore throws
`UnsupportedOperationException` when invoked directly from Java. Zeron-generated calls and
specialized function references use the evidence-bearing descriptor.

## Decisions and remaining work

Agreed direction:

1. Applied contract arguments are preserved in bounds and witness identity.
2. Declared nominal conformance automatically supplies instance-method evidence and cannot be
   overridden by a witness.
3. Witnesses can supply non-nominal instance-method implementations and may map class-side factory
   requirements to public named constructors.
4. A contract factory returns `&Self`; through a bound on `C`, its result is `&C`.
5. Evidence is selected statically, passed implicitly, and recursively composed from prerequisite
   bounds.
6. Witness bodies are read-only, access only public target members, and have no purity guarantee.
7. The orphan rule is based on the package of the contract or target type.
8. The current package is implicitly in witness scope; package-star imports activate other witness
   packages, but explicit symbol imports do not.
9. `Display.toString` supplies `String + T`; nullable lifting and compiler-provided built-ins apply
   as described above. Generic bounds are explicit.
10. `==` and `!=` are unchanged; future `Eq<T>` operator behavior is separate work.

Implementation must include generic and default witness methods, recursive evidence, import-scoped
ambiguity, built-in display, API-index round trips, separately compiled consumers, erased JVM
signature checks, and negative diagnostics. A complete overlap solver for every possible distinct
generic-pattern pair remains future hardening.
