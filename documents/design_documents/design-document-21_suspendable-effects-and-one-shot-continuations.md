# Zeron: Suspendable Effects and One-Shot Continuations

*Design document. Summarizes the discussion on async/await, generators and resumability in Zeron. Syntax marked as a proposal does not exist in the language yet. Revised 2026-11-22 to align with the settled ZEP-0119 revision and its backend prototype; the corrections are recorded in §0.*

## 0. Status and relationship to proposals

This is a design exploration, not an approved design. Its decisions are split into scoped proposals:

- [ZEP-0118](../zeps/ZEP-0118.md) — response-typed effects and tail-resumptive handlers;
- [ZEP-0119](../zeps/ZEP-0119.md) — one-shot continuations, deep handlers, and cancellation;
- [ZEP-0164](../zeps/ZEP-0164.md), [ZEP-0165](../zeps/ZEP-0165.md) — the non-generic `Await`
  carrier, `Completion`, and unified `Task` surface; ZEP-0120 is the superseded history;
- [ZEP-0173](../zeps/ZEP-0173.md) — source-level debug metadata and IDE stepping, deferred from the
  continuation work.

[ZEP-0007](../zeps/ZEP-0007.md) is superseded and remains the historical mechanism comparison, not
an active design home. Multi-shot continuations, the only scope not covered by the split, are
`Deferred` in [ZEP-0121](../zeps/ZEP-0121.md). [ZEP-0112](../zeps/ZEP-0112.md) remains the
structured-concurrency primitive on virtual threads, including blocking suspension. Where this
document and a ZEP disagree, the ZEP is authoritative.

Corrections recorded in this revision:

- Loom continuations are not implicit delimited control. A is the preferred backend subject to a
  provider/launcher gate, with B as fallback (§9.2).
- The handler ABI is not a blocker for reified generics (§9.3).
- A `handle` that implicitly cancels continuations does **not** gain `DisposeFailure` in its result
  row. Only an explicit `k.cancel()` reports cleanup failures through ordinary effect typing (§8.3,
  §10); the earlier static-capture rule is withdrawn.
- A blocking `await` is tail-resumptive and needs no continuation capture (§6.1, §9.2).
- `Async` is not assumed to be a nominal effect; it is shorthand for a row of async carriers (§4,
  §10).
- v1 is owner-thread confined. Cross-thread callbacks wake the owner; there is no public `resumeOn`
  or gate in v1 (§7.2, §9.2).
- The main use case is pull generators and general resumable handlers, not non-blocking `await`
  (§6.1, §9.2).
- Backend C (a public-API virtual-thread/park implementation) is part of the prototype and is
  rejected for per-element generator yields; A ships behind the provider boundary, and the portable
  compiler-managed backend is split into ZEP-0172 (§9.2).
- The generator surface is an ordinary standard-library `generator` function taking a trailing
  `() -> Unit performs Yield<T>` lambda; there is no generator declaration kind (§7.3).
- A replayable `GeneratorSource<T>` is an `Iterable<T>`; the one-shot `Generator<T>` cursor is an
  `Iterator<T>` and never an `Iterable<T>` (§7.3).
- Multi-shot resumption is out of scope; it remains `Deferred` in
  [ZEP-0121](../zeps/ZEP-0121.md).

## 1. Goal and scope

Zeron offers many modeling primitives (ADTs, classes without inheritance, contracts, typeclasses, dataclasses, newtypes, abortive effects, row polymorphism for effects). With the arrival of reified generics, existentials and generic effects there is a risk: ending up with **colored functions** (`async`/`suspend`/`pure`/`constexpr`).

This document explains how to offer the ergonomics of `async/await` and generators **without** splitting the world into functions of two colors, and how it could arrive at **resumable one-shot effects**. Delimited continuations are one implementation strategy to be validated on the JVM, not an assumed foundation.

The progression is:

1. The color problem, and why `Future<T>` + `do` does not solve it.
2. Effects as carriers, rows and handlers (Zeron's current model).
3. Async as an effect in direct style.
4. From abortive handlers to resumable handlers.
5. Continuations, cancellation and resources.
6. Implementation on the JVM.

## 2. Zeron's effect model

### 2.1 Carriers

An effect in Zeron is an **immutable, transparent carrier of data**. It is closer to a *data class* than to an ADT:

- It only declares immutable properties and non-mutating methods.
- It can implement contracts.
- It has no variants: an "error ADT" is modeled as several small effects (`Timeout`, `Conflict`), and the union lives in the row.
- Its methods must have an empty row. If a carrier's method could perform effects, it would stop being transparent: they would fire inside a `match` or a `toString`.

Because the carrier is data, it can be copied, stored or escape its handler without risk.

### 2.2 Rows

A function's row is a **set of nominal carrier types**:

```zeron
fn f(): R performs E                 // concrete E or row variable
fn g<E admits effect...>(): R performs E   // row-polymorphic
contract C<E admits effect> { ... }      // contracts parameterized by a row
```

Row subtraction operates at the type level. A contract without a row parameter means an empty row by default, which gives `pure` for free and leaves `C<E>` as an opt-in.

### 2.3 Handlers and `handle`

A named handler is a declaration with a set of `case`s. Its type is `T ~> { E1, E2, E3 }`, where `T` is the type of the handled expression and `{E...}` are the effects it consumes.

```zeron
handle (doSomeWork())     // doSomeWork(): T performs E1 + E2 + F
    with myHandler;       // myHandler: T ~> { E1, E2 }
                          // whole expression: T performs F + G
```

Typing rule:

```
e : T performs (E1 + E2 + F)
h : T ~> { E1, E2 }       // cases return T and may perform G
-----------------------------------------------
handle (e) with h : T performs (F + G)
```

Fixed properties:

- **T to T.** The handler does not change the type of the success value. There is no return clause. Abortive `case`s replace the whole expression with a `T`.
- **Unhandled effects propagate.** Any carrier the handler does not consume stays in the row (`F`).
- **Cases are outside the handler's scope.** Whatever a `case` performs escapes, as in a `catch`. Its row is added to the result (`G`).
- **Dispatch by type.** Thanks to reified generics, the handler finds the carrier with a runtime type test. Carriers can implement contracts, so a `case` can handle by contract (`case e: Retryable`). Comparable cases are tried most-specific first, independent of source order, with a warning for unreachable cases.
- **Generic and configurable handlers.** A singleton cannot carry state or parameters. Generic handlers (`catchAll<T, E>`) and configurable ones (`Retry(3)`) are factories. The singleton is a particular case.

### 2.4 `for ... collect`

`for source collect into myCollector<T>` is structurally a handler: a computation (the source) plus a named, typed object that decides what happens to what comes out of it. It can be read as a resumable `Yield<T>` carrier plus a collector with `init`, `accumulate` and `finish`. We will see that this reading is the simplest case of resumability.

### 2.5 Resources: `using`, `Disposable`, `DisposeFailure`

Resources implement the `Disposable` contract and are released in a `using` statement block. Cleanup exceptions accumulate as *suppressed*, and any `case` of a handler can request them with `with suppressed as NAME`. This turns out to be key for cancellation later.

## 3. The color problem

A function is "colored" when the language imposes a label on the whole call chain in order to use a capability (`await` only inside `async`). What hurts is:

- Duplicating `map`, `filter` and `fold` per color (`map` and `mapAsync`).
- Viral annotation: calling a colored function forces the caller to be colored.

Returning `Option<T>` is not color in this sense: the wrapper is data someone chose to return. Color appears when the wrapper is **mandatory** to access a capability.

### 3.1 First idea: `Future<T>` + do-notation

```zeron
fn doAsync<T>(callback: () -> T): Future<T> = ...

fn main() {
    let i = do<Future<_>> {
        let x <- doAsync { 5 };
        yield x;               // await
    };
    println(i);
}
```

It is more powerful than `async/await` (it works for any monad), but it reinvents color: the `Future<T>` return type and the `do` block **are** the color.

### 3.2 Second idea: async as a row element

```zeron
fn doAsync<T>(callback: () -> T): T performs Async = ...
```

Here the color becomes a **row element**: it is inferred, abstracted over and subtracted by a handler. But `Async.wrap()` as a handler that turns `T` into `Future<T>` breaks the rule that handlers are T to T. The type transformation lives in an ordinary function.

## 4. Async as an effect in direct style

Two different things must be separated:

1. **An async carrier as an effect:** "this function may suspend". It is direct style, with no
   wrapper. `Async` is shorthand; the concrete carrier is `Await`.
2. **`Future<T>` as a value:** "this computation is in flight and I can wait for it later". It is only needed for concurrency, not for sequencing.

```zeron
public effect Await responds Unit {                     // non-generic control effect
    public property pending: Completion;                // completion view, not the value
}

fn await<T, E admits effect...>(task: Task<T, E>): T performs Await, E
    // raise Await, then read the typed finished task

fn spawn<T, E admits effect...>(scope: Scope, body: () -> T performs E): &Task<T, {E}>
    // start a task in a ZEP-0112 scope

fn main() {                                            // entry installs the blocking Await handler
    using (scope = ConcurrentScope.open()) {
        let task = spawn(scope, () -> doAsync(op));
        println(await(task));
    }
}
```

The `E` parameter is elided in the rest of this document for readability. ZEP-0164 and ZEP-0165 own
the exact `Task`, `Completion`, and `Await` typing. `Await` is deliberately non-generic: the value
comes from the typed `Task`, not from the effect response.

- Sequential code needs neither `Future` nor `do`.
- `spawn` wraps the success value inside the handled expression, just like `toResult`:

```zeron
fn toResult<A, E admits effect>(f: () -> A performs E): Result<A, E> =
    handle (Ok(f())) with catchAll<Result<A, E>>
```

- `main` does not declare `Await`; the generated entry installs the blocking handler. Non-entry
  code declares or handles `Await` explicitly. `Async` is shorthand for "the row contains async
  carriers", not a nominal effect assumed by this document.

### 4.1 Is it the same as async/await?

With a responding `Await` carrier and tail-resumptive handlers, the source has the ergonomics of
`async/await`. In the first backend the handler blocks a virtual thread; a later backend may capture
a continuation instead. The immediate gain is not convenience but **polymorphism and uniformity**,
and it is static:

- `map<E>(f: A -> B performs E): ... performs E` works for pure, failing and asynchronous functions.
  There is no `mapAsync`.
- The async carrier is not a special case: it is a row element like `Timeout` or `NotFound`, with
  the same inference, subtraction and rules in contracts.
- Color is inferred inside bodies and only annotated in the public API.
- Discharging the color is a handler (`runBlocking`).

The color still exists, but it is abstractable. The alternative that removes it entirely (own stacks, Go style) sacrifices the visibility of suspension points and the static check that "this code never suspends", for example at the FFI boundary.

## 5. Do-notation, reify and reflect

Do-notation still has a place, but with a narrower role:

- **Effects:** suspension, errors, state, I/O. Direct style, no wrapper.
- **`reify`:** a handler plus a function that turns an effectful computation into a value (`Future<T>`, `Result<T, E>`).
- **`reflect`:** an operation that turns a monadic value into an effect (the `await`).
- **`do`:** sugar for `flatMap` over **multi-shot** monads (`List`, backtracking parsers), which cannot be expressed with one-shot effects.

`reify` and `reflect` are library functions per monad, not an abstraction over monads, so **HKT is not needed**: you abstract over the row `E`, not over `M<_>`.

Hygiene rule: avoid giving `Option`/`Result` both do-notation and an abortive effect for the same purpose. Pick one idiomatic path and leave the other as an explicit conversion.

## 6. From aborting to resuming

Aborting is the particular case of resuming with response type `Never`. Effects in Zeron do not
encode responses (an effect such as `IoFailure` is pure data), so the response type is part of the
effect declaration. A carrier without a response is abortive, and existing code does not change.

```zeron
public effect NotFound {                  // abortive: responds Never
    public property id: Id;
}
public effect Ask<T> responds T {}        // field-less data, responds T
public effect Yield<T> responds Unit {
    public property value: T;             // responds Unit
}
public effect Await responds Unit {       // non-generic control effect
    public property pending: Completion;  // completion view, not the task value
}
```

The syntax is illustrative and is owned by [ZEP-0118](../zeps/ZEP-0118.md). The compiler synthesizes
a marker contract `Responds<R>` for handler patterns such as `case e: Responds<Unit>`; it is not a
contract that a class or a foreign type can implement. The alternative `effect Ask is Responds<Int>`
surface is recorded as rejected in ZEP-0118 because it makes a library contract part of the
declaration mechanism and invites fictitious conformance.

The `perform` site becomes an expression of type `R`, taken from the declaration. A carrier without it
responds `Never`, which preserves typed early return. In a handler, a `case` over a responding
carrier receives a continuation: at level 2 it is a restricted tail-only token (§6.1), and at level 3
it has type `Cont<R, T>` ([ZEP-0119](../zeps/ZEP-0119.md)). For an abortive carrier, `perform` remains
`Never` and the surrounding code is unreachable; for a responding carrier, `perform` may return, so
subsequent statements are reachable. This is a flow-analysis change, not only a type change.

Rules for response declarations:

- An effect declares at most one response. Two declarations are a compile error.
- The response is invariant in `R`: the handler produces `R` and the `perform` site consumes it.
- `Responds<R>` has no members, so carriers stay immutable data and their methods keep an empty row.
  It is compiler-known and cannot be implemented explicitly by a class or a Java-library type.
- Handlers by contract work for patterns derived from the declaration: `case e: Responds<Unit>`
  handles every carrier that answers `Unit`, with `k: Cont<Unit, T>`.
- Only carriers declared in Zeron can be resumable. A response cannot be attached afterwards to a
  carrier from a Java library.
- A `perform` on a row variable `E` has no known response type, so raising is limited to concrete
  carriers unless a bound on a single effect such as `E: Responds<R> admits effect` is allowed. A row-level
  version such as `E: Responds<R> admits effect...` would wrongly claim that every carrier in the row has
  the same response; it needs a different form. This is an open question, not a solved part of the
  design. ZEP-0118 records that generic code can still call and forward effectful functions without
  performing a responding `perform` itself.

### 6.1 Three levels of handler

1. **Abortive.** Already exist. On the JVM, an abortive `perform` is a stackless exception and `handle` is a `catch` with a reified type test.
2. **Tail-resumptive.** The `case` ends with the resumption in tail position: exactly one `resume`,
   no later work, and no stored continuation. It covers `Yield`, `Ask`, `Log`, blocking `Await`, and
   most practical uses. It compiles to a direct call or a loop, **without capturing continuations**.
   The compiler detects it with a conservative syntactic criterion; helper calls that eventually
   resume are not detected and fall to level 3.
3. **General resumable.** The `case` stores the continuation and resumes it later (pull generators,
   stateful interpreters, handlers waiting on external events). Continuation capture is needed here.

The handler type `T ~> {...}` does not change between levels. At level 3 the `handle` expression
remains effect-subtracting: implicit cancellation of pending continuations does not add
`DisposeFailure` to its row. Only an explicit `k.cancel()` reports cleanup failures through ordinary
effect typing (see §8.3).

### 6.2 `for ... collect` as `Yield`

`Yield` is the simplest resumable case. The duality is:

| View | Who is in control | In Zeron |
| --- | --- | --- |
| Pull (external iterator) | the consumer calls `next()` | repeated `Option<T>` |
| Push (internal iteration) | the producer emits | `perform Yield(v)` + collector |

Turning pull into push is a `while` loop that calls `next()`. Turning push into pull is a **generator**
and requires suspending between elements, that is, general resumability. That is why pull generators
are the first feature that justifies continuation capture; a blocking `Await` does not, because its
handler resumes immediately.

Collectors have state and a return clause, so they transform the type, whereas handlers are T to T. They stay separate for now, with aligned syntax (`with` versus `collect into`) so they can be unified later. `break` is an abortive carrier handled by the `for` itself, not by the collector.

## 7. Designing `resume` and continuations

### 7.1 What `resume` is

`resume` does not exist in Zeron yet. Three forms were evaluated:

1. **Special form** valid only inside a `case`. Typing and linearity are simple, but the continuation cannot escape, so it does not work for a handler that stores `k` and resumes later.
2. **Intrinsic function.** In practice equivalent to a keyword with call syntax, unless Zeron has a general implicit-context mechanism.
3. **Method on a `Cont<R, T>` value.** The most general: the continuation is storable and capturable.

Since Zeron **has no linear or affine types**, one-shot cannot be guaranteed statically. The design
direction explored here is option 3 with a **dynamic** guarantee, comparable to Kotlin (calling
`Continuation.resume` twice throws `IllegalStateException`). [ZEP-0119](../zeps/ZEP-0119.md) owns that
decision and is still a Draft.

### 7.2 Proposed syntax

```zeron
// Unit ~> { Await }
public handler Scheduler {
    case Await as awaited, k -> {       // k: Cont<Unit, Unit>
        awaited.pending.awaitDone();    // wait on the owning thread
        return k.resume(unit);          // tail resume; a later Await re-enters this case
    }
}
```

The type of `k` and of the argument to `resume` comes from the response declaration on the handled
carrier (section 6). A completion callback on another thread must signal the owning thread; it must
not call `resume`.

Rules:

- A `case` that does not use `k` aborts, as today. Existing code does not change.
- `k.resume(v)` requires `v` to have the carrier's response type. Its return type is the handled
  expression's `T`.
- The continuation has a dynamic state: `suspended → running → done | failed`, with `cancelled` as an
  alternative terminal state. `resume` is legal only from `suspended`; a second `resume`, a `resume`
  after `cancel`, and a `resume` after the `handle` exits are **programming errors** (panics), not
  carriers: if they were effects, they would contaminate the whole row. `cancel()` is legal only on
  a suspended continuation and is idempotent on terminal states; there is no public `cancel()` on a
  running continuation.
- The compiler can warn with a lint when it sees two `resume` calls on the same path, without
  claiming to be complete. A continuation stored in a field or captured by a lambda cannot be checked
  locally.
- **Deep handler semantics for the continuation:** what is resumed stays inside the `handle`, so its
  subsequent `perform` calls are caught by the same handler. The `case` body itself remains outside the
  handler: an effect raised by the case escapes unless an outer handler catches it. A re-suspension
  runs the matching case again; a tail-position `return k.resume(v)` may use the runtime tail-transfer
  path so a generator keeps a bounded stack (ZEP-0119).
- A handler that stores `k` is a per-`handle` state machine; its state must survive across
  suspension. A named singleton handler that captures `k` is not stateless.
- `Cont` is confined to the `handle`'s thread. Resuming or cancelling from another thread is a
  runtime error in v1; a callback must wake the owning thread, which then calls `resume`. v1 has no
  public gate or `resumeOn`. Thread-local handler state does not travel with a captured stack
  automatically.
- Carrier methods keep an empty row.

### 7.3 A generator in this model

```zeron
public effect Yield<T> responds Unit {
    public property value: T;
}

fn numbers(): Unit performs Yield<Int> {
    for (i in 0..3) {
        perform Yield.new(i);
    }
}
```

A tail-resumptive handler for `Yield` just sees each value and resumes in tail position. A handler
that keeps `k` alive between elements produces an external iterator (push to pull). The shipped source
form is an ordinary standard-library function with a trailing lambda:

```zeron
public fn generator<T admits nullable + mutable>(
    body: () -> Unit performs Yield<T>
): GeneratorSource<T>

let numbers = generator {
    raise Yield.new(0);
    raise Yield.new(1);
};

for (let n in numbers) println(n);
```

There is no generator declaration kind and no new generator keyword, so generator-producing
functions have no separate color. The lambda's unhandled row is `Yield<T>`; other effects must be
handled inside it, and normal lambda capture rules apply. `GeneratorSource<T>` is the replayable
`Iterable<T>` factory: each `iterator()` invokes the lambda again. `Generator<T>` is the one-shot
`Iterator<T>` cursor and is never an `Iterable<T>`. `Iterator<T>` gains an idempotent `close`; `for`
and `Stream` close the cursor, and explicit `Generator.cancel()` reports `DisposeFailure`. For
constant stack, the library's handler uses the syntactic tail form `return k.resume(unit)` so the
runtime can transfer instead of retaining one case frame per element.

### 7.4 Multi-shot stays out

Resuming a computation twice while it holds an open `Disposable` resource would require duplicating
or replaying resource acquisition, which is one consequence of multi-shot. The reason for excluding
multi-shot is the single-stack one-shot runtime and the decision to keep the `List` monad and other
multi-shot reflection in `do`, not the resource problem alone. The dynamic flag blocks the unsafe
case.

## 8. Cancellation and resources

Without linearity, nothing forces you to resume or discard `k`. The hard case is the **abandoned
continuation**: nobody closes its open `Disposable` resources, and merely dropping a captured stack
does not run its `finally` blocks. Two rules are set:

1. **`k.cancel()`** resumes the computation with an internal signal that unwinds the stack and runs the `dispose` of open `using` blocks, in LIFO order. It is the same unwinding used for abortive effects, with a different cause.
2. **Cancellation scope of `handle`.** When the `handle` ends — by success, by an aborting case, by a
   case that returns without resuming, or by an effect escaping from a case — its pending
   continuations are cancelled automatically. This is a form of structured concurrency that replaces
   linearity. A scheduler that outlives its `handle` would be opt-in.

### 8.1 The cancellation signal is not a carrier

If it were an ordinary carrier, a generic `catchAll<T, E>` would intercept and swallow it (the known `CancellationException` problem in Kotlin). It must be an internal object, outside the row and any type test, that only `using` and `dispose` observe. A `case` cannot "handle cancellation"; whoever wants to react uses `Disposable`.

At the JVM level it should be a private `Throwable` subtype with an unforgeable token, so Java
`finally` blocks run. The trade-off is that Java code with `catch (Throwable)` can observe or swallow
it; the runtime must detect that case and document the boundary. A non-`Throwable` signal would avoid
interception but would not run Java cleanup, which is worse for interop.

### 8.2 Synchronous cancellation

Because `dispose(): Unit performs DisposeFailure` cannot suspend at level 3, no `using` suspends
during unwinding. A tail-resumptive level-2 effect in `dispose` is synchronous and does not violate
this. An unhandled `Await` in the cleanup row is rejected, and a capturing inline handler is rejected.
Any `Await` perform in cleanup is forbidden, including one reached through `blocking`: the runtime
marks each `dispose` and rejects the perform before dispatch. The marker also rejects any other
level-3 suspension attempt. A direct `task.value()` is a separate blocking call, allowed only when
`E` is empty and discouraged. `k.cancel()` unwinds and returns with no scheduler in between.

### 8.3 Where suppressions go

| Situation | What happens to `dispose` failures |
| --- | --- |
| Abortive `perform` on the live stack | The `using` blocks between the `perform` and the `handle` unwind **before** the `case` runs. Their suppressions are available in `with suppressed as s`. |
| Pending continuations when the `handle` ends | They are cancelled **after** the `case` has produced its `T`. The `case` can no longer see them. Implicit cancellation does **not** add `DisposeFailure` to the `handle`'s row: cleanup failures on a normal `T` go to the runtime diagnostic log, and on an escaping effect they are suppressed onto that effect. |
| Case has suspended the computation | The computation stays suspended with its resources open. Nothing has been unwound. |
| `case` aborts and a `dispose` also fails | The cause wins and cleanup failures accumulate as suppressed. |

The cancellation signal accumulates suppressions, and whoever called `cancel()` (the `handle`) collects them on return.

A `handle` that captures continuations stays effect-subtracting. The earlier static-capture rule,
which added `DisposeFailure` to the result row whenever a case captured a continuation, is withdrawn
in [ZEP-0119](../zeps/ZEP-0119.md): it made typing depend on an undefined notion of capture and
changed the normal path. Implicit cancellation therefore suppresses onto an escaping effect or logs;
only an explicit `k.cancel()` performs `DisposeFailure` at its call site.

### 8.4 `using` as a statement

- It has no value, so there is no result a failed `dispose` could replace. If the body does `return x`, `x` is evaluated, the `dispose` calls run, and then it returns. If a `dispose` performs `DisposeFailure`, that failure replaces the `return`, as in try-with-resources.
- `break`, `continue` and `return` go through the `dispose` calls. This should be tested with the `for` `break`s.
- A `using` adds `DisposeFailure` to its function's row, unless a `handle` inside handles it.
- With several resources (`using (a = open(), b = open())`), order is LIFO and suppressions accumulate.
- **Lint:** a `using` kept open across a `perform` whose handler suspends (level 3) keeps the resource
  alive during suspension. A tail-resumptive level-2 handler resumes immediately and does not. The
  compiler can detect the level-3 case when the body's row contains a carrier whose handler stores
  `k`; this is a lint, not a prohibition.

## 9. Implementation on the JVM (Java 25)

### 9.1 Abortive effects

An abortive `perform` is a stackless exception (`writableStackTrace=false`) wrapping the carrier.
`handle` is a `catch` with a reified type test, and `using` is a `try/finally` with suppression
accumulation. JVM unwinding gives cleanup for free. A responding `perform` at level 2 is a direct
handler call instead; it does not throw.

### 9.2 Resumable effects

There are three strategies:

**A. Loom delimited continuations.** `jdk.internal.vm.Continuation` is a one-shot continuation, but
it is **not implicit delimited control**. Suspending requires an explicit `yield(scope)` with the
right `ContinuationScope`; the perform site must still resolve which handler scope owns the carrier, so
a handler stack or evidence-passing mechanism remains. `run()` executes on the calling thread, so a
continuation cannot be resumed freely from an arbitrary callback thread without an explicit handoff.
Real stacks keep stack traces intact and the descriptor does not change, but that does not make the
handler model disappear. **Costs:** it is an internal, encapsulated API requiring
`--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`; every launch path, including programs started
outside `zeron run`, must carry the export; its status must be verified on each JDK; native frames
are a hard barrier; and suspension across an unaware Java frame is a semantic hazard even when the
frame is technically capturable. JEP 491 removed monitor pinning for **blocking** virtual threads; it
did not establish that direct `Continuation.yield` has the same monitor semantics, so that must be
tested rather than assumed.

**B. Kotlin-style state machine.** Portable, but every function with resumable operations receives a
continuation parameter and returns either the value or a `SUSPENDED` marker. It costs bytecode size,
broken stack traces and tail calls, and it changes descriptors for separate compilation.

**C. Virtual thread plus park/unpark.** Run the handled body on its own virtual thread. At a level-3
raise the body publishes the carrier and parks; the owning handler thread runs the case. `resume`
unparks the body and waits for the next suspension or completion; `cancel` delivers the token and
unparks. This uses only public API (`Thread.ofVirtual`, `LockSupport.park/unpark`, an
`ExecutorService`), reuses the ZEP-0112 virtual-thread and cancellation infrastructure, and needs no
internal JDK API or descriptor change. Its costs are one extra virtual thread per suspended body, a
context switch per effect, fragmented stack traces/profiling, and the requirement to wake both the
body and owning threads on cancellation.

**Decision: A ships; the portable backend is split out.** The standalone prototype in
[`prototypes/level3`](../../prototypes/level3/Level3BackendPrototype.java) measured a pull generator
on JDK 26: a hand-written state machine at 3.1–3.6 ns/pull, A's Loom continuation at 68.6–81.4
ns/pull, and C's virtual-thread rendezvous at 1.5–4.2 µs/pull. C also creates one virtual thread per
suspended generator, takes 31–37 µs to create it, and retains about 824 B versus A's 308 B. C is
rejected for per-element generator yields.

A preserves real stacks and descriptors and has the best debug story. Its internal API is a
packaging risk, not a semantic disqualification: it is isolated behind a `ContinuationProvider`,
compiled from `src/continuation/java` by a separate Maven step, and selected at startup.
`distribution/bin/zeron`, `zeron.cmd`, and the generated-program child process pass
`--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`; a plain `java -jar` cannot carry that export
implicitly, so external packaging remains a contract to cover. The provider also owns the API-shape
adapter and the supported-JDK matrix; a shape change should require a new adapter, not runtime-wide
edits. The provider boundary, launcher tests, the `Cont<R, T>` lifecycle, the `Level3Support`
perform-to-`Cont` bridge, tail-resume transfer, per-`handle` generation tokens, task cancellation,
the cancellation token, the `dispose` marker, and compiler lowering are implemented. JDK 25 is
covered by compilation plus the provider shape probe. The IDE debugger check moved to
[ZEP-0173](../zeps/ZEP-0173.md), because it needs compiler line and local metadata, which is not
emitted yet and is independent of the continuation runtime.

The portable compiler-managed backend is no longer a fallback in the continuation proposal. Its
transform, descriptor and API-index cost, cleanup states, and 1 µs/1 KB gate moved to
[ZEP-0172](../zeps/ZEP-0172.md), which is unscheduled. If A fails its gate, do not silently pay the
descriptor cost; decide the portable backend on its own terms. Do not fall back to C for generator
yields.

The prototype did not cover full handler scope resolution, monitors, native frames, unaware Java
callbacks, stack traces, source-level debugger support, JFR beyond the smoke check, or
external-event waits. Those remain implementation questions for A and B; debug metadata is
[ZEP-0173](../zeps/ZEP-0173.md)'s scope.

A blocking `await` does not need any of this machinery. Its handler calls
`Completion.awaitDone()` and resumes in tail position, which is level 2 in §6.1. Only a pull generator
or a general resumable handler needs the resumable backend.

### 9.3 ABI of row-polymorphic functions

With Loom continuations the descriptor problem shrinks, because suspension does not add a
continuation parameter to every function. With strategy B you would have to choose between
specializing by row, a dynamic `bind`, or a hybrid.
Generics, existentials, and the current handler ABI can still ship without a continuation parameter.
The handler and closure representations should reserve an extension point, but they must not depend on
this feature. A is preferred, so suspension does not change descriptors or the API index on the
primary path. If A fails its provider/launcher gate and B becomes the default, every function that may
suspend changes its descriptor and the API index needs a schema addition. The B descriptor shape must
reserve the state/resume parameter without making reified generics depend on it. Do not bump
`API_INDEX_SCHEMA_VERSION` in advance; bump it once only if the B path is actually adopted. C avoids
both but is rejected for generator yields on cost.

### 9.4 Boundaries where the row must forbid suspension

- FFI and callbacks from native code: a native frame is a hard barrier.
- An unaware Java callback: the frame may be technically capturable, but it may hold locks or
  thread-local state and does not expect to be suspended.
- `dispose`, because cancellation must be synchronous.

A `NoSuspend` capability in the signature, or a rule requiring a row without general resumable
operations at these boundaries, is advisable. Note that this is not merely an advisory lint: a
generic row variable `E` may be instantiated with a level-3 carrier, so the boundary needs either a
`NoSuspend` constraint or a monomorphic row free of level-3 carriers. Tail-resumptive level-2 effects
are synchronous and remain allowed. [ZEP-0119](../zeps/ZEP-0119.md) settles the initial rule for
`dispose`: unhandled `Await` and capturing handlers are rejected, and any `Await` perform or other
level-3 suspension under the runtime `dispose` marker panics, including a blocking `await` hidden in
a helper. A direct `task.value()` is a separate blocking call, allowed only when `E` is empty and
discouraged. A later `NoSuspend` capability can replace the marker.

## 10. Summary of language changes

**New:**

1. A `responds R` clause on effect declarations, with a compiler-synthesized `Responds<R>` marker
   for handler patterns (`effect Await responds Unit {}` is the current blocking policy). Carriers
   without it respond `Never`.
2. `perform` as an expression of type `R` (or wrapper functions such as `await(f)`).
3. A continuation received by the `case`: a restricted tail-only token at level 2, and `Cont<R, T>`
   with `resume` and `cancel` at level 3, with a one-shot check at runtime.
4. Deep-handler semantics for what is resumed.
5. Automatic cancellation of pending continuations when the `handle` ends.
6. An internal cancellation signal, outside the row, normalized to `CancellationException` at the
   task boundary.
7. The standard-library `generator` function, `Generator<T>`, `GeneratorSource<T>`, and
   `Iterator.close`. A generator source is a replayable `Iterable`; its cursor is a one-shot
   `Iterator`.

**Unchanged:**

- Function signatures (`f(): R performs E`). Effect declarations gain a response, but calls do not gain
  a wrapper or a `suspend` modifier.
- `T ~> {...}`. The handler type does not change between levels, and a level-3 `handle` remains
  effect-subtracting; implicit cancellation does not add `DisposeFailure` to its row.
- Contracts and existentials (they already carry `E`).
- `using`, `Disposable` and `with suppressed` for abortive code. Cancellation observes `dispose` and
  explicit `k.cancel()` can perform `DisposeFailure`, so this is not literally unchanged at level 3.
- There is no method-level keyword for suspending. The row is already the marker. The IDE highlights suspension points.

This summary is a design sketch. [ZEP-0118](../zeps/ZEP-0118.md), [ZEP-0119](../zeps/ZEP-0119.md),
[ZEP-0164](../zeps/ZEP-0164.md), and [ZEP-0165](../zeps/ZEP-0165.md) own the decisions; where this
document and a ZEP disagree, the ZEP is authoritative.

**Note on `yield`:** it is already used inside `do`, so it should not be reused for suspension. For generators, `Yield<T>` is a responding carrier.

## 11. Suggested implementation order

1. Response-typed carriers (`responds R`, `Never` by default) and `perform` as an expression
   ([ZEP-0118](../zeps/ZEP-0118.md)).
2. Rows with extension and subtraction, and handlers with type `T ~> {...}`.
3. Tail-resumptive handlers (`Yield`, `Ask`, `Log`) without continuation capture. Do not rewrite
   `for ... collect`; introduce `Yield` alongside it and revisit unification later.
4. The async surface as a blocking handler on virtual threads
   ([ZEP-0164](../zeps/ZEP-0164.md), [ZEP-0165](../zeps/ZEP-0165.md)); this needs no continuation
   capture.
5. `Cont<R, T>` with dynamic one-shot `resume`/`cancel`, as a runtime state machine. The backend
   prototype rejects C for generator yields and ships A behind the continuation-provider and
   launcher gate; the portable backend moved to [ZEP-0172](../zeps/ZEP-0172.md)
   ([ZEP-0119](../zeps/ZEP-0119.md)).
6. Pull generators and general resumable handlers: `Generator`/`GeneratorSource`, `Iterator.close`,
   the standard-library `generator` function, and `for`/Stream close propagation. Any cross-thread or
   multiplexing handoff remains a follow-up.
7. Later: general `reify`/`reflect` and multi-shot `do`.

## 12. Open decisions

- The final syntax of `responds`, `k` in the `case`, and `perform` as an expression. ZEP-0118 owns the
  response syntax; this document uses `responds` and keeps `is Responds<R>` only as a rejected
  alternative.
- How a response bound on a single effect or on a row variable is written, and whether it requires a
  new kind or constraint form.
- Whether handler state is per-`handle` and whether `Cont` remains thread-confined or later supports
  explicit handoff. ZEP-0119 settles owner-thread confinement for v1.
- Cross-thread resumption and handler-state handoff if confinement is ever relaxed.
- Whether the cancellation token should remain a private `Throwable` and how a swallowed token is
  detected in practice. ZEP-0119 settles the task-boundary normalization and early delivery rules.
- Whether to unify handlers and collectors (handlers with state and a return clause).
- How `break` and errors in the `for` body relate to the collector.
- Contracts with covariant rows: an implementation declaring fewer effects than `E`.
- Existentials: the proposal is to keep `E` visible in the packed type.
- How much of `Task` and the scheduler depends on compiler intrinsics at the start.
- Whether `Async` is a nominal effect, a super-effect, or only a documentation shorthand.
- The viability of `jdk.internal.vm.Continuation` on Java 25, and the portable alternative.
- The exact ABI of row-polymorphic functions if the state-machine backend ships.
