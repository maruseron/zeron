# Flow Typing, Type Tests, and Casts: Design and Roadmap

## Purpose

This note records the current design and remaining work for flow-sensitive type refinement, runtime
type tests, and explicit casts. It builds on nullable values and the separation between binding
reassignment and reference mutation in [design-document-04_mutability.md](design-document-04_mutability.md),
plus the class and contract model in [design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md).

Flow typing uses facts established by control flow to refine the type of a stable binding at a
particular program point. It does not change the binding's declared type and does not require
first-class union types. Flow typing is static type checking, not general error handling.

## Current Boundary

- `T?`, the internal `Null` type, and widening from `T` to `T?` are implemented. Flow refinement
  for direct local/parameter checks is implemented in statement-form branches and `if` expressions.
- `Any` is implemented as a non-null top type. Non-null values widen to `Any`; nullable values and
  `null` widen to `Any?`. Supported `is` tests narrow `Any` and `Any?` on proven paths.
- `Unit` uses a shared generated `UnitValue` singleton in `dist`, so `Unit` remains distinct from
  `null` and can widen to `Any`.
- Classes are final and can conform to multiple contracts. Invariant generic classes/contracts are
  supported with raw JVM erasure. There is no class inheritance, general subtype lattice, or
  source-level union type; parameterized runtime type tests remain unsupported.
- Arrays lower to `Object[]`; their element type is not represented in the JVM array class. A runtime
  test cannot validate `Array<Int>` versus `Array<String>` from the array object alone.
- Expression-level `is Type`, checked `as T`, safe `as? T`, and short-circuit `and`/`or`/`not`
  resolution and lowering are implemented for supported runtime-testable types.
- `if` expressions have parser, resolver, flow, and value-producing JVM lowering. Each branch is
  resolved under its condition facts; outgoing facts join across both branches. Loop flow tracks
  condition and `break` exits, treats `continue` as a backedge, and computes loop-header facts to a
  fixed point over preheader, fallthrough, and continue edges. Break states join with normal exits;
  `for` retains its zero-iteration path.
- Zeron has no source-level exception handling. Runtime exceptions from generated code propagate to
  the host caller; this is documented as a runtime boundary, not a recovery mechanism, in
  [design-document-00.md](design-document-00.md).

## Type-Testable Values

A runtime type test needs a target whose runtime identity can actually be distinguished. Implemented
targets are built-in scalar types, `Unit`, `String`, `Any`, declared classes, and contracts. Primitive
values widen to `Any` by boxing, so tests such as `value is Int` check the corresponding wrapper and
refine the expression back to the primitive source type.

A test such as `value is Person` can narrow an `Any` or contract-typed value to a final class. A test
against a contract checks the generated JVM interface. Class and contract type identity remains
source-level identity; a generated binary name is only the runtime representation.

Parameterized runtime tests are deferred. In particular, `value is Array<Int>` must not claim to
check an element type that the JVM array class does not retain. Generic nominal types and function
shapes need separate reifiability rules before they become valid test targets.

## Implemented Syntax

The parser implements `is Type` and `is not Type` at comparison precedence and casts at unary/cast
precedence. `is not Type` is parsed as `not (value is Type)`, using the same Boolean and flow
semantics. `as` is a keyword; `?` selects a safe cast:

```text
equality   ::= typeTest { ("==" | "!=") typeTest }
typeTest   ::= comparison [ "is" [ "not" ] Type ]
comparison ::= cast { (">" | ">=" | "<" | "<=") cast }
cast       ::= unary { "as" [ "?" ] Type }
```

Type tests bind more tightly than equality. Logical `and`/`or` remain above equality and use
short-circuit evaluation. Casts may be chained; each applies to the preceding expression.

## Runtime Type Tests and Casts

### Type test: `is`

`value is T` returns `Boolean` and does not throw. A null value fails any non-null target type test.
When the expression is a direct stable variable read, the true outcome refines that binding to
non-null `T`. The source binding is unchanged; outside a controlling flow region, its declared type
still applies.

Flow refinement recognizes direct variable reads (optionally parenthesized). It does not refine a
property, array slot, or function-call result because reevaluating that expression may produce a
different value. A test of `object.field` can still return a Boolean, but does not smart-cast a later
read of that field.

False outcomes may initially retain only facts already known on entry. `is not T` swaps the true and
false flow outcomes of `is T`; it does not infer a negative type fact or prove non-null, since null
also fails `is T`.

### Checked cast: `as`

`value as T` evaluates to `T` after a runtime check. A non-null source with an incompatible runtime
type fails with `ClassCastException`, which propagates to the host because Zeron has no catch
construct. For a nullable source and non-null target, require a prior non-null flow proof rather than
silently erasing nullability. A successful cast refines only the cast expression; it does not
permanently change the source binding's flow state.

The compiler emits a JVM `checkcast`; a prior `is T` proof does not change source-level semantics.
Eliminating redundant checks is a future optimization.

### Safe cast: `as?`

`value as? T` returns `T?`; it yields null if the source is null or has an incompatible runtime
type. The compiler emits a runtime `instanceof` check and a null-producing failure path. This is a
non-throwing alternative suitable for code that wants absence rather than an uncatchable cast
failure. It intentionally does not distinguish “input was null” from “type did not match”; a future
result/error type can represent that distinction if needed.

Neither cast changes the flow facts for the original binding. For example, after `let candidate =
value as? Person`, `candidate` has type `Person?`, while `value` retains its declared type unless
another condition proves otherwise. Targets must be runtime-testable: built-ins, classes, contracts,
and `Any` are supported; generic, array-element, and function-shape targets are rejected.

## Flow Facts

### Fact model

Keep declared binding types in `TypeDescriptor` and symbol metadata. Maintain a separate resolver
flow state keyed by stable binding identity. Each binding's flow fact records:

- nullness: unknown, definitely null, or definitely non-null;
- possible runtime types when narrowed by successful type tests.

A direct check of `value != null` removes null from its possible values. A successful `value is T`
intersects the possible runtime types with `T`. A disjunction can retain multiple alternatives,
such as `{T, R}`, without creating a user-visible `T | R` type.

At a read, use the current fact to determine the expression's effective type. If alternatives remain,
an operation is legal only when it is valid for every alternative. A common contract implemented by
all alternatives is a useful shared type; otherwise retain a flow-only alternative set for later
tests and only allow operations common to all possibilities. Assignment to `Any` is always safe.

Binding identity is essential: shadowed names must not share facts. The implementation keys facts by
the identity of each binding's declaration token, not source spelling or name-based `Bind.equals`.

### Branches and joins

Condition analysis returns separate states for true and false outcomes. An `if` statement resolves
both branches with their corresponding state. At the join, retain only facts valid on every normally
completing path. An absent `else` contributes the incoming false-path state.

A `return` contributes no state to a later join. This permits guard clauses:

```zeron
fn incrementIfPresent(value: Int?): Int {
    if (value == null) return 0;
    return value + 1;
}
```

The continuation has only the non-null path. Track normal completion separately from a type such as
`Never`; do not overload `Never` to represent resolver reachability.

Statement-form `if` branches and value-producing `if` expressions resolve their branches under the
corresponding true/false facts. An `if` expression joins the facts from both result branches after
resolving them; facts established in only one branch do not escape the expression.

### Boolean composition

Short-circuit operators use runtime lowering and matching condition-transfer rules:

- `A and B`: evaluate `B` under `A`'s true facts. The true outcome combines both true paths. The
  false outcome joins the paths where `A` is false and where `A` is true but `B` is false.
- `A or B`: evaluate `B` under `A`'s false facts. The true outcome joins the paths where `A` is
  true and where `A` is false but `B` is true. The false outcome requires both operands false.
- `not A`: swap `A`'s true and false states.

For example, `value != null and value is Person` tests the runtime type only after the non-null
proof. For `value is Person or value is Robot`, the true state carries the flow-only alternatives
`{Person, Robot}`. It permits common operations only; it does not create a declared union type.

The implementation preserves left-to-right short-circuit evaluation. Treating `and`/`or` as
ordinary eager Boolean operators would not implement these semantics. OR joins successful type tests
as flow-only alternatives; when all alternatives share a contract, that contract is the effective
type in the branch. No declared union type is created.

## Stability, Mutation, and Scope

- Refine local bindings and parameters. Mutable locals may be refined, but any assignment invalidates
  their facts before subsequent reads.
- A new null or type check may establish a fact after an assignment.
- Do not refine mutable globals in the first version; a function call may change them.
- Immutable local bindings and parameters may retain facts across calls because calls cannot rebind
  those names. Mutation through a referenced object (`&T`) does not rebind the local name.
- Do not refine properties, array slots, or call results across separate evaluations; aliases or
  calls may change the value.
- Do not carry facts into a lambda body in the first version. The body may run later; closure-specific
  refinement can be designed separately.
- `while`/`until` normal exits use condition-false facts joined across the fixed-point loop header and
  reachable `break` states. `continue` and normal body completion contribute backedges; nested loop
  transfers target the innermost loop. `for` includes a zero-iteration exit and joins reachable
  breaks. Infinite `loop` statements have no normal exit unless a reachable `break` exists.
- Loop facts converge over the finite nullability/type-alternative domain. Assignments kill facts at
  the assignment point; facts are not blanket-invalidated merely because a binding is written in a
  loop.
- At lexical scope exit, discard facts for bindings declared in that scope.

## Exception Boundary

Flow typing and `is` tests are static/type-directed and do not add an exception system. `as?` is
non-throwing. A failed checked `as` uses a JVM runtime exception and propagates to the host, like
other generated-code runtime failures. Zeron source code cannot catch it. If the language later
requires recoverable cast failures, model those as a result/option value or add a separately designed
error-handling feature rather than quietly making casts catchable.

## Implementation Roadmap

1. **Initial flow/type-test slice: implemented.** Direct null checks and `is` tests refine stable
  local/parameter reads in statement-form branches and `if` expressions. `and`, `or`, and `not`
  short-circuit and transfer true/false facts; OR alternatives remain flow-only and can use a shared
  contract. `if` expressions lower both branch values to their resolved common type.
2. **Checked and safe casts: implemented.** `as T` emits a checked JVM cast with host-propagating
  failure; `as? T` returns null on mismatch or null. Nullable checked sources require prior flow
  proof, and casts do not persistently refine their source binding.
3. **Loop fixed-point refinement: implemented.** Loop headers join preheader and reachable backedge
  states until stable; exits join condition-false and reachable breaks, with `for` preserving its
  zero-iteration edge. Broader closure-flow behavior remains deferred. Mutable globals, properties,
  and array slots remain conservative unless their stability can be established.

## Acceptance Criteria

### Acceptance Criteria for the Initial Slice

- Null comparisons and `is` tests refine only stable local/parameter reads on proven statement and
  `if`-expression branch paths.
- Guard clauses refine the continuation when the null path returns.
- `and`, `or`, and `not` preserve short-circuit evaluation and matching true/false flow facts.
- `x is T or x is R` keeps alternatives internally and exposes only operations valid for all
  alternatives, including a common contract when available.
- Assignments invalidate facts; shadowed bindings never share them. Loop exits join condition-false
  and reachable break states, `for` includes a zero-iteration path, and loop-written bindings lose
  refinements. Globals, properties, array slots, and lambda bodies remain conservative.
- Declared types and JVM identity remain unchanged by flow facts; no source-level union type or
  exception handling is introduced.

### Remaining Cast Coverage

- Runtime coverage across Unit, all primitive checked/safe cast pairs, classes, contracts, nulls, and
  checked failure propagation is implemented.
- Keep erased generic, array-element, and function-shape targets rejected unless a sound runtime check
  exists.
- Consider redundant `checkcast` elimination as an optimization, not a semantic requirement.
