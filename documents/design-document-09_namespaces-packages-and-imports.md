# Namespaces, Packages, and Imports: Language Design

## Status

Package headers, package-qualified nominal identities, explicit and star imports, aliases, and
public versus package-visible declarations are implemented. Named source namespaces group functions
and immutable values within a package, independent of project directory layout. Namespace sealing,
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
Packages continue to own declaration identity; namespaces group related declarations within a
package without imposing a source-directory convention.

## Current Boundary

- `Resolver` collects class and contract declarations before resolving the program, keyed by their
    package-qualified nominal names.
- `NominalDescriptor` stores a structured `QualifiedName`. `FunctionShapeKey` uses its canonical
    binary-name spelling as the nominal component.
- `SymbolTable` keys functions and values by source spelling; class and contract types live in separate resolver maps.
- Package and declaration identities are source-level names; the chosen JVM owners and output paths
    do not participate in name resolution. Build and output behavior is specified in doc 11.
- Imports resolve public classes, contracts, function overload families, and immutable values.
    An explicit function import names the family; call-site argument types select one public
    signature from it. Package-private
    declarations remain available within their package.

    Default parameters are properties of function and method declarations, not of their function types.
    Calls may provide any positional prefix that reaches the declaration's minimum arity; omitted
    suffixes are supplied by declaration-side wrappers. A contract's defaults are available through
    contract-typed and implementing-class-typed calls without being repeated by the implementation.
    Compiled-library consumers use the default-arity metadata and generated wrappers described in
    [design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).

## Influences

The cited languages offer several useful patterns for source-level naming and visibility:

- Kotlin gives each source file a package header and uses explicit, wildcard, or aliased imports. Top-level `internal` visibility is scoped to a compilation module. [Packages and imports](https://kotlinlang.org/docs/packages.html), [visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html).
- Scala package declarations are not defined by the directory tree, and imports can select or rename declarations. [Packages and imports](https://docs.scala-lang.org/tour/packages-and-imports.html).
- Rust distinguishes Cargo packages, compiler crates, and the module tree within a crate; `use` creates a scoped path shortcut, while `pub` controls exposure. [Packages and crates](https://doc.rust-lang.org/book/ch07-01-packages-and-crates.html), [`use` and paths](https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html).
- Haskell uses module headers and export lists, with selective, qualified, hidden, and aliased imports. [Haskell 2010, Chapter 5](https://www.haskell.org/onlinereport/haskell2010/haskellch5.html).
- On the JVM, source packages and imports are distinct from JPMS modules. The normative source-language reference is [JLS Chapter 7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html); build, runtime classpath, and JPMS concerns are covered in [doc 11](design-document-11_compilation-libraries-and-host-integration.md).

The implemented source model uses package headers, explicit and star imports, aliases, and public
declarations. Re-exports and module-level dependency semantics remain deferred.

## Goals and Non-Goals

Goals for the source-language layer:

- Give every class, contract, top-level function, and top-level value a stable source identity;
  namespace members additionally have a package-qualified namespace path.
- Allow same-spelled declarations in different packages without confusing type equality or function-shape keys.
- Make imported names, visibility, and ambiguity deterministic and statically checked.
- Keep package identity independent of JVM names; project builds validate that source directories
  match package declarations relative to their configured source root.

Non-goals for this language-design document:

- JPMS `module-info`, module-path resolution, services, or `opens`.
- Re-exports, extension imports, or a dependency repository.
- File-local visibility, friend modules, or a general build-system manifest.
- Project source discovery, compiled-library distribution, Java interop, and intrinsic implementation
    binding; see [doc 11](design-document-11_compilation-libraries-and-host-integration.md).

## Source Model

A source file is one compilation unit and has at most one package header before its declarations.
Package membership is declared in source and defines identity. In project-root builds, the source
directory relative to its configured root must match the package path; standalone file compilation
does not impose a directory convention. The default package remains available for root-level project
sources and standalone scripts. Explicit and star imports resolve public classes, contracts, functions, and
immutable values.

Implemented package, import, and namespace syntax:

```text
CompilationUnit       ::= [PackageDeclaration] ImportDeclaration* TopLevelDeclaration* EOF
PackageDeclaration    ::= "package" QualifiedName ";"
ImportDeclaration     ::= "import" QualifiedName ["as" Identifier] ";"
                         | "import" QualifiedName "." "*" ";"
QualifiedName         ::= Identifier {"." Identifier}
TopLevelDeclaration  ::= VariableDeclaration | FunctionDeclaration | ClassDeclaration
                         | ContractDeclaration | NamespaceDeclaration
                         | "public" (VariableDeclaration | FunctionDeclaration | ClassDeclaration
                         | ContractDeclaration)
NamespaceDeclaration ::= "namespace" Identifier "{" NamespaceMember* "}"
NamespaceMember      ::= ["public"] (FunctionDeclaration | VariableDeclaration)
```

### Named namespaces

A namespace is a compile-time bag of named functions and values, declared with a block such as
`namespace Array { ... }`. It is not a type or runtime value: it cannot be constructed, passed, or
returned. A qualified call such as `Array.sort(values)` resolves directly to the namespace member
function, not through runtime receiver dispatch. Namespace names occupy a
separate name category from types and values, so a namespace and a type may share a simple name;
`Array<T>` in a type context denotes the array type while `Array.sort(...)` denotes a qualified
namespace function.

Namespace identity is qualified by its containing package, not by its source file or directory.
Namespace blocks with the same name in different source files of the same package contribute to the
same namespace; duplicate member names are diagnosed. Members retain their ordinary declaration
visibility. The namespace's source location does not alter the project's package-path validation.
Nested namespaces and namespace-level visibility are not supported.

Namespace functions are otherwise ordinary functions: each declares its own type parameters,
parameters, and return type. Namespace values are immutable `let` declarations and use the ordinary
top-level initialization ordering and cycle rules. They may have runtime initializers; no special
constant syntax or compile-time folding guarantee is provided.

Namespace blocks may be reopened by source files in the same package. They do not grant external
code permission to add members. An explicit rule sealing a namespace against contributions beyond
their owning library is a separate deferred feature; it is not part of the namespace model.
Public namespace members can be addressed by their qualified namespace path or imported individually
with an explicit member import. Wildcard imports select public declarations in a package, not all
members of a namespace as a separate operation.

Imports resolve class, contract, function, and immutable value targets, including namespace members.
Star imports enumerate public Zeron declarations in a known package; Java package enumeration is
unsupported. Non-entry functions
require explicit return types so their signatures are available before bodies are resolved.
Every project top-level value requires an initializer. Project-local values are predeclared for
cross-unit resolution; inferred values can depend on earlier inferred values and on explicit-type
forward dependencies. Public immutable project values may be imported explicitly or through star
imports. Compiled libraries also export ordinary public immutable top-level values and public
namespace values; metadata preserves their declared types and generated storage/initialization
owners. Private values are available to units in the same package but cannot be imported from another
package. Public mutable values remain unsupported.

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
import geometry.*;

fn main(): Unit {
    let point: GeoPoint = GeoPoint.new(0, 0);
    println(sum(point.readX(), 1));
}
```

Imports are file-scoped and name declarations, not expressions. Explicit and star imports resolve
public classes, contracts, functions, and immutable top-level values; function imports participate in
ordinary calls and function-value resolution. Explicit imports take precedence over star imports.
Multiple star imports that provide the same unqualified name are ambiguous when that name is used;
an explicit import or alias disambiguates it. In a type annotation, explicitly import a type when
multiple star-imported packages are in scope so its qualified type identity is unambiguous. Star
imports are not re-exports. Dot remains the receiver-member operator.

### Project value initialization

All initialized project top-level values, including values in the entry source unit, initialize
eagerly once when the generated entry class is initialized, before its `main` method can run.
Dependencies are initialized before dependents. The compiler finds direct value reads and reads
through statically resolved Zeron function calls; ties are ordered by package name, configured source
root order, normalized root-relative source path, and declaration order. Standalone explicit-file
compilation uses source-path order after package name.

Statically discoverable dependency cycles are compile-time errors. Generated value accessors also
guard against a read before its value is initialized and throw `IllegalStateException` instead of
exposing a JVM default field value. If an initializer throws, JVM class initialization fails with
`ExceptionInInitializerError` preserving the original cause; later initializers and entry `main` do
not run. A compiled-library value getter invokes the provider's generated initialization gateway on
first access, triggering the provider's ordinary dependency-ordered initialization once. The
consumer does not initialize a provider that it never reads or replay the provider's dependencies.
The API index records the gateway owner; initializer bodies and private dependencies remain inside
the provider artifact.

## Namespaces and Resolution

Preserve two semantic namespaces already reflected in the resolver:

- The **type namespace** contains classes and contracts, which share one package-level namespace. Type parameters remain scoped to their declaration, and built-in type names remain reserved.
- The **value namespace** contains top-level functions and values plus local bindings. Local lexical bindings shadow imported values as they do today.

A structured `QualifiedName` should carry package components and a declaration name. Type equality, assignability, contract projection, and `FunctionShapeKey` use this source identity; JVM names remain a lowering detail. A package named `geometry` may therefore contain `Point`, while `graphics.Point` is a distinct nominal type.

For unqualified lookup:

1. Resolve local values in lexical scope, or type parameters in type position.
2. Resolve declarations in the current package.
3. Resolve explicit imports and aliases, then unique star-import candidates.
4. Resolve built-ins/predefined names in their existing namespaces.

An import does not silently shadow a different declaration in the current package. Two imported declarations with the same local name in the same namespace are an ambiguity error; use `as` to disambiguate. Type and value imports resolve in their respective syntactic contexts. Fully qualified type names bypass imports.

For classes, contracts, and functions, declarations marked `public` are importable from other
packages. Unmarked declarations are package-visible. The initial design has no top-level `private`
or `internal` modifier; package-private defaults avoid accidentally exporting a package's
implementation. Existing default-package scripts remain mutually visible as before.

Explicit imports target individual classes, contracts, functions, and immutable values, with aliases.
Star imports, re-exports, and value imports are distinct; imports do not re-export declarations.
Built-in types remain
implicitly available, but there is no implicit wildcard import of a standard library or `java.lang`.
The CLI compiles the bundled
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
4. **Named namespaces: implemented first slice.** Reopenable compile-time groups of functions and
   immutable values, package-qualified but independent of file and directory layout. Qualified member
   access and individual member imports are supported; namespaces have no runtime identity.
5. **Deferred language-level imports and ownership.** Re-exports, extension imports, and friend/module
   visibility require separate semantics.
6. **Namespace sealing: deferred.** Define library ownership and contribution boundaries before
   preventing downstream namespace augmentation.

## Acceptance Criteria

The following are acceptance criteria for the source naming and import model:

- Same-simple-name classes/contracts in different packages have distinct source identities and generated binary names.
- Same-package declarations are available without imports; only public declarations are imported across packages.
- Explicit and star imports resolve public classes, contracts, and top-level functions; ambiguous star-import uses fail deterministically, and explicit imports disambiguate them.
- Package-qualified type identity propagates through generic substitution, contract projection, and `FunctionShapeKey`.
- Imports and aliases are compile-time bindings; they do not by themselves load code or change source identity.
