# Java Interop

| Field | Value |
| --- | --- |
| Status | Proposed |
| ZEPs | [ZEP-0149](../../zeps/ZEP-0149.md) |

## Mission

Close the remaining Java interoperability gaps without weakening the host-access trust boundary,
the type and array mappings, generic handling, or checked-exception integration that already shipped.
The workstream keeps interop work explicit: each capability needs a concrete use case, descriptor
checks, diagnostics, and focused tests before it is implemented.

## Parts

| Part | ZEP | Scope | Depends on | Status | Shipped |
| --- | --- | --- | --- | --- | --- |
| 1 | [ZEP-0149](../../zeps/ZEP-0149.md) | Remaining interoperability inventory: inherited/interface members, SAM limits, external contracts, external enums, JPMS, binding extensibility, and conformance | — | Draft | — |

## Expected split

ZEP-0149 is an inventory, not a single shippable feature. It records several independent areas with
different risks and dependencies. When one area has a concrete use case and an accepted design, add a
new numbered part for that slice; do not renumber part 1.

Part 1 remains the umbrella inventory while areas are promoted. It narrows as slices move to their
own parts and should not become `Ready` as a whole. New slices receive parts 2, 3, and so on in the
order they are selected. This keeps the workstream useful now without pretending that one release
will deliver every interop gap.

## Shipped foundation

The following related ZEPs are already implemented and are not open parts of this workstream:

- [ZEP-0047](../../zeps/ZEP-0047.md) — Java type and array mappings.
- [ZEP-0048](../../zeps/ZEP-0048.md) — Java generics.
- [ZEP-0049](../../zeps/ZEP-0049.md) — Java exceptions and typed effects.
- [ZEP-0050](../../zeps/ZEP-0050.md) — Java host-access trust boundary.
- [ZEP-0061](../../zeps/ZEP-0061.md) — Java and external interop editor support.
- [ZEP-0148](../../zeps/ZEP-0148.md) — Java SAM conversion.

Their decisions remain authoritative. This workstream does not reopen them; it covers the remaining
areas that ZEP-0149 inventories.

## Current focus

Select the first concrete interop slice and split it from ZEP-0149 into its own numbered part. No
part is assigned to a release.
