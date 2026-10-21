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
- The compiler writes `META-INF/zeron/api-v<schema-version>.bin`. The index carries both the API
  index schema version and the required standard-library API version; their canonical definitions
  are in [ZeronVersions](../src/main/java/com/maruseron/zeron/ZeronVersions.java).
- Zeron libraries can be built into a class directory and consumed in compiled mode. Library loading
  validates the API-index schema and standard-library API versions and creates metadata-only
  declarations; consumer compilation does not re-emit library classes. Zeron library JARs are also
  supported, with the same index and compatibility checks.
- Java interop reads public class-file metadata from ordered, explicit class-directory and JAR roots,
  and directly from exported JDK modules. Public constructors and declared methods are callable,
  including expanded varargs for supported component types. Bundled external-class declarations
  expose a validated allowlist for `java.lang.System.out`, `java.io.PrintStream.print(Object)` /
  `println(Object)`, selected `java.io.File` constructors and instance methods, and the NIO members
  required by `zeron.fs.Path` and whole-file text I/O; this does not enable package scanning or
  wildcard discovery.
  Ordinary Java member interop supports `Object[]` and selected primitive-array parameters and
  results with copying mappings; typed reference arrays and unsupported primitive-array shapes
  remain unsupported. Explicitly imported generic Java classes and interfaces support the implemented
  class-level generic signatures, exact type arguments, and ZEP-0052 wildcard projections for member
  access; invocation still uses erased JVM descriptors. Generic method and constructor type
  parameters are supported when their signatures and bounds are representable. Raw Java types,
  generic arrays, and generic `throws` type variables remain unsupported, as specified by
  [ZEP-0048](zeps/ZEP-0048.md).
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

The single-file command remains available for default-package scripts. Multi-file project builds can
use the explicit `--root <directory>` and `--entry <source-file>` options or `zeron build`, which
loads `zeron.toml` from the current directory; `zeron build --manifest <path>` selects another
manifest. Build settings come only from the manifest, with `--debug` accepted as an operational
option. Paths in the manifest are resolved relative to the manifest file. The initial manifest
schema is:

`zeron init` creates a starter `zeron.toml` and `src/main.zn` in the current directory. It refuses
to overwrite either file if already present.

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"

[platform]
stdlib = "source" # or "compiled"

[dependencies]
zeron-libraries = ["lib/shared.jar"]
java-classpath = ["lib/host-api.jar"]
```

`project.entry` and at least one `project.source-roots` directory are required; `project.output`
defaults to `dist`, and the standard-library mode defaults to `source`. Dependency paths are local class directories or JARs and are available for compile-time resolution;
they are not embedded into generated output. `zeron run` builds the manifest project and launches its
entry class from the configured output directory. Its child JVM classpath includes the compiler
runtime, that output directory, and every declared Zeron library and Java classpath root. Compiled
standard-library mode requires a compatible standard-library artifact in `zeron-libraries`. Unknown
keys and incorrect value types are rejected. Dependency fetching, lockfiles, incremental compilation,
and configurable artifact packaging remain open. When the entry source unit declares
zero-argument `fn main(): Unit` or `fn main(args: Array<String>): Unit`, the generated entry class
also includes the standard `public static void main(String[] args)` launcher method. The
argument-taking form receives the Java launcher arguments in order; when both Zeron forms are
declared, the argument-taking form is used by the launcher.
`zeron explain <ZR0000>` prints the bundled Markdown explanation for a diagnostic code, and compiler
diagnostics direct users to that command. The legacy `--run-class` and `--stdlib-jar` flags are
unsupported; launching a built project uses its manifest so compile-time and runtime dependencies
stay consistent.

### Local Release Prototype

Run `powershell -ExecutionPolicy Bypass -File scripts/release.ps1` from a checkout with JDK 25 or later
and Maven installed. The script runs the test/package build, stages a self-contained
compiler JAR, builds the versioned compiled standard-library JAR, copies both launcher scripts and
the source error-code Markdown and standard-library source tree, exercises `explain` and a
compiled-standard-library build/run, then
creates `target/releases/zeron-<version>.zip` and a SHA-256 sidecar. The archive is a portable
distribution requiring the supported JDK; it is not a native executable. Error explanations are
also embedded in the compiler JAR so `zeron explain` works offline. The standard-library source tree
is installed under `share/zeron/stdlib` for language-server source navigation.

## Zeron Library Artifacts

The API index is a binary sidecar at `META-INF/zeron/api-v<schema-version>.bin`. Its schema version
is separate from the standard-library API version. It records qualified public signatures, generated JVM owners,
declaration and method generic parameters, nullability, reference views, mutability markers, callback
shapes, and each callable's minimum accepted arity. Public property requirements and getter/setter
capabilities are recorded alongside
class and contract signatures. Contract exports retain transitive-composition roots as applied
`requires` types. Effect classes carry an effect marker, and callable signatures retain their declared
raised-effect types. Sealed contracts additionally export their permitted class templates.
Nominal type identities include their package-qualified source names. Member, extension, and
constructor records retain parameter names as well as their type signatures; this metadata is part of
the version-2 index schema.
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
Archives contain the compiled classes and the versioned API-index sidecar, with entries in
deterministic order. The JAR is published only after packaging succeeds.

Consumers may select a Zeron library class directory or JAR with `--library`; compiled standard
library mode uses `--stdlib compiled --library <artifact>`. JAR contents are used both for compile-time
API-index loading and, when needed, as a normal runtime classpath entry. A consumer rejects an index
whose schema version is unsupported or whose required standard-library API version does not exactly
match its compiler. Update the corresponding value in `ZeronVersions` when the bundled source
contract changes incompatibly or the binary index format changes; the two versions remain separate.
The index schema is independent of its JAR packaging. It includes extension exports,
extension-property markers, effect markers, raised-effect signatures, witness and pattern metadata,
applied contract requirements, ordered nominal product components, newtype representation and
validation markers, external-facade checked-exception mappings, and class-owned numeric literal
suffix declarations with their factory mappings alongside callable and value exports.

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
Configured classpath roots retain their ordered lookup behavior. Independently, the bundled standard
library declares external-class facades whose Java binary names must match their qualified source
names. Each facade is an allowlist: constructors, instance methods, and static properties must be
declared explicitly and their descriptors are checked against public Java class-file metadata.
Facade calls cannot use other Java constructors or methods, and undeclared instance fields are not
available through a facade. The bundled facades currently expose `System.out`,
`PrintStream.print(Object)` / `println(Object)`, and the `File(String)` constructor plus
`exists`, `isFile`, `isDirectory`, `getName`, `getPath`, `getParent`, `mkdir`, `mkdirs`, and `delete`.
`System.out` is typed as nullable `&PrintStream?`, and calls therefore require safe navigation or
non-null flow refinement. The static field is read-only. Public facade metadata is included in the
versioned library API index, so the same member allowlists apply when consuming a compiled standard
library or another compiled library that exports a facade.
The `zeron.io.print` and `println` convenience functions skip output when `System.out` is null.

Public declared Java fields with supported descriptors may be read through a class qualifier when
static or through a mutable/read-only instance view when non-static. Staticness is checked at the
access site, and inherited fields are not searched. Writes are limited to non-final instance fields
through a mutable (`&T`) receiver; static writes and writes to final fields are rejected during
resolution. Java field targets retain their owner, name, descriptor, and static/final flags for
bytecode emission. Unsupported field descriptors and generic field signatures produce resolution
diagnostics rather than guessed source types.

The supported source mappings are intentionally narrow:

- Java `int`, `long`, `double`, and `boolean` map to Zeron `Int`, `Long`, `Float`, and `Boolean`;
  `void` maps to `Unit`. Java `float` results and field reads widen to Zeron `Float`; Java `float`
  parameters and writes are rejected to avoid narrowing.
- Unannotated Java reference and array parameters/results are nullable. Facades may promise
  non-null results, which are checked at runtime. Boxed supported scalar types map to nullable
  scalar types.
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
- Java `int[]`, `long[]`, `double[]`, and `boolean[]` map to nullable `Array<Int>`, `Array<Long>`,
  `Array<Float>`, and `Array<Boolean>`. Java `Object[]` maps to nullable `Array<Any?>`. These arrays
  are copied on arguments and results; object-array elements remain shared. Java `byte[]` maps
  directly to nullable `ByteArray`; `zeron.io.byteArrayToArray` and
  `zeron.io.arrayToByteArray` provide explicit copying conversions between non-null `ByteArray` and
  `Array<Byte>`. These mappings apply to method and constructor calls and expanded varargs, not Java
  array fields. Passing an already-packed array to a Java varargs method remains unsupported.
- Java `float[]`, `short[]`, `char[]`, typed reference arrays other than `Object[]`, and
  multidimensional arrays remain unsupported.
- Java exceptions from direct Java interop propagate to the host, except that calls declaring
  checked exceptions are rejected unless they resolve through an explicitly mapped declaration.
  External-class methods and constructors can map checked exceptions to declared Zeron effects;
  external functions can do so when their configured Java binding exposes class-file exception
  metadata. Every declared checked exception must be covered, and the original exception object is
  retained as the effect payload. Unchecked exceptions and `Error`s always propagate unchanged.

Imported Java generic classes can be used with explicit type arguments and `out T` / `in T`
projections. Supported class and member generic signatures, including `? extends`, `? super`, and
unbounded wildcards, are checked against the projected owner view; generated calls still use the
erased JVM descriptor. Raw Java generic types, generic `throws` type variables, and signatures with
unsupported bounds or forms remain unsupported. Java generic arrays, typed reference arrays and
remaining primitive-array shapes, callbacks/SAM
conversion, inherited members, static field writes, and JPMS module-path/readability configuration
are deferred. JDK module exports are honored for discovered classes; only unqualified exports are
accessible to generated unnamed modules. Java classpath roots provide compile-time metadata lookup.
Generated programs also require the corresponding Java classes at runtime: `zeron run` reuses the
roots declared in the project manifest, while any external launcher must configure its own runtime
classpath.
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

Zeron's `String` extensions use curated bindings for selected immutable `java.lang.String` operations.
These bindings do not relax Java interop receiver mutability or expose arbitrary members through the
extension API. The Zeron `String` extension API exposes UTF-16 code-unit `length`, `isEmpty`,
`indexOf`/`lastIndexOf` (returning `-1` when absent), `contains`, `startsWith`, `endsWith`, and
bounds-checked `substring(start, end): Option<String>`.
`Char` represents a Unicode scalar and uses JVM `int`; Java `char` remains unsupported and has no
implicit conversion to or from `Char`. Scalar-indexed `codePoint*` String extensions normalize lone
surrogates to U+FFFD, while their `Strict` variants return `Result<_, UnicodeError>`. The compiler
copies the small Unicode support class into generated outputs and standard-library artifacts so
compiled consumers do not need the compiler JAR at runtime.

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
artifact and Java class directory or JAR on the runtime classpath. `zeron run` constructs that
classpath from the project manifest; external launchers must provide the same dependencies
themselves. Imports provide compile-time name resolution; they do not load or package runtime code.

Future work should preserve that separation among source package identity, compilation units,
library artifacts, and Java symbols. JPMS modules remain distinct from source packages; intrinsic
bindings are covered in doc 12.
