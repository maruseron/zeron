# Patterns and Pattern Matching

## Status

Class-defined extractor patterns, nested patterns, OR-patterns, literal patterns, and built-in
product destructuring are implemented. Match roots must be non-null sealed-contract values,
products, supported literal types, or concrete non-open classes matched with a literal somewhere
in the patterns. Direct class roots without a literal test are not yet implemented; the accepted
opt-in design is described below. Pattern declarations have typed outputs, optional Boolean
refutation conditions, and immutable receivers. Nested patterns short-circuit; OR-patterns require
equivalent binding sets. The implementation retains the legacy single-public-property extraction
spelling as a compatibility fallback when it does not resolve to a class pattern.

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
    case Option.Some(value) if value > 0 -> value;
    case Option.Some(_) -> 0;
    case Option.None() -> defaultValue;
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

## Root eligibility and literal matching

Native sum types participate through their generated sealed-contract representation. A match root
must be justified by one of these modes:

1. **Closed-type matching.** A sealed contract or algebraic data type has a compiler-known set of
   alternatives. Type and case patterns may handle those alternatives, and coverage is checked
   against the closed set. A type parameter is eligible for this mode only when its applicable
   bound itself proves the same closed set; an unbounded or non-sealed bound is insufficient.
2. **Product matching.** A product type has exactly one constructor and a compiler-known ordered
   component list. A total product pattern or wildcard establishes coverage.
3. **Literal matching.** A root with a concrete, non-open static type may be matched by literal
   values, either directly or at leaves reached through a destructuring pattern. This is an explicit
   value test, not general matching over the root's runtime type. `Any`, non-sealed contracts, and
   type variables whose bounds do not establish a closed type are not eligible for this mode.

Literal patterns are restricted to integer, Boolean, and String literals of the statically known
type. They do not include arbitrary expressions, variable references, or implicit calls to `Eq<T>`
witnesses. Integer and Boolean comparisons use primitive value equality; String comparisons use
`String.equals`. Floating-point literals remain deferred until cases such as `NaN` have explicit
semantics. A wildcard or total destructuring pattern is required to complete a literal match over
an unbounded domain such as integers or strings. Both Boolean literals may establish coverage of
the finite Boolean domain, provided neither arm is guarded.

This model does not admit open roots merely because a wildcard is present: a wildcard completes a
literal match over an eligible concrete root, while it cannot make `Any` or a non-sealed contract a
valid root.

## Built-in product destructuring

Product types have one statically known constructor and may be destructured positionally using the
product type name:

```zeron
type Point = (x: Int, y: Int);

match (point) {
    case Point(0, 0) -> "origin";
    case Point(x, y) -> "other point";
}
```

The argument count and types must match the product components in declaration order. Each argument
may be a binding, `_`, a literal, or another supported nested pattern. Component getters are
evaluated left to right, and a failed nested pattern proceeds to the next arm. A product case
contributes root coverage only when every nested component pattern is total; a refutable literal
component does not cover the product by itself. The product root nevertheless permits a final
wildcard. Product patterns are also available recursively in sum payloads and class-pattern
outputs.

Concrete class roots use class-defined patterns for destructuring; at least one literal test must
occur in the match to opt into literal mode. Refutable patterns, including those containing a
literal, do not count as total class coverage. These rules keep destructuring distinct from an
open-ended runtime type test.

## Opting classes into root matching

**Accepted design; implementation pending.** A concrete class opts into direct root matching by
declaring at least one public named pattern. This declaration is the opt-in; no additional
`matchable` modifier is needed. The class remains a single concrete type, not a closed family of
runtime alternatives. `Any`, non-sealed contracts, and unconstrained type variables remain invalid
roots, even when a wildcard is present.

For an opted-in class root, cases invoke public named patterns declared by that class:

```zeron
class Point {
    x: Int;
    y: Int;

    public pattern coordinates(horizontal: Int, vertical: Int) {
        horizontal = this.x;
        vertical = this.y;
    }
}

match (point) {
    case Point.coordinates(0, vertical) -> "on the y-axis";
    case Point.coordinates(horizontal, vertical) -> "somewhere else";
}
```

The root is exhaustive only when covered by an unguarded wildcard or an unguarded total pattern.
A declared pattern without `when` is total at the extraction step, but nested patterns can still
make a case refutable: `Point.coordinates(0, vertical)` does not cover every `Point`. A pattern with
a `when` condition, a refutable nested pattern, or an arm guard does not contribute total coverage.
The compiler does not infer that multiple refutable patterns form a complete partition; users need
a total pattern or wildcard as the fallback.

This direct-root mode is separate from the existing literal mode, which admits a concrete class
root only when a supported literal is tested. It is also separate from sealed-contract matching:
the class opt-in does not establish a set of subclasses or permit matching an open contract. The
design assumes a concrete class root denotes that exact class; if class inheritance is introduced,
the root eligibility rule must be revisited to account for subclasses.

## Class-defined named patterns

Named patterns are currently usable in closed-root matches. The direct class-root examples in
this section illustrate the accepted opt-in design above and will become valid when that pending
root mode is implemented.

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

## Generic applications and JVM erasure

**Accepted direction: generic pattern arguments are static-only.** A pattern may use generic
arguments to check compatibility with the scrutinee's statically known applied type and to
substitute pattern output types. They are not runtime discriminators: generated tests can check the
erased class, not whether a value is a particular application such as `Box<Int>` rather than
`Box<String>`.

A pattern such as `Box<Int>` must not narrow a scrutinee whose static type is only `Any` or another
erased/unknown generic view to `Box<Int>`. The resolver must reject that use unless the scrutinee's
static type entails the required applied type; mere runtime-test compatibility or an upcast to a
broader covariant view is not enough. For example, a statically known `Box<Int>` can be matched as
`Box<Int>`, but `Any` or `Box<out Any?>` cannot be narrowed to `Box<Int>` by that pattern. The
resolver must not insert an unchecked cast or present the generic argument as runtime-verified. This
rule is about static evidence, not merely whether a raw JVM `instanceof Box` can be emitted.

An explicit safe projected view may be used when the scrutinee is unknown, for example
`Box<out Any?>`. The runtime test still checks only the raw `Box` class; the arm binding keeps the
projected type, and member access remains restricted by the projection rules. Such a pattern does
not capture or reveal the concrete type argument. The resolver must accept it only when the source
type can safely be viewed through that projection.

Two cases that differ only in generic arguments have the same erased runtime test and are not
distinct alternatives. Such cases must be diagnosed as duplicate or unreachable unless an
independent runtime condition, such as a value subpattern or guard, distinguishes them. A guard or
refutable value pattern can distinguish values; the generic spelling by itself cannot. Coverage
analysis must remain conservative and must not count erased generic arguments as separate sealed
alternatives.

This contract does not require general runtime reification. A future feature that wants to inspect
applied generic identity at runtime must define and propagate an explicit type-identity witness,
including its ABI and separate-compilation behavior; it is outside the current pattern-matching
contract. See [ZEP-0015](zeps/ZEP-0015.md) for the separate reification exploration. Generic
`is`/`as?` targets with static application proofs are proposed separately in
[ZEP-0053](zeps/ZEP-0053.md).

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
case, unless a final unguarded `_` covers all remaining values. An open class or non-sealed contract
is not an eligible root, even with a final `_`; wildcard coverage does not make an open root valid.
Concrete non-open class roots require either a supported literal test under the current literal mode
or the class opt-in described above.

OR-pattern alternatives are tried left to right. They contribute the union of their coverage only
when the entire arm is unguarded. Every alternative must bind the same set of names, and corresponding
bindings must have compatible types. The common bindings are available to the guard and body.
An alternative succeeds at most once; after one matches, later alternatives in that OR-pattern are
not evaluated.

Duplicate or wholly subsumed unguarded cases should be diagnosed as unreachable, including cases
that differ only by generic arguments erased to the same runtime test. Guarded cases may follow an
earlier case for the same class because an earlier guard can fail. An unguarded irrefutable case
makes later cases that it subsumes unreachable. The initial coverage analysis is intentionally
structural and conservative: it does not prove arbitrary value predicates or infer totality from a
refutable nested pattern.

## Implemented grammar

```ebnf
patternDeclaration   ::= visibility "pattern" IDENTIFIER
                       "(" [patternOutput {"," patternOutput}] ")"
                       ["when" expression] block
patternOutput        ::= IDENTIFIER ":" type

matchArm             ::= "case" matchCasePattern ["if" expression] "->" expression ";"
matchCasePattern     ::= matchCaseAlternative {"|" matchCaseAlternative}
matchCaseAlternative ::= IDENTIFIER "(" [patternArgument {"," patternArgument}] ")"
                       | patternAlternative
matchPattern         ::= patternAlternative {"|" patternAlternative}
patternAlternative   ::= "_"
                       | literalPattern ["as" IDENTIFIER]
                       | type ["." IDENTIFIER "(" [patternArgument
                         {"," patternArgument}] ")" | "(" [patternArgument
                         {"," patternArgument}] ")"] ["as" IDENTIFIER]
patternArgument      ::= "_" | IDENTIFIER | literalPattern ["as" IDENTIFIER] | matchPattern
iteralPattern     :  := INT | "-" INT | STRING | "true" | "false"
```

The type-qualified call form keeps class-pattern selection explicit and fits the current
`PermittedType.pattern(...)` match notation. A call-shaped product pattern uses
`ProductType(component, ...)`; at the match root, an unqualified `Name(...)` is resolved against the
scrutinee as either a sum case or product type, never through global name lookup. A type-only case
tests and covers the class without extracting outputs. A named pattern invocation must resolve to a
public pattern declared by that class; it must not silently resolve to a property getter. The parser
and resolver normalize the legacy one-property spelling only as a compatibility path, subject to
the no-collision rule.

Root cases of a nominal sum may use the case name without the sum type:

```zeron
match (list) {
    case Cons(head, tail) -> head;
    case Nil() -> 0;
}
```

These names are resolved only against the scrutinee's sum declaration; they are not searched
globally. The qualified form, such as `List.Cons(head, tail)`, remains valid. This shorthand is
currently limited to root cases in `match` expressions, not nested patterns, `matches` conditions,
or effect handlers.

Literal patterns and built-in product patterns are likewise limited to `match` expressions;
`matches` conditions and effect handlers retain their existing pattern syntax.

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
2. Verifies each class case is permitted for a sealed-contract scrutinee, or that a direct class
   root has opted in through a public named pattern; generic arguments must agree with the matched
   type.
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

Public pattern declarations are part of a class's source API. The API index preserves their names,
generic output signatures, and total/refutable status so consumers can type-check patterns without
library source files. The current API-index schema is version 6. Compiled classes expose the
generated helper ABI, while keeping helper names out of source-level name resolution.

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
6. Completed: public-pattern metadata and separately compiled consumers; metadata is preserved in
   the current API-index schema.
7. Completed: supported literal patterns and built-in product destructuring with conservative
   coverage.
8. Remaining: broaden negative tests for output assignment, visibility, mutability, and invalid OR
   bindings; decide when to migrate standard-library property patterns and remove the compatibility
   fallback.
9. Planned: allow a concrete class with at least one public named pattern to opt into direct
   class-root matching, with coverage limited to total patterns and wildcards.

Purity analysis, arbitrary value patterns, nullable matching, user-defined coverage proofs, general
algebraic data types beyond the closed cases described above, and source-level tuple outputs are
outside this initial design.
