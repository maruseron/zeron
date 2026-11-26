# Zeron Workstreams

Workstreams are the project's planning layer. They group related ZEPs by durable concern instead of
by a future version. A release is an event that may include parts from several workstreams; it is
not a container for unfinished scope. See [ZEP-0176](../zeps/ZEP-0176.md) for the process decision.

## Model

- A **workstream** has one stable slug, one folder, and one `README.md`.
- A **part** is an independently scoped, independently shippable slice with a stable workstream-local
  number.
- A **ZEP** records a design decision. ZEP numbers stay global and permanent in
  [`documents/zeps/`](../zeps/).
- A **release plan** is the temporary, release-scoped view of candidates and selected work. The
  active plan is [`documents/releases/current.md`](../releases/current.md); see
  [ZEP-0179](../zeps/ZEP-0179.md).
- A **release** records published artifacts. Historical releases are summarized in the release
  ledger below and in the [ZEP tracker](../zeps/ZEP.md).

Each workstream page owns its mission, scope, part table, dependencies, statuses, and ZEP links. This
central page owns only the cross-workstream rollup and the release ledger. There are no per-workstream
tracker files and no future version assignments.

Part statuses are `Draft`, `Accepted`, `Ready`, `Shipped`, and `Deferred`. Workstream statuses are
`Proposed`, `Active`, `Paused`, and `Complete`. A workstream can span releases, and a release can ship
only some of its parts.

## Workstream index

| Workstream | Status | Parts | Current focus |
| --- | --- | --- | --- |
| [Effect control and concurrency](effect-control/README.md) | Complete | 10 shipped | Released in `0.9.0`; no open parts |
| [Library breadth](library-breadth/README.md) | Complete | 8 shipped | Released in `0.9.0`; no open parts |
| [Test infrastructure](test-infrastructure/README.md) | Active | 1 shipped, 1 draft | Structured test reports and JSON diagnostics ([ZEP-0175](../zeps/ZEP-0175.md)) |
| [Model metadata and mapping](model-metadata/README.md) | Active | 5 open | Accept annotation consumption and mapping targets ([ZEP-0091](../zeps/ZEP-0091.md), [ZEP-0097](../zeps/ZEP-0097.md)) |
| [Scoped resources](scoped-resources/README.md) | Active | 4 open | Scoped projection and receiver design ([ZEP-0160](../zeps/ZEP-0160.md), [ZEP-0161](../zeps/ZEP-0161.md)) |
| [Continuation tooling](continuation-tooling/README.md) | Proposed | 2 open | Portable continuation backend and debug metadata ([ZEP-0172](../zeps/ZEP-0172.md), [ZEP-0173](../zeps/ZEP-0173.md)) |
| [Java interop](java-interop/README.md) | Proposed | 1 draft | Remaining interop inventory; expected to split ([ZEP-0149](../zeps/ZEP-0149.md)) |
| [Bytecode quality and optimization](bytecode-quality/README.md) | Proposed | 2 open | Quality baseline and staged emission work ([ZEP-0095](../zeps/ZEP-0095.md), [ZEP-0096](../zeps/ZEP-0096.md)) |
| [Static newtype verification](static-verification/README.md) | Proposed | 7 open | Predicate and fact IR foundation ([ZEP-0138](../zeps/ZEP-0138.md)) |
| [Language ergonomics](language-ergonomics/README.md) | Proposed | 4 open | Independent smoothing proposals ([ZEP-0085](../zeps/ZEP-0085.md), [ZEP-0163](../zeps/ZEP-0163.md), [ZEP-0167](../zeps/ZEP-0167.md), [ZEP-0168](../zeps/ZEP-0168.md)) |
| [Numeric surface](numeric-surface/README.md) | Proposed | 5 open | Literal, unit, and operator composition ([ZEP-0044](../zeps/ZEP-0044.md), [ZEP-0045](../zeps/ZEP-0045.md), [ZEP-0046](../zeps/ZEP-0046.md), [ZEP-0074](../zeps/ZEP-0074.md), [ZEP-0075](../zeps/ZEP-0075.md)) |

ZEPs that are not listed here remain ordinary unscheduled proposals in the [ZEP tracker](../zeps/ZEP.md).
They can join a workstream when a part is recorded for them.

## Release plans

The active [release plan](../releases/current.md) owns candidate and selected release scope, the
release theme, cross-workstream dependencies, and release-local tasks. It does not change workstream
part status. A candidate is not selected until its part is `Ready` and its dependencies are
satisfied. See [ZEP-0179](../zeps/ZEP-0179.md) for the process.

## Release ledger

The ledger records what shipped. It does not assign future versions. Releases before the workstream
model are recorded as legacy release sets and link to their detailed ZEP history. Published release
plans are archived under [`documents/releases/`](../releases/) and are linked from the corresponding
row when available.

| Release | Date | Workstream parts | Notes |
| --- | --- | --- | --- |
| `0.9.0` | 2026-11-25 | Effect control parts 1–10; library breadth parts 1–8; test infrastructure part 1 | 19 ZEPs; [release record](../zeps/ZEP.md#090--effect-control-and-response-typing) |
| `0.8.0` | 2026-11-20 | Legacy release set | 25 ZEPs; [release record](../zeps/ZEP.md#080--normalization-and-nominal-implementation) |
| `0.7.0` | 2026-11-14 | Legacy release set | 15 ZEPs; [release record](../zeps/ZEP.md#070--type-identity-reification-and-type-bounds) |
| `0.6.0` | — | Legacy release set | 25 ZEPs; [tracker](../zeps/ZEP.md) |

## Maintaining a workstream

When adding or changing work:

1. Add the ZEP to the part table, or create a new numbered part if the slice is independently
   shippable.
2. Update the part status and dependency notes in the workstream page.
3. Update the central index rollup and current focus when they change.
4. Keep the ZEP tracker's proposal status current; do not assign a future release version there.
5. Nominate release candidates through [`documents/releases/current.md`](../releases/current.md),
   not by adding a release column to the workstream part table.
6. At release time, mark shipped parts and ZEP rows with the published version and add the ledger
   entry.

Use `Ready` only when implementation is complete and dependencies are satisfied. Use `Shipped` only
after the release is published.
