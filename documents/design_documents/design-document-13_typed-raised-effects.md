# Design 13: Typed raised effects

## Status and scope

This document describes the initial implementation of statically declared, typed, synchronous
raised effects. The feature is intentionally separate from `Result<T, E>`: a `Result` is an ordinary
value that callers inspect, while a raised effect transfers control to a matching handler or to the
caller.

The implementation supports nominal `effect` declarations, including generic effect payloads with
closed applied identity; `performs` clauses on
functions, class methods, extension methods, named constructors, and function type annotations;
`perform` expressions; inline expression handlers; named reusable handlers; and first-class structural
handler types. Effects and handler shapes are checked by the resolver and represented in
compiled-library API metadata. Handler cases use the existing named-pattern syntax, can bind the
whole payload with `as`, and can include Boolean guards.

[ZEP-0118](../zeps/ZEP-0118.md) adds response-typed effects and tail-resumptive handlers. An effect
may declare `responds R`; `perform` then produces `R` instead of `Never`, and an inline handler case
may bind a resume token with `case Effect(...), k -> k.resume(value);`. The response is delivered
synchronously to the perform site and the protected computation continues. Continuation capture,
storage, and resumption after the handler returns remain outside this implementation and belong to
[ZEP-0119](../zeps/ZEP-0119.md).

The implementation supports effect-row polymorphism through declaration-level `effect...` type
parameters. It also supports explicit effect-only subtyping with `under` and the denotable top
`Effect`; the relation is independent of ordinary contract conformance. Generic effect payloads use
closed, invariant applications and a payload-carried ZEP-0015 descriptor for exact handler
matching. There is no implicit wildcard case, though an unguarded `case Effect` is an
explicit catch-all, and no asynchronous effects. Java checked exceptions can be mapped only through
explicit interop declarations and configured bindings described below; there is no automatic
translation.

Declared effects may escape from the entry-point `main`; top-level initializers remain effect-free.
Handlers remain expression-scoped; no statement-level `try`/`catch` form is introduced. The protected
operand is a single expression. [ZEP-0146](../zeps/ZEP-0146.md) implements value-producing block
expressions with a final `yield`, which lets a handler protect a block while keeping the handler an
expression and changing no coverage or runtime rule; it shipped in `0.8.0` and supersedes ZEP-0110.

Synchronous resource cleanup is provided separately by statement-style `using` scopes and the
`zeron.resource.Disposable` contract. Such scopes contribute body and disposal effects to the
enclosing callable. Handler cases may bind suppressed cleanup outcomes with
`with suppressed as name`; see the [resource-management guide](../../guide/26-resource-management.md).

## Surface syntax

An effect is declared with a nominal payload type:

```zeron
public effect NotFound {
    public property path: String;
}
```

An effect may also declare the type its handler returns to the perform site. `responds` follows the
optional `is`, `under`, and `deriving` clauses:

```zeron
public effect Ask<T> responds T {}

public effect Yield<T> responds Unit {
    public property value: T;
}
```

Effects use class construction syntax. A payload can be raised directly, and callables declare the
effects that can escape:

```zeron
fn read(path: String): String performs NotFound =
    perform NotFound.new(path);

class Store {
    public load(key: String): String performs NotFound {
        return read(key);
    }
}

fn readOrEmpty(path: String): String = handle (read(path)) with {
    case NotFound as failure -> "";
};
```

The `performs` list follows a callable's return type (or the closing parameter list for a named
constructor) and contains one or more effect types. The same clause may appear after a function type
annotation's return type:

```zeron
fn loadFunction(): (String) -> String performs NotFound = read;
```

A handler protects exactly one parenthesized expression. Each `case` begins with a concrete effect
type and may use the same nested named patterns as `match`, bind the whole payload with `as`, and
add an `if` guard:

```zeron
handle (read(path)) with {
    case NotFound.path(missingPath) if missingPath == path -> "";
    case NotFound as failure -> report(failure);
};
```

OR-pattern alternatives in a handler case must name the same effect type. Multiple cases for the
same effect are allowed. Comparable cases are tried most-specific first regardless of source
order, and the parser requires at least one case.

A case for a responding effect may bind a resume token after the pattern and any `as`, guard, or
suppressed binding, separated by a comma:

```zeron
fn answered(): String = handle (perform Ask<String>()) with {
    case Ask<String>, k -> k.resume("Ada");
};
```

The case is tail-resumptive: `k.resume(value)` must be its entire body and the argument must fit the
effect's response type. The token is not a first-class value, so it cannot be stored, passed,
returned, or resumed more than once. A case that omits the token keeps the ordinary abortive
behavior. Named and anonymous handler values currently reject resuming cases; the feature is
implemented for inline `handle` expressions.

### Named and first-class handlers

A named handler is an immutable top-level or namespace value. Its cases use the same syntax and
coverage rules as an inline handler:

```zeron
public handler EmptyText {
    case NotFound(_) -> "";
}

fn readOrEmpty(path: String): String = handle (read(path)) with EmptyText;
```

An anonymous `handler { ... }` expression can be stored, passed, or returned where a handler type
is expected. It may capture immutable locals by value, following lambda capture rules. Handler types
use the form `R ~> {E1, E2} [performs {G1}]`: `R` is the protected computation's normal result type,
the first finite set is the set of exact effects completely handled, and the optional `performs` set
contains effects that handler cases may perform. Omitting it means the handler cases perform no effects;
the explicit `performs {}` spelling has the same meaning. Both sets may be empty; effect types in
either set must be concrete.

```zeron
fn applyPolicy(policy: String ~> {NotFound}): String =
    handle (read("input.txt")) with policy;

fn example(): String = applyPolicy(handler {
    case NotFound.path(_) -> "";
});
```

Named and anonymous handlers with the same result type and effect sets have the same structural
type. Assignment and parameter compatibility require an exact match of all three components;
handler composition and effect subtyping are not supported.

## Static semantics

### Effect declarations

An effect is a class-backed, nominal payload type. It may declare type parameters used by its
payload and takes the ordinary class member model: payload properties (public or private), methods,
contract conformances, and named
constructors, with the canonical `new` derived from the complete payload. The effect marker is
independent of ordinary contract conformance, so implementing a contract neither makes a class
raisable nor widens handler matching. Fields and constructors follow class construction rules; the
effect class itself is exported as an ordinary class record plus an effect marker in the library
index, and methods, contract conformances, named constructors, and non-public payload state
round-trip through that record without a schema change.

Effect types in `performs` clauses and handler cases must resolve to a declared effect, either a
non-generic name or a fully applied closed generic effect. Ordinary classes, contracts, raw generic
types, nullable types, and mutable-reference types are not effect types. Applications are
invariant and exact: `Failure<Int>` and `Failure<String>` are different identities. Effect identity
is nominal plus exact application: generic effects have no applied `under` edges, while non-generic
effects are related only by the reflexive and transitive closure of their `under` edges, and every
declared effect is implicitly a subtype of the library top `Effect`.

An effect may name direct effect supertypes with `under`. The relation is independent of ordinary
class inheritance and `is` conformance. Cycles, self-edges, and naming `Effect` as an `under`
target are rejected. A child that names a payload-bearing parent redeclares every inherited payload
member with the same name, type, and mutation capability; its canonical `new` delegates to the
parent constructor by matching those declarations and stores no second copy. At most one direct
parent may contribute payload state; every other direct parent, including its ancestors, must be
stateless. The child may add further payload members.

The library top `Effect` is an ordinary stateless effect value. `Effect.fromException` wraps a host
`Exception` in a library-owned payload effect; no other host-to-effect mapping is inferred. A bare
`Effect` cannot be constructed, and ordinary classes cannot be assigned to it. Effect values are
ordinary values: they can be stored, passed, and raised again, and raising a value of static type
`Effect` adds `Effect` to the raised set.

A subeffect satisfies a broader `performs` requirement, and a handler case for a super-effect matches
its subeffects. Handler payloads retain the case's declared static type, so a parent-typed case
exposes inherited payload but not subtype-only members. Coverage removes every raised element that
is a subtype of a fully handled case, while a broader element remains.

### Responses and resumption

A response type is part of the effect declaration and is resolved in the effect's type-parameter
scope. `perform` on a responding effect produces the response type after substituting the applied
effect's arguments; on an effect without `responds` it stays `Never`. A responding `perform` is a
normal expression, so the statements after it remain reachable, while an abortive `perform` still
makes the rest of the block unreachable. The response type is not a second effect and never appears
in the raised row.

A tail-resumptive case binds a resume token after its pattern. The resolver records the token with
the response type for the arm's effect and accepts only `token.resume(argument)` as the complete arm
body, with the argument assignable to the response type. Using the token anywhere else, resuming
through a different token, or using a resume token on an effect without `responds` is an error.
Resuming cases are accepted in inline `handle` expressions. A named or anonymous handler value
cannot currently contain one, because its implementation is compiled without the inline handler
frame; this is a scoped limitation, not a different typing rule.

The runtime answer needs no continuation object and no stack capture. Only concrete or applied
effects can be resumed: generic code that performs through an `effect` or `effect...` parameter has no
known response, so it must forward the effect instead. Generic libraries can still declare and
export responding effects, and clients substitute concrete response types.

### Effect-row polymorphism

A generic parameter bounded by `effect...` denotes a finite effect row, which may be empty; the
existing `effect` bound still denotes exactly one nominal effect. A single `effect` value can be
used as a singleton row, but a row cannot instantiate an `effect` parameter. Explicit row arguments
use set syntax such as `{}` or `{MissingUser}`. In `performs` clauses, an `effect...` parameter
expands to its members, and at most one row variable may occur in a clause.

Row inference collects inclusion constraints and chooses the union of lower bounds as the minimal
solution. An unconstrained row at a generic call site defaults to `{}`; a row fixed by an enclosing
signature instead checks every lower bound against that row. Contravariant callback positions
contribute upper bounds, which are checked against the solution. A same-row recursive call preserves
the enclosing row rather than replacing it with `{}`. Fixed labels alongside a row variable are
subtracted before computing its lower bound.

Function compatibility requires the implementation's effects to fit the expected row. An open
expected row can cover additional fixed labels; row variables must otherwise remain preserved.
Handling a fixed effect removes only that fixed label from the known part of an escaping row and
leaves the unknown row unchanged. Row variables cannot be named by handler cases.

### Effect propagation and callable checking

`perform expression` resolves its operand as an effect payload, contributes that nominal effect to the
current effect set, and has type `Never`. A call contributes the effects declared by the resolved
callable signature. This applies uniformly to top-level functions, class and contract methods,
extension methods, named constructors, and function values. Effects in any resolved match arm or
other expression branch contribute naturally to the containing expression's effect set.

At the end of each callable body, every inferred escaping effect must be present in that callable's
declared `performs` list. A caller in turn must handle or declare the effects in the callee's signature.
Duplicate entries are rejected. An implementation may perform fewer effects than the function type it
is assigned to, but not more; effects are checked separately from the generated JVM function-shape
identity.

The entry-point `main` function may declare raised effects like any other callable. Effects not
handled within `main` must appear in its `performs` clause. Top-level value initializers cannot perform
effects because there is no enclosing callable or entry-point boundary to receive them. Java
exceptions are not translated by default. External-class methods and constructors, plus external
functions backed by configured Java bindings with class-file metadata, can explicitly map declared
Java checked exceptions to ordinary Zeron effects:

```zeron
public external class Reader = "java.io.Reader" {
    public read(): Int
        throws java.io.IOException -> FileFailure::fromIOException;
}

public external class FileReader = "java.io.FileReader" {
    public constructor new(path: String)
        throws java.io.FileNotFoundException -> FileFailure::fromIOException;
}

public external fn readConfigured(path: String): String?
    throws java.io.IOException -> FileFailure::fromIOException;
```

Every checked exception declared by the Java callable must be covered by a compatible mapping. Each
mapped exception type must be a checked Java subtype of `Throwable`, and the named public effect
constructor must accept one payload type capable of receiving the mapped exception. It may be a
supertype such as `Throwable`, which allows distinct exception types to share an effect while
preserving the original object. Mappings that do not correspond to a declared checked exception,
duplicate mappings for one exception type, and incomplete coverage are rejected. If multiple
mappings apply, the most-specific exception type takes precedence. Mapping validation uses
class-file metadata, so it does not load or initialize the Java class. Direct Java calls remain
invalid when checked exceptions are declared unless they resolve through one of these mapped
declarations; call-site mapping syntax is not provided. Generic Java `throws` type variables are
rejected rather than inferred from erased bounds.

The callable's Zeron `performs` set is derived from its mappings. At runtime, only the Java invocation
is covered by the translation handler: receiver and argument evaluation failures are not remapped.
`RuntimeException` descendants and `Error`s bypass translation even when a broad mapping names
`Exception` or `Throwable`; they continue to the host unchanged. The original Java exception is
passed as the payload to the selected Zeron effect constructor.

### Handler scope and result type

The protected expression is resolved with an isolated raised-effect set. On normal completion its
value is preserved. A handler removes an effect from that set only if it has an unguarded,
irrefutable pattern for that effect. In particular, guarded or refutable patterns do not prove
coverage; a set of refutable cases is not combined to infer exhaustiveness. An unmatched payload,
including one for a listed but incompletely handled effect, remains in the escaping set and must be
declared or handled by an outer scope. A super-effect case covers its subeffects, and an unguarded,
irrefutable `case Effect` covers the complete raised set. Inline handler arms and guards are resolved in the enclosing effect scope, so effects they perform
are not removed by that same handler. A first-class handler records its case-raised set in its
structural type. Applying it removes only its complete handled set from the protected computation
and adds the recorded case-raised set:

```text
(protected effects \ handled effects) ∪ handler-case effects
```

The handler value is evaluated once before the protected expression. Effects raised while
evaluating that value escape to the enclosing scope rather than being caught by the handler being
applied.

The handler expression has a common result type for the protected expression's normal result and all
handler arms. When the protected expression has type `Never`, only the arms determine the result
type. This ensures that both normal and handled paths produce values compatible with the expression's
static type.

OR-patterns, nested patterns, multiple outputs, and guards reuse `match` pattern semantics. OR
alternatives must bind the same names with compatible types. The handler has no wildcard case, so it
cannot silently erase an unlisted effect; an explicit `case Effect` is a deliberate catch-all.
Comparable matching cases select the most-specific effect type independent of source order.
Incomparable cases that can both match a visible effect are rejected at compile time. A subtype
introduced by a later library may make two previously incomparable cases match the same payload;
dispatch then aborts with an ambiguity error naming the payload and both cases instead of choosing
by source or import order.

### Functions and library compatibility

Raised effect lists are part of `FunctionDescriptor` signatures, including function type annotations
and exported signatures for functions, methods, extensions, and named constructors. A function type
accepts a value whose effect set is a subset of the expected effect row, including an empty effect
set where the expected row is open. Handler types are structural and require exact result,
handled-effect, and handler-raised-effect sets. JVM callable interfaces encode only the handler
result and dispatch argument; effect capabilities remain in source-level descriptors and library
metadata.

The compiled-library index serializes effect and row markers, callable raised sets, direct `under`
edges, handler shapes on exported values, response types on exported effects, and external-facade
exception mappings needed for separately compiled clients. The effective payload shape needs no
separate record: the exported canonical-constructor parameter types and names already describe the
child's complete payload. Response metadata was added with API-index schema 17; indexes written by
older compilers have no field and are read as if the effect were abortive. The standard-library API
version remains independent of the binary schema version. Consumers of schemas that predate the
relevant metadata must rebuild or use a compatible compiler; such an index cannot communicate
effect markers, row parameters, raised signatures, handler capabilities, response types, or
exception mappings safely.

## Runtime model

The compiler emits a final `zeron.runtime.RaisedEffect` runtime exception with an opaque payload.
`perform` evaluates the payload once, constructs this carrier, and throws it. The generated runtime
support class is emitted alongside compiled classes.

A handler compiles its protected expression into a JVM exception-table region catching only
`RaisedEffect`. Inline cases and first-class handler dispatch order comparable cases most-specific
first, evaluate a guard only after its pattern matches, and evaluate the arm only when both pattern
and guard succeed. If no case matches, the same carrier is rethrown. Ordinary Java exceptions are not caught
by effect handlers; only an explicit external-facade mapping translates the declared checked
exception at its Java call site.

A responding `perform` compiles differently. The compiler emits a call to
`zeron.runtime.EffectSupport.perform(payload)` instead of throwing, and an inline handler installs a
synchronous thread-local frame around its protected expression. `perform` invokes the innermost
frame's synthesized handler with the carrier, temporarily removing that frame while the handler
runs. A matching resuming arm evaluates `k.resume(value)` as `value`, so the frame returns the
response and the perform site continues. A non-matching handler rethrows the same carrier, `perform`
continues outward, and the frame is not restored. Effects raised while evaluating the response see
the next enclosing frame. The frame is restored on normal return and removed on every exceptional
exit, so no handler leaks past its protected expression. This is a direct call plus a handler stack;
it allocates no continuation and captures no stack. An unhandled carrier escaping the
generated `main` method likewise propagates unchanged across the host boundary;
the current compiler does not add a launcher that catches and formats it. A future launcher may
report an uncaught Zeron effect by catching only `RaisedEffect`, without translating Java exceptions
or implicitly stringifying the payload. Effect payload classes and carriers are emitted as normal
class files and public effect metadata is available to imported compiled libraries. The generated
payload class of each effect realizes the declared relation: the single payload-bearing parent is
the JVM superclass, each stateless parent is a marker interface the child implements, and every
effect implements the generated `Effect` marker. A stateless effect is itself emitted as a marker
interface with a package-private implementation class behind its static `new`; a payload-bearing
effect is emitted as a non-final class. Handler dispatch tests the payload with `instanceof`
against this generated hierarchy, never against ordinary class names or the carrier exception.

Effect-row parameters are erased at runtime and add no runtime witness; raised values continue to
use `RaisedEffect`. The API index preserves row-parameter kinds and row expressions for separately
compiled consumers. Higher-rank row quantification is not supported.

## Limitations and roadmap

Other follow-up areas:

1. [ZEP-0054](../zeps/ZEP-0054.md) adds ordinary nominal members and contract conformance for
   effect payloads while preserving exact effect identity; implemented 2026-11-09.
2. [ZEP-0055](../zeps/ZEP-0055.md) drafts an effect-kind constraint for generic declarations and
   propagation of abstract effects. It does not add generic effect payload declarations or generic
   handler cases.
3. [ZEP-0013](../zeps/ZEP-0013.md) adds effect subtyping and a denotable top `Effect` type, with
   inherited payload, most-specific handler selection, runtime ambiguity reporting, and direct
   `under` edges in the library index; implemented 2026-11-09.
4. Generic effect payload declarations are implemented from
   [ZEP-0005](../zeps/ZEP-0005.md) on the ZEP-0015 runtime identity model: closed, invariant
   applications carry a payload descriptor, and handlers compare it before binding. Applied
   ancestry, generic propagation through type variables, effect-variable handlers, omitted
   arguments, and wildcard applications remain deferred.
5. Extend explicit checked-exception mappings only as concrete interop needs arise; they remain an
   opt-in boundary and do not alter ordinary Java exception propagation.
6. [Effect-row polymorphism](../zeps/ZEP-0086.md) is implemented with declaration-level row
   quantification; higher-rank row-polymorphic function values remain unsupported.
7. Observable I/O remains outside the raised-effect system. `performs` describes typed control-flow
   effects, not purity or the absence of observable side effects, so `println` can have no declared
   raised effects without being pure. [ZEP-0050](../zeps/ZEP-0050.md) settles
   the implemented interop trust boundary: curated facades carry reviewed effect signatures, direct Java access
   requires an explicit `unsafe` expression, and no implicit `IO` effect is introduced.
   [ZEP-0148](../zeps/ZEP-0148.md) implements contextual Java SAM conversion; a converted lambda or
   top-level function reference must have an empty `performs` row, so a `zeron.runtime.RaisedEffect`
   never crosses into Java. A general purity or IO effect would apply to the whole language and
   remains a separate design; `main` may declare such effects and let them escape to the host
   boundary if one is adopted.
8. [ZEP-0056](../zeps/ZEP-0056.md) and [ZEP-0057](../zeps/ZEP-0057.md) implement named reusable
   handlers and first-class structural handler types. These remain abortive and preserve `match` for
   ordinary value inspection; resumable continuations are a separate design question.
9. [ZEP-0118](../zeps/ZEP-0118.md) implements response-typed effects and tail-resumptive handlers.
   `perform` on a responding effect produces the response type, and an inline handler case can resume
   synchronously with `k.resume(value)` through a thread-local handler frame. This adds no
   continuation and does not change abortive effects. [ZEP-0119](../zeps/ZEP-0119.md) covers general
   one-shot continuations, deep handlers, and cancellation; [ZEP-0120](../zeps/ZEP-0120.md) covers the
   async/task surface. Supporting general resumable operations would require capturing and invoking
   continuations and would change the compiler/runtime model substantially. Do not assume Project
   Loom's virtual threads provide arbitrary continuation capture and resumption; investigate a
   compiler-managed strategy such as CPS transformation, starting with one-shot continuations,
   before committing to this direction. [ZEP-0007](../zeps/ZEP-0007.md) records the mechanism
   analysis. The separable parts are proposed separately: value-producing block expressions
   ([ZEP-0146](../zeps/ZEP-0146.md)), witness-dispatched `do` notation at library level
   ([ZEP-0111](../zeps/ZEP-0111.md)), and direct-style suspension with structured concurrency
   ([ZEP-0112](../zeps/ZEP-0112.md)). Multi-shot continuations remain deferred in
   [ZEP-0121](../zeps/ZEP-0121.md); [ZEP-0007](../zeps/ZEP-0007.md) is superseded and remains the
   mechanism comparison.

Effect-set polymorphism is implemented in [ZEP-0086](../zeps/ZEP-0086.md); its design and
validation coverage are recorded there. `Result<T, E>` remains ordinary and is not a migration
target for this feature. The compiler does
not automatically catch Java exceptions, convert a `Result` into a raised effect, or convert a
raised effect into a `Result`.
