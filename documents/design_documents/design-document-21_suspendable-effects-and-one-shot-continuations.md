# Zeron: Suspendable Effects and One-Shot Continuations

*Design document. Summarizes the discussion on async/await, generators and resumability in Zeron. Syntax marked as a proposal does not exist in the language yet. Revised 2026-11-11 after review; the corrections are recorded in §0.*

## 0. Status and relationship to proposals

This is a design exploration, not an approved design. Its decisions are split into scoped proposals:

- [ZEP-0118](../zeps/ZEP-0118.md) — response-typed effects and tail-resumptive handlers;
- [ZEP-0119](../zeps/ZEP-0119.md) — one-shot continuations, deep handlers, and cancellation;
- [ZEP-0120](../zeps/ZEP-0120.md) — the async effect and `Future`/task surface.

[ZEP-0007](../zeps/ZEP-0007.md) is superseded and remains the historical mechanism comparison, not
an active design home. Multi-shot continuations, the only scope not covered by the split, are
`Deferred` in [ZEP-0121](../zeps/ZEP-0121.md). [ZEP-0112](../zeps/ZEP-0112.md) remains the
structured-concurrency primitive on virtual threads, including blocking suspension. Where this
document and a ZEP disagree, the ZEP is authoritative.

Corrections recorded in this revision:

- Loom continuations are not implicit delimited control and are not a shipping foundation (§9.2).
- The handler ABI is not a blocker for reified generics (§9.3).
- A `handle` that cancels continuations can gain `DisposeFailure` in its result row (§8.3, §10).
- A blocking `await` is tail-resumptive and needs no continuation capture (§6.1, §9.2).
- `Async` is not assumed to be a nominal effect; it is shorthand for a row of async carriers (§4,
  §10).
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
- Its methods must have an empty row. If a carrier's method could raise effects, it would stop being transparent: they would fire inside a `match` or a `toString`.

Because the carrier is data, it can be copied, stored or escape its handler without risk.

### 2.2 Rows

A function's row is a **set of nominal carrier types**:

```zeron
fn f(): R raises E                 // concrete E or row variable
fn g<E: effect...>(): R raises E   // row-polymorphic
contract C<E: effect> { ... }      // contracts parameterized by a row
```

Row subtraction operates at the type level. A contract without a row parameter means an empty row by default, which gives `pure` for free and leaves `C<E>` as an opt-in.

### 2.3 Handlers and `handle`

A named handler is a declaration with a set of `case`s. Its type is `T ~> { E1, E2, E3 }`, where `T` is the type of the handled expression and `{E...}` are the effects it consumes.

```zeron
handle (doSomeWork())     // doSomeWork(): T raises E1 + E2 + F
    with myHandler;       // myHandler: T ~> { E1, E2 }
                          // whole expression: T raises F + G
```

Typing rule:

```
e : T raises (E1 + E2 + F)
h : T ~> { E1, E2 }       // cases return T and may raise G
-----------------------------------------------
handle (e) with h : T raises (F + G)
```

Fixed properties:

- **T to T.** The handler does not change the type of the success value. There is no return clause. Abortive `case`s replace the whole expression with a `T`.
- **Unhandled effects propagate.** Any carrier the handler does not consume stays in the row (`F`).
- **Cases are outside the handler's scope.** Whatever a `case` raises escapes, as in a `catch`. Its row is added to the result (`G`).
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
fn doAsync<T>(callback: () -> T): T raises Async = ...
```

Here the color becomes a **row element**: it is inferred, abstracted over and subtracted by a handler. But `Async.wrap()` as a handler that turns `T` into `Future<T>` breaks the rule that handlers are T to T. The type transformation lives in an ordinary function.

## 4. Async as an effect in direct style

Two different things must be separated:

1. **An async carrier as an effect:** "this function may suspend". It is direct style, with no
   wrapper. `Async` is shorthand; the concrete carrier is `Await`.
2. **`Future<T>` as a value:** "this computation is in flight and I can wait for it later". It is only needed for concurrency, not for sequencing.

```zeron
public effect Await<T, E: effect...> responds T {   // ZEP-0118; ZEP-0120 owns exact typing
    public property future: Future<T, E>;
}

fn await<T, E: effect...>(f: Future<T, E>): T raises Await<T, E> + E
    // the "reflect": raising the carrier

fn spawn<T, E: effect...>(scope: Scope, f: () -> T raises E): Future<T, E>
    // the "reify": start a task in a ZEP-0112 scope

fn main() raises Await<Int, {}> {                    // Async is shorthand; see ZEP-0120
    using (scope = Scope.open()) {
        let f = spawn(scope, () -> doAsync(op));
        let i = await(f);
        println(i);
    }
}
```

The `E` parameter is elided in the rest of this document for readability. ZEP-0120 owns the exact
typing; an effect declaration with a row parameter is still an open question there.

- Sequential code needs neither `Future` nor `do`.
- `spawn` wraps the success value inside the handled expression, just like `toResult`:

```zeron
fn toResult<A, E: effect>(f: () -> A raises E): Result<A, E> =
    handle (Ok(f())) with catchAll<Result<A, E>>
```

- `main` declares the async carriers it uses, and the runtime installs a blocking handler.
  `Async` is shorthand for "the row contains async carriers", not a nominal effect assumed by this
  document. ZEP-0120 deliberately does not require a nominal `Async` effect.

### 4.1 Is it the same as async/await?

With a responding `Await` carrier and tail-resumptive handlers, the source has the ergonomics of
`async/await`. In the first backend the handler blocks a virtual thread; a later backend may capture
a continuation instead. The immediate gain is not convenience but **polymorphism and uniformity**,
and it is static:

- `map<E>(f: A -> B raises E): ... raises E` works for pure, failing and asynchronous functions.
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
public effect Await<T> responds T {
    public property future: Future<T>;    // responds T
}
```

The syntax is illustrative and is owned by [ZEP-0118](../zeps/ZEP-0118.md). The compiler synthesizes
a marker contract `Responds<R>` for handler patterns such as `case e: Responds<Unit>`; it is not a
contract that a class or a foreign type can implement. The alternative `effect Ask is Responds<Int>`
surface is recorded as rejected in ZEP-0118 because it makes a library contract part of the
declaration mechanism and invites fictitious conformance.

The `raise` site becomes an expression of type `R`, taken from the declaration. A carrier without it
responds `Never`, which preserves typed early return. In a handler, a `case` over a responding
carrier receives a continuation: at level 2 it is a restricted tail-only token (§6.1), and at level 3
it has type `Cont<R, T>` ([ZEP-0119](../zeps/ZEP-0119.md)). For an abortive carrier, `raise` remains
`Never` and the surrounding code is unreachable; for a responding carrier, `raise` may return, so
subsequent statements are reachable. This is a flow-analysis change, not only a type change.

Rules for response declarations:

- An effect declares at most one response. Two declarations are a compile error.
- The response is invariant in `R`: the handler produces `R` and the `raise` site consumes it.
- `Responds<R>` has no members, so carriers stay immutable data and their methods keep an empty row.
  It is compiler-known and cannot be implemented explicitly by a class or a Java-library type.
- Handlers by contract work for patterns derived from the declaration: `case e: Responds<Unit>`
  handles every carrier that answers `Unit`, with `k: Cont<Unit, T>`.
- Only carriers declared in Zeron can be resumable. A response cannot be attached afterwards to a
  carrier from a Java library.
- A `raise` on a row variable `E` has no known response type, so raising is limited to concrete
  carriers unless a bound on a single effect such as `E: effect + Responds<R>` is allowed. A row-level
  version such as `E: effect... + Responds<R>` would wrongly claim that every carrier in the row has
  the same response; it needs a different form. This is an open question, not a solved part of the
  design. ZEP-0118 records that generic code can still call and forward effectful functions without
  performing a responding `raise` itself.

### 6.1 Three levels of handler

1. **Abortive.** Already exist. On the JVM, an abortive `raise` is a stackless exception and `handle` is a `catch` with a reified type test.
2. **Tail-resumptive.** The `case` ends with the resumption in tail position: exactly one `resume`,
   no later work, and no stored continuation. It covers `Yield`, `Ask`, `Log`, blocking `Await`, and
   most practical uses. It compiles to a direct call or a loop, **without capturing continuations**.
   The compiler detects it with a conservative syntactic criterion; helper calls that eventually
   resume are not detected and fall to level 3.
3. **General resumable.** The `case` stores the continuation and resumes it later (non-blocking
   `Await`, stateful generators, schedulers). Continuation capture is needed here.

The handler type `T ~> {...}` does not change between levels. At level 3, however, the `handle`
expression's row can gain `DisposeFailure` when it cancels pending continuations (see §8.3), so
`handle` is not purely effect-subtracting in that case.

### 6.2 `for ... collect` as `Yield`

`Yield` is the simplest resumable case. The duality is:

| View | Who is in control | In Zeron |
| --- | --- | --- |
| Pull (external iterator) | the consumer calls `next()` | repeated `Option<T>` |
| Push (internal iteration) | the producer emits | `raise Yield(v)` + collector |

Turning pull into push is a `while` loop that calls `next()`. Turning push into pull is a **generator**
and requires suspending between elements, that is, general resumability. That is why pull generators
are the first feature that justifies continuation capture; a blocking `Await` does not, because its
handler resumes immediately.

Collectors have state and a return clause, so they transform the type, whereas handlers are T to T. They stay separate for now, with aligned syntax (`with` versus `collect into`) so they can be unified later. `break` is an abortive carrier handled by the `for` itself, not by the collector.

## 7. Designing `resume` and continuations

### 7.1 What `resume` is

`resume` does not exist in Zeron yet. Three forms were evaluated:

1. **Special form** valid only inside a `case`. Typing and linearity are simple, but the continuation cannot escape, so it does not work for a scheduler that resumes from a callback.
2. **Intrinsic function.** In practice equivalent to a keyword with call syntax, unless Zeron has a general implicit-context mechanism.
3. **Method on a `Cont<R, T>` value.** The most general: the continuation is storable and capturable.

Since Zeron **has no linear or affine types**, one-shot cannot be guaranteed statically. The design
direction explored here is option 3 with a **dynamic** guarantee, comparable to Kotlin (calling
`Continuation.resume` twice throws `IllegalStateException`). [ZEP-0119](../zeps/ZEP-0119.md) owns that
decision and is still a Draft.

### 7.2 Proposed syntax

```zeron
// Unit ~> { Await<_> }
public handler Scheduler {
    case Await(f), k -> {           // k: Cont<T, Unit>
        f.onComplete { v -> k.resume(v) };
    }
}
```

The type of `k` and of the argument to `resume` comes from the response declaration on the handled
carrier (section 6).

Rules:

- A `case` that does not use `k` aborts, as today. Existing code does not change.
- `k.resume(v)` requires `v` to have the carrier's response type. Its return type is the handled
  expression's `T`.
- The continuation has a dynamic state: `suspended → running → done | failed`, with `cancelled` as an
  alternative terminal state. `resume` is legal only from `suspended`; a second `resume`, a `resume`
  after `cancel`, and a `resume` after the `handle` exits are **programming errors** (panics), not
  carriers: if they were effects, they would contaminate the whole row. `cancel` is idempotent.
- The compiler can warn with a lint when it sees two `resume` calls on the same path, without
  claiming to be complete. A continuation stored in a field or captured by a lambda cannot be checked
  locally.
- **Deep handler semantics for the continuation:** what is resumed stays inside the `handle`, so its
  subsequent `raise` calls are caught by the same handler. The `case` body itself remains outside the
  handler: an effect raised by the case escapes unless an outer handler catches it.
- A handler that stores `k` is a per-`handle` state machine; its state must survive across
  suspension. A named singleton handler that captures `k` is not stateless.
- `Cont` is confined to the `handle`'s thread. Resuming from another thread is a runtime error in the
  first version; a scheduler must dispatch the resumption back to the handle's thread. Thread-local
  handler state does not travel with a captured stack automatically.
- Carrier methods keep an empty row.

### 7.3 A generator in this model

```zeron
public effect Yield<T> responds Unit {
    public property value: T;
}

fn numbers(): Unit raises Yield<Int> {
    for (i in 0..3) {
        raise Yield.new(i);
    }
}
```

A tail-resumptive handler for `Yield` just sees each value and resumes in tail position. A handler
that stores `k` between elements produces an external iterator (push to pull).

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

Because `dispose(): Unit raises DisposeFailure` cannot contain a general resumable effect (level 3), no `using` suspends during unwinding. A tail-resumptive level-2 effect in `dispose` is synchronous and does not violate this. `k.cancel()` unwinds and returns with no scheduler in between. Blocking a virtual thread directly in `dispose` is a separate library choice, not a handler suspension, and should still be avoided. This restriction on the `Disposable` contract matters.

### 8.3 Where suppressions go

| Situation | What happens to `dispose` failures |
| --- | --- |
| Abortive `raise` on the live stack | The `using` blocks between the `raise` and the `handle` unwind **before** the `case` runs. Their suppressions are available in `with suppressed as s`. |
| Pending continuations when the `handle` ends | They are cancelled **after** the `case` has produced its `T`. The `case` can no longer see them: they leave through the `handle`'s row as `DisposeFailure` (the first one, with the rest suppressed). |
| Case has suspended the computation | The computation stays suspended with its resources open. Nothing has been unwound. |
| `case` aborts and a `dispose` also fails | The cause wins and cleanup failures accumulate as suppressed. |

The cancellation signal accumulates suppressions, and whoever called `cancel()` (the `handle`) collects them on return.

A `handle` that captures continuations can therefore add `DisposeFailure` to its result row; it is
not purely effect-subtracting. The rule proposed by [ZEP-0119](../zeps/ZEP-0119.md) is: a `handle`
whose cases statically capture a continuation includes `DisposeFailure` in its result row. A
tail-resumptive or abortive handler does not. This is a typing cost; the alternative is an explicit
cancellation-report hook or wrapping the `handle` in a `using` scope.

### 8.4 `using` as a statement

- It has no value, so there is no result a failed `dispose` could replace. If the body does `return x`, `x` is evaluated, the `dispose` calls run, and then it returns. If a `dispose` raises `DisposeFailure`, that failure replaces the `return`, as in try-with-resources.
- `break`, `continue` and `return` go through the `dispose` calls. This should be tested with the `for` `break`s.
- A `using` adds `DisposeFailure` to its function's row, unless a `handle` inside handles it.
- With several resources (`using (a = open(), b = open())`), order is LIFO and suppressions accumulate.
- **Lint:** a `using` kept open across a `raise` whose handler suspends (level 3) keeps the resource
  alive during suspension. A tail-resumptive level-2 handler resumes immediately and does not. The
  compiler can detect the level-3 case when the body's row contains a carrier whose handler stores
  `k`; this is a lint, not a prohibition.

## 9. Implementation on the JVM (Java 25)

### 9.1 Abortive effects

An abortive `raise` is a stackless exception (`writableStackTrace=false`) wrapping the carrier.
`handle` is a `catch` with a reified type test, and `using` is a `try/finally` with suppression
accumulation. JVM unwinding gives cleanup for free. A responding `raise` at level 2 is a direct
handler call instead; it does not throw.

### 9.2 Resumable effects

There are two strategies:

**A. Loom delimited continuations.** `jdk.internal.vm.Continuation` is a one-shot continuation, but
it is **not implicit delimited control**. Suspending requires an explicit `yield(scope)` with the
right `ContinuationScope`; the raise site must still resolve which handler scope owns the carrier, so
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

Recommendation: **use A only as a prototype to falsify the semantics**, not as the production
backend. Use B, or a public continuation API if one exists, for any shipping decision. The source
language and the `Cont` contract must be identical under both, so the backend remains an
implementation choice. The prototype must answer: can the raise site resolve the handler scope
without an ABI change; can handler state survive a callback-thread handoff; does cancellation run
`using` and Java `finally` cleanup; and what happens across monitors, native frames, and unaware Java
callbacks.

A blocking `await` does not need any of this machinery. Its handler calls `Future.join` and resumes in
tail position, which is level 2 in §6.1. Only a non-blocking scheduler or a pull generator needs the
resumable backend.

### 9.3 ABI of row-polymorphic functions

With Loom continuations the descriptor problem shrinks, because suspension does not add a
continuation parameter to every function. With strategy B you would have to choose between
specializing by row, a dynamic `bind`, or a hybrid.
This decision does **not** have to be fixed before closing reified generics: generics, existentials,
and the current handler ABI can ship without a continuation parameter. The handler and closure
representations should reserve an extension point, but they must not depend on this feature. If B
ships later, every function that may suspend changes its descriptor and the API index needs a schema
change; if A ships, the descriptor does not change but the runtime depends on the internal API. The
backend decision is deferred until the prototype reports.

### 9.4 Boundaries where the row must forbid suspension

- FFI and callbacks from native code: a native frame is a hard barrier.
- An unaware Java callback: the frame may be technically capturable, but it may hold locks or
  thread-local state and does not expect to be suspended.
- `dispose`, because cancellation must be synchronous.

A `NoSuspend` capability in the signature, or a rule requiring a row without general resumable
operations at these boundaries, is advisable. Note that this is not merely an advisory lint: a
generic row variable `E` may be instantiated with a level-3 carrier, so the boundary needs either a
`NoSuspend` constraint or a monomorphic row free of level-3 carriers. Tail-resumptive level-2 effects
are synchronous and remain allowed. That is a new type-system feature and is left to
[ZEP-0119](../zeps/ZEP-0119.md).

## 10. Summary of language changes

**New:**

1. A `responds R` clause on effect declarations, with a compiler-synthesized `Responds<R>` marker
   for handler patterns (`effect Await<T> responds T {}`). Carriers without it respond `Never`.
2. `raise` as an expression of type `R` (or wrapper functions such as `await(f)`).
3. A continuation received by the `case`: a restricted tail-only token at level 2, and `Cont<R, T>`
   with `resume` and `cancel` at level 3, with a one-shot check at runtime.
4. Deep-handler semantics for what is resumed.
5. Automatic cancellation of pending continuations when the `handle` ends.
6. An internal cancellation signal, outside the row.

**Unchanged:**

- Function signatures (`f(): R raises E`). Effect declarations gain a response, but calls do not gain
  a wrapper or a `suspend` modifier.
- `T ~> {...}`. The handler type does not change between levels; at level 3 the `handle` expression's
  row can gain `DisposeFailure` when it cancels continuations.
- Contracts and existentials (they already carry `E`).
- `using`, `Disposable` and `with suppressed` for abortive code. Cancellation adds observation of
  `dispose` and can add `DisposeFailure` to a resumable `handle`'s row, so this is not literally
  unchanged at level 3.
- There is no method-level keyword for suspending. The row is already the marker. The IDE highlights suspension points.

This summary is a design sketch. [ZEP-0118](../zeps/ZEP-0118.md), [ZEP-0119](../zeps/ZEP-0119.md),
and [ZEP-0120](../zeps/ZEP-0120.md) own the decisions; where this document and a ZEP disagree, the
ZEP is authoritative.

**Note on `yield`:** it is already used inside `do`, so it should not be reused for suspension. For generators, `Yield<T>` is a responding carrier.

## 11. Suggested implementation order

1. Response-typed carriers (`responds R`, `Never` by default) and `raise` as an expression
   ([ZEP-0118](../zeps/ZEP-0118.md)).
2. Rows with extension and subtraction, and handlers with type `T ~> {...}`.
3. Tail-resumptive handlers (`Yield`, `Ask`, `Log`) without continuation capture. Do not rewrite
   `for ... collect`; introduce `Yield` alongside it and revisit unification later.
4. The async surface as a blocking handler on virtual threads
   ([ZEP-0120](../zeps/ZEP-0120.md)); this needs no continuation capture.
5. `Cont<R, T>` with dynamic one-shot `resume` and `cancel`, first as a runtime state machine and a
   Loom/compiler-transform spike ([ZEP-0119](../zeps/ZEP-0119.md)).
6. Non-blocking `Await` and schedulers as a library, plus pull generators.
7. Later: general `reify`/`reflect` and multi-shot `do`.

## 12. Open decisions

- The final syntax of `responds`, `k` in the `case`, and `raise` as an expression. ZEP-0118 owns the
  response syntax; this document uses `responds` and keeps `is Responds<R>` only as a rejected
  alternative.
- How a response bound on a single effect or on a row variable is written, and whether it requires a
  new kind or constraint form.
- Whether handler state is per-`handle` and whether `Cont` is thread-confined or supports explicit
  handoff. ZEP-0119 proposes confinement first.
- Whether a `handle` that captures continuations must add `DisposeFailure` to its result row, or
  whether cleanup failures go to an explicit hook.
- Cross-thread resumption and handler-state handoff if confinement is ever relaxed.
- Whether the cancellation token should be a private `Throwable` and how a swallowed token is
  detected.
- Whether to unify handlers and collectors (handlers with state and a return clause).
- How `break` and errors in the `for` body relate to the collector.
- Contracts with covariant rows: an implementation declaring fewer effects than `E`.
- Existentials: the proposal is to keep `E` visible in the packed type.
- How much of `Future` and the scheduler depends on compiler intrinsics at the start.
- Whether `Async` is a nominal effect, a super-effect, or only a documentation shorthand.
- The viability of `jdk.internal.vm.Continuation` on Java 25, and the portable alternative.
- The exact ABI of row-polymorphic functions if the state-machine backend ships.
