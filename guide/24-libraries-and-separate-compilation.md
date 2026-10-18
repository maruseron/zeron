# 24. Libraries and separate compilation

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

To consume a local Zeron class directory or JAR in a project, declare it in the manifest:

```toml
[dependencies]
zeron-libraries = ["lib/shared.jar"]
```

Use `zeron build` to compile against the manifest dependencies and `zeron run` to include those
dependencies when launching the entry class. Compiled-standard-library mode requires a compatible
standard-library artifact to be listed as a Zeron library. Java dependencies use the separate
`java-classpath` key.

For API inspection, run `zeron dump <api-index|class-directory|library.jar>`. This prints the
declarations exported by that library index. The index is not a substitute for runtime classes: an
index alone may allow resolution but will not provide implementation classes when the program
launches.

Dependency fetching, lockfiles, incremental compilation, and configurable packaging are not
provided by the current project model; dependencies are local paths. See
[design document 11](../documents/design-document-11_compilation-libraries-and-host-integration.md)
for the manifest and library compatibility details.
