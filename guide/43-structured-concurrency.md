# Structured concurrency

Zeron runs concurrent work on virtual threads. Blocking a virtual thread is cheap, so a task body
looks like ordinary blocking code: no `async`, no `await`, and no wrapper on every function that
participates in the work.

`Scope` gives that work a lifetime. Opening a scope, starting tasks in it, and closing the scope is
the whole protocol. A scope never leaves a task running after its `using` block exits.

```zeron
import zeron.concurrent.Scope;
import zeron.concurrent.Task;
import zeron.resource.DisposeFailure;

public fn loadBoth(): Int performs DisposeFailure {
    using (let scope = Scope.open()) {
        let first = scope.start(() -> load(1));
        let second = scope.start(() -> load(2));
        scope.join();
        return first.value() + second.value();
    }
}
```

`Scope.open()` uses one virtual thread per task. `Scope.openWith(executor)` uses an executor you
own, which is useful for tests or for sharing a bounded pool. The scope never shuts down an injected
executor.

## Starting and joining

`scope.start(block)` starts the block immediately and returns a `Task<T, E>` handle. The handle is
in flight, not a lazy computation. `value()` waits for the result; `isDone()` and `isCancelled()`
report its current state.

`scope.join()` waits for every task started so far and prevents new tasks from starting. Closing the
scope joins again, so an explicit `scope.join()` is optional when the `using` block already waits
before it exits. It is useful when the result values are needed before the block ends.

A `Task` deliberately has no `map`, `flatMap`, or `do` surface. It is a handle to running work, not a
deferred computation. Monadic composition, if it is added later, belongs to a separate abstraction.

## Futures, `spawn`, and `await`

`Future<T, E>` is the direct-style convenience layer over `Task`. `spawn(scope, body)` starts the
body and returns a future; `await(future)` blocks the current virtual thread and returns its value.
Neither adds a keyword, a modifier, or a wrapper to the enclosing signature:

```zeron
import zeron.concurrent.Scope;
import zeron.concurrent.await;
import zeron.concurrent.spawn;
import zeron.resource.DisposeFailure;

public fn loadBoth(): Int performs DisposeFailure {
    using (let scope = Scope.open()) {
        let first = spawn(scope, () -> load(1));
        let second = spawn(scope, () -> load(2));
        return await(first) + await(second);
    }
}
```

`await` is an ordinary generic function. It calls `Future.join()`, which waits for the task and
re-raises the body's captured effects at the `await` call site. The future's `E` row therefore has
the same checking rules as `Task.value()`: the effect belongs to the caller's `performs` clause.

`Future` keeps the primitive surface: `join()` waits, `isDone()` and `isCancelled()` report state,
and `cancel()` requests cooperative cancellation of that one task. Cancelling a future and calling
`scope.cancel()` have the same unwind rules as cancelling a task directly, including `using` cleanup
inside the body. A scope still owns its tasks: close it with `using`, and do not keep a future after
its scope has exited.

Starting the body does not perform its effects. Only `await` observes the outcome, so a future that
is started and never awaited still participates in the scope's join and cancellation but does not
re-raise its effect.

A task failure does not automatically cancel its siblings. `scope.join()` still waits for all of
them; call `scope.cancel()` when the first failure should stop the rest. `value()` is where a task's
captured effect is observed, so each caller decides whether to continue or cancel.

## Effects across a task boundary

A task body may perform effects, but they do not escape the task on their own thread. The effect is
captured as the task outcome. `value()` re-raises it in the joining thread, where ordinary handlers
and the caller's `performs` row apply.

```zeron
fn find(id: Int): User performs NotFound = loadUser(id);

fn findBoth(): Array<User> performs NotFound, DisposeFailure {
    using (let scope = Scope.open()) {
        let first = scope.start(() -> find(1));
        let second = scope.start(() -> find(2));
        return [first.value(), second.value()];
    }
}
```

Here `NotFound` belongs to `findBoth`'s row because `value()` re-raises it. Starting a task does not
perform the effect, and joining a task that has not been asked for its value does not perform it
either; `value()` is the join point that observes the outcome. The type `Task<T, {E}>` records the
task's effects so the compiler can check the caller's row.

A task that fails with an ordinary Java exception behaves the same way at the boundary: `value()`
re-throws the host failure unchanged. It is not translated into a Zeron effect. A task that was
cancelled throws `java.util.concurrent.CancellationException`; if the task also failed while the
cancellation was being delivered, that failure is attached as a suppressed exception.

## Cancellation and deadlines

`scope.cancel()` interrupts every running task and prevents new tasks from starting. A cancelled
`Future` or `Task` raises `java.util.concurrent.CancellationException` at its join point; the scope
then joins the task so `using` cleanup runs. Cancellation is cooperative: a task observes the
interrupt at a blocking call or by checking `Thread.currentThread().isInterrupted()`. The closing
join still waits for the task to unwind, so `using` cleanup inside the task runs before the scope
exits.

`task.cancel()` cancels one task. A cancelled task never publishes its result, even if its body had
already produced one when cancellation arrived.

`scope.deadline(duration)` schedules cancellation after a duration. A later deadline replaces an
earlier one. The deadline is armed while the scope joins, so it can interrupt a task that would
otherwise hang. It only requests cancellation; it does not add a separate failure channel.

```zeron
import zeron.time.Duration;

using (let scope = Scope.open()) {
    let task = scope.start(() -> waitForWork());
    scope.deadline(Duration.fromNanoseconds(5_000_000_000L));
    task.value();
}
```

## Cleanup and failure precedence

A `using` block inside a task is ordinary resource management. When the task body and a disposal
both fail, the body's effect stays primary and the disposal failures are suppressed, exactly as in
synchronous code. An enclosing handler can inspect them with `with suppressed as failures`.

The same rule applies when cancellation interrupts a task: cleanup still runs, and the cancellation
is reported at the join point. A scope's `dispose` itself performs `DisposeFailure` because joining
can be interrupted, so the enclosing `using` propagates that obligation like any other disposal.

## Scope request rules

- Open the scope in a `using` block. `Scope` implements `Disposable`; closing it joins.
- Start tasks after opening the scope and before joining it. Starting after `join()` or `cancel()`
  fails at runtime.
- Keep task bodies free of shared mutable state unless the mutation is explicit. The existing
  lambda capture rules apply; concurrency never implies shared mutation.
- Do not rely on inheritable thread-local state for scope context. Virtual threads may move between
  carriers, and scope-local context is not part of this API.
- `scope.join()` waits for all started tasks. If one task blocks forever, the scope blocks too
  unless a deadline or an explicit cancellation interrupts it.
