# Generated artifacts and release

This page covers what is generated, what is safe to delete, how to inspect output, and what the
release script actually validates.

## Artifact map

| Path | Produced by | Committed? | Notes |
| --- | --- | --- | --- |
| `target/` | Maven | No | Build output; never treat as source |
| `target/classes/` | `mvn compile` | No | Java classes and copied resources |
| `target/generated-resources/` | `generate-resources` | No | Standard-library source index and version file |
| `target/dependency/` | `process-resources` | No | Runtime JARs for the language server launch |
| `target/test-work-<n>/` | `generate-test-resources` | No | Isolated working directory for each test fork |
| `target/zeron-<version>.jar` | shade plugin | No | Shaded compiler JAR with main class `com.maruseron.zeron.Zeron` |
| `dist/` | `zeron` compilation | No | Generated Zeron program classes at the repository root |
| `sample_programs/*/dist/` | `zeron build` / `zeron run` in samples | No | Per-sample output |

`dist/` is regenerated output, not source. Do not hand-edit or commit it, and do not hand-edit
generated class files anywhere.

## Generated during the Maven build

The `generate-resources` execution writes:

- `target/generated-resources/stdlib/sources.index` — sorted list of bundled `.zn` paths, one per
  line, rooted at `/stdlib/...`. It is generated from the filesystem, so it updates automatically
  when a standard-library file is added or removed.
- `target/generated-resources/META-INF/zeron/version.properties` — the project version.

The `generate-test-resources` execution deletes and recreates `target/test-work-1` through
`target/test-work-4`. Tests write relative output there, which is why a test that must read the
repository uses `TestPaths` instead of the current directory.

## When to delete `dist/`

Before compiling Zeron source, including tests that generate Zeron programs:

```powershell
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
```

Stale classes and generated function-shape interfaces otherwise accumulate and can be read back by
a later run. This is a `dist/` cleanup; leave `target/` alone.

## Inspecting generated output

```powershell
# JVM shape: interface names, descriptors, method signatures
javap -classpath dist zeron.runtime.Lambda$V2$1$Int$To$Int

# Public API of a compiled directory or library JAR
zeron dump dist
zeron dump --stdlib zeron.collections

# Read an API index directly
zeron dump dist/META-INF/zeron/api-v14.bin
```

The API index filename is versioned: `api-v<API_INDEX_SCHEMA_VERSION>.bin`, under
`META-INF/zeron/`. Both the directory layout and the JAR entry are defined in
[`ZeronVersions.java`](../../src/main/java/com/maruseron/zeron/ZeronVersions.java).

## Version bumps

`ZeronVersions` holds two independent versions:

- `API_INDEX_SCHEMA_VERSION` — bump only when the serialized index schema changes.
- `STANDARD_LIBRARY_API_VERSION` — bump only when the public standard-library surface changes.

Each may be bumped once per release cycle. Consolidate required changes before incrementing
either. Follow [`ZEP-0012.md`](../zeps/ZEP-0012.md) for the canonical versioning policy, and record
the bundle state in [`ZEP.md`](../zeps/ZEP.md).

When the index schema or the index filename changes, check readers and writers together, including
backward-compatibility behavior for older indexes.

## Release process

1. Bump the project version in [`pom.xml`](../../pom.xml).
2. Run from the repository root:

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts/release.ps1
   ```

The script reads the POM version and both index versions, then:

1. Runs `mvn clean package`.
2. Stages `target/release-work/zeron-<version>/` with `bin/`, `lib/`, `share/zeron/errorcode/`, and
   `share/zeron/stdlib/`.
3. Copies the shaded compiler JAR, launcher scripts, explanation resources, and all standard-library
   sources.
4. Asserts that the staged standard-library source count matches the repository.
5. Builds the compiled standard library into
   `lib/zeron-stdlib-index-v<schema>-api-v<api>.jar`.
6. Runs smoke tests:
   - `zeron explain ZR2014` includes `ZR2014: Type mismatch`.
   - `zeron version` prints exactly `zeron <version>`.
   - A generated project runs with the default compiled standard library, and does not compile
     standard-library classes into the program output.
   - The same project runs with `[platform] stdlib = "source"`.
7. Writes `target/releases/zeron-<version>.zip` and a `.sha256` sidecar.
8. Verifies required archive entries, including the compiler JAR, the versioned standard-library
   JAR, `share/zeron/errorcode/ZR2014.md`, and a bundled `.zn` source.

If you add a top-level distribution file or change the archive layout, update both the staging
copies and the required-entry checks in the script.

## GitHub release workflow

[`.github/workflows/release.yml`](../../.github/workflows/release.yml) publishes the archive when a
release tag is pushed. The workflow runs on `windows-latest` because `release.ps1` uses the Windows
launcher and PowerShell path conventions, and it installs JDK 25 with Maven caching.

The workflow:

1. Accepts tags such as `0.7.0` or `v0.7.0`, and rejects `*-program-bundle` tags.
2. Checks that the tag, with an optional leading `v`, exactly matches the `pom.xml` version.
3. Runs `scripts/release.ps1`, so the full Maven test suite and archive smoke tests remain the
   publication gate.
4. Extracts the matching `## [<version>]` section from `CHANGELOG.md` and fails if that section is
   missing or empty. The section, rather than GitHub's generated summary, becomes the release body.
5. Creates the GitHub Release with `--notes-file`, or refreshes the notes and uploads replacements
   with `--clobber` when the release already exists. The uploaded assets are
   `zeron-<version>.zip` and its `.sha256` sidecar. Prerelease versions are marked as GitHub
   prereleases.

The workflow uses the repository's `GITHUB_TOKEN` with `contents: write`; no additional publishing
secret is required for GitHub Releases.

## Failure signatures

| Symptom | Likely cause |
| --- | --- |
| Tests see old generated classes or shape interfaces | Stale `dist/`; remove it |
| Index read fails after a schema change | `API_INDEX_SCHEMA_VERSION` not bumped, or reader/writer drift |
| `explain` works in the IDE but fails in the release | Explanation resource missing from the staged archive |
| Release fails on the standard-library source count | A `.zn` file was added or removed without a fresh packaging run |
| Language server launches stale code | Re-run `mvn compile`; the extension prefers `target/classes` |
| `zeron` prints a version different from `pom.xml` | Stale JAR or stale `version.properties`; rebuild |

## Checklist for public-API changes

- [ ] Index writer and reader updated together.
- [ ] Round-trip test added or updated.
- [ ] Schema version bumped only if serialization changed, and only once this cycle.
- [ ] Standard-library API version bumped only if the public surface changed.
- [ ] `zeron dump` output reviewed.
- [ ] `guide/24-libraries-and-separate-compilation.md` updated if user-visible.
- [ ] `CHANGELOG.md` updated.

## Related

- [`testing-and-verification.md`](./testing-and-verification.md)
- [`change-surface-map.md`](./change-surface-map.md)
- `guide/24-libraries-and-separate-compilation.md`
- `guide/25-configuration-releases-and-roadmap.md`
