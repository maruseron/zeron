# Zeron

Zeron is an experimental, statically typed programming language that compiles to JVM bytecode.
It aims to make the capabilities and behavior of a program visible in its types, while keeping
ordinary code direct and approachable.

## Design philosophy

- **Make requirements explicit.** Generic code states the operations it needs with contract
  bounds. When a type cannot declare a contract itself, a witness can provide that implementation
  without changing the target type.
- **Separate mutation from reassignment.** `let` bindings are immutable by default; `let mut`
  allows rebinding. A reference marked `&T` carries permission to mutate the referenced value.
- **Model alternatives in the type system.** Contracts describe shared behavior, while algebraic
  data types represent products, sums, and domain-specific newtypes. Pattern matching makes
  alternatives explicit and can check coverage for closed types.
- **Keep failure models distinct.** `Option` represents possible absence, `Result` represents a
  success or error value, and typed raised effects express exceptional control flow. They are
  separate tools, not automatic conversions of one another.
- **Prefer visible dispatch.** Contract conformance and witness evidence are checked and selected
  statically. Extensions are also statically dispatched. JVM generics are erased, and Zeron passes
  hidden method-handle evidence where bounded generic code needs it.
- **Use the JVM as the runtime platform.** Zeron emits JVM class files and can consume a deliberately
  limited Java API surface through explicit classpath configuration.

## Language features

The implemented language includes:

- Functions, default and variadic parameters, function values, lambdas, and generic functions and
  methods.
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
Zeron 0.5.0 targets stable Java 25 or later.

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
