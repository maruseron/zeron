# Effect Control and Concurrency

| Field | Value |
| --- | --- |
| Status | Complete |
| Released | `0.9.0` |
| ZEPs | [ZEP-0112](../../zeps/ZEP-0112.md), [ZEP-0118](../../zeps/ZEP-0118.md), [ZEP-0119](../../zeps/ZEP-0119.md), [ZEP-0123](../../zeps/ZEP-0123.md), [ZEP-0147](../../zeps/ZEP-0147.md), [ZEP-0164](../../zeps/ZEP-0164.md), [ZEP-0165](../../zeps/ZEP-0165.md), [ZEP-0166](../../zeps/ZEP-0166.md), [ZEP-0171](../../zeps/ZEP-0171.md), [ZEP-0174](../../zeps/ZEP-0174.md) |

## Mission

Make effects expressive and predictable across response values, handlers, concurrency, and
continuations, while keeping the runtime and source surface coherent.

This workstream shipped the response-typed effect model, its source vocabulary, structured
concurrency, the unified async handle, continuations, fail-fast scopes, and the do-notation language
rider. It is complete; later work on portable continuations and debug metadata is a separate
workstream.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0118](../../zeps/ZEP-0118.md) | Response-typed effects and tail-resumptive handlers | — | Shipped | `0.9.0` |
| 2 | [ZEP-0147](../../zeps/ZEP-0147.md) | Replace `raise`/`raises` with `perform`/`performs` | 1 | Shipped | `0.9.0` |
| 3 | [ZEP-0123](../../zeps/ZEP-0123.md) | Admissibility clauses and leading reification | 1 | Shipped | `0.9.0` |
| 4 | [ZEP-0112](../../zeps/ZEP-0112.md) | Structured concurrency on virtual threads | — | Shipped | `0.9.0` |
| 5 | [ZEP-0166](../../zeps/ZEP-0166.md) | Open generic effect applications | 1 | Shipped | `0.9.0` |
| 6 | [ZEP-0165](../../zeps/ZEP-0165.md) | Unify `Task` and `Future` into one async handle | 4 | Shipped | `0.9.0` |
| 7 | [ZEP-0164](../../zeps/ZEP-0164.md) | Erased `Await` carrier and blocking entry handler | 6 | Shipped | `0.9.0` |
| 8 | [ZEP-0119](../../zeps/ZEP-0119.md) | One-shot continuations, deep handlers, and cancellation | 1, 4 | Shipped | `0.9.0` |
| 9 | [ZEP-0171](../../zeps/ZEP-0171.md) | Fail-fast concurrent scopes and `concurrent` | 4, 7 | Shipped | `0.9.0` |
| 10 | [ZEP-0174](../../zeps/ZEP-0174.md) | `if` guards in do-notation through `Filterable` | — | Shipped | `0.9.0` |

## Out of scope

- Portable compiler-managed continuations and source-level debug metadata; those are parts of the
  [continuation tooling](../continuation-tooling/README.md) workstream.
- Multi-shot continuations and backtracking, recorded as deferred in
  [ZEP-0121](../../zeps/ZEP-0121.md).
