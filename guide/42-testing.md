# Testing with `zeron.test`

Zeron tests are ordinary top-level functions marked with `@Test`. The standard library provides the
annotations, the `TestFailure` effect, and assertion helpers. A project declares a test target, and
`zeron test` compiles the project plus its test roots, discovers the marked functions, generates a
runner, and executes it.

## Project layout

Test sources live under `test-roots`, which defaults to `tests`. Test roots must not overlap a
source root. A test root uses the same package-to-directory rule as a source root:

```text
my-project/
├── zeron.toml
├── src/
│   └── app/
│       └── Math.zn
└── tests/
    └── app/
        └── MathTest.zn
```

A test target has `kind = "test"` and must not declare `entry`:

```toml
[project]
source-roots = ["src"]
test-roots = ["tests"]
output = "dist"

[platform]
stdlib = "source"

[targets.app]
entry = "src/app/Main.zn"

[targets.unit]
kind = "test"
output = "dist/test"
```

The application target and the test target are independent. `zeron build` and `zeron run` never
compile test roots or generate a test runner. `zeron test` compiles the source roots and test roots
together, so tests can use the project's public API without a separate build step.

Use `stdlib = "source"` when working against a compiler checkout. A compiled standard library must
be new enough to contain `zeron.test`.

## Writing a test

A test is a public, top-level, parameterless function returning `Unit`. It may raise `TestFailure`;
that is how assertions report a failed expectation.

```zeron
package app;

import zeron.test.Test;
import zeron.test.TestFailure;
import zeron.test.assertEquals;

@Test
public fn additionAddsOperands(): Unit raises TestFailure {
    assertEquals(4, add(2, 2));
}
```

The function name is the test name. The runner calls it directly. A test must be a top-level
function or a function in a top-level namespace; a class member cannot be run as a test. A test
that requires arguments fails compilation when the generated runner calls it.

### Skipping a test

Add `@Ignore` beside `@Test`:

```zeron
import zeron.test.Ignore;

@Test
@Ignore(reason = "waiting for fixture support")
public fn parsesLargeDocument(): Unit {
}
```

The reason is a string literal. The runner reports the test as skipped and does not call it. Ignored
tests still count toward the discovered total.

### Assertions

The `zeron.test` module currently provides:

| Function | Fails when |
| --- | --- |
| `fail(message)` | Always; raises `TestFailure` with `message` |
| `assertTrue(condition, message = ...)` | `condition` is `false` |
| `assertFalse(condition, message = ...)` | `condition` is `true` |
| `assertEquals(expected, actual, message = "")` | The values differ |
| `assertNotEquals(unexpected, actual, message = "")` | The values are equal |
| `assertNull(value, message = ...)` | `value` is not null |
| `assertNotNull(value, message = ...)` | `value` is null |
| `assertApproximatelyEquals(expected, actual, tolerance, message = "")` | The absolute difference exceeds `tolerance` |

`assertEquals` and `assertNotEquals` require `Eq<T> + Display` evidence. `assertNull` and
`assertNotNull` accept nullable types. `assertApproximatelyEquals` operates on `Float` values and
checks the absolute difference in either direction.

Messages are optional. When an equality assertion has no explicit message, it builds a message from
the expected value and actual value. The generated runner currently reports the test name and
status; assertion messages are carried by the `TestFailure` effect but are not printed in the
per-test output.

You can also raise a failure directly:

```zeron
@Test
public fn rejectsEmptyName(): Unit raises TestFailure {
    if (nameIsValid("")) {
        fail("empty names should be rejected");
    }
}
```

## Running tests

From the project root:

```text
zeron test
```

The command selects a test target as follows:

1. an explicit target name, as in `zeron test unit`;
2. `[project].default-target`, if it names a test target;
3. the only test target, when exactly one exists;
4. otherwise, a synthesized test target whose output is `dist/test`.

If several test targets exist and none is selected explicitly or by `default-target`, `zeron test`
reports the available names instead of choosing one.

A successful run prints one line per test and a summary:

```text
running 2 tests in target 'unit'
test app.additionAddsOperands ... ok
test app.parsesLargeDocument ... skipped: waiting for fixture support
1 passed, 0 failed, 1 skipped
```

The command exits with status `0` only when every runnable test passes. Assertion failures and
other unhandled effects produce a nonzero status.

## How the runner works

Discovery is limited to top-level functions marked `@Test` in the configured test roots. Tests are
sorted by fully qualified name for a stable run order. The compiler then generates a synthetic
`zeron.generated.test.TestRunner` source unit that imports each runnable test and calls it inside a
handler:

- `TestFailure.message(message)` marks the test failed;
- any other effect marks the test as an error and still makes the runner fail.

Application targets never emit this unit, and the generated runner is not part of the library API
index.

## Current limits

The initial testing API is deliberately small. It does not yet provide test filtering, fixtures,
setup or teardown hooks, parameterized tests, parallel or isolated execution, or a machine-readable
report format. The runner also does not print assertion messages or per-test timings.

A test that raises an effect other than `TestFailure` is reported as `FAILED` and makes the command
exit nonzero, but the current summary does not include a separate error count.
