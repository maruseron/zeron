# Libraries and separate compilation

Zeron libraries have both compile-time metadata and runtime classes. The compiler emits a
versioned API index alongside generated classes; a consumer reads that index to resolve exported
Zeron declarations without recompiling the library source. Runtime use still requires the compiled
classes or JAR on the runtime classpath.

The index contains language-level information that may not be recoverable from JVM class files
alone, including applied source types and compiler metadata. It is a versioned compiler interface,
so the producing and consuming toolchain need compatible index formats. Separate compilation
allows clients to type-check against a library's public API while using its already-built
implementation.

Build the bundled standard library into a class directory with:

```text
java -cp target/classes com.maruseron.zeron.Zeron --build-stdlib target/zeron-stdlib
```

Add `--jar-output target/zeron-stdlib.jar` to produce a JAR. Keep this artifact separate from
`dist/`, which is the normal generated-program output. The standard library includes compiler-known
runtime support such as `zeron.lang.Unit`.

Release archives already contain a versioned standard-library JAR under `lib/`; the launcher sets
`zeron.home`, so projects use it without a manifest entry. Compilation resolves a compatible
`lib/zeron-stdlib-*.jar` automatically when `[platform].stdlib` is omitted, and falls back to the
bundled standard-library sources if the artifact is missing or incompatible. An explicit
`compiled` setting reports an error instead of falling back. A source checkout or test run without
`zeron.home` uses the same default resolution and can set the property to select a custom
installation.

To consume an additional local Zeron class directory or JAR in a project, declare it in the
manifest:

```toml
[dependencies]
zeron-libraries = ["lib/shared.jar"]
```

Use `zeron build` to compile against the manifest dependencies and `zeron run` to include those
dependencies when launching the entry class. An explicitly listed standard-library artifact takes
precedence over the bundled one. Java dependencies use the separate `java-classpath` key; attach
Java source directories for editor navigation with `java-sources`.

For API inspection, run `zeron dump <api-index|class-directory|library.jar>`. This prints the
declarations exported by that library index. The index is not a substitute for runtime classes: an
index alone may allow resolution but will not provide implementation classes when the program
launches.

Dependency fetching, lockfiles, incremental compilation, and configurable packaging are not
provided by the current project model; dependencies are local paths. See
[design document 11](../documents/design_documents/design-document-11_compilation-libraries-and-host-integration.md)
for the manifest and library compatibility details.
