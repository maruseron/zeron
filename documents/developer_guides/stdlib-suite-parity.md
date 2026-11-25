# Standard-library suite parity

The standard library's runtime behavior is verified by the Zeron suite in [`stdlib-tests/`](../../stdlib-tests/),
not by Java tests. This page records what moved, what stayed in Java, and why, so that a reviewer can
tell whether a change lost coverage or deliberately kept a host-level assertion.

## Running the suite

From the repository root, after `mvn compile`:

```powershell
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
$classpath = "target/classes;" + (Get-Content target/cp.txt -Raw).Trim()
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
java -cp $classpath com.maruseron.zeron.Zeron test --manifest stdlib-tests/zeron.toml stdlib-runtime
```

The classpath file is only needed because the compiler loads its Maven-managed dependencies from
`target/classes`; a packaged build can run the installed `zeron` launcher instead.

The suite manifest uses `stdlib = "source"`, so it exercises the same bundled sources that ship in
the distribution. `scripts/release.ps1` runs the same command with the packaged launcher before
producing the release archive.

The suite discovers 150 test functions: 147 ordinary `@Test` functions and three `@CompileFailure`
checks that the host runs after the generated runner. The table below lists the ordinary tests; the
compile-failure checks are the three fixtures listed after it.

## Suite layout

| File | Tests | Covers |
| --- | --- | --- |
| `tests/smoke/smoke_test.zn` | 1 | The suite itself runs: one trivial assertion. |
| `tests/lang/option_test.zn` | 7 | `Option` construction, `map`/`flatMap`/`filter`/`getOrElse`, nullable payloads, pattern matches. |
| `tests/lang/result_test.zn` | 7 | `Result` success/error paths, combinators, error mapping, match extraction. |
| `tests/lang/cell_test.zn` | 2 | `Cell` sharing between closures, escaping its scope, `@CompileFailure` for mutation through a read-only view. |
| `tests/lang/string_test.zn` | 23 | Splitting, lines, `charAt`/`at`, case conversion, trimming, blank checks, replacement, numeric parsing, regex splitting, joining and collector integration. |
| `tests/math/math_extensions_test.zn` | 9 | `abs`/`min`/`max`/`clamp` for `Int`, `Long`, `Float`, including `MIN_VALUE`; roots, powers, exponentials, logarithms, trigonometry, constants, JVM rounding, NaN/infinity, signed zero. |
| `tests/math/statistics_test.zn` | 3 | Mean, median, population and sample variance/deviation, and `None` for undefined statistics. |
| `tests/collections/array_list_test.zn` | 3 | Growth, insertion, replacement, removal, read-only projection, clearing, `@CompileFailure` for mutation through an immutable view. |
| `tests/collections/sorting_test.zn` | 7 | List sorting, sorted views, stream sorting, whitespace splitting. |
| `tests/collections/collector_test.zn` | 7 | Concrete collectors for lists, maps, sets, sums, and `collectWith`/`collect` composition. |
| `tests/collections/stream_test.zn` | 10 | Lazy operators, composition, short-circuiting counts, folding, ordinary iteration. |
| `tests/collections/for_expression_test.zn` | 17 | Lazy and collecting `for` expressions, filters, bindings, captures, nesting, `take`/`drop`/`takeWhile`/`dropWhile`, upstream-call counts, `@CompileFailure` for an immutable collect sink. |
| `tests/collections/hash_collections_test.zn` | 6 | `HashMap`/`HashSet` insertion, lookup, removal, iteration, equality and key collisions. |
| `tests/collections/alternative_collections_test.zn` | 5 | `LinkedList`, `ConsList`, and their shared list contract. |
| `tests/collections/hkt_test.zn` | 4 | Higher-kinded operations through `Foldable`, `Functor`, `ArrayFoldable`, and friends. |
| `tests/collections/sorted_collections_test.zn` | 4 | `PriorityQueue` polling, removal appearance, and heap iteration; `SortedSet`/`SortedMap` placement and cursor iteration. |
| `tests/collections/builders_test.zn` | 3 | `ListBuilder`, `SetBuilder`, `MapBuilder`, and `StreamBuilder` accumulation and `build`. |
| `tests/buffer/buffer_test.zn` | 5 | Endianness, integer and varint round trips, hex/Base64, truncation without cursor movement, seek/slice/remainder, bit helpers. |
| `tests/random/random_test.zn` | 3 | Seed determinism, bounded values and empty ranges, `pick`, `shuffle`. |
| `tests/text/formatter_test.zn` | 4 | Placeholder order, explicit indices and braces, fixed and zero-padded forms, typed template errors. |
| `tests/text/regex_test.zn` | 3 | Captures and named groups, whole/partial matching, replacements, invalid patterns. |
| `tests/generators/generator_test.zn` | 8 | `yield` suspension, delayed pulls, cleanup through `Disposable`, handling raised effects, cursor iteration. |
| `tests/ranges/int_range_test.zn` | 6 | Inclusive ascending/descending ranges, emptiness, iteration, integer-limit edges. |

Fixtures live under `tests/fixtures/`, named by the `@CompileFailure` annotation:
`collections/read_only_list.zn`, `collections/immutable_for_sink.zn`, `lang/cell_read_only.zn`.

## Java tests that were removed

Each removed Java test is replaced one-for-one by a suite file. The Java test asserted the same
runtime behavior through reflection, which the Zeron suite asserts directly.

| Removed | Replaced by |
| --- | --- |
| `StringApiTest.stringInventorySplitsLinesParsesAndJoins` | `tests/lang/string_test.zn` |
| `SortingApiTest.sortsListsSortsStreamsAndSplitsWhitespace` | `tests/collections/sorting_test.zn` |
| `CollectorApiTest.concreteCollectorsBuildCollectionsAndSummaries` | `tests/collections/collector_test.zn` |
| `compile/HashCollectionsTest` | `tests/collections/hash_collections_test.zn` |
| `compile/AlternativeCollectionsTest` | `tests/collections/alternative_collections_test.zn` |
| `compile/StandardLibraryExtensionsTest.statisticsOrderedCollectionsAndBuildersWork` | `tests/math/statistics_test.zn`, `tests/collections/sorted_collections_test.zn`, `tests/collections/builders_test.zn` |
| `compile/StandardLibraryExtensionsTest.randomBuffersFormatterAndRegexWork` | `tests/buffer/buffer_test.zn`, `tests/random/random_test.zn`, `tests/text/formatter_test.zn`, `tests/text/regex_test.zn` |
| `StandardLibraryTest.optionDistinguishesSomeNullableValueFromNone` | `tests/lang/option_test.zn` |
| `StandardLibraryTest.resultPreservesSuccessAndErrorAcrossCombinators` | `tests/lang/result_test.zn` |
| `StandardLibraryTest.cellSharesMutableStateAcrossClosuresAndEscapesItsScope` | `tests/lang/cell_test.zn` |
| `StandardLibraryTest.cellMutationRequiresMutableReferenceView` | `tests/lang/cell_test.zn` (`@CompileFailure`) |
| `StandardLibraryTest.standardCollectionsExposeTheirHigherKindedOperations` | `tests/collections/hkt_test.zn` |
| `StandardLibraryTest.forExpressionsCreateLazyStreamsAndCollectDirectly` | `tests/collections/for_expression_test.zn` |
| `StandardLibraryTest.forExpressionCollectRequiresMutableSink` | `tests/collections/for_expression_test.zn` (`@CompileFailure`) |
| `StandardLibraryTest.lazyStreamOperatorsComposeAndSupportOrdinaryIteration` | `tests/collections/stream_test.zn` |
| `StandardLibraryTest.arrayBackedListMutatesGrowsAndProjectsToReadOnlyView` | `tests/collections/array_list_test.zn` |
| `StandardLibraryTest.listMutationRequiresMutableReferenceView` | `tests/collections/array_list_test.zn` (`@CompileFailure`) |
| `StandardLibraryTest.intRangeStopsAtInclusiveIntegerBounds` | `tests/ranges/int_range_test.zn` |

## What stays in Java

Java tests remain for behavior that the Zeron suite cannot observe or that is not library behavior
at all. Do not migrate these without a replacement mechanism.

| Java surface | Why it stays |
| --- | --- |
| `MathApiTest.resolvesExactIntegerAndRationalLiteralsContextually` | Numeric literal resolution and constant folding are compiler behavior, not library behavior. |
| `MathApiTest.rejectsIntegerLiteralsOutsideTheExpectedPrimitiveRange` | Parse-resolution diagnostic range check. |
| `MathApiTest.exposesMathExtensionsConstantsAndJvmEdgeSemantics` | Pins generated method descriptors, class loading, and host exceptions from an invalid `clamp` range. The library semantics are duplicated in `tests/math/math_extensions_test.zn`. |
| `MathApiTest.publishesMathApiThroughCompiledLibraryIndex` | Compiled API-index publishing and versioning. |
| `TestCommandTest` | Discovery rules, compile-failure roots, failure reporting, capture behavior of the generated runner. |
| `compile/GeneratorTest` | Suspension bytecode, cursor-class shape, and rejection diagnostics for generators. |
| `StandardLibraryTest` (remaining methods) | Bundled-source layout and installation, print facades, iterable-contract compilation, project roots/imports, project values and initialization order, CLI library loading and packaging. |
| `generate*` / `javap`-based tests | Class-file names, descriptors, and shape identity. |

The guiding rule: assert library behavior in Zeron, assert the compiler's relationship to the
library in Java.
