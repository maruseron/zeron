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
- Generic bounds and witnesses, including applied contract types and compile-time evidence
  selection.
- Arrays, integer ranges, the `Iterable<T>`/`Iterator<T>` protocol, mutable lists, and lazy streams.
- Lazy for-expressions with `map`, `flatMap`, `filter`, `drop`, `take`, `dropWhile`, and `takeWhile`,
  or direct collection into a mutable sink.
- Sum and product types, guarded newtypes, flow-sensitive type tests and casts, and pattern matching
  with public-property and class-defined patterns.
- `Option<T>`, `Result<T, E>`, evidence-driven equality and ordering, and typed synchronous raised
  effects.
- Packages, imports, separate compilation and library indexes, a REPL, project manifests, and
  selected Java interoperability.

Zeron is currently **alpha**. Some areas are intentionally partial: the Java interop surface is
curated rather than general, generic bounds are not supported on generic classes or contracts,
generic type arguments are erased at runtime, and several features and type-system extensions
remain under development. The guide and design documents describe feature-specific rules and
limitations.

## Get started

See the [Zeron Guide](guide/README.md) for installation, project creation, language concepts, and
examples. It assumes JDK 26; Zeron currently uses Java preview features. The guide explains how to
build and run projects with the packaged launcher or from a source checkout.

For contributors, build with Maven:

```sh
mvn package
```

Run the test suite with preview features enabled:

```sh
mvn -DargLine=--enable-preview test
```

The [language overview](documents/design-document-00.md) summarizes the implemented foundations and
planned work. The [design documents](documents/) contain deeper specifications and implementation
notes.
