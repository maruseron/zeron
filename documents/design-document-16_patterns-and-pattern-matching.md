# Patterns and Pattern Matching

## Status

Class-defined extractor patterns, nested patterns, and OR-patterns are implemented on top of
exhaustive matching of non-null sealed-contract values. Pattern declarations have typed outputs,
optional Boolean refutation conditions, and immutable receivers. Nested patterns short-circuit;
OR-patterns require equivalent binding sets. The implementation retains the legacy single-public-
property extraction spelling as a compatibility fallback when it does not resolve to a class pattern.

The direction is to let class designers name and implement deconstruction patterns. A pattern is an
explicit public class member with declared output names and types; callers match through that API
instead of selecting fields or properties by name. Patterns may be refutable with a `when`
condition. Calls marked `mut` are disallowed in pattern bodies, but Zeron does not promise
effect-freedom: non-mutating calls may still have effects.

## Implemented foundation

`match` is currently an expression over a non-null sealed-contract value. Each case names a
permitted class, may bind the whole value with `as`, and may extract one public property using the
existing shorthand:

```zeron
match (option) {
    case Some<T>.value(value) if value > 0 -> value;
    case Some<T>.value(_) as some -> 0;
    case None<T> -> defaultValue;
}
```

The resolver checks that case types are permitted and their applied arguments match the scrutinee.
Guards must be Boolean; a guard-failing case does not count toward exhaustiveness. An unguarded case
covers its permitted class, and a final unguarded `_` may cover the remaining classes. Matching a
nullable value is currently unsupported. See
[design-document-05_classes-and-contracts.md](design-document-05_classes-and-contracts.md) for the
existing sealed-contract rules.

The single-property form is a transitional feature. Under the extractor design, pattern invocation
names resolve to declared class patterns, not implicitly to arbitrary properties. The existing form
can remain as a compatibility spelling only where it is unambiguous; a declared pattern may not
share a name with a property on its class.

## Class-defined named patterns

A class may declare a named pattern with typed outputs and an optional refutation condition:

```zeron
class List<T> {
    contents: &Array<T>;

    public pattern head(x: T, xs: List<T>) when this.size > 0 {
        x = contents[0];
        xs = contents.stream().drop(1).toList();
    }
}
```

The pattern declaration defines an extraction API:

- `head` is the pattern's name.
- Parameters are output declarations, not input arguments. Their names are the names available to
  the matching arm, and their types specify the extracted values.
- The body computes each output exactly once on every successful path.
- Without `when`, the pattern is total: for every receiver of the class type it produces its outputs.
- With `when`, the pattern is refutable. The condition is evaluated first; false means the pattern
  does not match, and the output body is not run.
- The declaration is a non-mutating instance member. Calls to methods marked `mut` are disallowed
  from its condition and body. This prevents mutation through the receiver but is not a general
  purity or effect guarantee.
- A class pattern name must not collide with any property name in that class. Such a collision is
  rejected, rather than choosing property extraction or the pattern based on context.

Matching calls the pattern by name and binds its outputs:

```zeron
match (list) {
    case List<T>.head(x, xs) -> println("head: " + x);
    case _ -> println("empty");
}
```

Pattern arguments are positional. An identifier binds the corresponding output, `_` ignores it, and
a nested pattern recursively matches that output. The whole matched value may still be bound with
`as`. Outputs and aliases are immutable, scoped to their match arm, and available to the arm's guard
and expression.

Examples of nested and alternative patterns:

```zeron
match (lists) {
    case List<T>.head(_, List<T>.head(y, _)) -> println("second: " + y);
    case List<T>.head(x, _) | NonEmptyList<T>.head(x, _) if x > 0 -> println("positive");
    case _ -> println("no matching head");
}
```

The exact generic spelling can follow existing type-qualified match cases. Type arguments on
patterns are checked against the statically known matched type, and generic pattern outputs are
substituted from that type. Inference may fill omitted pattern type arguments when unambiguous;
otherwise they must be written explicitly.

## Refutation and guards

There are two distinct conditional checks:

1. The pattern declaration's `when` condition determines whether that named pattern matches its
   receiver. It runs before output extraction.
2. A match-arm `if` guard runs only after the complete root, nested, and alternative pattern
   succeeds and its bindings exist.

Failure at either stage continues with the next arm. Pattern declaration conditions and arm guards
must have type `Boolean`. A false pattern condition or guard does not throw and does not count toward
coverage. Exceptions or other effects from non-mutating calls are not converted to pattern failure;
they retain their ordinary Zeron behavior.

An unguarded class case with no refutable pattern operation covers that class. A case containing a
refutable named pattern or any refutable nested pattern does not establish coverage for the root
class. A match-arm guard likewise makes the arm non-covering. A total pattern with nested total
patterns can cover the root class. In particular, merely binding or ignoring outputs imposes no
additional condition.

For a sealed-contract scrutinee, every permitted class must be covered by an unguarded, irrefutable
case, unless a final unguarded `_` covers all remaining values. For open classes and non-sealed
contracts there is no closed-world proof of exhaustiveness; require a final unguarded `_`. This
preserves the expression-match guarantee without pretending a refutable extractor covers values it
can reject.

OR-pattern alternatives are tried left to right. They contribute the union of their coverage only
when the entire arm is unguarded. Every alternative must bind the same set of names, and corresponding
bindings must have compatible types. The common bindings are available to the guard and body.
An alternative succeeds at most once; after one matches, later alternatives in that OR-pattern are
not evaluated.

Duplicate or wholly subsumed unguarded cases should be diagnosed as unreachable. Guarded cases may
follow an earlier case for the same class because an earlier guard can fail. An unguarded irrefutable
case makes later cases that it subsumes unreachable. The initial coverage analysis is intentionally
structural and conservative: it does not prove arbitrary value predicates or infer totality from a
refutable nested pattern.

## Implemented grammar

```text
patternDeclaration ::= visibility "pattern" IDENTIFIER
                       "(" [patternOutput {"," patternOutput}] ")"
                       ["when" expression] block
patternOutput      ::= IDENTIFIER ":" type

matchArm           ::= "case" matchPattern ["if" expression] "->" expression ";"
matchPattern       ::= patternAlternative {"|" patternAlternative}
patternAlternative ::= "_"
                     | type ["." IDENTIFIER "(" [patternArgument
                         {"," patternArgument}] ")"] ["as" IDENTIFIER]
patternArgument    ::= "_" | IDENTIFIER | matchPattern
```

The type-qualified call form keeps pattern selection explicit and fits the current
`PermittedType.pattern(...)` match notation. A type-only case tests and covers the class without
extracting outputs. A named pattern invocation must resolve to a public pattern declared by that
class; it must not silently resolve to a property getter. The parser and resolver normalize the
legacy one-property spelling only as a compatibility path, subject to the no-collision rule.

Pattern bodies use ordinary block syntax and assignments to the declared output names. Output names
are local to the pattern body and are not class properties or mutable out-parameters. Definite
assignment analysis ensures each output is assigned exactly once on all completing paths. Returns,
breaks, and continues that bypass output assignment are invalid. If future language work permits
raising typed effects in this context, those effects propagate normally and are distinct from
refutation.

## Resolution and diagnostics

Pattern declarations are collected and checked with class members. Resolution validates output
types, declaration visibility, unique output names, and the no-name-collision rule against class
properties. Pattern bodies resolve with the class receiver available as immutable `this`; mutable
receiver views are not provided. Any resolved call to a `mut` method is rejected. Calls to
non-mutating methods are allowed without a promise that they are pure.

At each match use, resolution:

1. Resolves the scrutinee once and establishes its static nominal/applied type.
2. Verifies each class case is permitted for a sealed-contract scrutinee and that generic arguments
   agree with the matched type.
3. Resolves named patterns on the matched class, substitutes their output types, and checks argument
   count and nested-pattern compatibility.
4. Creates arm-local immutable bindings, checks aliases and guards, and verifies that every OR
   alternative has an equivalent binding environment.
5. Computes conservative coverage from class tests, total/refutable pattern declarations, nested
   patterns, OR alternatives, and guards.
6. Joins flow state and result types across arms using the existing match-expression rules.

Invalid pattern names, property-name collisions, incomplete output assignment, attempted `mut`
calls, incompatible nested patterns, unequal OR bindings, duplicate/subsumed cases, and non-
exhaustive matches are compile-time diagnostics.

## Compilation strategy

The scrutinee is evaluated exactly once. Lowering emits a decision tree:

1. Perform the root class test before invoking any class pattern.
2. Invoke the selected pattern once. A total pattern provides its outputs directly; a refutable
   pattern first evaluates its condition and branches to the next alternative/case on false.
3. Test nested patterns recursively, short-circuiting at the first failure.
4. For OR-patterns, branch alternatives left to right to a shared success block and shared binding
   slots; only a successful alternative populates that common environment.
5. Evaluate the arm guard after all pattern bindings exist. On false, continue with the next arm.
6. Evaluate the result expression and join at the match expression's result.

The implementation emits a public synthetic instance helper with a deterministic compiler-reserved
name. It returns an `Object[]` for outputs, or `null` when the pattern condition fails. The array is
an internal calling convention, not a source-level tuple value. Generic outputs are converted at
the call site using their resolved applied types.

This strategy evaluates the extractor condition and body once per attempted pattern. The scrutinee
is stable across alternatives; a failed nested pattern may cause a parent extractor output to be
read once and then a nested extractor to be attempted. Property getter caching is not assumed:
pattern bodies and non-mutating calls may have effects, so compiler reordering or eliding reads
would change behavior. Evaluation order is left-to-right and source-defined.

## Libraries and compatibility

Public pattern declarations are part of a class's source API. Library-index schema v19 preserves
their names, generic output signatures, and total/refutable status so consumers can type-check
patterns without library source files. Compiled classes expose the generated helper ABI, while
keeping helper names out of source-level name resolution.

The implementation prefers a declared pattern when one exists. If there is no declared pattern
with the requested name, the old single-public-property extraction form remains available for
compatibility. Pattern/property name collisions are rejected, so this fallback is unambiguous. A
later breaking release may remove the fallback after standard-library patterns replace property
matches.

## Implementation milestones

1. Completed: pattern declarations, AST representation, visibility, and pattern/property collision rules.
2. Completed: output assignment checking on control-flow paths and rejection of `mut` calls.
3. Completed: named-pattern resolution and helper emission for multiple outputs.
4. Completed: refutable `when` patterns and null-result failure lowering.
5. Completed: nested patterns, short-circuiting, OR bindings, and conservative coverage propagation.
6. Completed: public-pattern metadata in library-index schema v19 and separately compiled consumers.
7. Remaining: broaden negative tests for output assignment, visibility, mutability, and invalid OR
   bindings; decide when to migrate standard-library property patterns and remove the compatibility
   fallback.

Purity analysis, literal/value patterns, nullable matching, user-defined coverage proofs, general
algebraic data types, and source-level tuple outputs are outside this initial design.
