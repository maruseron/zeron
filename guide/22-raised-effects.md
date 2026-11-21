# Raised effects

Some operations cannot finish their usual job. A function that loads a user may discover that the
user does not exist; a command may be rejected because the caller lacks permission. The function
cannot return the expected value, but it should still communicate what happened.

One option is to return a value such as `Result<T, E>`. That keeps success and failure as ordinary
values, so each caller decides how to inspect or pass them along. But sometimes the operation
should stop its normal path and transfer control to code that handles the problem. **Raised
effects** express that kind of exceptional control flow with named, typed payloads.

## Declaring and performing an effect

Declare an effect with a payload type that contains the information a handler may need:

```zeron
public effect MissingUser {
    public property id: Int;
}
```

Perform a value of that effect with `perform`. A function lists any effect that may escape in its
`performs` clause:

```zeron
fn findUser(id: Int): String performs MissingUser =
    perform MissingUser.new(id);
```

`perform` does not produce a normal result; its type is `Never`. The effect value carries details
about what happened, while the `performs MissingUser` declaration makes the possibility visible in
the function's type.

Effects are useful when a failure should interrupt the current operation and be handled at a
meaningful level above it, rather than being checked and transformed after every call.

## Response-typed effects

An effect may declare that its handler answers with a value. Add `responds` with the response type
before the payload body:

```zeron
public effect Ask<T> responds T {}

public effect Yield<T> responds Unit {
    public property value: T;
}
```

A `perform` of a responding effect is an expression of the response type, so the perform site can use
the handler's answer:

```zeron
fn question(): String performs Ask<String> =
    "Hello, " + perform Ask<String>() + "!";

fn answered(): String = handle (question()) with {
    case Ask<String>, k -> k.resume("Ada");
};
```

`answered()` produces `"Hello, Ada!"`. The value passed to `k.resume` becomes the result of the
`perform` expression, and the protected computation continues from there. An effect without
`responds` remains abortive: its `perform` still has type `Never` and does not continue.

Only a tail-resumptive handler is supported. The resume token `k` is not a value: it may appear
only as `k.resume(value)` in tail position of its own case, and that call must be the entire case
body. Storing `k`, passing it to a function, resuming it twice, or doing work after `resume` is
rejected. A case may instead omit the token and let the effect abort through the ordinary handler
path. Resuming cases are supported in inline `handle` expressions; named and first-class handler
values currently reject them.

The standard-library effects `Ask<T>`, `Yield<T>`, and `Log` use this model.
`Ask<T>` requests a `T`; `Yield<T>` carries a `T` and responds with `Unit`, which is the push half of
iteration; and `Log` carries a `String` message and responds with `Unit`. A `Yield` handler consumes
each value and resumes immediately:

```zeron
fn numbers(): Unit performs Yield<Int> {
    perform Yield<Int>(1);
    perform Yield<Int>(2);
}

fn printNumbers(): Unit = handle (numbers()) with {
    case Yield<Int>(value), k -> {
        println(value.toString());
        k.resume(());
    }
};
```

## Generic effects and exact applied identity

An effect may declare type parameters used by its payload. Applications are invariant and must be
fully written with closed arguments; `Failure<T>` inside generic code and wildcard applications
such as `Failure<_>` or `Failure<?>` are not accepted in this slice:

```zeron
public effect Failure<T admits nullable> {
    public property value: T;
}

fn load(id: Int): String performs Failure<Int> =
    perform Failure<Int>(id);

fn recovered(): String = handle (load(7)) with {
    case Failure<Int> as failure -> "missing " + failure.value.toString();
    case Failure<String>(_) -> "bad request";
};
```

A handler case for `Failure<Int>` matches only `Failure<Int>`: the erased payload class must match
and the payload's exact application descriptor must equal the case's descriptor. `Failure<String>`,
`Failure<Int?>`, and `Failure<Array<Int>>` are different identities. A generic effect payload
carries one descriptor through canonical and named constructors; non-generic effects remain
descriptor-free. `case Effect` still catches any application without exposing the applied payload
type. Applied `under` ancestry, omitted handler arguments, and generic propagation through
`E admits effect` applications are deferred work.

## Effect subtyping and the top `Effect`

Effects can form an explicit effect-only hierarchy with `under`. Every effect is implicitly a
subtype of the standard-library top `Effect`; a child names any additional effect supertypes and
redeclares the payload it inherits from the single payload-bearing parent:

```zeron
public effect IOError {
    public property path: String;
}

public effect Retryable {}

public effect FileNotFound under IOError, Retryable {
    public property path: String;
    public property code: Int;
}
```

`under` does not inherit behavior or imply contract conformance. The parent-typed view exposes the
parent's payload members; a child that wants the parent's methods on its own values must redeclare
them or declare the relevant `is` conformance. At most one parent path may carry payload state, and
every other parent must be stateless. Cycles and naming `Effect` itself are rejected.

A subeffect satisfies a broader `performs` requirement, so a function that may perform `FileNotFound`
can be used where `performs IOError` is expected:

```zeron
fn read(path: String): String performs FileNotFound = perform FileNotFound.new(path, 1);
fn readBroadly(path: String): String performs IOError = read(path);
```

`Effect` is a value type and an explicit catch-all case. `performs Effect` permits any effect to
escape, and an unguarded `case Effect` handles every raised effect:

```zeron
fn report(path: String): String =
    handle (read(path)) with {
        case Effect as failure -> "failed";
    };
```

`Effect.fromException` wraps a host `Exception` in a library-owned effect. A bare `Effect` cannot be
constructed, and ordinary classes are never assignable to it.

## Propagation

If a function calls another function that may perform an effect, it must either handle that effect
or list it in its own `performs` clause. This rule applies to functions, methods, extensions, named
constructors, and function values. The entry-point `main` can declare effects too.

A non-`Unit` entry has two effect boundaries. Producing the entry value runs `main` itself; an
effect raised there escapes before `Main<A>.run` is reached. Interpreting the value runs
`Main<A>.run`, whose contract declares `performs Effect`; a pure witness may narrow that row, while an
effectful interpreter can declare its own effects. Neither path installs an implicit handler.

```zeron
fn loadLabel(id: Int): String performs MissingUser =
    findUser(id);
```

Here, `loadLabel` does not handle `MissingUser`, so it declares that the effect can propagate to
its caller. A caller must continue that chain by handling or declaring the effect. If an effect is
handled inside the function, it no longer needs to appear in that function's escaping effects.

Raised effects are checked through function types as well. Passing a callback does not erase the
effects it may perform: the expected function type must allow those effects, or the callback must be
handled before it is passed.

## Polymorphic effect rows

A generic callback can accept a pure function or one that performs effects by quantifying over an
effect row. Use `effect...` for a row parameter; the existing `effect` bound continues to mean one
nominal effect type:

```zeron
public fn map<T, R, E admits effect...>(
    list: List<T>,
    transform: (T) -> R performs E
): List<R> performs E = ...
```

The compiler infers `{}` when `transform` is pure, or the callback's raised effects when it is
effectful. A row can also be supplied explicitly as a set, such as `{}` or `{MissingUser}`; a
single effect type is accepted as a singleton row. In a `performs` list, a name with kind `effect...`
expands to its row, while a name with kind `effect` contributes one effect.

Rows can mix fixed effects with one row parameter, for example `performs MissingUser, E`. Calls
propagate both parts. Handling a fixed effect removes that label from the known part, but does not
remove it from the unknown row: a handler cannot assume what an unresolved row contains. Function
compatibility uses effect-set inclusion, so a pure callback can satisfy an open row requirement and
an implementation cannot perform effects beyond its declared row.

Row parameters are compile-time only. They are preserved in compiled-library metadata, but add no
runtime representation; raised values continue to use the existing `RaisedEffect` carrier. Higher-
rank row-polymorphic function values are not supported: rows are quantified on declarations, not
inside function types. See [ZEP-0086](../documents/zeps/ZEP-0086.md) for the full design and
validation coverage.

## Handling effects

Use `handle` around an expression to catch effects raised while that expression runs:

```zeron
fn displayUser(id: Int): String =
    handle (findUser(id)) with {
        case MissingUser as missing -> "No user " + missing.id;
    };
```

Comparable handler cases are tried most-specific first, independent of source order. `as missing`
binds the whole effect payload, so the case can inspect its public `id` property. A case for an
effect supertype may match any subeffect and views the payload through that supertype, exposing only
its inherited members. A handler is an expression: on the normal path it preserves
the protected expression's result, and when an effect is handled it evaluates the matching arm.
The normal result and all handler-arm results must have compatible types.

A handler removes an effect from the escaping set only when an unguarded, irrefutable case handles
it. A guarded case handles the effect only when its guard succeeds; if it fails, the effect keeps
propagating. If no case matches, the same effect propagates to the surrounding handler or callable.
Effects raised by a handler arm also propagate unless that handler is itself inside another handler
or the enclosing callable declares them.

A handler case for a responding effect may bind a resume token after the pattern, separated by a
comma:

```zeron
handle (perform Ask<Int>()) with {
    case Ask<Int>, k -> k.resume(8);
};
```

The token makes the case tail-resumptive: the response is delivered to the perform site and the
protected computation continues. Nested inline handlers work, and effects raised while evaluating
the resume argument are handled by the next enclosing handler. Abortive effects in the same handler
continue to use the ordinary unwinding path, so a mixed handler can resume `Ask` and abort `Failure`.

## Reusable and first-class handlers

When a policy should be reused, declare a named handler. It is an immutable value and can be
imported from a compiled library:

```zeron
public handler MissingUserText {
    case MissingUser(_) -> "No user found";
}

fn displayUser(id: Int): String =
    handle (findUser(id)) with MissingUserText;
```

For a one-off policy, write an anonymous handler expression. It can capture immutable locals just
like a lambda:

```zeron
fn displayUser(id: Int): String {
    let prefix = "Missing user: ";
    let policy = handler {
        case MissingUser as missing -> prefix + missing.id;
    };
    return handle (findUser(id)) with policy;
}
```

A handler type records what a handler can do:

```zeron
String ~> {MissingUser}
```

The type before `~>` is the normal result type of the protected computation. The first set lists
effects that the handler completely handles, and the optional `performs` set lists effects that its
cases may perform. Omitting it means the handler cases perform no effects; an explicit `performs {}` is
equivalent and remains accepted. Both sets are finite and may be empty. Effect sets follow the same
subtyping relation as `performs` clauses: a set is covered when every element is a subtype of a
declared element, and assignment requires the same result with compatible effect sets.

Function types and handler types can look similar at first glance because both describe what
happens when some logic is applied, and both can mention effects with `performs`. But they describe
different things. A function type describes the function's inputs and normal result; a `performs`
clause says which effects may escape when the function is called. A handler type instead describes
the normal result of a protected computation, which effects the handler handles, and which effects
its cases may perform. You can think of a handler as a policy for dealing with some effects around a
computation.

Applying a handler removes its fully handled effects from the protected expression and adds its
case-raised effects. Guarded or refutable cases do not count as complete coverage. If a case does not
match at runtime, the original raised effect continues outward. Handler composition is not supported;
use nested `handle` expressions when distinct policies must be applied in sequence.

When a `using` scope performs an effect and disposal also fails, the original effect stays primary and
the cleanup failures are attached as suppressed outcomes. A handler arm can inspect them explicitly:

```zeron
case OperationFailure with suppressed as cleanupFailures -> cleanupFailures.length;
```

`cleanupFailures` is an immutable `Array<Any?>` binding containing the suppressed effect values or
unchecked throwable objects. It is empty when the primary effect had no cleanup failures. See the
[resource-management guide](26-resource-management.md) for cleanup order and failure precedence.

Handlers are expressions, not statement-level `try`/`catch`. The current syntax has no wildcard
catch-all case. Java exceptions are not converted automatically; external-class methods can
explicitly map declared checked exceptions to Zeron effects, as described in the
[Java interoperability guide](23-java-interoperability.md).

## Effects across task boundaries

A concurrent task started with `Scope.start` captures a raised effect at its boundary instead of
letting it cross threads. The effect stays dormant until the task is joined with `value()`, which
re-raises it in the joining thread:

```zeron
fn loadAll(): Array<User> performs NotFound, DisposeFailure {
    using (let scope = Scope.open()) {
        let first = scope.start(() -> find(1));
        let second = scope.start(() -> find(2));
        return [first.value(), second.value()];
    }
}
```

Starting the task does not perform `NotFound`; `first.value()` does. The `Task<T, {E}>` handle records
the task body's effects, so omitting `NotFound` from `loadAll`'s `performs` row is a compile-time
error. Ordinary handlers work at the join point because the effect is re-raised there.

An ordinary Java failure inside a task is not translated into a Zeron effect. It surfaces unchanged
from `value()`. Cancellation surfaces as `java.util.concurrent.CancellationException`, with a task
failure attached as suppressed if cancellation and failure overlap. See the
[structured concurrency guide](43-structured-concurrency.md) for scope lifetime, cancellation, and
deadlines.

## Effects and `Result`

`Result<T, E>` represents success or failure as an ordinary value. The caller can store it, return
it, inspect it with `match`, or transform it with its operations. Raised effects instead transfer
control until a matching handler catches them or a callable declares that they may escape.

Choose `Result` when callers should explicitly process the outcome as data, especially when the
failure is expected and should be handled locally. Choose a raised effect when the operation
should be interrupted and handling belongs at an enclosing boundary. They are separate models:
Zeron does not automatically convert a `Result` into an effect or vice versa.

## Runtime behavior and current limits

The compiler represents a raised effect at runtime as a JVM exception of type
`zeron.runtime.RaisedEffect`, carrying the effect payload. A handler catches this carrier around
its protected expression, tests cases in order, and rethrows the same carrier if none matches.
Ordinary Java exceptions are not caught by effect handlers and keep their usual JVM behavior, except
when an external-class method explicitly maps a declared checked exception to an effect at its call
boundary.

Raised effects are not a purity system. A callable with an empty `performs` set can still mutate
through a `&T` receiver, write output, read the environment, or perform other observable work.
`performs` records which typed effects may escape; it does not promise referential transparency. The
Java host boundary follows the same rule. Curated `external class` facades remain the trusted, typed
tier, and direct Java access requires an explicit `unsafe` boundary rather than an implicit purity
claim. A Zeron effect raised inside an `unsafe` operand still escapes and must be handled or declared
by the enclosing function. [ZEP-0148](../documents/zeps/ZEP-0148.md) implements Java SAM conversion: a
lambda or top-level function reference converted to a Java functional interface must have an empty
`performs` row, so a `RaisedEffect` never crosses into Java; handle or map the effect inside the
callback instead.

Tail-resumptive handlers use a synchronous thread-local handler-frame stack rather than capturing a
continuation. A responding `perform` selects the innermost matching frame, temporarily removes it while
the handler runs, and returns the response to the perform site. If the case does not match, the same
carrier continues outward. No continuation object, stack capture, or asynchronous suspension is
involved, and a first-class handler value with a resuming case is rejected rather than lowered
incorrectly.

Effect payload declarations remain synchronous and nominal. Non-generic effects keep their
descriptor-free payload ABI; generic effects add a descriptor for exact application identity.
Generic effect rows and explicit `under` subtyping are supported, but there is no implicit wildcard
handler, asynchronous effect propagation, handler composition, or automatic Java-exception
translation. An unhandled effect escaping
generated `main` propagates across the host boundary; the compiler does not currently add a launcher
that formats it. The same applies to an effect raised while `Main<A>.run` interprets an entry value.
A program that wants formatted failure behavior should return `Result<Unit, E>` or handle its
effects before returning.

For declaration, checking, and runtime details, see the
[typed raised effects design document](../documents/design_documents/design-document-13_typed-raised-effects.md).
