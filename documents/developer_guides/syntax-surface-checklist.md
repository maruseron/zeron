# Syntax surface checklist

Use this checklist whenever a change alters what source text the language accepts: a keyword,
operator, literal form, declaration clause, or type syntax.

Syntax is the most widely coupled surface in the project. A construct that works in the compiler
but not in the formatter, highlighter, or language server is an incomplete change.

## Ordered checklist

Work top to bottom. Earlier items usually expose the later ones.

- [ ] **Grammar spec** — update
      [`design-document-01_grammar.ebnf`](../design_documents/design-document-01_grammar.ebnf).
      It is the implemented grammar, not an aspiration. Change it in the same commit.
- [ ] **Tokens** — [`TokenType.java`](../../src/main/java/com/maruseron/zeron/scan/TokenType.java)
      and [`Scanner.java`](../../src/main/java/com/maruseron/zeron/scan/Scanner.java) if the
      lexeme is new or a keyword.
- [ ] **AST** — [`Stmt.java`](../../src/main/java/com/maruseron/zeron/ast/Stmt.java) or
      [`Expr.java`](../../src/main/java/com/maruseron/zeron/ast/Expr.java) for a new node; update
      existing nodes if the shape changes.
- [ ] **Parser** — [`Parser.java`](../../src/main/java/com/maruseron/zeron/ast/Parser.java), at the
      correct precedence and with a catalog-backed diagnostic for invalid forms.
- [ ] **Resolver** — the focused resolver for the construct; report semantics here, not in codegen.
- [ ] **Compiler** — the narrowest emitter; preserve evaluation order and control flow.
- [ ] **Formatter** — [`SourceFormatter.java`](../../src/main/java/com/maruseron/zeron/SourceFormatter.java)
      and, if layout rules change, [`CanonicalStyle.java`](../../src/main/java/com/maruseron/zeron/CanonicalStyle.java)
      plus [`style.toml`](../../src/main/resources/style.toml).
- [ ] **ANSI highlighter** — [`AnsiSyntaxHighlighter.java`](../../src/main/java/com/maruseron/zeron/diagnostic/AnsiSyntaxHighlighter.java).
- [ ] **LSP keyword completion** — the `KEYWORDS` list in
      [`LspFeatures.java`](../../src/main/java/com/maruseron/zeron/lsp/LspFeatures.java).
- [ ] **TextMate grammar** — [`zeron.tmLanguage.json`](../../editors/vscode/syntaxes/zeron.tmLanguage.json),
      including contextual-keyword patterns.
- [ ] **Editor configuration** — [`language-configuration.json`](../../editors/vscode/language-configuration.json)
      only if comment, bracket, or auto-closing behavior changes.
- [ ] **Library index** — if the syntax introduces or changes a public declaration shape that is
      serialized.
- [ ] **Diagnostics** — new catalog entries and explanation resources; see
      [`diagnostics-and-error-codes.md`](./diagnostics-and-error-codes.md).
- [ ] **Tests** — scanner or parser tests, resolver diagnostics, formatter round-trip, and grammar
      tests as applicable.
- [ ] **Docs** — the relevant `guide/` chapter, the design document, and `CHANGELOG.md`.
- [ ] **ZEP** — if the change is a language feature rather than a fix.

## Surface reference

| Surface | File | Required when |
| --- | --- | --- |
| Grammar | `documents/design_documents/design-document-01_grammar.ebnf` | Always |
| Lexing | `scan/TokenType.java`, `scan/Scanner.java` | New token or keyword |
| Parsing | `ast/Parser.java` | Always |
| AST | `ast/Stmt.java`, `ast/Expr.java` | New or reshaped node |
| Semantics | `analize/` focused resolver | Always |
| Emission | `compile/` narrowest emitter | Always |
| Formatting | `SourceFormatter.java`, `CanonicalStyle.java`, `style.toml` | Always |
| Terminal highlighting | `diagnostic/AnsiSyntaxHighlighter.java` | New token or keyword |
| LSP completion | `lsp/LspFeatures.java` | New keyword |
| TextMate | `editors/vscode/syntaxes/zeron.tmLanguage.json` | New token or contextual use |
| Editor behavior | `editors/vscode/language-configuration.json` | Comments, brackets |
| Library index | `domain/ZeronLibraryIndex.java` | Public serialized shape |
| Docs | `guide/`, `CHANGELOG.md` | User-visible change |

## Keywords: hard versus contextual

Zeron uses both. Choosing the wrong kind breaks existing programs.

- **Hard keyword** — reserved everywhere. Adding one can break identifiers in existing source, so
  treat it as a breaking change.
- **Contextual keyword** — only special in a syntactic position. The TextMate grammar needs a
  positional pattern for it, and the parser must still accept the word as an identifier elsewhere.

The editor grammar already does this for constructs such as `out`, `yield`, `collect`, `handler`,
and `data`. Mirror that pattern rather than adding the word to the global keyword list.

Contextual keywords are deliberately absent from `LspFeatures.KEYWORDS` when they would pollute
completion everywhere. Match the existing decision.

## Compile errors are the safety net

Sealed AST hierarchies and exhaustive switches mean `mvn compile` reports every phase that still
needs the new case. Do not work around those errors with a default branch; each is a real dispatch
site.

## Tests to add

| Change | Test |
| --- | --- |
| New token or literal | `scan/ScannerLocationTest`, `ParserDiagnosticsTest` |
| New statement or declaration | Parser test plus a resolver test |
| New expression or operator | Resolver test plus a compilation test |
| User-visible formatting | `SourceFormatterTest` (including idempotence) |
| Editor grammar | `editors/vscode/test/syntax-grammar.test.js` |
| Keyword completion | `lsp/` test if the list is asserted |

Formatter tests should assert both the formatted text and that formatting is idempotent.

## Frequent omissions

- Grammar EBNF left stale.
- Formatter emits the new construct with wrong spacing or breaks it across lines.
- TextMate grammar colours the new contextual keyword everywhere.
- `LspFeatures.KEYWORDS` not updated, so the keyword never completes.
- ANSI highlighter missed, so terminal diagnostics render the new token as plain text.
- Explanation resource added without the matching catalog entry, or the reverse.
- `CHANGELOG.md` skipped for a user-visible language change.

## Related

- [`compiler-changes.md`](./compiler-changes.md)
- [`change-surface-map.md`](./change-surface-map.md)
- [`diagnostics-and-error-codes.md`](./diagnostics-and-error-codes.md)
- [`testing-and-verification.md`](./testing-and-verification.md)
