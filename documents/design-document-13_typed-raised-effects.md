# Design 13: Typed raised effects

## Status and scope

This document describes the initial implementation of statically declared, typed, synchronous
raised effects. The feature is intentionally separate from `Result<T, E>`: a `Result` is an ordinary
value that callers inspect, while a raised effect transfers control to a matching handler or to the
caller.

The initial slice supports nominal, non-generic `effect` declarations; `raises` clauses on
functions, class methods, extension methods, named constructors, and function type annotations;
`raise` expressions; and expression handlers. Effects are checked by the resolver and represented in
compiled-library API metadata. Handler cases use the existing named-pattern syntax, can bind the
whole payload with `as`, and can include Boolean guards.

The implementation does not add effect inheritance, effect generics, effect rows, catch-all or
wildcard cases, or asynchronous effects. Java checked exceptions can be mapped only through the
explicit external-class method mapping described below; there is no automatic translation. Declared
effects may escape from the entry-point `main`; top-level initializers remain effect-free. The
implementation is limited to expression handlers; no statement-level `try`/`catch` form is
introduced.

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
exceptions are not translated by default. External-class methods can explicitly map declared Java
checked exceptions to ordinary Zeron effects:

```zeron
public external class Reader = "java.io.Reader" {
    public read(): Int
        throws java.io.IOException -> FileFailure::fromIOException;
}
```

Every checked exception declared by the Java method must be covered by a mapping. Each mapped
exception type must be a Java subtype of `Throwable`, and the named public effect constructor must
accept exactly one payload whose Java-facing type matches that exception type. If multiple mappings
apply, the most-specific exception type takes precedence; ambiguous mappings are rejected. Mapping
validation uses class-file metadata, so it does not load or initialize the Java class. Checked
exceptions are not inferred for direct Java interop calls: those calls are rejected when the selected
method declares checked exceptions. Java constructors declaring checked exceptions are currently
unsupported, including constructors exposed by an external facade.

The method's Zeron `raises` set is derived from its mappings. At runtime, only the Java invocation is
covered by the translation handler: receiver and argument evaluation failures are not remapped.
`RuntimeException` descendants and `Error`s bypass translation even when a broad mapping names
`Exception` or `Throwable`; they continue to the host unchanged. The original Java exception is
passed as the payload to the selected Zeron effect constructor.

### Handler scope and result type

The protected expression is resolved with an isolated raised-effect set. On normal completion its
value is preserved. A handler removes an effect from that set only if it has an unguarded,
irrefutable pattern for that effect. In particular, guarded or refutable patterns do not prove
coverage; a set of refutable cases is not combined to infer exhaustiveness. An unmatched payload,
including one for a listed but incompletely handled effect, remains in the escaping set and must be
declared or handled by an outer scope. Handler arms and guards are resolved in the enclosing effect
scope, so effects they raise are not removed by that same handler.

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
accepts a value whose effect set is a subset of the expected effect set. Erased JVM function-shape
names and SAM descriptors do not include raised effects because JVM invocation types are unchanged.
Runtime effect checking is preserved inside generated lambda bodies.

Effect metadata was first added in compiled-library index schema version 15. The current schema
serializes both the effect marker on exported nominal classes and raised sets on exported callable
signatures, as well as external-facade exception mappings needed to revalidate and emit those bridges
in separately compiled clients. The standard-library API version remains
independent of this binary schema version. Consumers of schemas that predate effect metadata must
rebuild or use a compatible compiler; such an index cannot communicate effect markers, raised
signatures, or exception mappings safely.

## Runtime model

The compiler emits a final `zeron.runtime.RaisedEffect` runtime exception with an opaque payload.
`raise` evaluates the payload once, constructs this carrier, and throws it. The generated runtime
support class is emitted alongside compiled classes.

A handler compiles its protected expression into a JVM exception-table region catching only
`RaisedEffect`. It tries the patterns in source order, evaluates a guard only after its pattern
matches, and evaluates the arm only when both pattern and guard succeed. If no case matches, it
rethrows the same carrier. Ordinary Java exceptions are not caught by effect handlers; only an explicit external-facade mapping
translates the declared checked exception at its Java call site. An unhandled carrier escaping the
generated `main` method likewise propagates unchanged across the host boundary;
the current compiler does not add a launcher that catches and formats it. A future launcher may
report an uncaught Zeron effect by catching only `RaisedEffect`, without translating Java exceptions
or implicitly stringifying the payload. Effect payload classes and carriers are emitted as normal
class files and public effect metadata is available to imported compiled libraries.

## Limitations and roadmap

Other follow-up areas:

1. Design effect subtyping and generic payloads, if needed, without conflating effect identity with
   nominal value-type inheritance.
2. Extend explicit checked-exception mappings only as concrete interop needs arise; they remain an
   opt-in boundary and do not alter ordinary Java exception propagation.
3. Explore effect-set polymorphism only after concrete callable and function-type compatibility has
   stabilized.
4. Decide whether observable I/O belongs in the raised-effect system. `raises` currently describes
   typed control-flow effects, not purity or the absence of observable side effects, so `println`
   can have no declared raised effects without being pure. If operations such as `println` were to
   raise a hypothetical `IO` effect, weigh the semantic benefit against the learning burden of
   introducing effect handling early; `main` may declare such effects and let them escape to the
   host boundary.
5. Explore first-class, typed effect-handler values that can be named and reused after `with`,
   while keeping `match` for ordinary value inspection and `handle` for raised control flow.
   Specify how handler result types relate to the protected expression's normal result, how
   unhandled effects propagate, and how handler-raised effects are checked; consider composition
   separately. Keep this distinct from resumable algebraic effect handlers unless continuation
   resumption is explicitly designed.
6. Decide whether raised effects should remain abortive or eventually grow into fully resumable
   algebraic effects. Resumption is not part of the current runtime model: `raise` throws a carrier
   and unwinds the JVM stack to a handler. Supporting resumable operations would require capturing
   and invoking continuations and would change the compiler/runtime model substantially. Do not
   assume Project Loom's virtual threads provide arbitrary continuation capture and resumption;
   investigate a compiler-managed strategy such as CPS transformation, starting with one-shot
   continuations, before committing to this direction.

`Result<T, E>` remains ordinary and is not a migration target for this feature. The compiler does
not automatically catch Java exceptions, convert a `Result` into a raised effect, or convert a
raised effect into a `Result`.
