# Zeron Enhancement Proposals

This file tracks ZEPs in numerical order, including their proposal status and independent release
state. Release state is maintained here as the single source of truth. See [ZEP-0000](./ZEP-0000.md)
for the proposal process and [ZEP-0001](./ZEP-0001.md) for the proposal template.

Release states are defined in ZEP-0000. `Candidate` means a proposed release target, not a
commitment; `Planned` means approved for a target bundle. Deferred rows have no release target or
release reference. The proposal lifecycle status and release state are separate.

A `Deferred` proposal has no approved design or implementation work. A `Deferred` release state
means the proposal was considered and intentionally excluded from current release planning. Both
are recorded for [ZEP-0121](./ZEP-0121.md): it is not unscheduled work that can enter a bundle
without reopening the design. `Implemented` records the accepted design that landed; follow-up
scope owned by other proposals does not keep the original row `Accepted` after its release row is
`Shipped` (see [ZEP-0068](./ZEP-0068.md)).

| Number | Name | Proposal status | Release state | Release / bundle reference |
| ------ | ---- | --------------- | ------------- | -------------------------- |
| [ZEP-0000](./ZEP-0000.md) | Zeron Enhancement Proposal Process | Accepted | Not applicable | — |
| [ZEP-0001](./ZEP-0001.md) | ZEP Template | Accepted | Not applicable | — |
| [ZEP-0002](./ZEP-0002.md) | Explicit Shared Mutable State in Lambdas | Implemented | Shipped | 0.3.0 |
| [ZEP-0003](./ZEP-0003.md) | Constraint-Aware Let-Bound Lambda Generalization | Implemented | Shipped | 0.4.0 |
| [ZEP-0004](./ZEP-0004.md) | Complete Generic Witness Overlap Checking | Implemented | Shipped | 0.2.0 |
| [ZEP-0005](./ZEP-0005.md) | Generic Effect Payloads with Closed Applied Identity | Implemented | Shipped | 0.7.0 |
| [ZEP-0006](./ZEP-0006.md) | First-Class Reusable Typed-Effect Handlers | Superseded | Unscheduled | — |
| [ZEP-0007](./ZEP-0007.md) | Resumable Algebraic Effects Exploration | Superseded | Unscheduled | — |
| [ZEP-0008](./ZEP-0008.md) | Explicit Contract Derivation | Implemented | Shipped | 0.3.0 |
| [ZEP-0009](./ZEP-0009.md) | Public Member Discovery API | Implemented | Shipped | 0.3.0 |
| [ZEP-0010](./ZEP-0010.md) | Generic Admissibility Markers | Superseded | Shipped | 0.5.0 |
| [ZEP-0011](./ZEP-0011.md) | Standard Library Phase 1: I/O, Environment, and Time | Implemented | Shipped | 0.3.0 |
| [ZEP-0012](./ZEP-0012.md) | Pre-1.0 and Major-Series Versioning | Accepted | Not applicable | — |
| [ZEP-0013](./ZEP-0013.md) | Effect Subtyping and the Top Effect Type | Implemented | Shipped | 0.7.0 |
| [ZEP-0014](./ZEP-0014.md) | Stable Generic Type Admissibility | Rejected | Unscheduled | — |
| [ZEP-0015](./ZEP-0015.md) | Runtime Reification of Applied Type Identity | Implemented | Shipped | 0.7.0 |
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
| [ZEP-0041](./ZEP-0041.md) | Unsigned Fixed-Width Integer Types | Rejected | Unscheduled | — |
| [ZEP-0042](./ZEP-0042.md) | Arbitrary-Precision Numeric Types | Implemented | Shipped | 0.6.0 |
| [ZEP-0043](./ZEP-0043.md) | Custom Numeric Literal Suffixes | Implemented | Shipped | 0.5.0 |
| [ZEP-0044](./ZEP-0044.md) | Unit Literals and Scaled Units | Draft | Unscheduled | — |
| [ZEP-0045](./ZEP-0045.md) | Heterogeneous Operator Signatures | Draft | Unscheduled | — |
| [ZEP-0046](./ZEP-0046.md) | Implicit Widening for Numeric Expressions | Draft | Unscheduled | — |
| [ZEP-0047](./ZEP-0047.md) | Java Type and Array Mapping Semantics | Implemented | Shipped | 0.5.0 |
| [ZEP-0048](./ZEP-0048.md) | Java Generic Interoperability | Implemented | Shipped | 0.5.0 |
| [ZEP-0049](./ZEP-0049.md) | Java Exceptions and Typed Effects | Implemented | Shipped | 0.5.0 |
| [ZEP-0050](./ZEP-0050.md) | Java Host-Access Trust Boundary | Implemented | Shipped | 0.8.0 |
| [ZEP-0051](./ZEP-0051.md) | Generic Covariance for Zeron | Implemented | Shipped | 0.5.0 |
| [ZEP-0052](./ZEP-0052.md) | Use-Site Variance and Type Projections | Implemented | Shipped | 0.5.0 |
| [ZEP-0053](./ZEP-0053.md) | Generic-Aware Runtime Type Tests and Safe Casts | Implemented | Shipped | 0.5.0 |
| [ZEP-0054](./ZEP-0054.md) | Nominal Effect Payload Types | Implemented | Shipped | 0.7.0 |
| [ZEP-0055](./ZEP-0055.md) | Effect-Kinded Generic Parameters | Superseded | Shipped | 0.5.0 |
| [ZEP-0056](./ZEP-0056.md) | Named Reusable Typed-Effect Handler Declarations | Implemented | Shipped | 0.5.0 |
| [ZEP-0057](./ZEP-0057.md) | First-Class Typed-Effect Handler Types | Implemented | Shipped | 0.5.0 |
| [ZEP-0058](./ZEP-0058.md) | Clause-Based For-Expressions and Lazy Yield | Implemented | Shipped | 0.5.0 |
| [ZEP-0059](./ZEP-0059.md) | Removing Contract Constructor Requirements | Implemented | Shipped | 0.5.0 |
| [ZEP-0060](./ZEP-0060.md) | Treating Arrays as Iterable Values | Implemented | Shipped | 0.6.0 |
| [ZEP-0061](./ZEP-0061.md) | Java and External Interop Language Support | Implemented | Shipped | 0.6.0 |
| [ZEP-0062](./ZEP-0062.md) | Mutable-Type Admissibility for Generic Parameters | Superseded | Shipped | 0.5.0 |
| [ZEP-0063](./ZEP-0063.md) | Standard Library Testing APIs | Implemented | Shipped | 0.8.0 |
| [ZEP-0064](./ZEP-0064.md) | Standard Library Logging APIs | Implemented | Shipped | 0.8.0 |
| [ZEP-0065](./ZEP-0065.md) | Standard Library JSON Parser | Implemented | Shipped | 0.6.0 |
| [ZEP-0066](./ZEP-0066.md) | Standard Library TOML Parser | Draft | Candidate | 0.9.0 |
| [ZEP-0067](./ZEP-0067.md) | Standard Library XML Parser | Draft | Candidate | 0.9.0 |
| [ZEP-0068](./ZEP-0068.md) | Serialization Contracts for Zeron | Implemented | Shipped | 0.6.0 |
| [ZEP-0069](./ZEP-0069.md) | Transparent Data Classes | Implemented | Shipped | 0.6.0 |
| [ZEP-0070](./ZEP-0070.md) | Generic and Nominal Newtype Representations | Implemented | Shipped | 0.8.0 |
| [ZEP-0071](./ZEP-0071.md) | Numeric Hierarchy and Context-Directed Literals | Implemented | Shipped | 0.6.0 |
| [ZEP-0072](./ZEP-0072.md) | Class-Scoped Static Functions as Companion Namespace Members | Implemented | Shipped | 0.7.0 |
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
| [ZEP-0091](./ZEP-0091.md) | Annotation Consumption | Accepted | Candidate | 0.10.0 |
| [ZEP-0092](./ZEP-0092.md) | Generated Serialization Operations | Implemented | Shipped | 0.6.0 |
| [ZEP-0093](./ZEP-0093.md) | Unified Property Identity and a Typed Code-Model API | Implemented | Shipped | 0.6.0 |
| [ZEP-0094](./ZEP-0094.md) | Collection Contracts | Implemented | Shipped | 0.6.0 |
| [ZEP-0095](./ZEP-0095.md) | Bytecode Quality and Evidence Inlining | Draft | Unscheduled | — |
| [ZEP-0096](./ZEP-0096.md) | Primitive Specialization for Generic Code | Draft | Unscheduled | — |
| [ZEP-0097](./ZEP-0097.md) | Annotation Target for Model Mapping Clauses | Accepted | Candidate | 0.10.0 |
| [ZEP-0098](./ZEP-0098.md) | Annotation Targets for Native Products, Sums, and Newtypes | Implemented | Shipped | 0.8.0 |
| [ZEP-0099](./ZEP-0099.md) | Row-Polymorphic Derived Witnesses and Inclusion Matching | Draft | Unscheduled | — |
| [ZEP-0100](./ZEP-0100.md) | Callable References for Methods, Constructors, and Static Functions | Implemented | Shipped | 0.7.0 |
| [ZEP-0101](./ZEP-0101.md) | Field Representation Adapters for Model Mappings | Draft | Unscheduled | — |
| [ZEP-0102](./ZEP-0102.md) | Generic Models through Model Factories | Draft | Unscheduled | — |
| [ZEP-0103](./ZEP-0103.md) | Polymorphic Models for Closed Sum Types | Draft | Unscheduled | — |
| [ZEP-0104](./ZEP-0104.md) | Wildcard Existentials and Type-Bound Pattern Matching | Implemented | Shipped | 0.7.0 |
| [ZEP-0105](./ZEP-0105.md) | General Existential Types and Escape Semantics | Implemented | Shipped | 0.7.0 |
| [ZEP-0106](./ZEP-0106.md) | Stored Existentials and Holder ABI | Implemented | Shipped | 0.7.0 |
| [ZEP-0107](./ZEP-0107.md) | Ancestor-Aware Runtime Type Descriptors | Accepted | Unscheduled | — |
| [ZEP-0108](./ZEP-0108.md) | Contract-Bound Runtime Tests | Deferred | Deferred | — |
| [ZEP-0109](./ZEP-0109.md) | Rank-2 Polymorphism: Polymorphic Function Types and Generic Lambdas | Implemented | Shipped | 0.7.0 |
| [ZEP-0110](./ZEP-0110.md) | Block-Bodied Handler Expressions | Superseded | Unscheduled | — |
| [ZEP-0111](./ZEP-0111.md) | Witness-Dispatched Do-Notation | Implemented | Shipped | 0.7.0 |
| [ZEP-0112](./ZEP-0112.md) | Structured Concurrency on Virtual Threads | Implemented | Candidate | 0.9.0 |
| [ZEP-0113](./ZEP-0113.md) | Declaration-Site Covariance for Sum and Product Types | Implemented | Shipped | 0.8.0 |
| [ZEP-0114](./ZEP-0114.md) | Named Witness Declarations, Default Witnesses, and Explicit Witness Imports | Implemented | Shipped | 0.7.0 |
| [ZEP-0115](./ZEP-0115.md) | Warning-Severity Compiler Diagnostics | Implemented | Shipped | 0.7.0 |
| [ZEP-0116](./ZEP-0116.md) | Remaining Existential Opening and Escape Work | Implemented | Shipped | 0.7.0 |
| [ZEP-0117](./ZEP-0117.md) | Declarative Project Targets and Generated CLI/Manifest Reference | Implemented | Shipped | 0.8.0 |
| [ZEP-0118](./ZEP-0118.md) | Response-Typed Effects and Tail-Resumptive Handlers | Implemented | Candidate | 0.9.0 |
| [ZEP-0119](./ZEP-0119.md) | One-Shot Continuations, Deep Handlers, and Cancellation | Draft | Candidate | 0.9.0 |
| [ZEP-0120](./ZEP-0120.md) | Direct-Style Futures with `spawn` and `await` | Superseded | Unscheduled | — |
| [ZEP-0121](./ZEP-0121.md) | Multi-Shot Continuations and Backtracking | Deferred | Deferred | — |
| [ZEP-0122](./ZEP-0122.md) | Runtime Applied-Type Match Patterns | Implemented | Shipped | 0.7.0 |
| [ZEP-0123](./ZEP-0123.md) | Admissibility Clauses and Leading Reification | Implemented | Candidate | 0.9.0 |
| [ZEP-0124](./ZEP-0124.md) | Nominal Static Contract Implementation | Implemented | Shipped | 0.8.0 |
| [ZEP-0125](./ZEP-0125.md) | Optional Higher-Kinded Implementation Blocks | Implemented | Shipped | 0.8.0 |
| [ZEP-0126](./ZEP-0126.md) | Compiler-Owned Main Entry-Point Contract | Implemented | Shipped | 0.8.0 |
| [ZEP-0127](./ZEP-0127.md) | Data Class Component Access and Positional Destructuring | Implemented | Shipped | 0.8.0 |
| [ZEP-0128](./ZEP-0128.md) | Sum-Type Parameter Bounds and Case-Local Type Parameters | Implemented | Shipped | 0.8.0 |
| [ZEP-0129](./ZEP-0129.md) | Unary Products versus Arbitrary-Valued Newtypes | Implemented | Shipped | 0.8.0 |
| [ZEP-0130](./ZEP-0130.md) | Standard-Library-Owned Intrinsic Declarations | Implemented | Shipped | 0.8.0 |
| [ZEP-0131](./ZEP-0131.md) | Chained Flow Refinement and Implicit Receiver Access | Implemented | Shipped | 0.8.0 |
| [ZEP-0132](./ZEP-0132.md) | Nullable Safe-Cast Correction | Implemented | Shipped | 0.8.0 |
| [ZEP-0133](./ZEP-0133.md) | Operator-Constraint Inference for Typeless Lambdas | Implemented | Shipped | 0.8.0 |
| [ZEP-0134](./ZEP-0134.md) | Invocation of Function-Valued Expressions | Implemented | Shipped | 0.8.0 |
| [ZEP-0135](./ZEP-0135.md) | Witness Provision, Requirement Evidence, and Owner-Directed Dispatch | Implemented | Shipped | 0.8.0 |
| [ZEP-0136](./ZEP-0136.md) | Witness Bundles and Coherent Prerequisite Evidence | Implemented | Shipped | 0.8.0 |
| [ZEP-0137](./ZEP-0137.md) | Explicit Witness Selection and Witness Contexts | Implemented | Shipped | 0.8.0 |
| [ZEP-0138](./ZEP-0138.md) | Predicate and Fact IR for Static Verification | Draft | Unscheduled | - |
| [ZEP-0139](./ZEP-0139.md) | Checked Construction for Guarded Newtypes | Draft | Unscheduled | - |
| [ZEP-0140](./ZEP-0140.md) | Comparison and Refined-Type Flow Facts | Draft | Unscheduled | - |
| [ZEP-0141](./ZEP-0141.md) | Predicate Functions and Fact Transfer | Draft | Unscheduled | - |
| [ZEP-0142](./ZEP-0142.md) | Interval and Difference-Bound Flow Facts | Draft | Unscheduled | - |
| [ZEP-0143](./ZEP-0143.md) | IntRange Bounds and Loop Facts | Draft | Unscheduled | - |
| [ZEP-0144](./ZEP-0144.md) | Parameter Type Refinement and Call-Site Preconditions | Draft | Unscheduled | - |
| [ZEP-0145](./ZEP-0145.md) | Unified Namespace and Extension Container | Draft | Unscheduled | - |
| [ZEP-0146](./ZEP-0146.md) | Value-Producing Block Expressions | Implemented | Shipped | 0.8.0 |
| [ZEP-0147](./ZEP-0147.md) | Replace `raise` and `raises` with `perform` and `performs` | Implemented | Candidate | 0.9.0 |
| [ZEP-0148](./ZEP-0148.md) | Java SAM Conversion | Implemented | Shipped | 0.8.0 |
| [ZEP-0149](./ZEP-0149.md) | Remaining Java Interoperability Work | Draft | Unscheduled | - |
| [ZEP-0150](./ZEP-0150.md) | Safe Invocation of Nullable Function Values | Implemented | Shipped | 0.8.0 |
| [ZEP-0151](./ZEP-0151.md) | Spread Arguments for Variadic Parameters | Implemented | Shipped | 0.8.0 |
| [ZEP-0152](./ZEP-0152.md) | Standard Library Randomness | Draft | Candidate | 0.9.0 |
| [ZEP-0153](./ZEP-0153.md) | Descriptive Statistics in `zeron.math` | Draft | Candidate | 0.9.0 |
| [ZEP-0154](./ZEP-0154.md) | Ordered Collections | Draft | Candidate | 0.9.0 |
| [ZEP-0155](./ZEP-0155.md) | Binary Buffers and Encodings | Draft | Candidate | 0.9.0 |
| [ZEP-0156](./ZEP-0156.md) | Text Formatting and Regular Expressions | Draft | Candidate | 0.9.0 |
| [ZEP-0157](./ZEP-0157.md) | Collection Building APIs | Draft | Candidate | 0.9.0 |
| [ZEP-0158](./ZEP-0158.md) | Lambda Resource Handles with Scoped Receivers | Draft | Candidate | 0.10.0 |
| [ZEP-0159](./ZEP-0159.md) | Generated Standard-Library Reference | Draft | Unscheduled | — |
| [ZEP-0160](./ZEP-0160.md) | Scoped Projection Types | Draft | Candidate | 0.10.0 |
| [ZEP-0161](./ZEP-0161.md) | Scoped Receiver Methods and Contract Conformance | Draft | Candidate | 0.10.0 |
| [ZEP-0162](./ZEP-0162.md) | Scoped Generic Admissibility | Draft | Unscheduled | — |
| [ZEP-0163](./ZEP-0163.md) | Automatic Read-Only Widening for Inherited Generic Method Bounds | Draft | Unscheduled | — |
| [ZEP-0164](./ZEP-0164.md) | Erased `Await` Carrier and Blocking Entry Handler | Accepted | Candidate | 0.9.0 |
| [ZEP-0165](./ZEP-0165.md) | Unify `Task` and `Future` into a Single Async Handle | Accepted | Candidate | 0.9.0 |

## Release bundles

The row-level release state above is authoritative. This section summarizes the current bundle plan;
see the [ZEP release plan](../developer_guides/zep-tracker-review.md) for the rationale and
sequencing.

### Bundle summary

| Bundle | Theme | Current state | Contents |
| --- | --- | --- | --- |
| `0.6.0` | Stabilization | Shipped | 25 rows |
| `0.7.0` | Type identity, reification, and type bounds | Shipped 2026-11-14 | 15 shipped; acceptance queue closed |
| `0.8.0` | Normalization and nominal implementation | Shipped 2026-11-20 | 25 shipped; acceptance queue closed |
| `0.9.0` | Effect control and response typing plus library breadth | Proposed; 15 candidates | ZEP-0118 (headline, implemented), ZEP-0112 (implemented), ZEP-0123 (implemented), ZEP-0147 (implemented), ZEP-0165 (accepted; unifies `Task`/`Future`), ZEP-0164 (accepted; erased `Await` carrier and blocking entry handler), ZEP-0119, ZEP-0066, ZEP-0067, and ZEP-0152–ZEP-0157; remaining designs `Draft` |
| `0.10.0` | Model system completion, scoped types, and resource handling | Proposed; 5 candidates | ZEP-0091 and ZEP-0097 model metadata; ZEP-0158, ZEP-0160, and ZEP-0161 scoped resource handling; ZEP-0162 generic admissibility is unscheduled |

### `0.7.0` — type identity, reification, and type bounds

Shipped rows: [ZEP-0005](./ZEP-0005.md), [ZEP-0013](./ZEP-0013.md), [ZEP-0015](./ZEP-0015.md),
[ZEP-0054](./ZEP-0054.md), [ZEP-0072](./ZEP-0072.md), [ZEP-0100](./ZEP-0100.md),
[ZEP-0104](./ZEP-0104.md), [ZEP-0105](./ZEP-0105.md), [ZEP-0106](./ZEP-0106.md),
[ZEP-0109](./ZEP-0109.md), [ZEP-0111](./ZEP-0111.md), [ZEP-0114](./ZEP-0114.md),
[ZEP-0115](./ZEP-0115.md), [ZEP-0116](./ZEP-0116.md), and [ZEP-0122](./ZEP-0122.md).

ZEP-0122 is the required match-side companion to ZEP-0005: ordinary applied patterns and generic
effect handler cases share one exact-descriptor test, and the two must ship together in `0.7.0`.

ZEP-0109 shipped in `0.7.0`: rank-2 syntax, checking, eta-expansion, the hidden-slot runtime
ABI, API-index preservation, separate compilation, and the 2026-11-13 application-cost benchmark
are covered. Effect-binder support remains follow-up work. ZEP-0116 shipped in `0.7.0`: mixed
and nested opening, `else if` and value-producing `if` placements, and complete escape rejection;
binder bounds, `in` opening, result-position existentials, escape approximation, and variance
changes remain outside the design.

The `0.7.0` acceptance queue is closed. [ZEP-0107](./ZEP-0107.md) remains `Accepted` but has been
removed from the program bundle and is `Unscheduled`; [ZEP-0108](./ZEP-0108.md) remains `Deferred`
and is likewise outside the bundle. The bundle shipped on 2026-11-14; its rows are `Shipped` /
`0.7.0`.

### `0.8.0` — normalization and nominal implementation

The bundle targeted smaller normalization and smoothing work rather than new control-flow semantics.
It shipped on 2026-11-20 with these twenty-five rows:

- [ZEP-0050](./ZEP-0050.md) — Java host-access trust boundary. Direct Java host access becomes an
  explicit `unsafe` boundary; curated facades remain the trusted, compiler-validated tier; checked
  exceptions still require mappings; unchecked exceptions and `Error`s propagate as host exceptions;
  and `raises` remains control-flow, not purity.
- [ZEP-0063](./ZEP-0063.md) — standard-library testing APIs. Tests are top-level functions marked
  `@Test`; `zeron.test` provides assertions that raise a typed `TestFailure` effect; the compiler
  discovers tests and generates a runner; and `zeron test` builds and runs the `kind = "test"`
  targets declared in the project manifest. Filtering, machine-readable output, fixtures, parallel
  execution, and process isolation remain follow-up work.
- [ZEP-0064](./ZEP-0064.md) — standard-library logging APIs. `zeron.log` provides severity levels,
  structured fields, explicit sinks with filtering and console output, named loggers with copied
  context, lazy message construction, and a plain formatter. Configuration is passed explicitly;
  destination failure reporting and cross-thread guarantees remain follow-up work.
- [ZEP-0148](./ZEP-0148.md) — Java SAM conversion. Effect-free lambdas and top-level function
  references may implement an explicitly written Java functional interface; the compiler discovers
  the interface from class-file metadata and lowers the conversion through `LambdaMetafactory`. No
  raised effect may cross into Java, and no callback lifetime or thread-safety guarantee is made.
- [ZEP-0117](./ZEP-0117.md) — declarative project targets and generated CLI/manifest reference.
  A tooling and configuration change with no language, runtime, standard-library, compiled-library,
  or API-index compatibility impact.
- [ZEP-0124](./ZEP-0124.md) — nominal static contract implementation. Public class static functions
  and first-order `implementation` block entries satisfy static requirements directly.
- [ZEP-0125](./ZEP-0125.md) — optional higher-kinded implementation blocks. An `is` conformance plus
  public members supplies HKT operations when no explicit block is present.
- [ZEP-0126](./ZEP-0126.md) — compiler-owned `Main<A>` entry-point contract. A launchable `main`
  returns a closed entry value, and the generated launcher interprets it through `Main<A>` evidence
  after the user function returns. Implemented with standard `Unit`, `Int`, and `Result<Unit, E>`
  witnesses.
- [ZEP-0127](./ZEP-0127.md) — data-class component access and positional destructuring. Canonical
  components are readable whenever the type is nameable, and positional patterns use only those
  components at the root and in nested positions.
- [ZEP-0070](./ZEP-0070.md) — generic and nominal newtype representations. A newtype may use a
  type parameter, a function type, a nominal value, or another admissible representation as its
  direct carrier; `.value` is removed and `this` is the representation inside extensions and
  witnesses.
- [ZEP-0128](./ZEP-0128.md) — sum-type parameter bounds and case-local type parameters. Sum and
  product parameters accept contract bounds, a case may introduce an existential parameter such as
  the `B` in `Bind<B>`, and unambiguous case constructors may omit the sum qualifier.
- [ZEP-0129](./ZEP-0129.md) — unary products versus arbitrary-valued newtypes. Products stay at two
  or more components; the single-value need is met by ZEP-0070's direct-representation newtypes
  rather than by legalizing a unary product.
- [ZEP-0098](./ZEP-0098.md) — annotation targets for native products, sums, and newtypes. Product
  components and sum cases gain stable annotation sites, closing the metadata gap left by
  ZEP-0031 without changing runtime representation.
- [ZEP-0113](./ZEP-0113.md) — declaration-site covariance for sum and product types. Checked `out`
  parameters extend to the primary immutable carriers, so `Option`, `Result`, and `Pair` can widen
  safely at API boundaries without mapping every value.
- [ZEP-0130](./ZEP-0130.md) — standard-library-owned intrinsic declarations. Intrinsic types keep
  compiler-owned identity, while a broad `String` inventory—including `split`, `splitRegex`,
  `charAt`/`at`, case conversion, trimming, replacement, lines, parsing, and `StringJoiner`-backed
  joining, all over `Stream<String>` results—is declared in canonical `zeron.lang` extension
  modules rather than in a second class or intrinsic ID. Joining is a `Collector`, and
  `Stream.collectWith` drives any collector, and the `Collector` namespace ships the concrete
  builders for lists, maps, sets, and numeric sums.
- [ZEP-0131](./ZEP-0131.md) — chained flow refinement and implicit receiver access. `is` and
  `matches` transfer through composed conditions and stable member paths, and unqualified field or
  property reads use the implicit `this` receiver.
- [ZEP-0132](./ZEP-0132.md) — nullable safe-cast correction. `as?` always produces a nullable
  target, including when the target is written `T?`; the correction lands first because the current
  behavior can bypass a required null check.
- [ZEP-0133](./ZEP-0133.md) — operator-constraint inference for typeless lambdas. An
  uncontextualized immutable let-bound lambda can infer a rank-1 scheme from numeric or comparison
  operator use, so `(a, b) -> a + b` becomes `<T: Numeric<T>> (T, T) -> T` and
  `(a, b) -> a < b` becomes `<T: Comparable<T>> (T, T) -> Boolean`, rather than a concrete default.
- [ZEP-0134](./ZEP-0134.md) — invocation of function-valued expressions. A postfix argument list
  may follow any non-null function-valued expression, so `makeFunction()()` and `add(2)(3)` work
  without introducing a temporary binding or a new runtime representation. It is implemented and
  accepted for `0.8.0`; nullable callees are handled by ZEP-0150.
- [ZEP-0150](./ZEP-0150.md) — safe invocation of nullable function values. `couldBeNull?.()` reuses
  safe-navigation syntax, skips the arguments and result when the callee is null, and otherwise
  invokes the function value and produces a nullable result. Hover and signature help cover
  function-valued expression calls.
- [ZEP-0135](./ZEP-0135.md) — witness provision, requirement evidence, and owner-directed dispatch.
  A witness provides only its declared contract, `requires` contracts select their own canonical
  provider, and operations dispatch through their declaring contract. This removes the
  `Option`/`Monad`/`Alternative` diamond ambiguity without multi-contract witnesses or a
  per-type coherence rule.
- [ZEP-0136](./ZEP-0136.md) — witness bundles and coherent prerequisite evidence. A named or
  `default` bundle groups the providers for one target, records the prerequisite closure at
  declaration time, and makes named bundles selection-only while `default` bundles stay ambient.
- [ZEP-0137](./ZEP-0137.md) — explicit witness selection and witness contexts. A statement-only
  `using witness (...)` block selects bundles or named witnesses lexically, shadows ambient defaults
  locally, and captures evidence in closures without runtime lookup or dynamic scope.
- [ZEP-0146](./ZEP-0146.md) — value-producing block expressions. A brace block in expression
  position ends with one final `yield` and produces that value; statement blocks and every
  statement-block consumer remain unchanged. Accepted and implemented on 2026-11-20; the `0.8.0`
  candidacy records the bundle it targets.
- [ZEP-0151](./ZEP-0151.md) — spread arguments for variadic parameters. `...values` forwards an
  existing array to a variadic parameter without repacking it, or expands any `Iterable<E>` into
  the packed array through the existing iteration protocol. The spread must be the final argument
  and contributes the remaining variadic elements; it introduces no runtime or API-index
  representation.

ZEP-0124 and ZEP-0125 are implementation-coupled through one static/receiverless matcher, even
though they are independently decidable designs. They should be implemented and validated together
so the matcher, diagnostics, and separate-compilation behavior stay consistent. ZEP-0117 is
independent: it is the only candidate that changes the CLI and manifest surface, and it adds no
runtime ABI. ZEP-0126 is the only candidate that changes the entry-point rule; it depends on the
static-requirement work in ZEP-0124 for nominal `Main<A>` conformance, but its witness-only form can
ship independently. ZEP-0050 is the host-access trust boundary: it defines the direct-access gate and
should land first. ZEP-0148 implements Java SAM conversion on the same call-resolution path after the
gate exists. ZEP-0149 carries the umbrella's remaining interop inventory: general inherited-member
lookup, external contracts for Java interfaces, external enums, JPMS, binding extensibility, and
conformance. It is unscheduled, so none of it ships with `0.8.0` by implication. The bundle does not
change `raise` or `handle` typing.

All twenty-five rows shipped in `0.8.0` on 2026-11-20; the acceptance queue is closed.
ZEP-0098 and ZEP-0113 were accepted and implemented on 2026-11-16, and ZEP-0146 and ZEP-0151
were accepted and implemented on 2026-11-20. ZEP-0134 and ZEP-0146 began as `Draft` /
`Candidate` rows and were completed before the release. ZEP-0133 shipped with the bundle and its
proposal status was advanced to `Implemented` afterwards, since the implementation landed before
the release but the row was not updated in time. ZEP-0149 holds the remaining interop inventory and stays `Draft` / `Unscheduled`,
so it is not part of the bundle. The `String` inventory is declared in standard-library
source, multi-value results are `Stream<String>`, `StringJoiner` owns joining, and
`Stream.collectWith` is the general terminal operation behind it, over collectors the
`Collector` namespace provides. No intrinsic ID was added.

### `0.9.0` — effect control and response typing

`0.9.0` is the effect-control bundle. It has fifteen `Candidate` rows; [ZEP-0112](./ZEP-0112.md),
[ZEP-0118](./ZEP-0118.md), [ZEP-0123](./ZEP-0123.md), and [ZEP-0147](./ZEP-0147.md) are
implemented, [ZEP-0165](./ZEP-0165.md) and [ZEP-0164](./ZEP-0164.md) are accepted, and the
remaining designs are `Draft`. The effect-control rows carry the depth, and the library rows
add surface that does not touch handler typing or code generation:

- [ZEP-0118](./ZEP-0118.md) — response-typed effects and tail-resumptive handlers. Implemented:
  an effect declares a response type, `perform` becomes an expression whose value the handler
  supplies, and tail-resumptive cases compile to direct handler calls without stack capture. It
  also introduces the `Yield<T>`, `Ask<T>`, and `Log` library effects and the push direction of
  generators. The row stays `Candidate` until the bundle ships.
- [ZEP-0147](./ZEP-0147.md) — replace `raise` and `raises` with `perform` and `performs`.
  Implemented: a source-breaking rename of the effect-operation vocabulary; it does not change
  response typing, effect identity, or handler behavior.
- [ZEP-0123](./ZEP-0123.md) — admissibility clauses and leading reification. Implemented: the
  `admits` clause separates "which type arguments may be substituted" from "which operations are
  available"; it
  moves `reified` to a leading capability and leaves response constraints to ZEP-0118's
  `Responds<R>` bound.
- [ZEP-0112](./ZEP-0112.md) — structured concurrency on virtual threads. Implemented: a
  library-only `Scope` / `Task<T, E>` track with cancellation and deadlines; effect rows are
  captured at the task boundary and re-raised at `value()`. Independent of response typing.
- [ZEP-0165](./ZEP-0165.md) — unify `Task` and `Future` into a single async handle. Accepted:
  collapses the eager `Future` wrapper into `Task`, keeps `spawn`/`await` on `Task`, and supersedes
  ZEP-0120's `Future` type. The blocking direct-style policy, effect capture, cancellation, and
  structured-scope semantics are retained. Its companion [ZEP-0164](./ZEP-0164.md) supplies the
  `Await` carrier and must ship the blocking entry handler with it so `main` stays ergonomic.
- [ZEP-0164](./ZEP-0164.md) — erased `Await` carrier and blocking entry handler. Accepted: adds a
  non-generic responding `Await` effect whose payload carries `Task<Any?, Effect>`, changes `await`
  to `performs Await + E`, and installs a blocking handler at the program entry boundary. It stays
  identity-erased, so no effect-application ABI, wildcard handler, or class reification is needed.
  It depends on ZEP-0165's unified `Task` handle and ZEP-0126's entry contract.
- [ZEP-0119](./ZEP-0119.md) — one-shot continuations, deep handlers, and cancellation.
  Prototype-gated: candidacy records the intended target, and the backend prototype plus the
  continuation ABI questions must be resolved before acceptance.
- [ZEP-0066](./ZEP-0066.md) — standard-library TOML parser. A `zeron.parsers.toml` package with
  explicit value, date/time, and diagnostic semantics, following the shipped JSON parser.
- [ZEP-0067](./ZEP-0067.md) — standard-library XML parser. A `zeron.parsers.xml` package with
  explicit namespace, document-model, and resource-handling semantics and safe defaults.
- [ZEP-0152](./ZEP-0152.md) — standard-library randomness. A seedable `Random` with primitive and
  bounded generation, uniform collection picking, and in-place shuffling.
- [ZEP-0153](./ZEP-0153.md) — descriptive statistics in `zeron.math`. Mean, median, variance, and
  standard deviation with explicit population and sample variants and explicit empty-input results.
- [ZEP-0154](./ZEP-0154.md) — ordered collections. A binary-heap `PriorityQueue` and red–black-tree
  `SortedSet` and `SortedMap` ordered by `Comparable`.
- [ZEP-0155](./ZEP-0155.md) — binary buffers and encodings. `ByteBuffer`, `ByteReader`, endianness,
  LEB128 varints, bit helpers, and checked hex and Base64 codecs in `zeron.buffer`.
- [ZEP-0156](./ZEP-0156.md) — text formatting and regular expressions. A placeholder `Formatter`
  and a compiled `Regex` with matches, group access, replacement, and match iteration.
- [ZEP-0157](./ZEP-0157.md) — collection building APIs. `List.build`, `Set.build`, `Map.build`, and
  `Stream.build` receiver-lambda blocks over curated builder types.

The library rows are additive and independent of the effect work. They are preferred over
bytecode-quality rows here because ZEP-0095 is a compiler-wide emission program and ZEP-0096 is
ABI-changing primitive specialization; both would widen the release's internal surface at the same
time as the response-typing and continuation work.

ZEP-0000 requires an Accepted design before a `Candidate` row becomes `Planned`, so this is a
proposed release set, not an approved scope. ZEP-0107 is `Accepted` but stays `Unscheduled`: its
implementation needs the generic-effect propagation work that ZEP-0005 leaves deferred, namely
applications containing type parameters, forwarding through generic calls, and omitted handler
arguments. ZEP-0121 remains `Deferred` and outside the bundle. ZEP-0111 shipped in `0.7.0`,
ZEP-0110 is superseded by [ZEP-0146](./ZEP-0146.md), which shipped in `0.8.0`, and ZEP-0120 is
superseded by [ZEP-0165](./ZEP-0165.md), which carries the unified `Task` surface into `0.9.0`
together with [ZEP-0164](./ZEP-0164.md)'s `Await` carrier and entry handler.

### `0.10.0` — model system completion, scoped types, and resource handling (tentative)

This bundle gives the model-metadata orphans a coherent home rather than scheduling annotation
work in isolation. It is tentative and not scheduled, and it follows the `0.9.0` effect-control
work. The accepted candidates are [ZEP-0091](./ZEP-0091.md) (annotation consumption) and
[ZEP-0097](./ZEP-0097.md) (`MAPPING` annotation target for model clauses).
[ZEP-0160](./ZEP-0160.md) defines `scoped` projection types, origins, and the escape checker;
[ZEP-0161](./ZEP-0161.md) defines `scoped fn`, `scoped mut fn`, `Self` propagation, and contract
conformance; and [ZEP-0158](./ZEP-0158.md) is the expression-valued resource block that consumes a
`scoped &File` receiver. The three form a resource-handling companion. They are placed here because
the blocks depend on the effect-control and continuation work in `0.9.0` settling first, and
because their cleanup rules must agree with ZEP-0112's scope lifetime and ZEP-0119's resumption
points. [ZEP-0162](./ZEP-0162.md) records the future generic admissibility rules and remains
`Draft` / `Unscheduled`; version 1 deliberately rejects scoped type arguments and container
scoping. ZEP-0097 depends on ZEP-0091's compiler-recognized annotation model; ZEP-0091 Direction B
also unblocks external names in [ZEP-0092](./ZEP-0092.md).

The model-improvement drafts [ZEP-0101](./ZEP-0101.md) (field representation adapters),
[ZEP-0102](./ZEP-0102.md) (generic models through factories), and
[ZEP-0103](./ZEP-0103.md) (polymorphic models for closed sums) are intended for the same bundle,
but remain `Draft` / `Unscheduled` until their designs are accepted. They cannot be marked
`Candidate` yet. The shipped model foundation is [ZEP-0079](./ZEP-0079.md), [ZEP-0092](./ZEP-0092.md),
[ZEP-0093](./ZEP-0093.md), and [ZEP-0098](./ZEP-0098.md).

[ZEP-0107](./ZEP-0107.md) is not part of this bundle. It extends runtime descriptors with ancestor
metadata for generic effects, not model declarations or annotation metadata. It stays
`Accepted` / `Unscheduled` until the generic-effect propagation slice it depends on exists; folding
it into the model bundle would couple two unrelated API-index changes.

### Static newtype verification (proposed, unscheduled)

Seven `Draft` / `Unscheduled` proposals record a static-verification track outside the current
effect-control and model bundles. They are not candidates and have no release target. The proposed
order is dependency-driven:

1. [ZEP-0138](./ZEP-0138.md) — predicate and fact IR. This is the shared foundation and should be
   designed and accepted before the feature ZEPs.
2. [ZEP-0139](./ZEP-0139.md) — checked `new` for guarded newtypes. It is the most separable feature
   and can start with constant evaluation plus a runtime fallback.
3. [ZEP-0140](./ZEP-0140.md) — comparison and refined-type flow facts. It builds on ZEP-0131 and
   supplies the first useful proofs for checked construction.
4. [ZEP-0141](./ZEP-0141.md) — predicate functions. It depends on the fact IR and the flow-fact
   transfer.
5. [ZEP-0142](./ZEP-0142.md) — interval and difference-bound facts. It is the largest analysis
   component and supplies the relational proofs used by ranges and parameter refinements.
6. [ZEP-0143](./ZEP-0143.md) — `IntRange` bounds and loop facts. It depends on the interval backend.
7. [ZEP-0144](./ZEP-0144.md) — parameter refinements. It is the capstone and consumes the fact
   stores, predicate metadata, interval proofs, and range facts.

The foundation and the first feature slice should be reviewed before a bundle is named. Promoting
these rows to `Candidate` is a separate release decision and should not imply that all seven ship
together.
