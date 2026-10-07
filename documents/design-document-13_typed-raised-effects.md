# Design 13: Typed raised effects

## Status and scope

This document describes the initial implementation of statically declared, typed, synchronous
raised effects. The feature is intentionally separate from `Result<T, E>`: a `Result` is an ordinary
value that callers inspect, while a raised effect transfers control to a matching handler or to the
caller.

The initial slice supports nominal, non-generic `effect` declarations; `raises` clauses on
functions, class methods, extension methods, named constructors, and function type annotations;
`raise` expressions; and expression handlers. Effects are checked by the resolver and represented in
compiled-library API metadata. A handler may list one or more exact effect types, optionally bind
the whole payload with `as`, and evaluate an expression for each case.

The implementation does not add effect inheritance, effect generics, effect rows, catch-all or
wildcard cases, property patterns, asynchronous effects, or Java exception translation. Effects may
not escape from the entry-point `main` function or top-level initializers. The implementation is
limited to expression handlers; no statement-level `try`/`catch` form is introduced.

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

A handler protects exactly one parenthesized expression. Each `case` names a concrete effect type,
may bind the entire payload to an immutable alias with `as`, or bind one named public property with
`Effect.property(binding)` (using `_` when the property value is not needed), and ends with an
expression and semicolon. Cases are tried in source order. The parser requires at least one case
and rejects duplicate effect types during resolution.

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

The entry-point `main` function cannot declare raised effects. Effects in its body therefore need
local handlers. Top-level value initializers also cannot raise effects because there is no enclosing
callable to receive them. Java exceptions are not translated into Zeron effects, and external Java
functions have no raised effects in their signatures.

### Handler scope and result type

The protected expression is resolved with an isolated raised-effect set. On normal completion its
value is preserved. The handler removes the effect types listed by its cases from the protected
expression's escaping set; any other effects remain and must be declared or handled by an outer
scope. Handler arms are resolved in the enclosing effect scope, so effects raised by an arm are not
removed by that same handler.

The handler expression has a common result type for the protected expression's normal result and all
handler arms. When the protected expression has type `Never`, only the arms determine the result
type. This ensures that both normal and handled paths produce values compatible with the expression's
static type.

Cases do not establish exhaustiveness. A missing case is a propagation path, not a compile-time
error; the caller must declare the unmatched effect or handle it elsewhere. Wildcards and catch-all
cases are intentionally absent, so a handler cannot silently erase an unlisted effect.

### Functions and library compatibility

Raised effect lists are part of `FunctionDescriptor` signatures, including function type annotations
and exported signatures for functions, methods, extensions, and named constructors. A function type
accepts a value whose effect set is a subset of the expected effect set. Erased JVM function-shape
names and SAM descriptors do not include raised effects because JVM invocation types are unchanged.
Runtime effect checking is preserved inside generated lambda bodies.

The compiled-library index schema was bumped to version 15. The index serializes both the effect
marker on exported nominal classes and raised sets on exported callable signatures. The standard
library API version remains independent of this binary schema version. Consumers of older schema
files must rebuild or use a compatible compiler; an older index cannot communicate effect markers or
raised signatures safely.

## Runtime model

The compiler emits a final `zeron.runtime.RaisedEffect` runtime exception with an opaque payload.
`raise` evaluates the payload once, constructs this carrier, and throws it. The generated runtime
support class is emitted alongside compiled classes.

A handler compiles its protected expression into a JVM exception-table region catching only
`RaisedEffect`. It checks the carrier payload against each declared effect class, binds the matching
payload alias or reads the requested public property, and evaluates the matching arm. If no case
matches, it rethrows the same carrier. Ordinary Java exceptions are not caught or rewritten. Effect
payload classes and carriers are emitted as normal class files and public effect metadata is
available to imported compiled libraries.

## Limitations and roadmap

Handler property patterns are limited to one public property and no guard. OR-patterns and catch-all
cases are deferred until coverage and propagation rules are specified; any future wildcard must not
make an undeclared effect disappear silently.

Other follow-up areas:

1. Design effect subtyping and generic payloads, if needed, without conflating effect identity with
   nominal value-type inheritance.
2. Design multi-property, nested, and OR handler patterns with coverage diagnostics.
3. Settle the host-boundary policy for raised effects. The current rule requires effects in `main`
   to be handled locally; explore whether unhandled effects may instead escape from `main` to the
   host, either for all effect types or only for designated host-facing effects. This could keep
   beginner programs from needing handlers for routine operations, but must define how the host
   reports or handles an uncaught effect rather than silently dropping it. Decide separately how
   top-level initializers should behave.
4. Add an explicit Java exception bridge only as an opt-in boundary construct; ordinary external
   functions should remain effect-free and Java exceptions should continue to propagate unchanged.
5. Explore effect-set polymorphism only after concrete callable and function-type compatibility has
   stabilized.
6. Decide whether observable I/O belongs in the raised-effect system. `raises` currently describes
   typed control-flow effects, not purity or the absence of observable side effects, so `println`
   can have no declared raised effects without being pure. If operations such as `println` were to
   raise a hypothetical `IO` effect, weigh the semantic benefit against the learning burden of
   introducing effect handling early; consider that choice together with the proposed `main`
   boundary policy.
7. Explore first-class, typed effect-handler values that can be named and reused after `with`,
   while keeping `match` for ordinary value inspection and `handle` for raised control flow.
   Specify how handler result types relate to the protected expression's normal result, how
   unhandled effects propagate, and how handler-raised effects are checked; consider composition
   separately. Keep this distinct from resumable algebraic effect handlers unless continuation
   resumption is explicitly designed.
8. Decide whether raised effects should remain abortive or eventually grow into fully resumable
   algebraic effects. Resumption is not part of the current runtime model: `raise` throws a carrier
   and unwinds the JVM stack to a handler. Supporting resumable operations would require capturing
   and invoking continuations and would change the compiler/runtime model substantially. Do not
   assume Project Loom's virtual threads provide arbitrary continuation capture and resumption;
   investigate a compiler-managed strategy such as CPS transformation, starting with one-shot
   continuations, before committing to this direction.

`Result<T, E>` remains ordinary and is not a migration target for this feature. The compiler does
not automatically catch Java exceptions, convert a `Result` into a raised effect, or convert a
raised effect into a `Result`.
