# Zeron Enhancement Proposals

This file tracks ZEPs in numerical order, including their proposal status and independent release
state. Release state is maintained here as the single source of truth. See [ZEP-0000](./ZEP-0000.md)
for the proposal process and [ZEP-0001](./ZEP-0001.md) for the proposal template.

Release states are defined in ZEP-0000. `Candidate` means a proposed release target, not a
commitment; `Planned` means approved for a target bundle. Deferred rows have no release target or
release reference. The proposal lifecycle status and release state are separate.

| Number | Name | Proposal status | Release state | Release / bundle reference |
| ------ | ---- | --------------- | ------------- | -------------------------- |
| [ZEP-0000](./ZEP-0000.md) | Zeron Enhancement Proposal Process | Accepted | Not applicable | — |
| [ZEP-0001](./ZEP-0001.md) | ZEP Template | Accepted | Not applicable | — |
| [ZEP-0002](./ZEP-0002.md) | Explicit Shared Mutable State in Lambdas | Implemented | Shipped | 0.3.0 |
| [ZEP-0003](./ZEP-0003.md) | Constraint-Aware Let-Bound Lambda Generalization | Implemented | Shipped | 0.4.0 |
| [ZEP-0004](./ZEP-0004.md) | Complete Generic Witness Overlap Checking | Implemented | Shipped | 0.2.0 |
| [ZEP-0005](./ZEP-0005.md) | Generic Effect Payloads with Closed Applied Identity | Accepted | Candidate | 0.7.0 |
| [ZEP-0006](./ZEP-0006.md) | First-Class Reusable Typed-Effect Handlers | Superseded | Unscheduled | — |
| [ZEP-0007](./ZEP-0007.md) | Resumable Algebraic Effects Exploration | Superseded | Unscheduled | — |
| [ZEP-0008](./ZEP-0008.md) | Explicit Contract Derivation | Implemented | Shipped | 0.3.0 |
| [ZEP-0009](./ZEP-0009.md) | Public Member Discovery API | Implemented | Shipped | 0.3.0 |
| [ZEP-0010](./ZEP-0010.md) | Generic Admissibility Markers | Implemented | Shipped | 0.5.0 |
| [ZEP-0011](./ZEP-0011.md) | Standard Library Phase 1: I/O, Environment, and Time | Implemented | Shipped | 0.3.0 |
| [ZEP-0012](./ZEP-0012.md) | Pre-1.0 and Major-Series Versioning | Accepted | Not applicable | — |
| [ZEP-0013](./ZEP-0013.md) | Effect Subtyping and the Top Effect Type | Implemented | Candidate | 0.7.0 |
| [ZEP-0014](./ZEP-0014.md) | Stable Generic Type Admissibility | Rejected | Unscheduled | — |
| [ZEP-0015](./ZEP-0015.md) | Runtime Reification of Applied Type Identity | Implemented | Candidate | 0.7.0 |
| [ZEP-0016](./ZEP-0016.md) | Standard Library Phase 2: Math, Characters, and Networking | Superseded | Unscheduled | — |
| [ZEP-0017](./ZEP-0017.md) | Anonymous contract implementations | Implemented | Shipped | 0.3.0 |
| [ZEP-0018](./ZEP-0018.md) | Final-Version Release Targets in ZEP Tracker | Accepted | Not applicable | — |
| [ZEP-0019](./ZEP-0019.md) | Higher-Kinded Abstractions and Declarative Contracts | Implemented | Shipped | 0.4.0 |
| [ZEP-0020](./ZEP-0020.md) | Incremental Compilation | Deferred | Deferred | — |
| [ZEP-0021](./ZEP-0021.md) | Zeron Language Server Protocol Support | Implemented | Shipped | 0.3.0 |
| [ZEP-0022](./ZEP-0022.md) | Relaxing Visibility Requirements for Methods and Properties | Implemented | Shipped | 0.3.0 |
| [ZEP-0023](./ZEP-0023.md) | Semicolon-Free Statement and Declaration Syntax | Rejected | Unscheduled | — |
| [ZEP-0024](./ZEP-0024.md) | Standard Library Math APIs | Implemented | Shipped | 0.4.0 |
| [ZEP-0025](./ZEP-0025.md) | Character Type and String Interoperation | Implemented | Shipped | 0.4.0 |
| [ZEP-0026](./ZEP-0026.md) | Standard Library Networking APIs | Implemented | Shipped | 0.5.0 |
| [ZEP-0027](./ZEP-0027.md) | VS Code Language Client for Zeron | Implemented | Shipped | 0.3.0 |
| [ZEP-0028](./ZEP-0028.md) | Project-Aware Source Formatting | Implemented | Shipped | 0.4.0 |
| [ZEP-0029](./ZEP-0029.md) | Query Engine Sample Project | Implemented | Shipped | 0.4.0 |
| [ZEP-0030](./ZEP-0030.md) | Receiver-Scoped Extension Wildcard Imports | Superseded | Unscheduled | — |
| [ZEP-0031](./ZEP-0031.md) | Declarative Annotations and Declaration Metadata | Implemented | Shipped | 0.6.0 |
| [ZEP-0032](./ZEP-0032.md) | Exploration: Runtime Reflection | Draft | Unscheduled | — |
| [ZEP-0033](./ZEP-0033.md) | Soft `get` and `set` Accessor Keywords | Implemented | Shipped | 0.4.0 |
| [ZEP-0034](./ZEP-0034.md) | Namespace-Scoped Wildcard Imports | Implemented | Shipped | 0.4.0 |
| [ZEP-0035](./ZEP-0035.md) | Standard Library Source in Release Bundle | Implemented | Shipped | 0.4.0 |
| [ZEP-0036](./ZEP-0036.md) | Operator Overloading Through Algebraic Contracts | Implemented | Shipped | 0.5.0 |
| [ZEP-0037](./ZEP-0037.md) | Null Tests with the `is` Operator | Implemented | Shipped | 0.5.0 |
| [ZEP-0038](./ZEP-0038.md) | CLI Version Command | Implemented | Shipped | 0.5.0 |
| [ZEP-0039](./ZEP-0039.md) | Digit Separators in Numeric Literals | Implemented | Shipped | 0.5.0 |
| [ZEP-0040](./ZEP-0040.md) | Radix-Prefixed Integer Literals | Implemented | Shipped | 0.5.0 |
| [ZEP-0041](./ZEP-0041.md) | Unsigned Fixed-Width Integer Types | Draft | Unscheduled | — |
| [ZEP-0042](./ZEP-0042.md) | Arbitrary-Precision Numeric Types | Draft | Unscheduled | — |
| [ZEP-0043](./ZEP-0043.md) | Custom Numeric Literal Suffixes | Implemented | Shipped | 0.5.0 |
| [ZEP-0044](./ZEP-0044.md) | Unit Literals and Scaled Units | Draft | Unscheduled | — |
| [ZEP-0045](./ZEP-0045.md) | Heterogeneous Operator Signatures | Draft | Unscheduled | — |
| [ZEP-0046](./ZEP-0046.md) | Implicit Widening for Numeric Expressions | Draft | Unscheduled | — |
| [ZEP-0047](./ZEP-0047.md) | Java Type and Array Mapping Semantics | Implemented | Shipped | 0.5.0 |
| [ZEP-0048](./ZEP-0048.md) | Java Generic Interoperability | Implemented | Shipped | 0.5.0 |
| [ZEP-0049](./ZEP-0049.md) | Java Exceptions and Typed Effects | Implemented | Shipped | 0.5.0 |
| [ZEP-0050](./ZEP-0050.md) | Remaining Java Interoperability Work | Draft | Unscheduled | — |
| [ZEP-0051](./ZEP-0051.md) | Generic Covariance for Zeron | Implemented | Shipped | 0.5.0 |
| [ZEP-0052](./ZEP-0052.md) | Use-Site Variance and Type Projections | Implemented | Shipped | 0.5.0 |
| [ZEP-0053](./ZEP-0053.md) | Generic-Aware Runtime Type Tests and Safe Casts | Implemented | Shipped | 0.5.0 |
| [ZEP-0054](./ZEP-0054.md) | Nominal Effect Payload Types | Implemented | Candidate | 0.7.0 |
| [ZEP-0055](./ZEP-0055.md) | Effect-Kinded Generic Parameters | Implemented | Shipped | 0.5.0 |
| [ZEP-0056](./ZEP-0056.md) | Named Reusable Typed-Effect Handler Declarations | Implemented | Shipped | 0.5.0 |
| [ZEP-0057](./ZEP-0057.md) | First-Class Typed-Effect Handler Types | Implemented | Shipped | 0.5.0 |
| [ZEP-0058](./ZEP-0058.md) | Clause-Based For-Expressions and Lazy Yield | Implemented | Shipped | 0.5.0 |
| [ZEP-0059](./ZEP-0059.md) | Removing Contract Constructor Requirements | Implemented | Shipped | 0.5.0 |
| [ZEP-0060](./ZEP-0060.md) | Treating Arrays as Iterable Values | Implemented | Shipped | 0.6.0 |
| [ZEP-0061](./ZEP-0061.md) | Java and External Interop Language Support | Implemented | Shipped | 0.6.0 |
| [ZEP-0062](./ZEP-0062.md) | Mutable-Type Admissibility for Generic Parameters | Implemented | Shipped | 0.5.0 |
| [ZEP-0063](./ZEP-0063.md) | Standard Library Testing APIs | Draft | Unscheduled | — |
| [ZEP-0064](./ZEP-0064.md) | Standard Library Logging APIs | Draft | Unscheduled | — |
| [ZEP-0065](./ZEP-0065.md) | Standard Library JSON Parser | Implemented | Shipped | 0.6.0 |
| [ZEP-0066](./ZEP-0066.md) | Standard Library TOML Parser | Draft | Unscheduled | — |
| [ZEP-0067](./ZEP-0067.md) | Standard Library XML Parser | Draft | Unscheduled | — |
| [ZEP-0068](./ZEP-0068.md) | Serialization Contracts for Zeron | Accepted | Shipped | 0.6.0 |
| [ZEP-0069](./ZEP-0069.md) | Transparent Data Classes | Implemented | Shipped | 0.6.0 |
| [ZEP-0070](./ZEP-0070.md) | Generic and Nominal Newtype Representations | Draft | Unscheduled | — |
| [ZEP-0071](./ZEP-0071.md) | Numeric Hierarchy and Context-Directed Literals | Implemented | Shipped | 0.6.0 |
| [ZEP-0072](./ZEP-0072.md) | Class-Scoped Static Functions as Companion Namespace Members | Implemented | Candidate | 0.7.0 |
| [ZEP-0073](./ZEP-0073.md) | Documentation Comments for Declarations | Implemented | Shipped | 0.6.0 |
| [ZEP-0074](./ZEP-0074.md) | Numeric Literal Suffixes for Newtypes | Draft | Unscheduled | — |
| [ZEP-0075](./ZEP-0075.md) | General Contextual Numeric Literal Construction | Draft | Unscheduled | — |
| [ZEP-0076](./ZEP-0076.md) | Delegating Witness Implementations | Implemented | Shipped | 0.6.0 |
| [ZEP-0077](./ZEP-0077.md) | Delegating Higher-Kinded Implementations | Implemented | Shipped | 0.6.0 |
| [ZEP-0078](./ZEP-0078.md) | Default Method Implementations in Higher-Kinded Contracts | Implemented | Shipped | 0.6.0 |
| [ZEP-0079](./ZEP-0079.md) | Model Declarations | Implemented | Shipped | 0.6.0 |
| [ZEP-0080](./ZEP-0080.md) | Typed Code Models for Selected Expressions | Implemented | Shipped | 0.6.0 |
| [ZEP-0081](./ZEP-0081.md) | Type-Safe Embedded DSL Support | Implemented | Shipped | 0.6.0 |
| [ZEP-0082](./ZEP-0082.md) | Receiver Lambdas and Type-Safe Builders | Implemented | Shipped | 0.6.0 |
| [ZEP-0083](./ZEP-0083.md) | Lambda and Function-Type Syntax Ergonomics | Implemented | Shipped | 0.6.0 |
| [ZEP-0084](./ZEP-0084.md) | 64-Bit Hashes for Zeron Values | Implemented | Shipped | 0.6.0 |
| [ZEP-0085](./ZEP-0085.md) | Explicit Mutable Projections for Inferred Bindings | Draft | Unscheduled | - |
| [ZEP-0086](./ZEP-0086.md) | Effect-Row Polymorphism | Implemented | Shipped | 0.6.0 |
| [ZEP-0087](./ZEP-0087.md) | Negative Flow Refinement for Closed Sum Types | Implemented | Shipped | 0.6.0 |
| [ZEP-0088](./ZEP-0088.md) | Non-Null Assertion Operator | Implemented | Shipped | 0.6.0 |
| [ZEP-0089](./ZEP-0089.md) | Explicit Unchecked Casts | Draft | Unscheduled | - |
| [ZEP-0090](./ZEP-0090.md) | Property References | Implemented | Shipped | 0.6.0 |
| [ZEP-0091](./ZEP-0091.md) | Annotation Consumption | Accepted | Unscheduled | - |
| [ZEP-0092](./ZEP-0092.md) | Generated Serialization Operations | Implemented | Shipped | 0.6.0 |
| [ZEP-0093](./ZEP-0093.md) | Unified Property Identity and a Typed Code-Model API | Implemented | Shipped | 0.6.0 |
| [ZEP-0094](./ZEP-0094.md) | Collection Contracts | Implemented | Shipped | 0.6.0 |
| [ZEP-0095](./ZEP-0095.md) | Bytecode Quality and Evidence Inlining | Draft | Unscheduled | — |
| [ZEP-0096](./ZEP-0096.md) | Primitive Specialization for Generic Code | Draft | Unscheduled | — |
| [ZEP-0097](./ZEP-0097.md) | Annotation Target for Model Mapping Clauses | Accepted | Unscheduled | — |
| [ZEP-0098](./ZEP-0098.md) | Annotation Targets for Native Products, Sums, and Newtypes | Draft | Unscheduled | — |
| [ZEP-0099](./ZEP-0099.md) | Row-Polymorphic Derived Witnesses and Inclusion Matching | Draft | Unscheduled | — |
| [ZEP-0100](./ZEP-0100.md) | Callable References for Methods, Constructors, and Static Functions | Implemented | Candidate | 0.7.0 |
| [ZEP-0101](./ZEP-0101.md) | Field Representation Adapters for Model Mappings | Draft | Unscheduled | — |
| [ZEP-0102](./ZEP-0102.md) | Generic Models through Model Factories | Draft | Unscheduled | — |
| [ZEP-0103](./ZEP-0103.md) | Polymorphic Models for Closed Sum Types | Draft | Unscheduled | — |
| [ZEP-0104](./ZEP-0104.md) | Wildcard Existentials and Type-Bound Pattern Matching | Accepted | Candidate | 0.7.0 |
| [ZEP-0105](./ZEP-0105.md) | General Existential Types and Escape Semantics | Implemented | Candidate | 0.7.0 |
| [ZEP-0106](./ZEP-0106.md) | Stored Existentials and Holder ABI | Draft | Unscheduled | — |
| [ZEP-0107](./ZEP-0107.md) | Ancestor-Aware Runtime Type Descriptors | Draft | Unscheduled | — |
| [ZEP-0108](./ZEP-0108.md) | Contract-Bound Runtime Tests | Draft | Unscheduled | — |
| [ZEP-0109](./ZEP-0109.md) | Rank-2 Polymorphism: Polymorphic Function Types and Generic Lambdas | Draft | Unscheduled | — |
| [ZEP-0110](./ZEP-0110.md) | Block-Bodied Handler Expressions | Draft | Unscheduled | — |
| [ZEP-0111](./ZEP-0111.md) | Witness-Dispatched Do-Notation | Implemented | Candidate | 0.7.0 |
| [ZEP-0112](./ZEP-0112.md) | Structured Concurrency on Virtual Threads | Draft | Unscheduled | — |
| [ZEP-0113](./ZEP-0113.md) | Declaration-Site Covariance for Sum and Product Types | Draft | Unscheduled | — |
| [ZEP-0114](./ZEP-0114.md) | Named Witness Declarations, Default Witnesses, and Explicit Witness Imports | Implemented | Candidate | 0.7.0 |
| [ZEP-0115](./ZEP-0115.md) | Warning-Severity Compiler Diagnostics | Implemented | Candidate | 0.7.0 |
| [ZEP-0116](./ZEP-0116.md) | Remaining Existential Opening and Escape Work | Draft | Unscheduled | — |
| [ZEP-0117](./ZEP-0117.md) | Declarative Project Targets and Generated CLI/Manifest Reference | Draft | Unscheduled | — |
| [ZEP-0118](./ZEP-0118.md) | Response-Typed Effects and Tail-Resumptive Handlers | Draft | Unscheduled | — |
| [ZEP-0119](./ZEP-0119.md) | One-Shot Continuations, Deep Handlers, and Cancellation | Draft | Unscheduled | — |
| [ZEP-0120](./ZEP-0120.md) | Async as an Effect and the Future/Task Surface | Draft | Unscheduled | — |
| [ZEP-0121](./ZEP-0121.md) | Multi-Shot Continuations and Backtracking | Deferred | Unscheduled | — |

## Release bundles

The row-level release state above is authoritative. This section summarizes the current bundle plan;
see the [ZEP release plan](../developer_guides/zep-tracker-review.md) for the rationale and
sequencing.

### Bundle summary

| Bundle | Theme | Current state | Contents |
| --- | --- | --- | --- |
| `0.6.0` | Stabilization | Shipped | 24 rows |
| `0.7.0` | Type identity, reification, and type bounds | Candidate set recorded | 11 candidates; five-item acceptance queue |
| `0.8.0` | Effect control and response typing | Proposed | No candidates; ZEP-0118 and ZEP-0110 headline the bundle |

### `0.7.0` — type identity, reification, and type bounds

Candidates: [ZEP-0005](./ZEP-0005.md), [ZEP-0013](./ZEP-0013.md), [ZEP-0015](./ZEP-0015.md),
[ZEP-0054](./ZEP-0054.md), [ZEP-0072](./ZEP-0072.md), [ZEP-0100](./ZEP-0100.md),
[ZEP-0104](./ZEP-0104.md), [ZEP-0105](./ZEP-0105.md), [ZEP-0111](./ZEP-0111.md),
[ZEP-0114](./ZEP-0114.md), and [ZEP-0115](./ZEP-0115.md).

[ZEP-0106](./ZEP-0106.md), [ZEP-0107](./ZEP-0107.md), [ZEP-0108](./ZEP-0108.md),
[ZEP-0109](./ZEP-0109.md), and [ZEP-0116](./ZEP-0116.md) are the acceptance queue. They remain
`Unscheduled` and become candidates only after their designs are accepted.

### `0.8.0` — effect control and response typing

Proposed bundle: [ZEP-0118](./ZEP-0118.md) (response-typed effects and tail-resumptive handlers)
first, then [ZEP-0110](./ZEP-0110.md) (block-bodied handlers).
[ZEP-0111](./ZEP-0111.md) joins `0.8.0` only if it does not ship in `0.7.0`.

[ZEP-0112](./ZEP-0112.md) is an independent library track. [ZEP-0120](./ZEP-0120.md) depends on
ZEP-0118 and ZEP-0112. [ZEP-0119](./ZEP-0119.md) is prototype-gated, and
[ZEP-0121](./ZEP-0121.md) is `Deferred`.

There are no `0.8.0` candidates yet: ZEP-0118 and ZEP-0110 are `Draft`, and ZEP-0000 requires an
Accepted design before a ZEP can be marked `Candidate` or `Planned`.

