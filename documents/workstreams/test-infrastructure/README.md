# Test Infrastructure

| Field | Value |
| --- | --- |
| Status | Active |
| ZEPs | [ZEP-0169](../../zeps/ZEP-0169.md), [ZEP-0175](../../zeps/ZEP-0175.md) |

## Mission

Make the standard-library and compiler test suites reliable, diagnosable, and usable in automation.
The workstream owns test discovery and execution, compile-failure coverage, reporting formats, and
the diagnostics needed to interpret failures. It does not own the language features exercised by the
tests.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0169](../../zeps/ZEP-0169.md) | Standard-library suite split, `@CompileFailure`, and parity tracking | — | Shipped | `0.9.0` |
| 2 | [ZEP-0175](../../zeps/ZEP-0175.md) | Per-test timing, JSON test reports, and `--diagnostics json` | 1 | Draft | — |

## Current focus

Part 2 is the active design. It adds a versioned JSON report and schema, per-test timing, and
machine-readable diagnostics while preserving the existing text output. The part is `Draft`; it is
not assigned to a release. The next step is design acceptance, followed by implementation and a
`Ready` status.

## Boundaries

- Runtime test assertions and fixtures are part of the suite, but new language behavior belongs to
  the relevant feature workstream.
- CI configuration and local developer commands are in scope when they are needed to consume the
  report or select the suite.
