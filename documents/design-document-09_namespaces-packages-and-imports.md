# Namespaces, Packages, and Imports: Language Design

## Status

Package headers, package-qualified nominal identities, type/function imports, aliases, and public
versus package-visible declarations are implemented. Top-level value imports, wildcard imports,
re-exports, and module-level visibility remain deferred. Project source discovery, library artifacts,
Java interop, and compiler intrinsic bindings are covered separately in
[design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).

## Purpose

Names, imports, packages, and modules solve related but different problems:

- A **namespace** gives declarations stable names and separates name categories.
- A **package** groups source declarations under a qualified name.
- An **import** makes a declaration available through a local name; it does not load code or change the declaration's identity.
- A **module/library** is a build and dependency unit, potentially with its own exported API; those
    mechanisms are outside this source-language design.

Keep these layers separate. In particular, do not make a source package declaration imply a JPMS module.

## Current Boundary

- `Resolver` collects class and contract declarations before resolving the program, keyed by their
    package-qualified nominal names.
- `NominalDescriptor` stores a structured `QualifiedName`. `FunctionShapeKey` uses its canonical
    binary-name spelling as the nominal component.
- `SymbolTable` keys functions and values by source spelling; class and contract types live in separate resolver maps.
- Package and declaration identities are source-level names; the chosen JVM owners and output paths
    do not participate in name resolution. Build and output behavior is specified in doc 11.
- Imports resolve public classes, contracts, and top-level functions. Package-private declarations
    remain available within their package; values are not importable.

## Influences

The cited languages offer several useful patterns for source-level naming and visibility:

- Kotlin gives each source file a package header and uses explicit, wildcard, or aliased imports. Top-level `internal` visibility is scoped to a compilation module. [Packages and imports](https://kotlinlang.org/docs/packages.html), [visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html).
- Scala package declarations are not defined by the directory tree, and imports can select or rename declarations. [Packages and imports](https://docs.scala-lang.org/tour/packages-and-imports.html).
- Rust distinguishes Cargo packages, compiler crates, and the module tree within a crate; `use` creates a scoped path shortcut, while `pub` controls exposure. [Packages and crates](https://doc.rust-lang.org/book/ch07-01-packages-and-crates.html), [`use` and paths](https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html).
- Haskell uses module headers and export lists, with selective, qualified, hidden, and aliased imports. [Haskell 2010, Chapter 5](https://www.haskell.org/onlinereport/haskell2010/haskellch5.html).
- On the JVM, source packages and imports are distinct from JPMS modules. The normative source-language reference is [JLS Chapter 7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html); build, runtime classpath, and JPMS concerns are covered in [doc 11](design-document-11_compilation-libraries-and-host-integration.md).

The implemented source model uses package headers, explicit imports, aliases, and public declarations.
Wildcard imports, re-exports, and module-level dependency semantics remain deferred.

## Goals and Non-Goals

Goals for the source-language layer:

- Give every class, contract, top-level function, and top-level value a stable source identity.
- Allow same-spelled declarations in different packages without confusing type equality or function-shape keys.
- Make imported names, visibility, and ambiguity deterministic and statically checked.
- Keep package identity independent of directory layout and JVM names.

Non-goals for this language-design document:

- JPMS `module-info`, module-path resolution, services, or `opens`.
- Wildcard imports, re-exports, extension imports, or a dependency repository.
- File-local visibility, friend modules, or a general build-system manifest.
- Project source discovery, compiled-library distribution, Java interop, and intrinsic implementation
    binding; see [doc 11](design-document-11_compilation-libraries-and-host-integration.md).

## Source Model

A source file is one compilation unit and has at most one package header before its declarations.
Package membership is declared in source; directory layout is a project convention, not the
definition of identity. The default package remains available for existing standalone scripts.
The implemented imports resolve public class, contract, and function declarations. Top-level value
imports remain deferred.

Implemented syntax:

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
import zeron.io.println;

fn main(): Unit {
    let point: GeoPoint = GeoPoint.new(0, 0);
    println(sum(point.readX(), 1));
}
```

Imports are file-scoped and name declarations, not expressions. Imports and aliases resolve public
classes, contracts, and top-level functions; function imports participate in ordinary calls and
function-value resolution. Top-level value imports and fully qualified value expressions remain
deferred. Dot remains the receiver-member operator.

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

## Relationship to Compilation and Interop

Packages and imports define source identities and compile-time name bindings. They do not decide how
source files are discovered, where JVM classes are written, how library APIs are indexed, or how Java
classes are resolved. Those project, artifact, intrinsic, and host-interoperability boundaries are
described in [design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).

## Implementation Roadmap

1. **Qualified source identity: implemented.** Package-qualified class and contract identities remain
   distinct from JVM names and generated artifact paths.
2. **Package headers and name resolution: implemented.** Packages define declaration namespaces;
   local and imported names resolve deterministically in the type and value namespaces.
3. **Selective imports and visibility: implemented first slice.** Public classes, contracts, and
   functions can be imported with aliases; unmarked declarations remain package-visible.
4. **Deferred language-level imports.** Top-level value imports, wildcard imports, re-exports,
   extension imports, and friend/module visibility require separate semantics.

## Acceptance Criteria

The following are acceptance criteria for the source naming and import model:

- Same-simple-name classes/contracts in different packages have distinct source identities and generated binary names.
- Same-package declarations are available without imports; only public declarations are imported across packages.
- Explicit imports and aliases resolve public classes, contracts, and top-level functions; ambiguous imports fail deterministically.
- Package-qualified type identity propagates through generic substitution, contract projection, and `FunctionShapeKey`.
- Imports and aliases are compile-time bindings; they do not by themselves load code or change source identity.
