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
| [ZEP-0054](./ZEP-0054.md) | Nominal Effect Payload Types | Implemented | Shipped | 0.7.0 |
| [ZEP-0055](./ZEP-0055.md) | Effect-Kinded Generic Parameters | Superseded | Shipped | 0.5.0 |
| [ZEP-0056](./ZEP-0056.md) | Named Reusable Typed-Effect Handler Declarations | Implemented | Shipped | 0.5.0 |
| [ZEP-0057](./ZEP-0057.md) | First-Class Typed-Effect Handler Types | Implemented | Shipped | 0.5.0 |
| [ZEP-0058](./ZEP-0058.md) | Clause-Based For-Expressions and Lazy Yield | Implemented | Shipped | 0.5.0 |
| [ZEP-0059](./ZEP-0059.md) | Removing Contract Constructor Requirements | Implemented | Shipped | 0.5.0 |
| [ZEP-0060](./ZEP-0060.md) | Treating Arrays as Iterable Values | Implemented | Shipped | 0.6.0 |
| [ZEP-0061](./ZEP-0061.md) | Java and External Interop Language Support | Implemented | Shipped | 0.6.0 |
| [ZEP-0062](./ZEP-0062.md) | Mutable-Type Admissibility for Generic Parameters | Superseded | Shipped | 0.5.0 |
| [ZEP-0063](./ZEP-0063.md) | Standard Library Testing APIs | Draft | Unscheduled | — |
| [ZEP-0064](./ZEP-0064.md) | Standard Library Logging APIs | Draft | Unscheduled | — |
| [ZEP-0065](./ZEP-0065.md) | Standard Library JSON Parser | Implemented | Shipped | 0.6.0 |
| [ZEP-0066](./ZEP-0066.md) | Standard Library TOML Parser | Draft | Unscheduled | — |
| [ZEP-0067](./ZEP-0067.md) | Standard Library XML Parser | Draft | Unscheduled | — |
| [ZEP-0068](./ZEP-0068.md) | Serialization Contracts for Zeron | Accepted | Shipped | 0.6.0 |
| [ZEP-0069](./ZEP-0069.md) | Transparent Data Classes | Implemented | Shipped | 0.6.0 |
| [ZEP-0070](./ZEP-0070.md) | Generic and Nominal Newtype Representations | Accepted | Candidate | 0.8.0 |
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
| [ZEP-0091](./ZEP-0091.md) | Annotation Consumption | Accepted | Unscheduled | - |
| [ZEP-0092](./ZEP-0092.md) | Generated Serialization Operations | Implemented | Shipped | 0.6.0 |
| [ZEP-0093](./ZEP-0093.md) | Unified Property Identity and a Typed Code-Model API | Implemented | Shipped | 0.6.0 |
| [ZEP-0094](./ZEP-0094.md) | Collection Contracts | Implemented | Shipped | 0.6.0 |
| [ZEP-0095](./ZEP-0095.md) | Bytecode Quality and Evidence Inlining | Draft | Unscheduled | — |
| [ZEP-0096](./ZEP-0096.md) | Primitive Specialization for Generic Code | Draft | Unscheduled | — |
| [ZEP-0097](./ZEP-0097.md) | Annotation Target for Model Mapping Clauses | Accepted | Unscheduled | — |
| [ZEP-0098](./ZEP-0098.md) | Annotation Targets for Native Products, Sums, and Newtypes | Draft | Unscheduled | — |
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
| [ZEP-0110](./ZEP-0110.md) | Block-Bodied Handler Expressions | Draft | Unscheduled | — |
| [ZEP-0111](./ZEP-0111.md) | Witness-Dispatched Do-Notation | Implemented | Shipped | 0.7.0 |
| [ZEP-0112](./ZEP-0112.md) | Structured Concurrency on Virtual Threads | Draft | Unscheduled | — |
| [ZEP-0113](./ZEP-0113.md) | Declaration-Site Covariance for Sum and Product Types | Draft | Unscheduled | — |
| [ZEP-0114](./ZEP-0114.md) | Named Witness Declarations, Default Witnesses, and Explicit Witness Imports | Implemented | Shipped | 0.7.0 |
| [ZEP-0115](./ZEP-0115.md) | Warning-Severity Compiler Diagnostics | Implemented | Shipped | 0.7.0 |
| [ZEP-0116](./ZEP-0116.md) | Remaining Existential Opening and Escape Work | Implemented | Shipped | 0.7.0 |
| [ZEP-0117](./ZEP-0117.md) | Declarative Project Targets and Generated CLI/Manifest Reference | Implemented | Candidate | 0.8.0 |
| [ZEP-0118](./ZEP-0118.md) | Response-Typed Effects and Tail-Resumptive Handlers | Draft | Unscheduled | — |
| [ZEP-0119](./ZEP-0119.md) | One-Shot Continuations, Deep Handlers, and Cancellation | Draft | Unscheduled | — |
| [ZEP-0120](./ZEP-0120.md) | Async as an Effect and the Future/Task Surface | Draft | Unscheduled | — |
| [ZEP-0121](./ZEP-0121.md) | Multi-Shot Continuations and Backtracking | Deferred | Unscheduled | — |
| [ZEP-0122](./ZEP-0122.md) | Runtime Applied-Type Match Patterns | Implemented | Shipped | 0.7.0 |
| [ZEP-0123](./ZEP-0123.md) | Admissibility Clauses and Explicit Effect Responses | Draft | Unscheduled | — |
| [ZEP-0124](./ZEP-0124.md) | Nominal Static Contract Implementation | Accepted | Candidate | 0.8.0 |
| [ZEP-0125](./ZEP-0125.md) | Optional Higher-Kinded Implementation Blocks | Accepted | Candidate | 0.8.0 |
| [ZEP-0126](./ZEP-0126.md) | Compiler-Owned Main Entry-Point Contract | Accepted | Candidate | 0.8.0 |
| [ZEP-0127](./ZEP-0127.md) | Data Class Component Access and Positional Destructuring | Accepted | Candidate | 0.8.0 |
| [ZEP-0128](./ZEP-0128.md) | Sum-Type Parameter Bounds and Case-Local Type Parameters | Accepted | Candidate | 0.8.0 |
| [ZEP-0129](./ZEP-0129.md) | Unary Products versus Arbitrary-Valued Newtypes | Accepted | Candidate | 0.8.0 |
| [ZEP-0130](./ZEP-0130.md) | Standard-Library-Owned Intrinsic Declarations | Accepted | Candidate | 0.8.0 |
| [ZEP-0131](./ZEP-0131.md) | Chained Flow Refinement and Implicit Receiver Access | Accepted | Candidate | 0.8.0 |
| [ZEP-0132](./ZEP-0132.md) | Nullable Safe-Cast Correction | Accepted | Candidate | 0.8.0 |
| [ZEP-0133](./ZEP-0133.md) | Operator-Constraint Inference for Typeless Lambdas | Accepted | Candidate | 0.8.0 |
| [ZEP-0134](./ZEP-0134.md) | Invocation of Function-Valued Expressions | Draft | Candidate | 0.8.0 |

## Release bundles

The row-level release state above is authoritative. This section summarizes the current bundle plan;
see the [ZEP release plan](../developer_guides/zep-tracker-review.md) for the rationale and
sequencing.

### Bundle summary

| Bundle | Theme | Current state | Contents |
| --- | --- | --- | --- |
| `0.6.0` | Stabilization | Shipped | 24 rows |
| `0.7.0` | Type identity, reification, and type bounds | Shipped 2026-11-14 | 15 shipped; acceptance queue closed |
| `0.8.0` | Normalization and nominal implementation | Proposed; 13 candidates | ZEP-0070, ZEP-0117, ZEP-0124–ZEP-0134; ZEP-0132 is the immediate corrective |
| `0.9.0` | Effect control and response typing | Proposed | No candidates; ZEP-0118 and ZEP-0110 headline the bundle |

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

The bundle targets smaller normalization work rather than new control-flow semantics. Its thirteen
candidates are:

- [ZEP-0117](./ZEP-0117.md) — declarative project targets and generated CLI/manifest reference.
  A tooling and configuration change with no language, runtime, standard-library, compiled-library,
  or API-index compatibility impact.
- [ZEP-0124](./ZEP-0124.md) — nominal static contract implementation. Public class static functions
  and first-order `implementation` block entries satisfy static requirements directly.
- [ZEP-0125](./ZEP-0125.md) — optional higher-kinded implementation blocks. An `is` conformance plus
  public members supplies HKT operations when no explicit block is present.
- [ZEP-0126](./ZEP-0126.md) — compiler-owned `Main<A>` entry-point contract. A launchable `main`
  returns a closed entry value, and the generated launcher interprets it through `Main<A>` evidence
  after the user function returns.
- [ZEP-0127](./ZEP-0127.md) — data-class component access and positional destructuring. Canonical
  components are readable whenever the type is nameable, and positional patterns use only those
  components at the root and in nested positions.
- [ZEP-0070](./ZEP-0070.md) — generic and nominal newtype representations. A newtype may use a
  type parameter, a function type, a nominal value, or another admissible representation as its
  direct carrier; `.value` is removed and `this` is the representation inside extensions and
  witnesses.
- [ZEP-0128](./ZEP-0128.md) — sum-type parameter bounds and case-local type parameters. Sum
  parameters accept contract bounds, a case may introduce an existential parameter such as the `B`
  in `Bind<B>`, and unambiguous case constructors may omit the sum qualifier.
- [ZEP-0129](./ZEP-0129.md) — unary products versus arbitrary-valued newtypes. Products stay at two
  or more components; the single-value need is met by ZEP-0070's direct-representation newtypes
  rather than by legalizing a unary product.
- [ZEP-0130](./ZEP-0130.md) — standard-library-owned intrinsic declarations. Intrinsic types keep
  compiler-owned identity, while a broad `String` inventory—including `split`, `splitRegex`,
  `charAt`/`at`, case conversion, trimming, replacement, lines, parsing, and joining—is declared in
  canonical `zeron.lang` extension modules rather than in a second class or intrinsic ID.
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
  without introducing a temporary binding or a new runtime representation. The proposal is still
  `Draft`; its `0.8.0` candidacy records the intended target, not an accepted design.

ZEP-0124 and ZEP-0125 are implementation-coupled through one static/receiverless matcher, even
though they are independently decidable designs. They should be implemented and validated together
so the matcher, diagnostics, and separate-compilation behavior stay consistent. ZEP-0117 is
independent: it is the only candidate that changes the CLI and manifest surface, and it adds no
runtime ABI. ZEP-0126 is the only candidate that changes the entry-point rule; it depends on the
static-requirement work in ZEP-0124 for nominal `Main<A>` conformance, but its witness-only form can
ship independently. The bundle does not change `raise` or `handle` typing.

These thirteen are the current target; further small normalization items may join `0.8.0` as additional
candidates. The acceptance queue remains open. ZEP-0134 is the only draft row in the target set; it
must be accepted before implementation and is not a committed bundle item yet.

The `0.8.0` acceptance queue remains open for further normalization items. ZEP-0126, ZEP-0127,
and ZEP-0128–ZEP-0133 were reviewed there and are now `Accepted` / `Candidate`; ZEP-0070 was
promoted from the backlog. ZEP-0134 was added directly as a `Draft` / `Candidate` for `0.8.0`; its
acceptance review is still pending. All count in the bundle above.

### `0.9.0` — effect control and response typing

The effect-control bundle moves to `0.9.0` now that `0.8.0` is reserved for normalization. No row is
a candidate yet. The bundle is proposed around the effect-control work, and its headline proposals
remain `Draft`. ZEP-0107 is `Accepted` but has been removed from the program bundle and stays
`Unscheduled`. Its implementation would require the generic-effect propagation work that ZEP-0005
leaves deferred: applications containing type parameters, forwarding through generic calls, and
omitted handler arguments.

Proposed headline bundle: [ZEP-0118](./ZEP-0118.md) (response-typed effects and tail-resumptive
handlers), then [ZEP-0110](./ZEP-0110.md) (block-bodied handlers).
[ZEP-0111](./ZEP-0111.md) shipped in `0.7.0`; it is not part of `0.9.0`.

[ZEP-0112](./ZEP-0112.md) is an independent library track. [ZEP-0120](./ZEP-0120.md) depends on
ZEP-0118 and ZEP-0112. [ZEP-0119](./ZEP-0119.md) is prototype-gated, and
[ZEP-0121](./ZEP-0121.md) is `Deferred`.

ZEP-0118 and ZEP-0110 remain `Draft`; ZEP-0000 requires an Accepted design before either can be
marked `Candidate` or `Planned` for `0.9.0`.

[ZEP-0123](./ZEP-0123.md) is a `Draft` syntax companion to this bundle: it proposes the `admits`
clause and response-indexed effect markers, and its `effect<R>` form depends on ZEP-0118. It
remains `Unscheduled` and is not a candidate.
