# Design 13: Typed raised effects

## Status and scope

This document describes the initial implementation of statically declared, typed, synchronous
raised effects. The feature is intentionally separate from `Result<T, E>`: a `Result` is an ordinary
value that callers inspect, while a raised effect transfers control to a matching handler or to the
caller.

The implementation supports nominal, non-generic `effect` declarations; `raises` clauses on
functions, class methods, extension methods, named constructors, and function type annotations;
`raise` expressions; inline expression handlers; named reusable handlers; and first-class structural
handler types. Effects and handler shapes are checked by the resolver and represented in
compiled-library API metadata. Handler cases use the existing named-pattern syntax, can bind the
whole payload with `as`, and can include Boolean guards.

The implementation does not add effect inheritance, effect generics, effect rows, catch-all or
wildcard cases, or asynchronous effects. Java checked exceptions can be mapped only through explicit
interop declarations and configured bindings described below; there is no automatic translation.
Declared effects may escape from the entry-point `main`; top-level initializers remain effect-free.
Handlers remain expression-scoped; no statement-level `try`/`catch` form is introduced.

Synchronous resource cleanup is provided separately by statement-style `using` scopes and the
`zeron.resource.Disposable` contract. Such scopes contribute body and disposal effects to the
enclosing callable. Handler cases may bind suppressed cleanup outcomes with
`with suppressed as name`; see the [resource-management guide](../guide/26-resource-management.md).

## Surface syntax

An effect is declared with a nominal payload type:

```zeron
public effect NotFound {
    public property path: String;
}
```

Effects use class construction syntax. A payload can be raised directly, and callables declare the
effects that can escape:

```zeron
fn read(path: String): String raises NotFound =
    raise NotFound.new(path);

class Store {
    public load(key: String): String raises NotFound {
        return read(key);
    }
}

fn readOrEmpty(path: String): String = handle (read(path)) with {
    case NotFound as failure -> "";
};
```

The `raises` list follows a callable's return type (or the closing parameter list for a named
constructor) and contains one or more effect types. The same clause may appear after a function type
annotation's return type:

```zeron
fn loadFunction(): (String) -> String raises NotFound = read;
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
same effect are allowed and tried in source order. The parser requires at least one case.

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
use the form `R ~> {E1, E2} [raises {G1}]`: `R` is the protected computation's normal result type,
the first finite set is the set of exact effects completely handled, and the optional `raises` set
contains effects that handler cases may raise. Omitting it means the handler cases raise no effects;
the explicit `raises {}` spelling has the same meaning. Both sets may be empty; effect types in
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
handler composition, effect-row polymorphism, and effect subtyping are not supported.

## Static semantics

### Effect declarations

An effect is a class-backed, nominal payload type. It is non-generic and cannot implement contracts
or declare methods. Its properties must be public so code handling an imported effect can inspect
the payload through an alias. Fields and constructors follow class construction rules; the effect
class itself is exported as an ordinary public class plus an effect marker in the library index.

Effect types in `raises` clauses and handler cases must resolve to a declared, non-generic effect.
Ordinary classes, contracts, generic types, nullable types, and mutable-reference types are not
effect types. Effect identity is exact nominal identity; there is no subclass matching or
subtyping.

### Effect propagation and callable checking

`raise expression` resolves its operand as an effect payload, contributes that nominal effect to the
current effect set, and has type `Never`. A call contributes the effects declared by the resolved
callable signature. This applies uniformly to top-level functions, class and contract methods,
extension methods, named constructors, and function values. Effects in any resolved match arm or
other expression branch contribute naturally to the containing expression's effect set.

At the end of each callable body, every inferred escaping effect must be present in that callable's
declared `raises` list. A caller in turn must handle or declare the effects in the callee's signature.
Duplicate entries are rejected. An implementation may raise fewer effects than the function type it
is assigned to, but not more; effects are checked separately from the generated JVM function-shape
identity.

The entry-point `main` function may declare raised effects like any other callable. Effects not
handled within `main` must appear in its `raises` clause. Top-level value initializers cannot raise
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

The callable's Zeron `raises` set is derived from its mappings. At runtime, only the Java invocation
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
declared or handled by an outer scope. Inline handler arms and guards are resolved in the enclosing effect scope, so effects they raise
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
alternatives must bind the same names with compatible types. The handler has no wildcard or
catch-all case, so it cannot silently erase an unlisted effect.

### Functions and library compatibility

Raised effect lists are part of `FunctionDescriptor` signatures, including function type annotations
and exported signatures for functions, methods, extensions, and named constructors. A function type
accepts a value whose effect set is a subset of the expected effect set. Handler types are structural
and require exact result, handled-effect, and handler-raised-effect sets. JVM callable interfaces
encode only the handler result and dispatch argument; effect capabilities remain in source-level
descriptors and library metadata.

The compiled-library index serializes effect markers, callable raised sets, handler shapes on
exported values, and external-facade exception mappings needed for separately compiled clients. The
standard-library API version remains independent of the binary schema version. Consumers of schemas
that predate the relevant metadata must rebuild or use a compatible compiler; such an index cannot
communicate effect markers, raised signatures, handler capabilities, or exception mappings safely.

## Runtime model

The compiler emits a final `zeron.runtime.RaisedEffect` runtime exception with an opaque payload.
`raise` evaluates the payload once, constructs this carrier, and throws it. The generated runtime
support class is emitted alongside compiled classes.

A handler compiles its protected expression into a JVM exception-table region catching only
`RaisedEffect`. Inline cases and first-class handler dispatch try patterns in source order, evaluate
a guard only after its pattern matches, and evaluate the arm only when both pattern and guard
succeed. If no case matches, the same carrier is rethrown. Ordinary Java exceptions are not caught
by effect handlers; only an explicit external-facade mapping translates the declared checked
exception at its Java call site. An unhandled carrier escaping the
generated `main` method likewise propagates unchanged across the host boundary;
the current compiler does not add a launcher that catches and formats it. A future launcher may
report an uncaught Zeron effect by catching only `RaisedEffect`, without translating Java exceptions
or implicitly stringifying the payload. Effect payload classes and carriers are emitted as normal
class files and public effect metadata is available to imported compiled libraries.

## Limitations and roadmap

Other follow-up areas:

1. [ZEP-0054](./zeps/ZEP-0054.md) drafts ordinary nominal members and contract conformance for
   effect payloads while preserving exact effect identity.
2. [ZEP-0055](./zeps/ZEP-0055.md) drafts an effect-kind constraint for generic declarations and
   propagation of abstract effects. It does not add generic effect payload declarations or generic
   handler cases.
3. [ZEP-0013](./zeps/ZEP-0013.md) explores effect subtyping and a denotable top `Effect` type.
   These remain proposals and do not change the exact-matching semantics described above.
4. Generic effect payload declarations remain separately explored in [ZEP-0005](./zeps/ZEP-0005.md)
   and depend on a sound runtime identity model.
5. Extend explicit checked-exception mappings only as concrete interop needs arise; they remain an
   opt-in boundary and do not alter ordinary Java exception propagation.
6. Explore effect-set polymorphism only after concrete callable and function-type compatibility has
   stabilized.
7. Decide whether observable I/O belongs in the raised-effect system. `raises` currently describes
   typed control-flow effects, not purity or the absence of observable side effects, so `println`
   can have no declared raised effects without being pure. If operations such as `println` were to
   raise a hypothetical `IO` effect, weigh the semantic benefit against the learning burden of
   introducing effect handling early; `main` may declare such effects and let them escape to the
   host boundary.
8. [ZEP-0056](./zeps/ZEP-0056.md) and [ZEP-0057](./zeps/ZEP-0057.md) implement named reusable
   handlers and first-class structural handler types. These remain abortive and preserve `match` for
   ordinary value inspection; resumable continuations are a separate design question.
9. Decide whether raised effects should remain abortive or eventually grow into fully resumable
   algebraic effects. Resumption is not part of the current runtime model: `raise` throws a carrier
   and unwinds the JVM stack to a handler. Supporting resumable operations would require capturing
   and invoking continuations and would change the compiler/runtime model substantially. Do not
   assume Project Loom's virtual threads provide arbitrary continuation capture and resumption;
   investigate a compiler-managed strategy such as CPS transformation, starting with one-shot
   continuations, before committing to this direction.

`Result<T, E>` remains ordinary and is not a migration target for this feature. The compiler does
not automatically catch Java exceptions, convert a `Result` into a raised effect, or convert a
raised effect into a `Result`.
