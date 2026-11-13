# Testing and verification

This page covers where tests live, which test shape to use, and how to verify a change without
running the full suite for every edit.

The suite currently takes roughly **9 minutes**, so treat `mvn test` as the final gate, not the
inner loop.

## Test layout

Tests mirror the compiler phases. Put a test next to the layer you changed.

| Package | Covers | Typical style |
| --- | --- | --- |
| `com.maruseron.zeron.scan` | Scanner and source positions | Token/span assertions |
| `com.maruseron.zeron.ast` (root tests) | Parsing and parser diagnostics | `Scanner` + `Parser` |
| `com.maruseron.zeron.analize` | Resolution, typing, flow, effects, generics | Resolve source strings, assert diagnostics or resolved types |
| `com.maruseron.zeron.compile` | Bytecode emission and execution | Compile to a temp directory, load and invoke with reflection |
| `com.maruseron.zeron.diagnostic` | Catalog, formatting, highlighting | Resource and string assertions |
| `com.maruseron.zeron.domain` | Descriptors, shape keys, Java signatures | Pure unit assertions |
| `com.maruseron.zeron.lsp` | Workspace analysis and LSP features | Temp project + protocol objects |
| `com.maruseron.zeron.runtime` | Runtime helpers (`json`, numeric values) | Direct unit tests |
| `com.maruseron.zeron.api` | Public member discovery | Catalog assertions |
| `com.maruseron.zeron.fixtures` | Java classes used by interop tests | Not tests |

Root-level tests cover the CLI, projects, the REPL, the standard library, and the formatter.

There is no `src/test/resources` tree. Fixtures are Java sources under `fixtures/`, and generated
Zeron projects are written to temporary directories.

## Three test shapes

### 1. Resolution test (fastest, most common)

Parse a source string and assert on diagnostics or resolved types. This is the default for
type-system work.

```java
final var unit = Parser.of(Scanner.from("""
        fn invalid(value: Widget?): Int = value.read();
        """).scanTokens()).parseCompilationUnit("src/main.zn");

final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

assertEquals(1, result.errors().size());
assertEquals("ZR2013", result.errors().getFirst().code().toString());
```

Assert the code and a stable fragment of the message, not the whole sentence.

### 2. Compilation test (behavior)

Use `CompilationService` to resolve and compile, then load the generated classes and invoke them.
Assert behavior, not bytecode, unless the JVM shape is the requirement.

### 3. Bytecode inspection

When interface names, descriptors, or shape identity matter, read the generated class files or
inspect them with `javap`. See
[`generated-artifacts-and-release.md`](./generated-artifacts-and-release.md).

## Running tests

```powershell
# Full suite: final gate only, ~9 minutes
mvn test

# One class
mvn test -Dtest=FlowTypingTest

# One method
mvn test -Dtest=FlowTypingTest#refinesNullableAndAnyValuesAcrossBranches

# Several classes
mvn test -Dtest=FlowTypingTest,GenericFunctionTest
```

Add `-q` to reduce Maven noise when you only need the failure. Surefire splits the run across four
forks; class-level filtering is much faster than running everything.

Editor tests are separate:

```powershell
npm --prefix editors/vscode test
```

## Working directories and project-root reads

Surefire runs four forked JVMs, each in `target/test-work-<n>` so that relative output such as
`dist/` does not race between tests. Two consequences:

- Tests that write output should write relative paths or temp directories under `target/`.
- Tests that must read repository files use
  [`TestPaths`](../../src/test/java/com/maruseron/zeron/TestPaths.java), which anchors to
  `zeron.test.project.root`. Do not use `Path.of(".")` for source-tree reads.

Prefer `TemporaryFolder` or `Files.createTempDirectory(Path.of("target"), "...")` for compilation
output.

## Before generating Zeron programs

Remove `dist/` before compiling Zeron source, including tests that generate programs:

```powershell
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
```

Stale classes and generated shape interfaces otherwise accumulate and can make a later run read
old output. Do not delete `target/` for this cleanup; that is Maven's build directory.

## Debugging a failure

- `zeron build|run --debug` prints parser, resolver, and compiler traces.
- `zeron explain ZR####` prints the bundled explanation for a diagnostic.
- `zeron dump --stdlib <package>` prints the public standard-library API.
- `zeron fmt --check <paths>` shows formatting drift without rewriting files.
- For generated code, inspect `dist/` with `javap -classpath dist`.

## Test hygiene

- Cover both accepted and rejected forms. Invalid input is where diagnostics regress.
- Assert diagnostic codes, not formatted terminal text; formatting is tested separately.
- Keep test names descriptive of the behavior, not the implementation.
- Do not assert on absolute paths, wall-clock time, or iteration order that the code does not
  guarantee.
- Do not weaken or delete an existing assertion to make a new behavior pass. If the assertion is
  now wrong, explain why in the change.

## Verification checklist

- [ ] Focused tests for the changed layer pass.
- [ ] `mvn compile` passes (this also reveals missing exhaustive-switch cases).
- [ ] Formatter and diagnostic catalog tests pass if either surface changed.
- [ ] `dist/` removed before any Zeron program generation.
- [ ] Full `mvn test` run once before declaring a compiler or resolver change done.
- [ ] Editor tests run if the grammar or extension changed.

## Related

- [`compiler-changes.md`](./compiler-changes.md)
- [`change-surface-map.md`](./change-surface-map.md)
- [`generated-artifacts-and-release.md`](./generated-artifacts-and-release.md)
