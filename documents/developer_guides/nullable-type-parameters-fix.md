# Fix: unchecked dereference of `T: nullable` values in generic bodies

| Field | Value |
| ----- | ----- |
| Kind | Bug fix (type checker); not a language feature |
| Scope | Checking of generic function, method, and class bodies |
| Affects | Resolver / type checker, standard-library audit |
| Does not affect | Syntax, call-site type inference, runtime representation, library index schema |

## Summary

A type parameter declared `T: nullable` admits `null`-containing arguments such as `String?`. Inside
the generic body, a value of type `T` must therefore be treated as possibly null. The checker must
not let such a value be dereferenced, passed where a non-null type is required, or used with a
bound's operations until it has been checked against `null`. Without this rule, a generic body can
compile and then fail with a `NullPointerException` at run time, because generics are erased and a
`null` crosses the call boundary unnoticed.

This note describes the required behavior, the changes needed to get there, and the tests that
validate it.

## Problem

`nullable` is an admissibility marker. It widens the set of types a caller may supply for `T`, so
that `Option<String?>` or `identity<String?>(null)` is legal. It does not rewrite the occurrences of
`T` in a signature. That is correct, and must stay so: in

```zeron
fn firstOf<T: nullable>(values: Array<T>): T
```

the result is `String` when `T = String` and `String?` when `T = String?`. If every `T` were
projected to `T?`, the first case would lose its non-null guarantee.

The defect is in the body, not the signature. Inside the body the checker has to decide what it
knows about a `T` value. If it treats `T` like an ordinary non-null type, this compiles:

```zeron
fn describe<T: nullable>(value: T): String = value.toString();
```

and `describe<String?>(null)` fails at run time. The same gap applies to three other uses of a
`T: nullable` value:

- a call through a contract bound, as in `T: nullable + Comparable<T>`, where the receiver may be
  `null`;
- passing the value to a parameter whose type does not admit `null`, including `Any`;
- using the value as an operand.

Test 1 below must be run first. It is expected to compile on the current checker; if it is already
rejected, the problem is limited to the bound, argument, and operand cases.

## Required behavior

### Signatures

Type-argument substitution at a call site is unchanged. `T` is replaced exactly by the argument the
caller supplies, nullable or not. A type parameter without the `nullable` marker still rejects
`null`-containing arguments.

### Values of type `T: nullable` inside the body

Inside the body, a value whose type is a `nullable` type parameter is **possibly null and opaque**.
The checker applies these rules:

1. **Allowed without a check.**
   - Passing it as an argument to a parameter whose type is that same `T`, or `T?`, or `Any?`.
   - Returning it where `T` or `T?` is expected.
   - Storing it in a field, local, array, or generic container of `T`.
   - Comparing it with `null` using `==` or `!=`.
   - Using it as the receiver of `?.`, in which case the result is nullable.
   - Using the explicit assertion `!!`, which is the caller's decision to accept the failure.
2. **Rejected until the value is known non-null.**
   - Member access and method calls, including `toString()`.
   - Operations required by a contract bound, such as `compareTo` for `Comparable<T>`.
   - Operators that take it as an operand.
   - Passing it to a parameter whose type does not admit `null`, including `Any`.
   - Iterating over it or indexing it when its bound makes that possible.
3. **Refinement.** Existing flow refinement applies. After `value != null`, inside the matching
   branch, or after an early exit on `value == null`, the value is non-null for the rest of that
   flow. In that region the bound's operations and the members of `Any` are available. The refined
   type is internal; the source language has no spelling for it, and none is added.
4. **Invalidation.** Refinement of a mutable local ends at the next assignment to that local.
5. **Bounds.** For `T: nullable + C<T>`, the operations of `C` apply to the non-null part of `T`. A
   body that wants to support `null` elements must handle them explicitly.

### Diagnostics

Add one diagnostic for rules 2 and 5, with a message in the form: "`value` has type `T`, which may
be null. Check it against `null` before using it." Allocate a code with the other type-checking
diagnostics. The diagnostic should point at the dereference, not at the declaration of `T`.

A comparison of a value whose type does not admit `null` with `null` (for example `x == null` where
`x: A` and `A: mut`) is always false. Report it as a warning. This is a lint and is not required by
the fix.

### Runtime

No run-time null checks are added. The fix is static only. Generic code continues to compile to
erased `Object` operations.

## Changes required

1. **Type representation.** Carry the admissibility marker on the type-parameter type, so the
   checker can tell a `nullable` parameter from one that is not. If the representation already has
   this information for call-site checking, reuse it.
2. **Member and bound lookup.** When the receiver type is a `nullable` type parameter, require a
   non-null refinement before resolving members or bound operations. Report the new diagnostic when
   it is missing.
3. **Assignability.** Treat a `nullable` type parameter as assignable to `T`, `T?`, and `Any?` only.
   Make it unassignable to `Any` and to any other type that does not admit `null`.
4. **Flow analysis.** Extend the existing null-refinement logic to values whose type is a type
   parameter, including the early-exit and branch forms, and invalidate on assignment.
5. **Operators.** Apply the same rule to unary and binary operators and to `match` scrutinees.
6. **Standard library.** Audit every declaration that uses `nullable` and fix each body that now
   fails. Start with these:
   - `Sortable.sorted` and `InPlaceSortable.sort`, whose bounds are `nullable + mut + Comparable<T>`.
     Comparing elements with `compareTo` dereferences each one. Remove `nullable` from these bounds
     if `null` has no meaningful order. If a null policy is wanted, define it (for example nulls
     first) and implement it explicitly.
   - Bodies of `Option`, `Result`, `Step`, `Iterable`, and `Iterator`, which are all `nullable` and
     mostly move values. They should pass unchanged, and any that call a member must be checked.
   - Any other contract that combines `nullable` with a behavioral bound.
7. **Rollout.** Ship in two steps. First report the diagnostic as a warning and run the audit
   against the standard library and the test suite. Then promote it to an error once the audit
   passes. Nothing in the library index changes, because the check applies to bodies when they are
   compiled; consumers of an already compiled library are unaffected.

## Non-goals

- A source spelling for the refined type, such as `T & Any`.
- Changing how type arguments are inferred or substituted at call sites.
- Making `nullable` imply a null-safe equality or ordering for bound operations.
- Run-time checks, boxing, or changes to how `null` is represented.
- The `x == null` lint for non-nullable types, which may ship later.

## Test cases

The first four tests establish the problem and the basic rule. Run test 1 before any change.

### Unchecked use

```zeron
// 1. Member access on an unrefined value.            Expected: error
fn describe<T: nullable>(value: T): String = value.toString();

// 2. Branch refinement.                              Expected: compiles
fn describeBranch<T: nullable>(value: T): String =
    if (value != null) then value.toString() else "null";

// 3. Early exit refinement.                          Expected: compiles
fn describeEarly<T: nullable>(value: T): String {
    if (value == null) return "null";
    return value.toString();
}

// 4. Use before the check, even in the same function.      Expected: error
fn checkedTooLate<T: nullable>(value: T): String {
    let text = value.toString();
    if (value == null) return "null";
    return text;
}
```

### Moving values

```zeron
// 5. Identity and wrapping move the value without using it.   Expected: compiles
fn identityOf<T: nullable>(value: T): T = value;
fn wrap<T: nullable>(value: T): Option<T> = Option.Some(value);

// 6. Safe navigation yields a nullable result.               Expected: compiles
fn safeText<T: nullable>(value: T): String? = value?.toString();
```

### Parameter types

```zeron
fn takesAny(value: Any): String = "ok";
fn takesAnyOrNull(value: Any?): String = "ok";

// 7. Passing to a non-null parameter.                 Expected: error
fn leak<T: nullable>(value: T): String = takesAny(value);

// 8. Passing to a nullable parameter.                 Expected: compiles
fn pass<T: nullable>(value: T): String = takesAnyOrNull(value);

// 9. Passing after a check.                           Expected: compiles
fn passChecked<T: nullable>(value: T): String =
    if (value != null) then takesAny(value) else "null";
```

### Contract bounds

Use the actual comparison method of `Comparable<T>` in place of `compareTo` if it is named
differently.

```zeron
// 10. Bound operation on an unrefined receiver.       Expected: error
fn order<T: nullable + Comparable<T>>(left: T, right: T): Int =
    left.compareTo(right);

// 11. Bound operation after checks on both values.    Expected: compiles
fn orderChecked<T: nullable + Comparable<T>>(left: T, right: T): Int {
    if (left == null or right == null) return 0;
    return left.compareTo(right);
}
```

### Call sites are unchanged

```zeron
fn firstOf<T: nullable>(values: Array<T>): T = values[0];

// 12. T = String keeps a non-null result.              Expected: compiles
let name: String = firstOf(["Ada"]);

// 13. T = String? keeps a nullable result.             Expected: error
let nullableNames: Array<String?> = ["Ada", null];
let bad: String = firstOf(nullableNames);

// 14. A parameter without the marker rejects null.      Expected: error
fn only<A: mut>(value: A): A = value;
let rejected = only(null);

// 15. A nullable parameter accepts null.                Expected: compiles
let accepted = identityOf<String?>(null);
```

### Refinement lifetime

```zeron
// 16. Assignment invalidates the refinement.             Expected: error
fn reassigned<T: nullable>(value: T, replacement: T): String {
    let mut current = value;
    if (current == null) return "null";
    current = replacement;
    return current.toString();
}
```

### Library and compatibility

17. **Standard library under the warning.** Compile the standard library with the diagnostic enabled
    as a warning. Record every site it reports. After the audit, the same compilation produces no
    warnings, and the diagnostic is promoted to an error.
18. **Separate compilation.** Compile a library that contains tests 5 and 8, then call those functions
    from a separate project with `String` and `String?` arguments. Results and library-index contents
    are identical to before the change.
19. **Existing code.** The existing test suite passes unchanged except for tests that dereference
    unchecked `nullable` values. Each such test is rewritten with an explicit check, or expected to
    produce the new diagnostic.
20. **Run-time behavior.** Compile test 5's `identityOf<String?>` and run it with `null`. It returns
    `null` and raises no exception, which confirms that no run-time check was added.
