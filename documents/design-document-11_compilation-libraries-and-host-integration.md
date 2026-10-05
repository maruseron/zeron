# Compilation, Libraries, and Host Integration

## Scope

This document covers how source units are discovered and compiled, how Zeron libraries are indexed
and distributed, and how Java class-directory interop reaches the backend. Source-level qualified
names, packages, imports, visibility, and lookup rules belong in
[design-document-09_namespaces-packages-and-imports.md](design-document-09_namespaces-packages-and-imports.md).
This document describes the build and runtime boundaries that consume those names; it does not make
a source package a JVM module.

## Current Status

- The CLI accepts explicit source files or project `--root` and `--entry` options. It compiles a
  source set under one output root, emits package-qualified class files, and gives top-level
  functions deterministic per-source-unit JVM holders.
- Project-local top-level values can be imported and are initialized eagerly before entry `main`.
  Initialization follows static dependencies, rejects statically discoverable cycles, guards
  read-before-initialization, and aborts startup if an initializer fails. Public immutable values are
  importable; public mutable values and compiled-library value exports remain deferred. Non-entry
  functions require explicit return types.
- Bundled source mode is the default and includes `zeron.collections` iteration, list, and
  lazy-sequence APIs, `zeron.ranges`, and `zeron.io`. Standard-library declarations remain explicit
  imports. The canonical source tree under `src/main/resources/stdlib/` mirrors these package names.
- The compiler writes `META-INF/zeron/api-v5.bin`. The index has schema version 5 and carries the
  required standard-library API version, currently 8.
- Zeron libraries can be built into a class directory and consumed in compiled mode. Library loading
  validates the API-index schema and standard-library API versions and creates metadata-only
  declarations; consumer compilation does not re-emit library classes. Zeron library JARs are also
  supported, with the same index and compatibility checks.
- Java interop reads public class-file metadata from explicit class-directory roots. Public
  constructors and declared methods are callable, including expanded varargs for supported component
  types. Top-level external function declarations can also bind to public static methods in those
  directories.
- Compiler intrinsic IDs and lowering are described separately in
  [design-document-12_intrinsics-and-external-bindings.md](design-document-12_intrinsics-and-external-bindings.md).

## Project Compilation

Project compilation discovers `.zn` files under configured roots and selects one entry source. Each
source unit's package declaration must match the directory path relative to the most-specific
configured source root containing that file; a root-level source uses the default package. Nominal
JVM classes are written below the output root using package paths. Top-level functions use
deterministic synthetic holders whose names derive from source paths. Standalone explicit-file
compilation remains available without package-path validation.

Top-level project values require initializers. Values across all configured source roots initialize
once before entry `main`, ordered by dependency and then by package name, configured root order,
normalized root-relative source path, and declaration order. Static dependencies include direct reads
and reads reached through statically resolved Zeron functions; statically discoverable cycles are
compile-time errors. Generated accessors fail on a read before initialization rather than returning a
default value. An initializer exception aborts the class initializer, preserves its original cause,
and prevents later values and `main` from running. Only public immutable values may be imported;
compiled-library value exports remain deferred. Generated program classes are written to `dist/`;
compiler classes remain under Maven's `target/classes`.

The single-file command remains available for default-package scripts. Multi-file project builds use
`--root <directory>` and `--entry <source-file>`. More general build manifests, incremental
compilation, and configurable artifact layouts remain open. When the entry source unit declares
zero-argument `fn main(): Unit`, the generated entry class also includes the standard
`public static void main(String[] args)` launcher method, which delegates to the Zeron function.
Run a generated launcher from `dist/` with `--run-class <path-to-class-file>`, for example
`--run-class dist/main.class`. The command launches a child JVM with `dist/`, the compiler runtime,
and `target/zeron-stdlib.jar` on its classpath. If the standard-library JAR is missing, it is built
and cached there; `--stdlib-jar <path>` overrides that default. Repeatable `--library` arguments add
validated library JARs or class directories to the runtime classpath.

## Zeron Library Artifacts

The API index is a binary sidecar at `META-INF/zeron/api-v5.bin`. Its schema version is separate from
the standard-library API version. It records qualified public signatures, generated JVM owners,
declaration and method generic parameters, nullability, reference views, mutability markers, and
callback shapes. Public property requirements and getter/setter capabilities are recorded alongside
class and contract signatures. Sealed contracts additionally export their permitted class templates.
Method type parameters and references to enclosing class or contract parameters use the same scoped
type encoding as generic function signatures. It does not contain bodies or private implementation
details.

`--build-stdlib <output-directory>` compiles the canonical bundled units as a class directory.
`--jar-output <file.jar>` packages a successful source/project compilation from a temporary staging
directory, leaving the default `dist/` output unchanged when the option is omitted. When combined
with `--build-stdlib`, the requested class directory is preserved and also packaged as a JAR.
Archives contain the compiled classes and `META-INF/zeron/api-v5.bin`, with entries in deterministic
order. The JAR is published only after packaging succeeds.

Consumers may select a Zeron library class directory or JAR with `--library`; compiled standard
library mode uses `--stdlib compiled --library <artifact>`. JAR contents are used both for compile-time
API-index loading and, when needed, as a normal runtime classpath entry. A consumer rejects an index
whose schema version is unsupported or whose required standard-library API version does not exactly
match its compiler. Increment `StandardLibrary.API_VERSION` when the bundled source contract changes
incompatibly; do not use it as a substitute for the binary index schema version. The index schema is
independent of its JAR packaging, so adding JAR support does not itself require a schema-version bump.

`ZeronLibraryIndexDump` accepts an index file, a class directory, or a Zeron library JAR.

The ordinary project index omits bundled declarations to avoid duplicate exports. The dedicated
standard-library build includes them.

## Java Interoperability

The CLI accepts repeatable `--java-classpath <class-directory>` roots. A requested class is found by
its binary-name path under one of those roots. The current reader accepts public classes and
interfaces, excluding annotation, enum, and module classes; it exposes public declared constructors
and methods, not fields or inherited-member lookup. Java classes are not discovered automatically
from the runtime classpath or JDK modules.

The supported source mappings are intentionally narrow:

- Java `int`, `double`, and `boolean` map to Zeron `Int`, `Float`, and `Boolean`; `void` maps to
  `Unit`.
- `String` and boxed scalar parameters/results are nullable scalar types.
- Other Java reference parameters/results map to nullable mutable views (`&T?`). Constructors
  return non-null mutable views (`&T`), and instance calls require a mutable receiver.
- Overloads are selected by supported argument types. Applicable fixed-arity overloads take
  precedence over varargs overloads; remaining equal-cost candidates are errors. Return types do not
  select overloads.
- Expanded Java varargs calls pack trailing arguments into the JVM's typed array, including an empty
  tail. Primitive and reference components are supported when their component type is mapped. Passing
  an already-packed Java array is not supported.
- Java exceptions propagate to the host.

Java generics, ordinary Java array parameters/results, fields, callbacks/SAM conversion, inherited
members, Java JAR classpath roots, JDK module discovery, and JPMS readability/exports are deferred.
Zeron library JAR support does not extend `--java-classpath`; these are separate extensions and must
not be inferred from the Zeron API-index reader.

### Top-Level JVM Function Bindings

A signature-only external function declares its source API without embedding a JVM target recipe:

```zeron
public external fn add(left: Int, right: Int): Int;
```

The compiler-side `FunctionBindingRegistry` maps the qualified Zeron name to a typed target. A direct
JVM static-method target must resolve under a configured `--java-classpath` root and match the source
signature exactly after supported Java-to-Zeron mappings. Generic owners or methods, varargs targets,
unsupported descriptors, and ambiguous matches are rejected. The compiler emits a typed Zeron bridge
in the declaration's normal function holder, so imports, calls, function references, and API-index
exports use ordinary Zeron function behavior. Curated platform bindings can use a typed
static-field/instance-method plan, such as `System.out` followed by `PrintStream.println`, without
general JDK class discovery or a source-level invocation DSL.

This is not yet an `external class` or instance-method declaration form. It does not add JDK-module
discovery; `java.io.IO` remains unavailable unless provided through a supported metadata root or a
future JDK binding provider.

## Roadmap

1. **Project source sets and package-path output: implemented.** `--root`/`--entry`, multi-unit
   resolution, package paths, deterministic function holders, and dependency-ordered project value
   initialization are available. Compiled-library value exports remain deferred.
2. **Zeron library artifacts: implemented.** Versioned API-index production/loading, class-directory
   libraries, deterministic JAR packaging/loading, and compiled standard-library mode work. Both
   index-schema and standard-library API compatibility are checked.
3. **Java class-directory interop: initial slice implemented.** Public constructors/methods, mapped
   types, overload selection, mutable receivers, expanded varargs, and host exception propagation
   work. Ordinary arrays, fields, inheritance, callbacks, JDK module discovery, and JARs remain out
   of scope.
4. **Expand distribution and host integration deliberately.** Define external declarations and JDK
   access requirements before adding module discovery. Add each Java feature as a separately tested
   mapping rather than broadening reflection implicitly. Intrinsic architecture is tracked in doc 12.

## Acceptance Boundaries

The implemented Zeron library boundary accepts class directories and JARs and requires explicit
dependencies. Java interop remains class-directory based. Generated programs need their own output
directory plus each Zeron library artifact and Java class directory on the runtime classpath. Imports
provide compile-time name resolution; they do not load or package runtime code.

Future work should preserve that separation among source package identity, compilation units,
library artifacts, and Java symbols. JPMS modules remain distinct from source packages; intrinsic
bindings are covered in doc 12.
