# Type-Class Witnesses and Generic Bounds

## Status

This document is a historical design snapshot for applied contract bounds and non-nominal method
witnesses, including witnesses targeting nominal sum types. Generic functions and methods retain
applied contract bounds and pass hidden `MethodHandle` evidence where needed. Witness selection uses
source-level applied types; runtime evidence uses erased JVM descriptors.

ZEP-0059 supersedes the class-side constructor evidence described in this document: contracts no
longer declare constructor requirements, witnesses no longer map factories, and callers pass
non-generic factories explicitly or use HKT collection capabilities.

Constructor-family HKT contracts and conformance use the same compile-time evidence architecture but
are distinct from instance contracts and are specified in
[ZEP-0019](zeps/ZEP-0019.md).

The API index preserves witness patterns and their implementation metadata across separately
compiled libraries. The current coherence validation rejects overlapping witness patterns it can
identify; a complete overlap solver for all generic patterns remains future hardening.

The witness model makes evidence relationships explicit as a language concept, in the direction of
type classes, while using compiler-passed method handles as the runtime representation. A class's
declared contract conformance remains authoritative for its instance methods. An explicit witness
may provide instance-method evidence for a target that does not declare that contract.

## Applied contract bounds

A generic bound denotes a complete applied contract type, not merely a raw contract name. For
example, `Sink<Int>` and `Sink<String>` are distinct constraints even though both erase to the same
JVM interface. Substitution and generic inference retain all nested contract arguments at source
level. Generic declarations combine invariant-by-default parameters, checked declaration-site
covariance, and use-site projections; see
[design-document-19_generic-type-system.md](design-document-19_generic-type-system.md) for the
current system-wide status.

Generic functions and methods may bind a type parameter to an applied contract. Construction for a
generic family uses an HKT capability such as `Collector<C<_>>`, not a behavioral contract:

```zeron
import zeron.collections.Collector;

fn collectInto<E: nullable + mut, C<_>: Collector<C>>(
    source: Iterable<E>
): &C<E> {
    let result = C.empty<E>();
    for (let item in source) C.add(result, item);
    return result;
}
```

Applied bounds such as `C: Sink<E>` retain their type arguments during resolution and conformance
checking, even though those arguments erase in JVM descriptors. Mutating instance operations are
available through a bound when the receiver is a mutable reference. Constructor-family operations
use HKT evidence; a factory for one concrete type is passed explicitly.

The accepted constrained-lambda rule in
[ZEP-0003](zeps/ZEP-0003.md) uses the same applied-bound and evidence-selection semantics. Bounds
inferred from a directly let-bound lambda body (including substituted bounds of generic callees)
must be discharged statically at each specialization. Selected evidence is captured by that
monomorphic closure variant; it is not looked up at runtime, and an unsatisfied or ambiguous bound
is a compile-time diagnostic tied to the use and the lambda binding. This does not change witness
coherence or API-index metadata, because these schemes are local and are not serialized.

Generic class parameters may also declare applied contract bounds. A class application must satisfy
each bound after substituting all class arguments. Evidence for class parameter bounds is selected
at construction and retained in private final `MethodHandle` fields on the instance; instance
methods load evidence from the receiver. Construction through Zeron uses a hidden evidence-bearing
descriptor. The ordinary erased Java constructor or named-factory entry point throws
`UnsupportedOperationException` when evidence is required, while Java may call instance methods on
objects constructed by Zeron.

Contract type parameters may declare the same bounds. Every generic contract application, required
contract, witness contract, and nominal conformance validates the substituted bounds. A generic
implementer must explicitly bound its type parameters sufficiently to meet those requirements.
Generated contract interfaces expose synthetic evidence accessors for their bounded parameters;
default methods use those accessors, and implementing instances retain the selected handles. This
lets bounded contract defaults work through ordinary interface dispatch without runtime witness
lookup. The library index already records bounds on contract type parameters, so this feature does
not change its binary schema.

## Conformance and witness declarations

An existing class declaration remains the source of nominal instance conformance:

```zeron
class IntList is Sink<Int> {
    public constructor new;
    public mut add(value: Int): Unit { }
}
```

That conformance automatically supplies evidence for `Sink<Int>` instance-method use. A witness
does not create a second instance or replace a declared nominal conformance. Contracts have no
class-side factory requirements; a generic API uses an HKT capability for a type family or accepts
an explicit factory for a concrete type.

For a non-nominal target, witness methods provide contract instance-method evidence:

```zeron
public contract Display<T> {
    toString(): String;
}

witness Display<Int> for Int {
    fn toString(): String = intToString(this);
}
```

A sum target is witnessed once as the sum type, not once per case:

```zeron
type Maybe<T> = Just(value: T) | Nothing;
contract Describable {
    describe(): String;
}
witness<T> Describable for Maybe<T> {
    fn describe(): String = match (this) {
        case Maybe.Just(_) -> "just";
        case Maybe.Nothing() -> "nothing";
    };
}
```

The helper receives the sum value, and its body may inspect cases using exhaustive `match` or case
refinement. This supplies evidence only for the sum target; it does not establish contract
conformance for generated case classes or automatically synthesize a witness for each case.
Generic witness target patterns must cover the sum's complete type-parameter pattern.

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

HKT operations remain statically dispatched. An HKT contract operation whose first parameter is
marked `this: F<A>` may additionally be called as `value.operation(...)`; the marker is preserved
in the API index and receiver syntax supplies that first argument to the selected evidence helper.
The constructor-bound form `F.operation(value, ...)` remains equivalent. Concrete nominal methods
win before HKT receiver operations, and HKT operations without the marker are not exposed as
receiver calls.

When compiling a generic function, each applied behavioral or HKT bound contributes hidden evidence
parameters to its implementation signature. Calls through those bounds dispatch using the
corresponding evidence; they do not inspect an erased type parameter or perform runtime witness
lookup. Generic callers forward compatible evidence to other bounded functions. Direct callers
infer source type arguments, recursively select the unique witness or constructor implementation for
each substituted bound, and pass its hidden method handles.

Default contract methods selected for non-nominal targets are statically lowered as witness
implementations. Calls from those defaults to other contract requirements use the target's witness
evidence and any prerequisite evidence. Nominal class conformance continues to own instance-method
behavior; a standalone witness cannot change it.

## Display and string concatenation

Stringification uses the `Display` contract and its `toString` method. `String + T` requires
`Display<T>` evidence. There is no fallback to host/JVM `Object.toString()`. Generic function bodies
must declare an explicit `Display<T>` bound; the compiler does not infer hidden bounds from an
operator use.

`Display<T>` is for user-facing text, not debugging output or serialization. Implementations should
present a useful textual form, avoid runtime representation details such as object addresses, and be
stable for a given value. There is no round-trip or escaping guarantee. Built-in `String` display is
the string itself, without quotes; consequently a derived enclosing value may not be unambiguously
parseable.

The canonical style for a derived nominal value uses source-level names and declaration order:
products include their type name and labeled fields, while sums include their type and case names
and labeled payloads. For example, a point may display as `Point(x=3, y=4)`, while an option may
display as `Option.Some(value=hello)` or `Option.None`. Generic type arguments and generated JVM
class names are omitted. Nested fields use their own `Display` implementation. Derivation is
opt-in: compiler-known products and sums are suitable candidates, while ordinary classes should
define a presentation intentionally because their internal state need not be their public display.
Types whose fields lack `Display` evidence cannot receive a derived implementation.

For nullable `T?`, stringification is built-in nullable lifting: null renders as `"null"` and a
non-null value uses `Display<T>`. No `Display<T?>` witness is needed. Compiler-provided built-in
`Display` evidence covers `Int`, `Float`, `Boolean`, `String`, and `Unit`. `Array<T>` and `Any` are
deferred. `Eq<T>` now provides evidence-driven `==` and `!=` for built-in scalar types and through
explicit bounds, nominal conformance, and witnesses. Nullable lifting and structural equality for
sums, including direct self-recursion, are implemented; mutually recursive sum declarations remain
unsupported. See
[design-document-17_structural-equality-and-hashing.md](design-document-17_structural-equality-and-hashing.md).

## Algebraic operator evidence

`Add<T>`, `Addition<T>`, `Mult<T>`, and `Multiplication<T>` in `zeron.algebra` describe
algebraic operator evidence; code explicitly imports the contracts it uses. `Addition<T>` requires
`Add<T>` and adds `negate`; `Multiplication<T>` requires `Mult<T>` and adds `reciprocal`.
User-defined types may satisfy these contracts through nominal conformance or in-scope witnesses.
Generic operator use requires an explicit corresponding bound, and the compiler selects and
forwards the same static method-handle evidence as for other contract calls.

For example, an evidence set for built-in scalar types can be expressed as:

| Type | `Add<T>` | `Addition<T>` | `Mult<T>` | `Multiplication<T>` |
| ---- | -------- | ------------- | --------- | ------------------- |
| `Int` | yes | yes | yes | no |
| `Long` | yes | yes | yes | no |
| `Float` | yes | yes | yes | yes |

Concrete arithmetic on `Int`, `Long`, and `Float` remains intrinsic. The standard-library witnesses
are used when these types are passed to generic functions requiring algebraic bounds. `Float`
reciprocal uses IEEE floating-point division; conformance does not promise inverse laws for zero,
infinities, or NaN. Integer multiplication has no reciprocal contract because zero is not invertible.

Only `+`, `*`, unary `-`, and binary `-` use this evidence. Binary subtraction is resolved as
negation of the right operand followed by addition. Mixed-type promotion and `/` are not included.
`String + T` retains its existing `Display<T>` behavior and precedence.

## JVM representation and library metadata

The witness is runtime evidence; generic type arguments are not reified. The implementation passes
`java.lang.invoke.MethodHandle` values as hidden evidence and invokes them with erased signatures.
The API index preserves source-level applied bounds, function signatures, witness patterns, HKT
implementation metadata, method signatures, helper owners/names, and prerequisite bounds. Its
schema version must be advanced when the evidence metadata format changes.

Witness methods are emitted as static helpers rather than generated dictionary classes. HKT
declaration-site implementations are also static helpers, emitted in the implementing class so
their bodies can access its private members. Receiver-marked methods keep the receiver as the first
helper parameter; a public synthetic JVM helper does not add an ordinary source-level class method.
Generic signatures and method results use erased JVM descriptors, with casts where required.
Applied types and receiver markers remain available for compile-time resolution and separate
compilation.

For reflection compatibility, bounded generic functions and methods with method-level bounds expose
their ordinary erased descriptor; calling it directly from Java throws
`UnsupportedOperationException` because it has no witness context. Bounded class constructors and
named factories follow the same rule when construction requires evidence. Class instance methods
retain ordinary descriptors and can be called from Java on instances constructed by Zeron because
they load evidence from the receiver. Zeron-generated generic calls and specialized function
references use evidence-bearing descriptors where required.

## Decisions and remaining work

Agreed direction:

1. Applied contract arguments are preserved in bounds and witness identity.
2. Declared nominal conformance automatically supplies instance-method evidence and cannot be
   overridden by a witness.
3. Witnesses supply non-nominal instance-method implementations; constructor-family operations use
   HKT evidence, while a concrete factory is passed explicitly (ZEP-0059).
5. Evidence is selected statically, passed implicitly, and recursively composed from prerequisite
   bounds.
6. Non-nominal witness bodies are read-only, access only public target members, and have no purity
   guarantee. Declaration-site HKT implementation helpers may access private members of their
   implementing class.
7. The orphan rule is based on the package of the contract or target type.
8. The current package is implicitly in witness scope; package-star imports activate other witness
   packages, but explicit symbol imports do not.
9. `Display.toString` supplies `String + T`; nullable lifting and compiler-provided built-ins apply
   as described above. Generic bounds are explicit.
10. `Eq<T>` evidence drives `==` and `!=`; nullable values lift evidence, and sums synthesize
    structural evidence from their payload requirements.

Implementation must include generic and default witness methods, recursive evidence, import-scoped
ambiguity, built-in display, class-instance and bounded-contract-default evidence, API-index round
trips, separately compiled consumers, erased JVM signature checks, and negative diagnostics. A
complete overlap solver for every possible distinct generic-pattern pair remains future hardening.
