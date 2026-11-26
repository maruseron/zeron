# Continuation Tooling

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0172](../../zeps/ZEP-0172.md), [ZEP-0173](../../zeps/ZEP-0173.md) |

## Mission

Make continuation-based execution portable and debuggable. This workstream covers the
compiler-managed continuation backend and the source-level metadata needed for readable stack traces,
IDE stepping, and diagnostics. It follows the continuation semantics shipped in `0.9.0`; it does not
reopen the handler or resumption model.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0172](../../zeps/ZEP-0172.md) | Portable compiler-managed continuation backend | — | Draft | — |
| 2 | [ZEP-0173](../../zeps/ZEP-0173.md) | Source-level debug metadata and IDE stepping | — | Draft | — |

## Sequencing

The parts can be designed independently. Part 1 owns the state-machine transform, descriptor and
API-index changes, cleanup states, stack-trace costs, and the performance gate. Part 2 owns
`SourceFile`, line and local metadata, generated-name readability or a source map, and support-frame
presentation. Both are `Draft` and are not assigned to a release.

## Out of scope

- Multi-shot continuations and backtracking, which remain deferred in
  [ZEP-0121](../../zeps/ZEP-0121.md).
- Changing the shipped Loom backend or the public handler contract.
