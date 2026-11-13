# Diagnostics and error codes

Every user-visible diagnostic has a stable `ZR####` code, a catalog entry, and a bundled
explanation. This page explains how to add or change one without breaking the catalog
invariants.

## Where diagnostics live

| Artifact | Location | Role |
| --- | --- | --- |
| Catalog | [`DiagnosticCatalog.java`](../../src/main/java/com/maruseron/zeron/diagnostic/DiagnosticCatalog.java) | Code, title, severity, explanation resource |
| Code type | [`DiagnosticCode.java`](../../src/main/java/com/maruseron/zeron/diagnostic/DiagnosticCode.java) | Enforces the `ZR0000` form |
| Explanations | [`src/main/resources/errorcode/`](../../src/main/resources/errorcode/) | One Markdown file per code |
| User index | [`errorcode/errors.md`](../../src/main/resources/errorcode/errors.md) | Message list grouped by phase |
| Rendering | [`DiagnosticFormatter.java`](../../src/main/java/com/maruseron/zeron/diagnostic/DiagnosticFormatter.java), [`ExplanationFormatter.java`](../../src/main/java/com/maruseron/zeron/diagnostic/ExplanationFormatter.java) | Terminal output and `zeron explain` |

The language server publishes the same diagnostics, preserving error and warning severity.
`--deny-warnings` turns reported warnings into a failed invocation. `--no-warnings` hides warning
display only; warnings are still collected for exit-status policy.

## Code ranges

Pick the range that matches the phase that detects the problem.

| Range | Phase | Current use |
| --- | --- | --- |
| `ZR1001`–`ZR1099` | Scanning | `1001`–`1006` |
| `ZR1101`–`ZR1199` | Parsing | `1101`–`1116` |
| `ZR2001`–`ZR2999` | Resolution, typing, flow, effects | `2001`–`2026` |
| `ZR3001`–`ZR3099` | Compilation and warnings | `3001`, `3010`–`3017` |
| `ZR4001`–`ZR4099` | Project and workspace validation | `4001` |

Use the next free number in the range. Never reuse a retired code and never renumber. Changing a
code is a user-visible break.

## Adding a diagnostic

1. **Add the catalog entry.** Follow the existing style and keep `ENTRIES` ordered by code.

   ```java
   public static final Entry INVALID_WHATEVER =
           entry("ZR2027", "Invalid whatever");
   ```

   Use `entry(...)` for errors and `warning(...)` for warnings. Add the constant to `ENTRIES`.

2. **Write the explanation.** Create `src/main/resources/errorcode/ZR2027.md`. The first line must
   match the catalog title exactly:

   ```markdown
   # Error code ZR2027: Invalid whatever
   ```

   Then explain the rule, show a short failing `zeron` example with a caret marker, and show the
   corrected form. `ExplanationFormatter` strips Markdown fences and headings for terminal output,
   so keep fenced blocks valid.

3. **Index the message.** Add the message text under the matching phase heading in
   [`errors.md`](../../src/main/resources/errorcode/errors.md). Keep the wording aligned with what
   the compiler actually emits.

4. **Update the count.** `DiagnosticCatalogTest` asserts the exact number of entries. Change that
   number in the same commit.

5. **Report through the entry.** Never construct a code string at a call site. Pass the catalog
   entry to the phase's reporting path, for example:

   - Parser: `error(token, DiagnosticCatalog.EXPECTED_SYNTAX, ...)`
   - Resolver: `context.diagnostics.record(new ResolutionError(entry, ...))`
   - Token-anchored: `Diagnostic.atToken(entry, token, sourcePath, message)`

6. **Add a test.** Assert the code and a stable message fragment. If the diagnostic is
   actionable, assert the help text too.

## Writing a good message

- State the rule, not the parser state: "Property assignment requires a mutable reference", not
  "expected mut".
- Name the offending symbol when it helps: `Unknown function '{name}'.`
- Keep catalog titles short and stable; they are the explanation heading and the `explain` output
  title.
- Put remediation in help notes rather than in the message when it is optional advice.
- Prefer error over warning unless the program is still correct and the finding is advisory.

## Warnings

Warnings live in the same catalog with `Severity.WARNING` and mostly use `ZR30xx`. They must also
have explanation resources. Warnings:

- do not fail a build by default;
- fail under `--deny-warnings`;
- appear in the language server with warning severity.

If you promote a warning to an error, treat it as a breaking change and note it in `CHANGELOG.md`.

## Verification

```powershell
mvn test -Dtest=DiagnosticCatalogTest
mvn test -Dtest=ResolutionDiagnosticsTest
```

Then exercise the user path:

```powershell
java -cp target/classes com.maruseron.zeron.Zeron explain ZR2027
```

The release script also runs `zeron explain ZR2014` as a smoke test, so a broken catalog or missing
resource fails packaging.

## Common mistakes

- Explanation heading does not match the catalog title exactly.
- Code added to the catalog but not to `ENTRIES` (or the reverse), so `explain` cannot find it.
- Missing `errors.md` entry, leaving the user index incomplete.
- Forgetting to update the entry-count assertion.
- Reporting a diagnostic during compilation that belongs in resolution. Emit it in the phase that
  can point at the source construct.
- Reusing a code for a second condition because it is "close enough".

## Related

- [`change-surface-map.md`](./change-surface-map.md)
- [`testing-and-verification.md`](./testing-and-verification.md)
- `guide/29-formatting-and-diagnostics.md`
