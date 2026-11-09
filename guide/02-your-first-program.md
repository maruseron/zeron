# Your first program

The project you created in [chapter 1](01-install-and-create-a-project.md) contains an entry file,
`src/main.zn`. A Zeron program starts from a zero-argument `main` function in that entry unit.
There is nothing between the braces in the `main` function right now, which means no code for our
program to execute. Let's change that - add the following code to your entry file:

```zeron
fn main() {
    println("Hello, world!");
}
```

You might have guessed just now that `println` stands for "print line"; you'd be correct. This
program should print _Hello, world_ to the console once run. To run the project directly, use
`zeron run`, which will build it before launching:

```text
> zeron run
Hello, world!
```

The `main` function belongs to the configured entry source file (default `src/main.zn`); the build
operation does not search for an arbitrary function named `main` in every package and guess which
one to launch; that'd be dumb. As a rule of thumb, keep startup work inside of the entry file small
and delegate useful behavior to other modules within your project. As the program gets bigger, this
makes it easier to test individual operations, manage your codebase, and keeps the command-line
entry predictable.

If the compiler reports that `println` is unknown, an import has failed. Confirm that the project
can resolve a standard library: an installed distribution supplies the bundled compiled artifact,
while a source checkout uses the bundled sources. An explicit `stdlib = "compiled"` requires a
compatible artifact.

Try changing the message and running `zeron run` again. Compilation errors are reported during the
build phase and prevent the program from launching; a runtime failure happens after compilation,
when generated code is executing. Keeping these phases distinct helps narrow down problems.

The starter `main` is intentionally small. As your program grows, move reusable functions and
types into other source files under a configured source root. Mark declarations `public` when they
need to be imported from another package. See
[packages and imports](11-packages-namespaces-and-imports.md) for how source organization controls
which names are visible across files.
