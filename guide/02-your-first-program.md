# 2. Your first program

The project created in [subject 1](01-install-and-create-a-project.md) contains an entry file,
`src/main.zn`. A Zeron program starts from a zero-argument `main` function in that entry unit:

```zeron
fn main(): Unit {
    println("Hello, Zeron!");
}
```

`Unit` is the type for a function that has no useful result. The standard output functions are
available to the bundled source standard library. Build the project and then run it:

```text
zeron build
zeron run
```

`build` compiles the project described by `zeron.toml` into its configured output directory.
`run` builds the same project and launches its entry point. For a compiler diagnostic with a code,
use `zeron explain <code>` to read the bundled explanation.

The entry function belongs to the configured entry source file; the build does not search for an
arbitrary function named `main` in every package and guess which one to launch. Keep startup work
small and delegate useful behavior to named functions. As the program gets bigger, this makes it
easier to test individual operations and keeps the command-line entry predictable.

If the compiler reports that `println` is unknown, check the import: standard-library names are
not all automatically in scope. If the import itself fails, confirm that the project uses the
source standard library or has a compatible compiled standard-library library configured.

Try changing the message and running `zeron run` again. A compile error is reported during the
build phase and prevents the program from launching; a runtime failure happens after compilation,
when generated code is executing. Keeping these phases distinct helps narrow down problems.

The starter `main` is intentionally small. As the program grows, move reusable functions and
types into other source files under a configured source root. Mark declarations `public` when they
need to be imported from another package. See
[packages and imports](11-packages-namespaces-and-imports.md) for how source organization controls
which names are visible across files.
