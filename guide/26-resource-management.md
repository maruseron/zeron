# Resource management

Use a lexical `using` scope for a resource that must be disposed when control leaves a block. A
resource implements `zeron.resource.Disposable`; the bundled contract declares a mutating
`dispose(): Unit performs DisposeFailure` operation.

```zeron
using (let file: &File = File.open(path)) {
    read(file);
}
```

`using` is a statement, not an expression: it has no block result. Functions that need to return a
value use an explicit `return`. Effects from resource initializers, the body, and disposal remain in
the enclosing callable's effect set, so they must be handled or declared with `performs`.

## Scopes own task lifetimes

A concurrent scope is itself a `Disposable` resource. Open it in a `using` block, start tasks in it,
and let the closing join bound their lifetimes:

```zeron
using (let scope = ConcurrentScope.open()) {
    let first = scope.start(() -> load(1));
    let second = scope.start(() -> load(2));
    scope.join();
    return first.value() + second.value();
}
```

Closing the scope joins every task, so no task outlives the block. Cancelling the scope interrupts
its tasks before that join, and `using` cleanup inside a task still runs as the task unwinds. When a
task body and a resource disposal both fail, the body's effect stays primary and the cleanup
failures are suppressed, using the same precedence as synchronous code. The
[structured concurrency guide](43-structured-concurrency.md) covers effects, cancellation, and
deadlines.

## Mutable resource views

Disposal can mutate the resource, so each binding must have a mutable reference type (`&T`) whose
type conforms to `Disposable`. The binding itself is immutable: `let mut` is not needed to dispose
or reassign a resource.

## Multiple resources and cleanup order

Bindings are acquired left-to-right. Each successfully acquired resource is disposed exactly once
in reverse order when the scope exits, including on `return`, `break`, or `continue`. If a later
initializer fails, already acquired resources are still disposed in reverse order.

```zeron
using (
    let input: &Input = Input.open(path),
    let output: &Output = Output.create(destination)
) {
    copy(input, output);
}
```

If acquisition or the body fails, that failure remains primary. Cleanup is attempted for every
acquired resource, and cleanup failures are attached as suppressed outcomes rather than replacing
the primary failure. If the body completes normally but disposal fails, the first cleanup failure
becomes primary and later cleanup failures are suppressed.

The standard `DisposeFailure` effect has a `cause: Any?` property for the original failure object.
Unchecked Java exceptions and `Error`s are not translated; they propagate unchanged as the primary
failure when thrown by the body or disposal.

Cleanup must not block or suspend. While a `dispose` call runs, performing `Await` or entering a new
continuation is a runtime error, and the resolver rejects an `Await` perform, an `Await` entry in a
`dispose` row, or a continuation-capturing `handle` in a method named `dispose`. Wait for a task
outside cleanup and dispose only what is already finished.

## Observing suppressed cleanup failures

An effect handler may opt into the cleanup outcomes associated with its caught effect:

```zeron
fn closeAndReport(resource: &Resource): Int =
    handle (run(resource)) with {
        case OperationFailure with suppressed as cleanupFailures ->
            cleanupFailures.length;
    };
```

The optional `with suppressed as name` clause appears after a case's optional guard and before
`->`. The binding is immutable and has type `Array<Any?>`; entries are suppressed effect values or
unchecked throwable objects, in cleanup order. It is empty when no cleanup failed. It is available
only on handler arms that request it.

Resource management is synchronous. Asynchronous disposal, cancellation, and ownership-based
cleanup are not part of the current model.
