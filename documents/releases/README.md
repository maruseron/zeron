# Release Plans

Release plans are temporary, release-scoped views over the durable
[workstream](../workstreams/README.md) model. They record what one release is considering, what it
has selected, and what release-local work must finish. They do not replace the
[ZEP tracker](../zeps/ZEP.md), workstream pages, or the release ledger.

See [ZEP-0179](../zeps/ZEP-0179.md) for the process decision.

## Current plan

The active plan is [`current.md`](./current.md). When a release is published, archive that plan as
`<version>.md` and reset `current.md` to an empty planning state.

## Three records

| Record | Owns |
| --- | --- |
| [ZEP tracker](../zeps/ZEP.md) | Proposal status and published release history |
| [Workstream pages](../workstreams/README.md) | Durable concern, part scope, part status, and dependencies |
| Release plan | Candidate and selected release scope, release-local work, and the publication checklist |

Workstream part status is authoritative on the workstream page. A release plan records release
dispositions only; it must not introduce a second copy of `Draft`/`Accepted`/`Ready`/`Shipped` or
change a ZEP status.

## Plan statuses

- **Planning** — candidates are being collected and reviewed.
- **Scope frozen** — selected scope is fixed; every selected part is `Ready` with dependencies
  satisfied.
- **Stabilization** — the release candidate is being built and validated.
- **Published** — the release is published and the plan has been archived.
- **Abandoned** — the plan will not produce a release; record the reason.

## Release dispositions

- **Candidate** — nominated for this release but not committed. A candidate may still be `Draft` or
  `Accepted`.
- **Selected** — in the frozen scope. Only a `Ready` part with satisfied dependencies can be
  selected.
- **Dropped** — removed from this plan with a date and reason. The underlying part is unchanged.

A candidate is not a `Candidate` or `Planned` ZEP tracker state. Its tracker row remains
`Unscheduled` until the release publishes and it becomes `Shipped`.

## Required sections

`current.md` and each archived plan use these sections:

1. **Metadata** — target version or `unassigned`, status, theme, release lead, last reviewed date.
2. **Candidates** — part, ZEP, workstream, next gate, dependencies, owner, and disposition.
3. **Selected scope** — empty while planning; only `Ready` parts at scope freeze.
4. **Dependencies and risks** — cross-workstream or release-specific blockers.
5. **Release engineering** — release notes, version bump, API-index checks, packaging, smoke tests,
   migration, and documentation tasks.
6. **Dropped or deferred** — explicit removals and reasons.
7. **Publishing checklist** — the final publication gates.

Release-local tasks use plan-local names. Do not invent a workstream part for release notes,
packaging, or other one-release work.

## Workflow

1. **Open the plan.** Create `current.md`, set the target version if the release is deliberately
   opened, and begin collecting candidates.
2. **Nominate candidates.** Link each candidate to its workstream part and ZEP. Record the next gate
   and dependencies.
3. **Freeze scope.** Select only `Ready` parts. Move everything else to candidates or dropped with a
   reason.
4. **Stabilize.** Build and validate the release candidate. Workstream owners update their own part
   statuses; the release lead updates release dispositions and engineering tasks.
5. **Publish.** Mark included parts `Shipped`, add the release-ledger row, mark the corresponding ZEP
   rows `Shipped`, archive `current.md` as `<version>.md`, and reset `current.md` to planning.

## Rules

- Exactly one `current.md` is active.
- A part is selected only when it is `Ready` and its dependencies are satisfied.
- No workstream page or ZEP tracker row gains a future release version.
- The plan owns release membership; the workstream page owns part status.
- A candidate that remains unselected across review checkpoints must be re-nominated or dropped.
- A published plan becomes historical evidence; do not keep editing an archived plan.

## Validation

Before freezing scope:

- every candidate points to an existing workstream part and ZEP;
- every selected part is `Ready` and has no unresolved dependency;
- no future version is assigned in the workstream part tables or ZEP tracker;
- release-local work has an owner or an explicit `—`;
- the publishing checklist is complete enough to run `scripts/release.ps1`;
- after publication, the archived plan, release ledger, workstream statuses, and tracker rows agree.
