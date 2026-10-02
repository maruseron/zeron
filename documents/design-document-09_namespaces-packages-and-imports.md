# Namespaces, Packages, and Imports: Design Proposal

## Status

Package headers, package-qualified nominal identities, project source roots, per-unit top-level
function holders, and selective type/function imports are implemented. Cross-package top-level
values remain deferred pending an initialization policy. This note does not define JVM module
integration or Java interop.

## Purpose

Names, imports, packages, and modules solve related but different problems:

- A **namespace** gives declarations stable names and separates name categories.
- A **package** groups source declarations under a qualified name.
- An **import** makes a declaration available through a local name; it does not load code or change the declaration's identity.
- A **module/library** is a build and dependency unit, potentially with its own exported API.

Keep these layers separate. In particular, do not make a source package declaration imply a JPMS module.

## Current Boundary

- `Resolver` collects class and contract declarations before resolving the program, keyed by their
    package-qualified nominal names.
- `NominalDescriptor` stores a structured `QualifiedName`. `FunctionShapeKey` uses its canonical
    binary-name spelling as the nominal component.
- `SymbolTable` keys functions and values by source spelling; class and contract types live in separate resolver maps.
- The CLI accepts `--root` and `--entry`; it discovers `.zn` files under each root and uses the
    entry source filename for the entry holder. Explicit source-file lists remain supported.
- Package-qualified classes and contracts are emitted below `dist/` using package paths. Top-level
    functions are emitted into deterministic package-local holders, one per source unit. Cross-package
    top-level values cannot yet be declared.
- The bundled iteration contracts and `zeron.ranges.IntRange` are loaded as canonical source units,
  rather than being copied into each consumer package. The `..` syntax lowers to the range factory.

These facts favor introducing a structured source-level qualified name before changing JVM output paths.

## Influences

The cited languages offer several useful patterns:

- Kotlin gives each source file a package header and uses explicit, wildcard, or aliased imports. Top-level `internal` visibility is scoped to a compilation module. [Packages and imports](https://kotlinlang.org/docs/packages.html), [visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html).
- Scala package declarations are not defined by the directory tree, and imports can select or rename declarations. [Packages and imports](https://docs.scala-lang.org/tour/packages-and-imports.html).
- Rust distinguishes Cargo packages, compiler crates, and the module tree within a crate; `use` creates a scoped path shortcut, while `pub` controls exposure. [Packages and crates](https://doc.rust-lang.org/book/ch07-01-packages-and-crates.html), [`use` and paths](https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html).
- Haskell uses module headers and export lists, with selective, qualified, hidden, and aliased imports. [Haskell 2010, Chapter 5](https://www.haskell.org/onlinereport/haskell2010/haskellch5.html).
- On the JVM, source packages and imports are distinct from JPMS modules. JPMS adds module readability and package exports; build tools separately maintain compile and runtime classpaths and module paths. [Gradle Java module guide](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_modular). The normative references are [JLS Chapter 7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html) and [JVMS §4.2.1](https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.2.1).

The initial proposal adopts package headers, explicit imports, aliases, and explicit public API declarations. It defers wildcard imports, re-exports, and module-level dependency semantics.

## Goals and Non-Goals

Goals:

- Give every class, contract, top-level function, and top-level value a stable source identity.
- Allow same-spelled declarations in different packages without confusing type equality or function-shape keys.
- Compile multiple mutually referencing source units in one project.
- Make imported names, visibility, and ambiguity deterministic and statically checked.
- Preserve current single-file, default-package use during migration.

Non-goals for the first package slice:

- Java class/member interop or reflective lookup.
- JPMS `module-info`, module-path resolution, services, or `opens`.
- Wildcard imports, re-exports, extension imports, or a dependency repository.
- File-local visibility, friend modules, or a general build-system manifest.

## Proposed Source Model

A source file is one compilation unit and has at most one package header before its declarations.
Package membership is declared in source; directory layout is a project convention, not the
definition of identity. The default package remains available for existing standalone scripts.
Imports currently resolve public class, contract, and function declarations. Top-level value imports
remain deferred.

Initial proposed syntax:

```text
CompilationUnit       ::= [PackageDeclaration] ImportDeclaration* TopLevelDeclaration* EOF
PackageDeclaration    ::= "package" QualifiedName ";"
ImportDeclaration     ::= "import" QualifiedName ["as" Identifier] ";"
QualifiedName         ::= Identifier {"." Identifier}
TopLevelDeclaration  ::= VariableDeclaration | FunctionDeclaration | ClassDeclaration
                         | ContractDeclaration | "public" (FunctionDeclaration
                         | ClassDeclaration | ContractDeclaration)
```

Imports resolve class, contract, and function targets. Non-entry functions require explicit return
types so their signatures are available before bodies are resolved. Non-entry top-level values
remain unsupported until initialization order is specified.

Example producer:

```zeron
package geometry;

public class Point {
    x: Int;
    y: Int;
    public constructor new;
    public readX(): Int = this.x;
}
```

Example consumer:

```zeron
package app;

import geometry.Point as GeoPoint;
import math.add as sum;

fn main(): Unit {
    let point: GeoPoint = GeoPoint.new(0, 0);
    print(sum(point.readX(), 1));
}
```

Imports are file-scoped and name declarations, not expressions. The implemented first slice imports
classes and contracts, whose aliases are available in type positions and class-factory expressions.
Dot remains the receiver-member operator. Function/value imports and fully qualified value
expressions remain deferred.

## Namespaces and Resolution

Preserve two semantic namespaces already reflected in the resolver:

- The **type namespace** contains classes and contracts, which share one package-level namespace. Type parameters remain scoped to their declaration, and built-in type names remain reserved.
- The **value namespace** contains top-level functions and values plus local bindings. Local lexical bindings shadow imported values as they do today.

A structured `QualifiedName` should carry package components and a declaration name. Type equality, assignability, contract projection, and `FunctionShapeKey` use this source identity; JVM names remain a lowering detail. A package named `geometry` may therefore contain `Point`, while `graphics.Point` is a distinct nominal type.

For unqualified lookup:

1. Resolve local values in lexical scope, or type parameters in type position.
2. Resolve declarations in the current package.
3. Resolve explicit imports and aliases.
4. Resolve built-ins/predefined names in their existing namespaces.

An import does not silently shadow a different declaration in the current package. Two imported declarations with the same local name in the same namespace are an ambiguity error; use `as` to disambiguate. Type and value imports resolve in their respective syntactic contexts. Fully qualified type names bypass imports.

For classes, contracts, and functions, declarations marked `public` are importable from other
packages. Unmarked declarations are package-visible. The initial design has no top-level `private`
or `internal` modifier; package-private defaults avoid accidentally exporting a package's
implementation. Existing default-package scripts remain mutually visible as before.

Imports target individual classes, contracts, and functions, with aliases. Wildcard imports,
re-exports, and value imports remain deferred. Built-in types remain implicitly available, but there
is no implicit wildcard import of a standard library or `java.lang`. The CLI compiles the bundled
`zeron.collections` and `zeron.ranges` source units as ordinary units.

## Compilation and Discovery

Package support requires a compilation set, not independent file compilation:

1. Read explicit source paths or discover `.zn` units under configured source roots, with one entry unit.
2. Collect package headers and all top-level declarations before resolving signatures or bodies. This permits cross-file references and cyclic type-signature references.
3. Validate duplicate qualified names and imports, then resolve each file using its package and import environment.
4. Compile all units to one output root. Each source unit with top-level functions receives a
    deterministic holder; top-level values outside the entry unit remain rejected.

Package directory layout should conventionally mirror package components (for example, `src/main/zeron/geometry/Point.zn` for `package geometry;`), but the declared package remains authoritative. The build tool may warn when paths disagree.

Top-level functions and values need a JVM owner because the JVM has no package-level methods or
fields. Top-level functions use a deterministic synthetic holder per source unit, with names derived
from root-relative source paths. Symbol resolution keeps the source qualified name and records the
generated owner separately. Top-level values in non-entry units are currently rejected; define
initialization order and cycle diagnostics before relaxing that restriction.

Nominal classes and contracts lower to qualified binary names. For example, `geometry.Point` becomes `ClassDesc.of("geometry.Point")` and is emitted at `dist/geometry/Point.class`. The runtime classpath root remains `dist`; the generated main class's binary name includes its package when declared. Lambda shape identities must use qualified nominal source identities without depending on generated interface names.

The existing single-file command remains valid for default-package scripts. A project command/source-root mechanism is required for multi-file compilation; its CLI spelling and any project manifest are deliberately left open here.

## JVM Libraries and Modules

Language imports are compile-time name resolution. They do not locate or load bytecode by themselves. The first implementation should resolve source declarations within the selected Zeron compilation set and emit ordinary packaged class files.

Later, compiled Zeron libraries can be discovered from class directories or JARs using explicit classpath roots and a Zeron API/symbol index. Java interoperability additionally requires reading class-file metadata for types, methods, visibility, generics, and module exports; nominal-name loading alone cannot provide static type checking. Only after classpath-based library resolution exists should Zeron consider JPMS module descriptors and `requires`/`exports`. A package is a namespace, not a module.

## Implementation Roadmap

1. **Qualified identity and package headers: implemented.** Parse package headers, preserve
    structured nominal identity, compile explicit multi-package source sets, and emit package-path
    class files.
2. **Selective imports and visibility: type/function slice implemented.** Imports and aliases resolve
    public classes, contracts, and functions; package-private declarations are not importable.
3. **Project discovery and JVM output: first slice implemented.** `--root` and `--entry` discover
    units, nominal files use package paths, and top-level functions use deterministic per-unit holders.
    Specify top-level value initialization before supporting cross-unit values.
4. **Compiled libraries and JVM interop.** Add Zeron symbol metadata for class directories/JARs, then design Java class-file imports and JPMS module-path integration as separate follow-on work.

## Acceptance Criteria

The following are end-state criteria for package/import support; current support covers project source
discovery and type/function imports, but not imported values or compiled-library metadata.

- Same-simple-name classes/contracts in different packages have distinct source identities and generated binary names.
- Same-package declarations are available without imports; only public declarations are imported across packages.
- Explicit imports and aliases resolve classes, contracts, top-level functions, and values; ambiguous imports fail deterministically.
- Cross-file declarations can mutually reference signatures because registration precedes resolution.
- Package-qualified type identity propagates through generic substitution, contract projection, and `FunctionShapeKey`.
- Generated class files use package paths and load from one output root; importing a declaration does not itself trigger runtime loading.
- Existing default-package sample programs continue to compile and run unchanged.
