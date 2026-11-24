# Structured concurrency

Zeron runs concurrent work on virtual threads. Blocking a virtual thread is cheap, so a task body
looks like ordinary blocking code: no `async`, no `await`, and no wrapper on every function that
participates in the work.

`ConcurrentScope` gives that work a lifetime. Opening a scope, starting tasks in it, and closing the scope is
the whole protocol. A scope never leaves a task running after its `using` block exits.

```zeron
import zeron.concurrent.ConcurrentScope;
import zeron.concurrent.Task;

public fn loadBoth(): Int {
    using (let scope = ConcurrentScope.open()) {
        let first = scope.start(() -> load(1));
        let second = scope.start(() -> load(2));
        scope.join();
        return first.value() + second.value();
    }
}
```

`ConcurrentScope.open()` uses one virtual thread per task and is fail-fast by default: the first
genuine task failure cancels every sibling and rejects new starts. `openManual()` preserves the
caller-coordinated behaviour, where a failure stays on its own handle and does not cancel the rest.
`ConcurrentScope.openWith(executor)` and `openManualWith(executor)` use an executor you own, which
is useful for tests or for sharing a bounded pool. The scope never shuts down an injected executor.

## The `concurrent` helper

`concurrent { ... }` opens a fail-fast scope, installs the blocking `Await` handler, and lends a
read-only scope receiver. Call `start`, `join`, `cancel`, and `deadline` without a `scope.` prefix;
the body does not declare `DisposeFailure`, and `await` does not need a separate `blocking` wrapper:

```zeron
import zeron.concurrent.await;
import zeron.concurrent.concurrent;

public fn loadBoth(): Array<User> performs DbError =
    concurrent {
        let first = start(() -> repo.user(1));
        let second = start(() -> repo.user(2));
        return [await(first), await(second)];
    };
```

When the body exits, the helper cancels and joins every task that is still running. `join()` is
available when the body needs every task to finish. `concurrentWith(executor, body)` is the same
helper on an injected executor.

## Starting and joining

`scope.start(block)` starts the block immediately and returns a `Task<T, E>` handle. The handle is
in flight, not a lazy computation. `value()` waits for the result; `isDone()` and `isCancelled()`
report its current state.

`scope.join()` waits for every task started so far and prevents new tasks from starting. Closing the
scope joins again, so an explicit `scope.join()` is optional when the `using` block already waits
before it exits. It is useful when the result values are needed before the block ends.

A `Task` deliberately has no `map`, `flatMap`, or `do` surface. It is a handle to running work, not a
deferred computation. Monadic composition, if it is added later, belongs to a separate abstraction.

## Tasks, `spawn`, and `await`

`Task<T, E>` is the single eager handle. `spawn(scope, body)` starts the body and returns
`&Task<T, {E}>`; `await(task)` waits and returns its value. Neither adds a keyword, a modifier, or a
wrapper to the enclosing signature:

```zeron
import zeron.concurrent.Await;
import zeron.concurrent.ConcurrentScope;
import zeron.concurrent.await;
import zeron.concurrent.spawn;

public fn loadBoth(): Int performs Await {
    using (let scope = ConcurrentScope.open()) {
        let first = spawn(scope, () -> load(1));
        let second = spawn(scope, () -> load(2));
        return await(first) + await(second);
    }
}
```

`await` is an ordinary generic function. It first performs the `Await` effect, which lets a handler
choose the waiting policy, and then reads the finished task with `value()`. The value and the task's
captured effects `E` come from that typed join, so the caller's row needs `Await, E` exactly as
written above.

`Await` is non-generic. Its payload is a `Completion`, which exposes only `awaitDone()`, `isDone()`,
`isCancelled()`, and `cancel()`; a handler chooses how to wait but cannot invent a value. The public
`blocking(body)` wrapper installs the default blocking policy for an explicit boundary. The program
entry point installs the same policy implicitly, so `main` can await without declaring `Await`:

```zeron
fn main(): Unit performs DisposeFailure {
    using (let scope = ConcurrentScope.open()) {
        let task = spawn(scope, () -> load());
        println(await(task));
    }
}
```

Cancelling a `Task` and calling `scope.cancel()` have the same unwind rules as before, including
`using` cleanup inside the body. A scope still owns its tasks: close it with `using`, and do not keep
a task after its scope has exited.

Starting the body does not perform its effects. `value()`/`await` observes the outcome at the join.
In fail-fast mode, a genuine task failure that is started and never awaited still participates in
the scope's cancellation and is reported at close unless another join observed it.

A task body that awaits is an ordinary effectful body. Direct-style waiting inside a task is not
resumable in this slice; awaiting in the task's caller, including `main`, is the supported path.

In fail-fast mode, the first genuine task failure becomes the scope's primary failure and cancels
its siblings. `scope.join()` waits for all of them but does not raise. The failed task re-raises its
own failure at `value()`/`await`; a sibling cancelled by the primary re-raises the primary there
instead of hiding it behind `CancellationException`. If the body returns without observing a
genuine failure, close reports the first unobserved one. `openManual()` restores caller-coordinated
failure handling, where a task failure does not cancel siblings and is only observed at its join.

## Effects across a task boundary

A task body may perform effects, but they do not escape the task on their own thread. The effect is
captured as the task outcome. `value()` re-raises it in the joining thread, where ordinary handlers
and the caller's `performs` row apply.

```zeron
fn find(id: Int): User performs NotFound = loadUser(id);

fn findBoth(): Array<User> performs NotFound {
    using (let scope = ConcurrentScope.open()) {
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
`Task` raises `java.util.concurrent.CancellationException` at its join point; the scope
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

using (let scope = ConcurrentScope.open()) {
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
is reported at the join point. `ConcurrentScope.dispose` is effect-free; it joins every task and, in
fail-fast mode, reports the first unobserved genuine failure as a runtime safety net. An enclosing
handler can observe a raised effect if one is active at the close site; otherwise it surfaces as a
host failure. The compiler does not require a `DisposeFailure` obligation for scope-only code.

Explicit `cancel()` and deadline cancellation are cancellation causes, not failures. They cancel the
scope without becoming the primary failure, and task failures recorded during that cancellation are
not escalated at close.

## Scope request rules

- Open the scope in a `using` block. `ConcurrentScope` implements `Disposable`; closing it joins.
- Start tasks after opening the scope and before joining it. Starting after `join()` or `cancel()`
  fails at runtime.
- Keep task bodies free of shared mutable state unless the mutation is explicit. The existing
  lambda capture rules apply; concurrency never implies shared mutation.
- Do not rely on inheritable thread-local state for scope context. Virtual threads may move between
  carriers, and scope-local context is not part of this API.
- `scope.join()` waits for all started tasks. If one task blocks forever, the scope blocks too
  unless a deadline or an explicit cancellation interrupts it.
