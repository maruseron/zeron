# Zeron

Zeron is an experimental, statically typed, hybrid programming language for the JVM. It aims for
the expressive power of highly academic languages like Scala and Haskell with the ergonomics of
productivity-focused languages like Kotlin and Swift. It is built around a simple belief: you will
read your code far more often than you write it, so the language should make that reading pleasant,
and make the important things impossible to miss. For the impatient ones that would like to see
what Zeron has to offer from the get go, here's a sneak peek:

```zeron
import zeron.collections.Array.stream;
import zeron.collections.Iterable;
import zeron.numeric.Numeric;
import zeron.math.PI;

data class Point(x: Float, y: Float) {}

// sum and product types
type Shape =
      Circle(center: Point, radius: Float) 
    | Rect(corner: Point, width: Float, height: Float);

// single expression function body
fn area(shape: Shape): Float = match (shape) {
        case Circle(_, r) -> PI * r * r;
        case Rect(_, w, h) -> w * h;
    };

// generic function and operator overloading
fn total<T: Numeric<T>>(items: Iterable<T>, zero: T): T {
    let mut sum = zero;
    for (let item in items) {
        sum += item;
    }
    return sum;
}

fn main() {
    let shapes = [ 
        Shape.Circle(Point.new(0.0, 0.0), 1.5), 
        Shape.Rect(Point.new(1.0, 1.0), 2.0, 3.0) 
    ];

    // for expression: no parentheses, yields a value
    let areas = for shape in shapes yield area(shape);

    // trailing lambda syntax
    let big = shapes.stream().filter { shape -> area(shape) > 6.0 };

    println("Total area: " + total(areas, 0.0));

    // no ternary: if expressions instead
    println(if (big.count() > 0) then "Some big shapes" else "All small");
}
```

## Design philosophy

Zeron makes a handful of promises. None of them is revolutionary on its own, but I think they
add up to a language that is easy to trust.

### Code is read more than it is written

Zeron prefers words to symbols. Boolean logic is `and`, `or`, and `not`. Type tests are `is`
and `is not`. A conditional can be an expression: `if (count == 1) then "one" else "many"`.
Symbols stay where everyone already knows them (arithmetic, comparisons) or where a word
would only add noise (`?.` and `??`).

Concise does not mean cryptic. Types are inferred when the compiler can see them, there is no
ceremony around starting a program, and there is no `++` because `+= 1` is not that much
longer, and nobody has ever had to reread it twice.

### Nothing important happens behind your back

- **Mutation is a choice you make.** `let` bindings are immutable. `let mut` allows
  rebinding, and a reference marked `&T` carries the permission to mutate what it points to.
  Most values never change, so the few that do get to stand out.
- **Absence is part of the type.** A `String` is never `null`. A `String?` might be, and the
  compiler makes sure you deal with it.
- **Failure is not a surprise party.** Zeron has no unchecked exceptions. `Option` models
  possible absence, `Result` models success or error, and typed raised effects model
  exceptional control flow. They are separate tools and never convert into one another
  behind the scenes. The few deliberate ways to fail at runtime, like the `!!` operator, are
  short, visible, and easy to search for. If your program is going to crash, it will be
  because you said so.

### The principle of least surprise

When Zeron has to choose between clever and predictable, predictable wins. `==` compares
values and `===` compares identity. Contract conformance, witness evidence, and extensions
are all resolved statically, so what runs is what you can see. Compilation errors and runtime
failures are different phases, and the compiler never guesses which `main` you meant.

### Hybrid language

Zeron has classes, contracts, namespaces, and extensions. It also has immutability by default,
algebraic data types, pattern matching, lambdas, effects, and typeclass-style witnesses. I believe
both have their place: objects are incredibly expressive as a tool to model boundaries and internal
information, and ADTs, pattern matching and higher-order functions are incredibly ergonomic as a
way to model logic. Zeron wants you to use both: model a domain with classes where objects fit,
with sum and product types where data fits, and mix them freely in the same project, or the same
file, or the same function. I don't judge. The compiler won't either.

### Platform honesty

Zeron emits JVM class files along with a binary sidecar that contains an index with all signatures
and documentation found in your project; and can use a deliberately limited Java API surface
through explicit classpath configuration. Generic type arguments are erased at runtime, as on the
JVM, and Zeron passes hidden method-handle evidence where bounded generic code needs it. Where
there are rough edges, the guide and design documents say so.

## Language features

The implemented language includes:

- Functions, default and variadic parameters, function values, lambdas, generic functions and
  methods, polymorphic function types, and generic lambdas.
- Classes, properties, constructors, contracts, contract composition, and statically dispatched
  extensions.
- Generic bounds and witnesses, higher-kinded abstractions, checked covariance, and use-site
  projections, with compile-time evidence selection.
- Arrays, integer ranges, the `Iterable<T>`/`Iterator<T>` protocol with `Step<T>` results, mutable
  lists, and lazy streams.
- Clause-based for-expressions with ordered generators, local bindings, guards, and lazy `yield` or
  eager `collect` results.
- Numeric operator overloading through explicit `Numeric`, `Integral`, `Fractional`, and `Floating`
  contracts, exact context-directed numeric literals, arbitrary-precision `Integer` and `Rational`
  types, and class-defined literal suffixes.
- Sum and product types, guarded newtypes, flow-sensitive type tests and casts, and pattern matching
  with public-property and class-defined patterns.
- `Option<T>`, `Result<T, E>`, evidence-driven equality and ordering, and typed synchronous raised
  effects, with synchronous `using` scopes for disposable resources.
- Generic admissibility markers for nullable, effect, and mutable-reference type arguments.
- Packages, imports, separate compilation and library indexes, a REPL, project manifests, and
  selected Java interoperability.

Zeron is currently **alpha**. Some areas are intentionally partial: the Java interop surface is
curated rather than general, generic inference and variance have documented boundaries, and generic
type arguments are erased at runtime. Witness-overlap analysis and pattern exhaustiveness also have
documented limits. The guide and design documents describe feature-specific rules and limitations.
Zeron 0.7.0 targets stable Java 25 or later.

## Get started

See the [Zeron Guide](guide/README.md) for installation, project creation, language concepts, and
examples. It assumes JDK 25 or later. The guide explains how to build and run projects with the
packaged launcher or from a source checkout.

Check which compiler distribution is active with `zeron version` (or `zeron --version`). The
command works from any directory and does not require a project manifest.

For contributors, build with Maven:

```sh
mvn package
```

Run the test suite:

```sh
mvn test
```

The project includes a compiler-backed language server for diagnostics, completion, hover, and
definition navigation, a project-aware `zeron fmt` formatter with `--check` mode, and a portable
launcher distribution workflow. The language server currently supports single-project workspaces;
see the [VS Code extension](editors/vscode/README.md) for setup and its current limitations.

The [language overview](documents/design_documents/design-document-00.md) summarizes implemented
features and remaining work. The [design documents](documents/design_documents/) contain deeper
specifications and implementation notes.

Contributor guidance for compiler changes, registries, intrinsics, and module conventions is in
[`documents/developer_guides/`](documents/developer_guides/).

## License

Unless otherwise noted, Zeron's compiler, bundled standard library, compiler-emitted runtime
support, documentation, and VS Code grammar are licensed under the Apache License 2.0; see
[LICENSE](LICENSE). Third-party components remain under their respective licenses.

Programs written by Zeron users remain under the terms chosen by their authors. Where a generated
distribution includes Zeron-authored runtime or support components, those components are covered by
the Apache License 2.0.

## VS Code syntax highlighting

The VS Code extension in [editors/vscode](editors/vscode/README.md) provides TextMate syntax
highlighting, comment toggling, bracket matching, and a language client for the Zeron language
server. The server provides diagnostics, completion, hover, and definition navigation. To try it
from a source checkout, install the extension dependencies and open a sample project in an
Extension Development Host:

```powershell
npm --prefix editors/vscode install
code --extensionDevelopmentPath=editors/vscode sample_programs/list_monad
```
