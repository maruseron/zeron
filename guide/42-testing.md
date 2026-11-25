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

A test is a public, top-level, parameterless function returning `Unit`. It may perform `TestFailure`;
that is how assertions report a failed expectation.

```zeron
package app;

import zeron.test.Test;
import zeron.test.TestFailure;
import zeron.test.assertEquals;

@Test
public fn additionAddsOperands(): Unit performs TestFailure {
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
| `fail(message)` | Always; performs `TestFailure` with `message` |
| `assertTrue(condition, message = ...)` | `condition` is `false` |
| `assertFalse(condition, message = ...)` | `condition` is `true` |
| `assertEquals(expected, actual, message = "")` | The values differ |
| `assertNotEquals(unexpected, actual, message = "")` | The values are equal |
| `assertNull(value, message = ...)` | `value` is not null |
| `assertNotNull(value, message = ...)` | `value` is null |
| `assertContains(text, part, message = "")` | `text` does not contain `part` |
| `assertNotContains(text, part, message = "")` | `text` contains `part` |
| `assertStartsWith(text, prefix, message = "")` | `text` does not start with `prefix` |
| `assertEndsWith(text, suffix, message = "")` | `text` does not end with `suffix` |
| `assertGreaterThan(actual, expected, message = "")` | `actual` is not greater than `expected` |
| `assertLessThan(actual, expected, message = "")` | `actual` is not less than `expected` |
| `assertApproximatelyEquals(expected, actual, tolerance, message = "")` | The absolute difference exceeds `tolerance` |

`assertEquals` and `assertNotEquals` require `Eq<T> + Display` evidence. `assertNull` and
`assertNotNull` accept nullable types. `assertGreaterThan` and `assertLessThan` require
`Comparable<T> + Display`. `assertApproximatelyEquals` operates on `Float` values and checks the
absolute difference in either direction.

Messages are optional. When an equality assertion has no explicit message, it builds a message from
the expected value and actual value. A failed assertion prints its message under the test line, so
a failure is diagnosable without rerunning the test.

You can also perform a failure directly:

```zeron
@Test
public fn rejectsEmptyName(): Unit performs TestFailure {
    if (nameIsValid("")) {
        fail("empty names should be rejected");
    }
}
```

### Expected compilation failures

A test that must *not* compile is marked `@CompileFailure` instead of `@Test`. The runner never calls
the function; it compiles the named fixture and checks the reported diagnostics.

```zeron
import zeron.test.CompileFailure;

@CompileFailure(fixture = "cell/read_only.zn", code = "ZR2005")
public fn cellMutationRequiresMutableReferenceView(): Unit {
}
```

The annotation carries:

- `fixture`: a path relative to a configured compile-failure root; required;
- `code`: an optional diagnostic code that must appear among the reported errors;
- `message`: an optional substring that must appear in that diagnostic’s text.

A missing `code` accepts any resolution, type, parse, or compilation error, but a fixture that
compiles successfully, a missing fixture, or an internal compiler failure is reported as a failed
test, never as a pass.

Fixtures are deliberately invalid, so they are excluded from the normal suite compilation. Point
the test target at one or more roots:

```toml
[targets.unit]
kind = "test"
output = "dist/test"
compile-failure-roots = ["tests/fixtures"]
```

`compile-failure-roots` is only valid on a test target, and a root must not overlap a project source
root. A fixture may import the project’s public API and the standard library. Each fixture is
compiled on its own, so several annotations may reference several fixtures; one annotation asserts
one fixture.

## Running tests

From the project root:

```text
zeron test
```

The test command accepts three selection and reporting options:

- `--filter <pattern>` keeps the tests whose fully qualified name contains `pattern`, for both
  ordinary and compile-failure tests;
- `--list` prints the discovered tests without compiling or running them;
- `--fail-fast` stops after the first failing test, including the compile-failure phase.

The command selects a test target as follows:

1. an explicit target name, as in `zeron test unit`;
2. `[project].default-target`, if it names a test target;
3. the only test target, when exactly one exists;
4. otherwise, a synthesized test target whose output is `dist/test`.

If several test targets exist and none is selected explicitly or by `default-target`, `zeron test`
reports the available names instead of choosing one.

A run prints one line per test and a summary:

```text
running 2 tests in target 'unit'
1 expected-compilation-failure tests run by the host
test app.additionAddsOperands ... ok
test app.rejectsEmptyName ... FAILED
  empty names should be rejected
1 passed, 1 failed, 0 errors, 0 skipped
compile-failure test app.cellMutationRequiresMutableReferenceView ... ok
1 compile-failure tests passed, 0 failed
```

Ordinary tests run first through the generated runner; compile-failure tests then run through the
host compiler and print their own summary. `--list` adds `(ignored)` and `(compile failure)` markers
to distinguish the two kinds.

The command exits with status `0` only when every runnable test passes. Assertion failures and
other unhandled effects produce a nonzero status, as does an unmet compile-failure expectation.

## How the runner works

Discovery is limited to top-level functions marked `@Test` or `@CompileFailure` in the configured
test roots. Tests are sorted by fully qualified name for a stable run order. The compiler then
generates a synthetic `zeron.generated.test.TestRunner` source unit that imports each runnable test
and calls it inside a handler:

- `TestFailure.message(message)` marks the test failed and prints the message;
- any other effect marks the test as an error and still makes the runner fail.

Compile-failure tests are not called. After the generated runner exits, the host compiles each
fixture in process, collects the compiler diagnostics, and matches them against the annotation; the
fixture’s output directory is removed afterwards. Warnings produced by a fixture are suppressed, so
an expected failure does not disturb the suite’s warning state.

Application targets never emit this unit, and the generated runner is not part of the library API
index.

## Current limits

The testing API is deliberately small. It does not yet provide fixtures with setup or teardown
hooks, parameterized tests, parallel or isolated execution, or a machine-readable report format. The
runner reports a distinct error count but not per-test timings, and a `--diagnostics json` mode for
compiler diagnostics is not implemented.

The standard library’s own runtime tests use this facility: the compiler repository keeps them in
`stdlib-tests/`, a project whose test target runs against `stdlib = "source"`. The coverage map,
including the assertions that deliberately remain Java tests, is in
[`documents/developer_guides/stdlib-suite-parity.md`](../documents/developer_guides/stdlib-suite-parity.md).
