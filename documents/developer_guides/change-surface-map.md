# Change surface map

Use this page before editing. It answers one question: **for this kind of change, which files
move together?** It is a routing table, not a design reference. Follow the links for semantics.

This map does not replace [`compiler-changes.md`](./compiler-changes.md); it covers the cases that
guide does not: standard-library code, diagnostics, tooling, the editor surface, and release
artifacts.

## Fast path

1. Find your change type in the table below and open the "Start here" column.
2. Grep for the nearest existing feature of the same shape and copy its wiring. New code should
   look like its sibling, not like a new pattern.
3. Compile early. The AST hierarchies are sealed and phase dispatch is exhaustive, so
   `mvn compile` turns missing dispatch sites into a to-do list.
4. Add tests in the package that matches the phase you changed (see
   [`testing-and-verification.md`](./testing-and-verification.md)).
5. If the change generates Zeron programs, remove `dist/` first (see
   [`generated-artifacts-and-release.md`](./generated-artifacts-and-release.md)).

## Change types

| Change | Start here | Also touch | Verify |
| --- | --- | --- | --- |
| Scanner characters, literals, number suffixes | [`Scanner.java`](../../src/main/java/com/maruseron/zeron/scan/Scanner.java), [`TokenType.java`](../../src/main/java/com/maruseron/zeron/scan/TokenType.java) | Grammar EBNF, ANSI highlighter, TextMate grammar | `ScannerLocationTest`, `ParserDiagnosticsTest` |
| Keyword or declaration syntax | [`Parser.java`](../../src/main/java/com/maruseron/zeron/ast/Parser.java), [`Stmt.java`](../../src/main/java/com/maruseron/zeron/ast/Stmt.java) | Every row of [`syntax-surface-checklist.md`](./syntax-surface-checklist.md) | Scanner, parser, formatter, grammar tests |
| Expression or operator | [`Expr.java`](../../src/main/java/com/maruseron/zeron/ast/Expr.java), `Parser` precedence, [`ExpressionFlowResolver.java`](../../src/main/java/com/maruseron/zeron/analize/ExpressionFlowResolver.java) | [`ConstantFolder.java`](../../src/main/java/com/maruseron/zeron/compile/ConstantFolder.java), formatter, highlighter | `analize/` plus `compile/` tests |
| Assignability and type projection | [`TypeCompatibility.java`](../../src/main/java/com/maruseron/zeron/analize/TypeCompatibility.java), [`TypeProjector.java`](../../src/main/java/com/maruseron/zeron/analize/TypeProjector.java) | [`TypeUnifier.java`](../../src/main/java/com/maruseron/zeron/analize/TypeUnifier.java), [`TypeFormatter.java`](../../src/main/java/com/maruseron/zeron/domain/TypeFormatter.java), LSP hover | `analize/` type tests |
| Flow typing, type tests, casts | [`FlowState.java`](../../src/main/java/com/maruseron/zeron/analize/FlowState.java), `ExpressionFlowResolver` | [`ExpressionFlowEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/ExpressionFlowEmitter.java), [design document 08](../design_documents/design-document-08_flow-typing-type-tests-and-casts.md) | `FlowTypingTest`, `compile/` execution tests |
| Generics and inference | [`TypeUnifier.java`](../../src/main/java/com/maruseron/zeron/analize/TypeUnifier.java), [`FunctionDescriptor.java`](../../src/main/java/com/maruseron/zeron/domain/FunctionDescriptor.java) | [`TypeSubstitution.java`](../../src/main/java/com/maruseron/zeron/domain/TypeSubstitution.java), library index | `GenericFunctionTest`, `ResolverInferenceTest`, `compile/` tests |
| Typed raised effects | [`RowInference.java`](../../src/main/java/com/maruseron/zeron/analize/RowInference.java), [`RaisedEffectFlow.java`](../../src/main/java/com/maruseron/zeron/analize/RaisedEffectFlow.java) | [`EffectRows.java`](../../src/main/java/com/maruseron/zeron/domain/EffectRows.java), [`RuntimeSupportEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/RuntimeSupportEmitter.java) | `EffectRowTest`, `EffectSubtypingTest`, `TypedRaisedEffectsTest` |
| Classes, contracts, sealed hierarchies | [`DeclarationResolver.java`](../../src/main/java/com/maruseron/zeron/analize/DeclarationResolver.java), [`DeclarationRegistry.java`](../../src/main/java/com/maruseron/zeron/analize/DeclarationRegistry.java) | [`NominalTypeEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/NominalTypeEmitter.java), [`ClassSignatureEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/ClassSignatureEmitter.java) | `ClassContractTest`, `AnonymousContractObjectTest`, `ClassTypeParameterBoundTest` |
| Pattern matching | [`Parser.java`](../../src/main/java/com/maruseron/zeron/ast/Parser.java), `ExpressionFlowResolver` | [`ExpressionFlowEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/ExpressionFlowEmitter.java), [design document 16](../design_documents/design-document-16_patterns-and-pattern-matching.md) | `SumTypeTest`, `AppliedTypeMatchCompilationTest` |
| Java interop | [`MemberInteropResolver.java`](../../src/main/java/com/maruseron/zeron/analize/MemberInteropResolver.java), [`JavaTypeMapping.java`](../../src/main/java/com/maruseron/zeron/domain/JavaTypeMapping.java) | [`JavaClassPath.java`](../../src/main/java/com/maruseron/zeron/domain/JavaClassPath.java), fixtures in `src/test/java/com/maruseron/zeron/fixtures/` | `JavaInteropTest`, `JavaGenericSignatureParserTest` |
| Intrinsic or host binding | [`FunctionBindingRegistry.java`](../../src/main/java/com/maruseron/zeron/domain/FunctionBindingRegistry.java), [`IntrinsicRegistry.java`](../../src/main/java/com/maruseron/zeron/domain/IntrinsicRegistry.java) | Read [`registries-and-modules.md`](./registries-and-modules.md) first | Binding tests plus a program that calls it |
| Standard-library function | A `.zn` module under [`src/main/resources/stdlib/`](../../src/main/resources/stdlib/) | `guide/` chapter if user-facing; add a binding only if it needs host code | `StandardLibraryTest` or a focused sample |
| Emitted runtime helper | [`RuntimeSupportEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/RuntimeSupportEmitter.java) | [`RuntimeTypeDescriptorEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/RuntimeTypeDescriptorEmitter.java), `javap` expectations | `RuntimeSupportEmissionTest`, `javap -classpath dist` |
| Lambda lowering | [`LambdaCompilationPlan.java`](../../src/main/java/com/maruseron/zeron/compile/LambdaCompilationPlan.java), [`LambdaSupportEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/LambdaSupportEmitter.java) | [`FunctionShapeKey.java`](../../src/main/java/com/maruseron/zeron/domain/FunctionShapeKey.java), [design document 03](../design_documents/design-document-03_lambda-lowering.md) | `FunctionShapeKeyTest`, `javap -classpath dist` |
| CLI command or flag | [`CliParser.java`](../../src/main/java/com/maruseron/zeron/CliParser.java), [`CliHelp.java`](../../src/main/java/com/maruseron/zeron/CliHelp.java) | [`Zeron.java`](../../src/main/java/com/maruseron/zeron/Zeron.java), `README.md` | `CliParserTest` |
| Project manifest or config | [`ProjectManifest.java`](../../src/main/java/com/maruseron/zeron/ProjectManifest.java) | [`ProjectAnalysisWorkspace.java`](../../src/main/java/com/maruseron/zeron/ProjectAnalysisWorkspace.java), guide 25 | `ProjectManifestTest`, `ProjectInitTest` |
| Diagnostic or error code | [`DiagnosticCatalog.java`](../../src/main/java/com/maruseron/zeron/diagnostic/DiagnosticCatalog.java) | Read [`diagnostics-and-error-codes.md`](./diagnostics-and-error-codes.md) | `DiagnosticCatalogTest` |
| Source formatting | [`SourceFormatter.java`](../../src/main/java/com/maruseron/zeron/SourceFormatter.java), [`CanonicalStyle.java`](../../src/main/java/com/maruseron/zeron/CanonicalStyle.java) | [`style.toml`](../../src/main/resources/style.toml) | `SourceFormatterTest` |
| Language server | [`lsp/`](../../src/main/java/com/maruseron/zeron/lsp/) | [`ProjectAnalysisWorkspace.java`](../../src/main/java/com/maruseron/zeron/ProjectAnalysisWorkspace.java), editor client | `LspProtocolTest`, `LspWorkspaceTest` |
| Editor highlighting or behavior | [`zeron.tmLanguage.json`](../../editors/vscode/syntaxes/zeron.tmLanguage.json), [`language-configuration.json`](../../editors/vscode/language-configuration.json) | [`syntax-grammar.test.js`](../../editors/vscode/test/syntax-grammar.test.js) | `npm --prefix editors/vscode test` |
| Public API or compiled library index | [`ZeronLibraryIndex.java`](../../src/main/java/com/maruseron/zeron/domain/ZeronLibraryIndex.java) | [`ZeronVersions.java`](../../src/main/java/com/maruseron/zeron/ZeronVersions.java), index reader/writer paths | `ZeronLibraryIndexTest`, `zeron dump` |
| Release or packaging | [`pom.xml`](../../pom.xml), [`scripts/release.ps1`](../../scripts/release.ps1) | Read [`generated-artifacts-and-release.md`](./generated-artifacts-and-release.md) | `scripts/release.ps1` smoke tests |
| User-facing docs | [`guide/`](../../guide/) | `README.md`, `CHANGELOG.md`, design documents, ZEP | Markdown lint, links |

## Coupled surfaces that are easy to miss

These are the pairs that most often leave a change half-done:

- **Syntax and formatter.** A new construct that the formatter cannot print will corrupt source on
  `zeron fmt`. Formatting is part of the language contract.
- **Syntax and highlighter.** [`AnsiSyntaxHighlighter.java`](../../src/main/java/com/maruseron/zeron/diagnostic/AnsiSyntaxHighlighter.java)
  and the TextMate grammar are separate implementations. Both need the new token.
- **Resolution and library index.** If a public declaration gains a property or type argument,
  check index writing, reading, validation, and round-trip tests together.
- **Type descriptor and LSP.** Changing how a type prints or resolves usually changes hover,
  completion, and diagnostics in the language server.
- **Standard library and release.** A new `.zn` file changes the bundled source count that
  `release.ps1` asserts, and the release archive layout.
- **Diagnostic and docs.** Every catalog entry needs an explanation resource whose first line
  matches exactly.

## Finding the rest

Prefer these signals over searching for prose:

- `mvn compile` after adding a sealed-interface case. Unhandled `switch` sites fail to compile.
- `grep` for the nearest sibling node or catalog entry, then follow its references.
- `javap -classpath dist` when JVM shape matters.
- `zeron dump --stdlib <package>` to inspect the public API surface.
- The ZEP tracker for the intended design before changing behavior.

## Related

- [`compiler-changes.md`](./compiler-changes.md) — statement, declaration, and expression plumbing.
- [`syntax-surface-checklist.md`](./syntax-surface-checklist.md) — the full syntax-change checklist.
- [`testing-and-verification.md`](./testing-and-verification.md) — where tests live and how to run them.
- [`diagnostics-and-error-codes.md`](./diagnostics-and-error-codes.md) — adding an error code.
