# ZEP Tracker Review and Near-Term Shipping Order

| Field | Value |
| ----- | ----- |
| Date | 2026-11-05 |
| Scope | Review of `documents/zeps/ZEP.md` and near-term sequencing |
| Purpose | Decide what belongs in the next release bundle and in what order to finish it |

This is a planning snapshot. It does not modify the tracker, and it does not change any proposal's
status or release state.

## Summary

- **`0.6.0` should be treated as a stabilization bundle for work that has already landed**, not as
  an opportunity to add new scope. Several accepted candidates are already implemented, and several
  implemented ZEPs are still unscheduled.
- The highest-value work is finishing the pieces that make already-implemented features usable in
  ordinary code, especially serialization and model declarations.
- New feature ZEPs should follow `0.6.0`, not compete with it.
- The runtime-support emission optimization that motivated this review is an implementation change
  and does not need a ZEP.

## Current `0.6.0` candidate set

| ZEP | Name | Proposal status | Notes |
| --- | --- | --- | --- |
| ZEP-0061 | Java and External Interop Language Support | Accepted | Candidate |
| ZEP-0065 | Standard Library JSON Parser | Accepted | Parser and writer implemented; typed integration pending |
| ZEP-0068 | Serialization Contracts for Zeron | Accepted | Core contracts implemented |
| ZEP-0069 | Transparent Data Classes | Accepted | Core implemented |
| ZEP-0071 | Numeric Hierarchy and Context-Directed Literals | Implemented | Candidate |
| ZEP-0073 | Documentation Comments for Declarations | Implemented | Candidate |

Two tracker issues affect this set:

- **ZEP-0076/0077/0078** were marked `Implemented / Unscheduled` while still carrying a `0.6.0`
  release reference. Their references were removed so unscheduled rows have no release reference,
  as ZEP-0000 requires.
- **ZEP-0084** (64-bit hashes) is a draft and is not currently a candidate, but it is a natural
  companion to the serialization and collection work.

## Recommended near-term order

### 1. ZEP-0092 - Generated serialization operations

This is the missing link that makes the already-implemented serialization contracts useful. Without
generated operations, users must hand-write encoders and decoders for every type. Finish this before
considering a broader serialization API.

### 2. ZEP-0065 completion - JSON typed integration

The parser and writer exist. Finish typed integration so JSON can be used through the serialization
contracts from ZEP-0068/0069. Treat this as completion of an accepted candidate, not new scope.

### 3. ZEP-0079 - Model declarations

The file, tracker, and decision text now agree on `Draft`. Model declarations are the foundation
for ZEP-0080, ZEP-0082, ZEP-0090, and ZEP-0093; the remaining work is `writer` tracking, the
consumer examples, and a decision to accept the proposal.

### 4. ZEP-0091 - Annotation consumption

Annotations are implemented, but consumers cannot act on them generically. Annotation consumption
is the bridge between the metadata model and tooling and serialization. It also unblocks work that
currently depends on naming conventions.

### 5. ZEP-0084 - 64-bit hashes

The proposal is already drafted. It fits naturally after the collection and serialization work,
because hashing and equality are needed by map-backed sets and generated serialization.

### 6. ZEP-0061 - Java and external interop language support

This is an accepted candidate. Finish the accepted scope before taking on the larger deferred Java
array and wrapper work recorded in ZEP-0047/0048/0053.

### 7. ZEP-0095 - Bytecode quality and evidence inlining

The classfile-quality inventory has accumulated a coherent set of optimizations around generated
support code, evidence dispatch, and interface shape. ZEP-0095 bundles them into one proposal with
measurable acceptance criteria rather than landing them piecemeal.

### 8. ZEP-0096 - Primitive specialization

Primitive specialization affects representation, ABI, and generated interfaces. ZEP-0096 records
the design, and it should follow the bytecode-quality work so the baseline is stable.

### 9. ZEP-0070 - Generic and nominal newtype representations

Newtypes are a foundational type-system feature and a prerequisite for several numeric and
serialization follow-ups. Take them after the `0.6.0` stabilization bundle.

### 10. ZEP-0013 - Effect subtyping and the top effect type

Effect subtyping is a prerequisite for handler composition and the remaining effect-system work.
Resolve it before drafting handler composition as a new ZEP.

## Release sequencing guidance

1. **`0.6.0`**: finish accepted candidates and reconcile status. Do not add new feature scope.
2. **Post-`0.6.0` stabilization**: close the gaps in already-shipped features, especially
   collection completion and derivation expansion.
3. **New feature ZEPs**: start after the stabilization bundle, beginning with the follow-up
   inventory in [zep-follow-up-inventory.md](./zep-follow-up-inventory.md).

## Tracker corrections (applied 2026-11-05)

These inconsistencies were corrected; they are recorded here for traceability:

- **ZEP-0047**: the file `Status` now reads `Implemented`, matching the tracker's
  `Implemented / Shipped 0.5.0`.
- **ZEP-0073**: the file `Status` now reads `Implemented`, matching the tracker's
  `Implemented / Candidate 0.6.0`.
- **ZEP-0079**: the file `Status` now reads `Draft`, matching the tracker and the decision text.
- **ZEP-0076/0077/0078**: the `0.6.0` reference was removed from these `Unscheduled` rows.
- The implemented-but-unscheduled set (`0031`, `0060`, `0080`, `0082`, `0083`, `0086`, `0087`,
  `0088`, `0090`, `0093`, `0094`) needs one bundle decision pass.

## Notes

- Proposal status and release state are independent. A row can be `Implemented` and still
  `Unscheduled`; that is not an error by itself.
- Do not reuse a ZEP number. The next available number is `0097`.
- Routine fixes and contained changes do not need a ZEP.

## References

- [ZEP tracker](../zeps/ZEP.md)
- [ZEP process](../zeps/ZEP-0000.md)
- [ZEP template](../zeps/ZEP-0001.md)
- [Classfile quality inventory](./classfile-quality-inventory.md)
- [ZEP follow-up inventory](./zep-follow-up-inventory.md)
