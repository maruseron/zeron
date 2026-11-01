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
| [ZEP-0005](./ZEP-0005.md) | Generic Effect Payloads | Deferred | Unscheduled | — |
| [ZEP-0006](./ZEP-0006.md) | First-Class Reusable Typed-Effect Handlers | Superseded | Unscheduled | — |
| [ZEP-0007](./ZEP-0007.md) | Resumable Algebraic Effects Exploration | Deferred | Unscheduled | — |
| [ZEP-0008](./ZEP-0008.md) | Explicit Contract Derivation | Implemented | Shipped | 0.3.0 |
| [ZEP-0009](./ZEP-0009.md) | Public Member Discovery API | Implemented | Shipped | 0.3.0 |
| [ZEP-0010](./ZEP-0010.md) | Generic Admissibility Markers | Implemented | Shipped | 0.5.0 |
| [ZEP-0011](./ZEP-0011.md) | Standard Library Phase 1: I/O, Environment, and Time | Implemented | Shipped | 0.3.0 |
| [ZEP-0012](./ZEP-0012.md) | Pre-1.0 and Major-Series Versioning | Accepted | Not applicable | — |
| [ZEP-0013](./ZEP-0013.md) | Effect Subtyping and the Top Effect Type | Draft | Unscheduled | — |
| [ZEP-0014](./ZEP-0014.md) | Stable Generic Type Admissibility | Rejected | Unscheduled | — |
| [ZEP-0015](./ZEP-0015.md) | Runtime Reification of Generic Type Witnesses | Draft | Unscheduled | — |
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
| [ZEP-0031](./ZEP-0031.md) | Exploration: Declaration Decorators | Draft | Unscheduled | — |
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
| [ZEP-0054](./ZEP-0054.md) | Nominal Effect Payload Types | Draft | Unscheduled | — |
| [ZEP-0055](./ZEP-0055.md) | Effect-Kinded Generic Parameters | Implemented | Shipped | 0.5.0 |
| [ZEP-0056](./ZEP-0056.md) | Named Reusable Typed-Effect Handler Declarations | Implemented | Shipped | 0.5.0 |
| [ZEP-0057](./ZEP-0057.md) | First-Class Typed-Effect Handler Types | Implemented | Shipped | 0.5.0 |
| [ZEP-0058](./ZEP-0058.md) | Clause-Based For-Expressions and Lazy Yield | Implemented | Shipped | 0.5.0 |
| [ZEP-0059](./ZEP-0059.md) | Removing Contract Constructor Requirements | Implemented | Shipped | 0.5.0 |
| [ZEP-0060](./ZEP-0060.md) | Treating Arrays as Iterable Values | Draft | Unscheduled | — |
| [ZEP-0061](./ZEP-0061.md) | Java and External Interop Language Support | Accepted | Candidate | 0.6.0 |
| [ZEP-0062](./ZEP-0062.md) | Mutable-Type Admissibility for Generic Parameters | Implemented | Shipped | 0.5.0 |
| [ZEP-0063](./ZEP-0063.md) | Standard Library Testing APIs | Draft | Unscheduled | — |
| [ZEP-0064](./ZEP-0064.md) | Standard Library Logging APIs | Draft | Unscheduled | — |
| [ZEP-0065](./ZEP-0065.md) | Standard Library JSON Parser | Accepted | Candidate | 0.6.0 |
| [ZEP-0066](./ZEP-0066.md) | Standard Library TOML Parser | Draft | Unscheduled | — |
| [ZEP-0067](./ZEP-0067.md) | Standard Library XML Parser | Draft | Unscheduled | — |
| [ZEP-0068](./ZEP-0068.md) | Serialization Contracts for Zeron | Accepted | Candidate | 0.6.0 |
| [ZEP-0069](./ZEP-0069.md) | Transparent Data Classes | Accepted | Candidate | 0.6.0 |
| [ZEP-0070](./ZEP-0070.md) | Generic and Nominal Newtype Representations | Draft | Unscheduled | — |
| [ZEP-0071](./ZEP-0071.md) | Numeric Hierarchy and Context-Directed Literals | Implemented | Candidate | 0.6.0 |
| [ZEP-0072](./ZEP-0072.md) | Class-Scoped Static Functions as Companion Namespace Members | Draft | Unscheduled | — |
| [ZEP-0073](./ZEP-0073.md) | Documentation Comments for Declarations | Accepted | Candidate | 0.6.0 |
| [ZEP-0074](./ZEP-0074.md) | Numeric Literal Suffixes for Newtypes | Draft | Unscheduled | — |
| [ZEP-0075](./ZEP-0075.md) | General Contextual Numeric Literal Construction | Draft | Unscheduled | — |
| [ZEP-0076](./ZEP-0076.md) | Delegating Witness Implementations | Implemented | Unscheduled | — |
| [ZEP-0077](./ZEP-0077.md) | Delegating Higher-Kinded Implementations | Implemented | Unscheduled | — |
| [ZEP-0078](./ZEP-0078.md) | Default Method Implementations in Higher-Kinded Contracts | Implemented | Unscheduled | — |
| [ZEP-0079](./ZEP-0079.md) | Schema Declarations | Draft | Unscheduled | — |
| [ZEP-0080](./ZEP-0080.md) | Typed Code Models for Selected Expressions | Draft | Unscheduled | — |
| [ZEP-0081](./ZEP-0081.md) | Type-Safe Embedded DSL Support | Draft | Unscheduled | — |
| [ZEP-0082](./ZEP-0082.md) | Contextual Blocks and Receiver Lambdas | Draft | Unscheduled | — |
