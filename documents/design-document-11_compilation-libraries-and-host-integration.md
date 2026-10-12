# Compilation, Libraries, and Host Integration

## Scope

This document covers how source units are discovered and compiled, how Zeron libraries are indexed
and distributed, and how Java classpath metadata interop reaches the backend. Source-level qualified
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
  importable; public mutable values remain unsupported. Compiled-library metadata exports both
  ordinary public immutable top-level values and public namespace-member values. A provider's
  values initialize in provider order when the first exported value is read; unused libraries are
  not initialized. Non-entry functions require explicit return types.
- Bundled source mode is the default and includes every `.zn` file under
  `src/main/resources/stdlib/`. Maven generates a sorted classpath source index from that tree during
  resource generation; runtime loading uses the index, so adding a standard-library source does not
  require a separate registration-list edit. Standard-library declarations remain explicit imports,
  and the canonical source tree mirrors package names.
- The compiler writes `META-INF/zeron/api-v22.bin`. The index has schema version 22 and carries the
  required standard-library API version, currently 15.
- Zeron libraries can be built into a class directory and consumed in compiled mode. Library loading
  validates the API-index schema and standard-library API versions and creates metadata-only
  declarations; consumer compilation does not re-emit library classes. Zeron library JARs are also
  supported, with the same index and compatibility checks.
- Java interop reads public class-file metadata from ordered, explicit class-directory and JAR roots. Public
  constructors and declared methods are callable, including expanded varargs for supported component
  types. A separate curated source facade exposes only `java.lang.System.out` and
  `java.io.PrintStream.print(Object)` / `println(Object)`; this does not enable general JDK discovery.
  Ordinary Java member interop supports `Object[]` parameters and results with explicit mappings;
  typed reference and primitive array signatures remain unsupported.
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
and prevents later values and `main` from running. Only public immutable values may be imported; public mutable values remain unsupported. The compiled
library API index exports ordinary top-level and namespace-member immutable values, including their
declared type, storage owner, optional namespace, and provider initialization owner. Generated
program classes are written to `dist/`; compiler classes remain under Maven's `target/classes`.

The single-file command remains available for default-package scripts. Multi-file project builds use
`--root <directory>` and `--entry <source-file>`. More general build manifests, incremental
compilation, and configurable artifact layouts remain open. When the entry source unit declares
zero-argument `fn main(): Unit`, the generated entry class also includes the standard
`public static void main(String[] args)` launcher method, which delegates to the Zeron function.
Run a generated launcher from `dist/` with `--run-class <path-to-class-file>`, for example
`--run-class dist/main.class`. The command launches a child JVM with `dist/`, the compiler runtime,
and a cached standard-library JAR named for the current API-index schema and standard-library API
versions (for example, `target/zeron-stdlib-index-v22-api-v15.jar`). If that versioned JAR is missing,
it is built; `--stdlib-jar <path>` overrides the default. Repeatable `--library` arguments add
validated library JARs or class directories to the runtime classpath. Java classpath roots used for
metadata discovery are not recorded in generated classes or automatically reused at launch; pass
each required `--java-classpath <class-directory-or-JAR>` again with `--run-class` to add those
runtime dependencies to the child JVM. Both directory and JAR roots are supported. If launching
outside this command, the host is responsible for supplying all required Java dependencies on its
runtime classpath.

## Zeron Library Artifacts

The API index is a binary sidecar at `META-INF/zeron/api-v22.bin`. Its schema version is separate from
the standard-library API version. It records qualified public signatures, generated JVM owners,
declaration and method generic parameters, nullability, reference views, mutability markers, callback
shapes, and each callable's minimum accepted arity. Public property requirements and getter/setter
capabilities are recorded alongside
class and contract signatures. Contract exports retain transitive-composition roots as applied
`requires` types. Effect classes carry an effect marker, and callable signatures retain their declared
raised-effect types. Sealed contracts additionally export their permitted class templates.
Method type parameters and references to enclosing class or contract parameters use the same scoped
type encoding as generic function signatures. Function and value exports carry an explicit namespace
name when declared inside a namespace; ordinary top-level value exports have no namespace. Callable
exports retain their full-arity signatures and record both minimum arity and whether the final
parameter is variadic. Each qualified function name has at most one source declaration. Class and
contract method lists likewise have unique names; calls resolve directly to that declaration and use
its generic, minimum-arity, default-argument, and variadic metadata. Named-constructor exports also
record their variadic marker so consumers can
pack direct factory-call arguments correctly. Consumers omit only trailing defaulted fixed parameters
and call generated provider-side JVM wrappers; direct variadic arguments are packed by the consumer,
while function values keep the full array-shaped signature. Default expressions are not serialized or re-evaluated by
consumers. Removing defaults removes wrappers and can break existing binaries; changing a default
expression changes behavior for callers running against the updated provider. Value exports record
both their generated storage owner and the provider's initialization-gateway owner.
Explicitly exported extension methods record their receiver type, receiver type-parameter count,
callable signature and arity metadata, receiver mutability, and generated static-method owner.
Consumers reconstruct these declarations from index metadata and invoke them as receiver-first static
calls; extension bodies and private implementation details are not exported. Extensions are activated
only by explicit extension imports, not star imports, and are considered after applicable visible
instance methods. The index does not contain initializer bodies or private implementation details.

Generated value accessors call the provider gateway before returning a value. This active use triggers
the provider entry class's JVM initialization, which executes the provider's existing
dependency-ordered initializer plan exactly once. Thus a first read initializes the provider's value
set, including private dependencies, rather than computing each immutable value independently.
Libraries that are not read remain uninitialized. The consumer does not need or receive the
provider's initializer bodies.

`--build-stdlib <output-directory>` compiles the canonical bundled units as a class directory.
`--jar-output <file.jar>` packages a successful source/project compilation from a temporary staging
directory, leaving the default `dist/` output unchanged when the option is omitted. When combined
with `--build-stdlib`, the requested class directory is preserved and also packaged as a JAR.
Archives contain the compiled classes and `META-INF/zeron/api-v22.bin`, with entries in deterministic
order. The JAR is published only after packaging succeeds.

Consumers may select a Zeron library class directory or JAR with `--library`; compiled standard
library mode uses `--stdlib compiled --library <artifact>`. JAR contents are used both for compile-time
API-index loading and, when needed, as a normal runtime classpath entry. A consumer rejects an index
whose schema version is unsupported or whose required standard-library API version does not exactly
match its compiler. Increment `StandardLibrary.API_VERSION` when the bundled source contract changes
incompatibly; do not use it as a substitute for the binary index schema version. The index schema is
independent of its JAR packaging. The current schema is version 22; it includes extension exports,
extension-property markers, effect markers, raised-effect signatures, witness and pattern metadata,
and applied contract requirements alongside callable and value exports.

`ZeronLibraryIndexDump` accepts an index file, a class directory, or a Zeron library JAR.

The ordinary project index omits bundled declarations to avoid duplicate exports. The dedicated
standard-library build includes them.

## Java Interoperability

The CLI accepts repeatable `--java-classpath <class-directory-or-JAR>` roots for user-provided
classes. Roots are searched in command-line order, and the requested binary name is looked up
directly as a class-file path; roots are not scanned for packages and do not create implicit imports.
For multi-release JARs, lookup uses the highest versioned entry applicable to the running JDK,
falling back to the base entry; versioned entries are ignored unless the JAR manifest marks the
archive as multi-release. A found but malformed class entry or unreadable JAR is reported as a
resolution diagnostic and does not fall through to a later root. The reader accepts public classes
and interfaces, excluding annotation, enum, and module classes; it exposes public declared
constructors and methods, not inherited-member lookup.

JDK classes are discovered by direct binary name from system-module class files. The resolver reads
class metadata without loading or initializing classes, and only accepts classes in packages with
unqualified module exports (accessible to the generated unnamed module). JDK packages are not
scanned, do not enter the default import scope, and are not implicitly imported; use a single-type
import or a qualified type/member name. JDK star-import package discovery is not supported.
Configured classpath roots retain their ordered lookup behavior; the curated `System` and
`PrintStream` metadata comes from the JDK. Independently, the bundled standard library declares a
curated external-class facade for `java.lang.System` and `java.io.PrintStream`; only `System.out`,
`PrintStream.print(Object)`, and `PrintStream.println(Object)` are exposed through that facade.
The facade is checked against the same public field and method metadata as Java interop.
`System.out` is typed as nullable `&PrintStream?`, and calls therefore require safe navigation or
non-null flow refinement. The static field is read-only. The same narrowly curated bindings remain
available when using the compiled standard library; no extra Java classpath root is needed for them.
The `zeron.io.print` and `println` convenience functions skip output when `System.out` is null.

Public declared Java fields with supported descriptors may be read through a class qualifier when
static or through a mutable/read-only instance view when non-static. Staticness is checked at the
access site, and inherited fields are not searched. Writes are limited to non-final instance fields
through a mutable (`&T`) receiver; static writes and writes to final fields are rejected during
resolution. Java field targets retain their owner, name, descriptor, and static/final flags for
bytecode emission. Unsupported field descriptors and generic field signatures produce resolution
diagnostics rather than guessed source types.

The supported source mappings are intentionally narrow:

- Java `int`, `double`, and `boolean` map to Zeron `Int`, `Float`, and `Boolean`; `void` maps to
  `Unit`.
- `String` and boxed scalar parameters/results are nullable scalar types.
- Other Java reference parameters/results map to nullable mutable views (`&T?`). Constructors
  return non-null mutable views (`&T`), and instance calls require a mutable receiver.
- Java method overloads are selected by supported argument types. Applicable fixed-arity Java
  methods take precedence over varargs methods; remaining equal-cost candidates are errors. This is
  host-interop resolution only; ordinary Zeron declarations cannot overload. Receiver-distinct
  extension families may share a name and are selected only by receiver applicability; overlapping
  applicable extensions are ambiguous.
- Expanded Java varargs calls pack trailing arguments into the JVM's typed array, including an empty
  tail. Primitive and reference components are supported when their component type is mapped. Passing
  an already-packed Java array is not supported.
- Ordinary Java method parameters support only `Object[]`. Zeron arrays are shallow-copied to a fresh
  `Object[]` before invocation so Java writes cannot inject values that violate Zeron's invariant
  element type; element objects themselves remain shared. Java `Object[]` results map to nullable
  `Array<Any?>` without adaptation. Typed reference arrays and primitive arrays are rejected during
  resolution; Java array fields remain unsupported.
- Java exceptions propagate to the host.

Java generics, typed reference/primitive Java array signatures, callbacks/SAM conversion, inherited members,
static field writes, and JPMS module-path/readability configuration are deferred. JDK module exports
are honored for discovered classes; only unqualified exports are accessible to generated unnamed
modules. Java classpath roots provide compile-time metadata lookup only. Generated programs still
require the corresponding Java classes at runtime: `--run-class` accepts repeatable
`--java-classpath` roots and appends them to its child JVM classpath, while any other launcher must
configure its own runtime classpath.
Zeron library JAR support remains separate from `--java-classpath` and must not be inferred from the
Zeron API-index reader.

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

The curated bundled facade uses `external class` declarations and ordinary Java member invocation.
It is limited to two JDK owners and three members. Other public types in exported JDK packages are
available through direct Java symbol discovery, subject to the supported interop mappings; generic
types and unsupported signatures remain unavailable.

## Roadmap

1. **Project source sets and package-path output: implemented.** `--root`/`--entry`, multi-unit
   resolution, package paths, deterministic function holders, and dependency-ordered project value
   initialization are available. Public namespace and ordinary top-level immutable values are
   included in compiled-library metadata, with provider initialization triggered on first read.
2. **Zeron library artifacts: implemented.** Versioned API-index production/loading, class-directory
   libraries, deterministic JAR packaging/loading, and compiled standard-library mode work. Both
   index-schema and standard-library API compatibility are checked.
3. **Java interop: initial slices implemented.** Ordered class-directory and JAR roots support
   direct public class metadata lookup, constructors/methods, mapped types, Java overload selection,
   mutable receivers, expanded varargs, and host exception propagation. Multi-release JAR lookup
   selects the highest entry applicable to the running JDK; malformed requested class entries produce
   resolution diagnostics. Direct JDK module metadata lookup accepts public classes only from
   unqualified-export packages and never initializes classes. Only explicitly imported or qualified
   JDK symbols resolve; package scanning and implicit imports remain unsupported. Public declared
   fields with supported descriptors can be read; non-final instance fields can be written only
   through mutable receivers. Static writes, inherited fields, callbacks, and JDK module-path
   configuration remain out of scope. The curated facade adds `System.out` and
   `PrintStream.print`/`println`.
4. **Expand distribution and host integration deliberately.** Add each Java feature as a separately
   tested mapping rather than broadening metadata lookup implicitly. Intrinsic architecture is
   tracked in doc 12.

## Acceptance Boundaries

The implemented Zeron library boundary accepts class directories and JARs and requires explicit
dependencies. Java interop metadata discovery accepts ordered class directories/JARs and exported
JDK-module class files. Generated programs need their own output directory plus each Zeron library
artifact and Java class directory or JAR on the runtime classpath. The `--run-class` launcher
constructs that runtime classpath from `--library` and `--java-classpath`; external launchers must
provide the same dependencies themselves. Imports provide compile-time name resolution; they do not
load or package runtime code.

Future work should preserve that separation among source package identity, compilation units,
library artifacts, and Java symbols. JPMS modules remain distinct from source packages; intrinsic
bindings are covered in doc 12.
