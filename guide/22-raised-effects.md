# 22. Raised effects

Some operations cannot finish their usual job. A function that loads a user may discover that the
user does not exist; a command may be rejected because the caller lacks permission. The function
cannot return the expected value, but it should still communicate what happened.

One option is to return a value such as `Result<T, E>`. That keeps success and failure as ordinary
values, so each caller decides how to inspect or pass them along. But sometimes the operation
should stop its normal path and transfer control to code that handles the problem. **Raised
effects** express that kind of exceptional control flow with named, typed payloads.

## Declaring and raising an effect

Declare an effect with a payload type that contains the information a handler may need:

```zeron
public effect MissingUser {
    public property id: Int;
}
```

Raise a value of that effect with `raise`. A function lists any effect that may escape in its
`raises` clause:

```zeron
fn findUser(id: Int): String raises MissingUser =
    raise MissingUser.new(id);
```

`raise` does not produce a normal result; its type is `Never`. The effect value carries details
about what happened, while the `raises MissingUser` declaration makes the possibility visible in
the function's type.

Effects are useful when a failure should interrupt the current operation and be handled at a
meaningful level above it, rather than being checked and transformed after every call.

## Propagation

If a function calls another function that may raise an effect, it must either handle that effect
or list it in its own `raises` clause. This rule applies to functions, methods, extensions, named
constructors, and function values. The entry-point `main` can declare effects too.

```zeron
fn loadLabel(id: Int): String raises MissingUser =
    findUser(id);
```

Here, `loadLabel` does not handle `MissingUser`, so it declares that the effect can propagate to
its caller. A caller must continue that chain by handling or declaring the effect. If an effect is
handled inside the function, it no longer needs to appear in that function's escaping effects.

Raised effects are checked through function types as well. Passing a callback does not erase the
effects it may raise: the expected function type must allow those effects, or the callback must be
handled before it is passed.

## Handling effects

Use `handle` around an expression to catch effects raised while that expression runs:

```zeron
fn displayUser(id: Int): String =
    handle (findUser(id)) with {
        case MissingUser as missing -> "No user " + missing.id;
    };
```

The handler's cases are tried in order. `as missing` binds the whole effect payload, so the case
can inspect its public `id` property. A handler is an expression: on the normal path it preserves
the protected expression's result, and when an effect is handled it evaluates the matching arm.
The normal result and all handler-arm results must have compatible types.

A handler removes an effect from the escaping set only when an unguarded, irrefutable case handles
it. A guarded case handles the effect only when its guard succeeds; if it fails, the effect keeps
propagating. If no case matches, the same effect propagates to the surrounding handler or callable.
Effects raised by a handler arm also propagate unless that handler is itself inside another handler
or the enclosing callable declares them.

Handlers are expressions, not statement-level `try`/`catch`. The current syntax has no wildcard
catch-all case. Java exceptions are not caught or converted into Zeron effects.

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
Ordinary Java exceptions are not caught by effect handlers and keep their usual JVM behavior.

Effects are synchronous, nominal, and non-generic in the current implementation. There is no
effect inheritance, wildcard handler, asynchronous effect propagation, or automatic Java-exception
translation. An unhandled effect escaping generated `main` propagates across the host boundary;
the compiler does not currently add a launcher that formats it.

For declaration, checking, and runtime details, see the
[typed raised effects design document](../documents/design-document-13_typed-raised-effects.md).
