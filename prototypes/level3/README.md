# ZEP-0119 level-3 backend prototype

This directory contains the standalone backend-selection harness for
[ZEP-0119](../../documents/zeps/ZEP-0119.md). It is not a Zeron compiler feature and is not
compiled by the normal Maven build.

The harness runs the same pull-generator lifecycle under three strategies:

- **A, Loom:** `jdk.internal.vm.Continuation` with an explicit `ContinuationScope`. This requires
  `--add-exports java.base/jdk.internal.vm=ALL-UNNAMED`.
- **B, state machine proxy:** a hand-written resume state machine. This is the best-case shape a
  compiler-managed transform can emit; it is not the transform itself.
- **C, virtual thread:** the body runs on a virtual thread and uses a two-way
  `LockSupport.park/unpark` rendezvous with the owning consumer thread.

It measures generator creation, per-element pull, cancellation, retained heap for generators
suspended at their first yield, virtual-thread count, and a one-million-element tail run.

## Run

From the repository root, using Git Bash on Windows or any POSIX shell:

```bash
./scripts/run-level3-prototypes.sh
```

The script compiles with `-source 25 -target 25` because `--release 25` cannot be combined with
`--add-exports` for a system module. The runtime target is Java 25; the prototype runs on the
installed JDK, which was JDK 26.0.1 for the recorded results.

Useful options:

```text
--elements N      values per measured pull run (default 1000000)
--runs N          measured pull runs (default 5)
--warmup N        warmup pull runs (default 3)
--cancel-runs N   cancellation samples (default 10000)
--suspended N     generators retained for the heap sample (default 5000)
--deep N          values in the long tail-resuming run (default 1000000)
```

## Recorded result

Six full runs on Windows 11 with JDK 26.0.1, `-Xms512m -Xmx2g`:

| Backend | Create ns | Pull ns | Cancel ns | Heap B/suspended | Virtual threads |
| --- | ---: | ---: | ---: | ---: | ---: |
| B state-machine proxy | 720–1,580 | 3.1–3.6 | 22.6–29.3 | 36 | 0 |
| A Loom continuation | 9,280–12,960 | 68.6–81.4 | 654–902 | 308 | 0 |
| C virtual thread | 30,820–37,340 | 1,514–4,198 | 7,090–8,979 | 824 | 1 |

A one-million-element tail run completed without stack overflow: B in 3–4 ms, A in 67–76 ms, and C
in 2.3–5.2 s. The harness also checks ordering, terminal `DONE`, owner confinement, and exactly one
cleanup block on cancellation. C's pull time varied more than A's or B's; use the range rather than
the fastest sample.

C is rejected for generator yields: its per-element context switch is roughly 19–59× A and
420–1,170× the B proxy. A is the fastest stack-capturing strategy but uses an internal JDK API and
requires a module export on every launch path. ZEP-0119 prefers A, subject to a provider/launcher
and JDK-support gate, with B as the portable fallback.

## Limits

This prototype models a generator only. It does not implement `Cont`, a general `handle` case,
non-tail resumption, external-event waits, stack-trace quality, monitor/native boundaries, or the
compiler transform. The B number is a lower bound on the real transform's cost, not evidence that
the transform is implemented. See ZEP-0119's "Prototype results" section for the decision gate.
