# Intrinsics and External Bindings: Design and Roadmap

## Scope

This document covers compiler/runtime-provided operations whose source signatures are checked by
Zeron but whose implementations are not ordinary Zeron function or class bodies. It records the
internal intrinsic registry, the two current lowering paths, and the unresolved design for broader
external or expected declarations.

Array type and operation semantics remain in
[design-document-06_intrinsic-arrays.md](design-document-06_intrinsic-arrays.md). Project
compilation, library artifacts, and Java classpath metadata discovery remain in
[design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).
Intrinsics are not a Java class lookup mechanism; Java classpath and JDK module discovery are
separate interop facilities.

## Current Status

- `IntrinsicRegistry` associates stable `IntrinsicId` values with syntax-operation signatures.
- Resolver-annotated syntax operations carry a `ResolvedIntrinsicOperation` with the selected ID,
  instantiated parameter types, and result type. The compiler dispatches on that ID.
- `FunctionBindingRegistry` maps signature-only top-level external declarations to typed JVM targets
  by qualified source name. The resolver checks the declaration signature, and the compiler emits a
  Zeron bridge for the binding. Trailing defaults are supported on these declarations through
  generated shorter-arity Zeron wrappers; the registered target remains full arity. Registered
  intrinsic bindings are also supported as targets, with signature checking against the intrinsic
  registry.
- Array literal, fill, length, read, and write operations are intrinsics. `zeron.io.print` and
  `zeron.io.println` are ordinary Zeron functions implemented through the curated Java class facade.
- `Int.toFloat()` is a public extension that calls the private external helper `zeron.lang.intToFloat`;
  the helper is bound to the `INT_TO_FLOAT` intrinsic and lowers to JVM `i2d`.
- `String` operations in `zeron.lang` are ordinary Zeron extensions backed by a curated set of
  typed immutable `java.lang.String` instance-method bindings. Their indexing units follow Java's
  UTF-16 code-unit semantics; substring validates bounds and returns `Option<String>`.
- `Float.toInt()` is a public checked conversion returning `Option<Int>`. It truncates toward zero,
  returning `Option<Int>.None()` for NaN, infinities, or values whose truncated result is outside the 32-bit
  `Int` range; fractional values in the fringe around either endpoint are accepted when truncation
  yields a representable `Int`.
- `Float.NaN`, `Float.isNaN()`, and `Float.isFinite()` expose IEEE floating-point classification.
  `Float.sign` returns `Option<Int>`: NaN has no sign, while finite values and infinities produce
  `Some(-1)`, `Some(0)`, or `Some(1)`.
- Intrinsic IDs and function bindings are internal. There is no source-level `intrinsic` keyword or
  expected-class declaration. Public `external class` declarations provide an explicit, metadata-
  checked allowlist for selected Java constructors, instance and static methods, and static properties.

## Binding Model

An intrinsic operation has two parts that must remain aligned:

1. A source-facing signature and type rules used by the resolver.
2. A stable ID that selects the compiler/runtime implementation.

The operation signature is authoritative for checking. The compiler must not re-infer types or select
an implementation from an unchecked string at emission time. `IntrinsicRegistry` rejects duplicate
IDs and property bindings. `FunctionBindingRegistry` rejects duplicate qualified function bindings.

### Syntax Operations

Some operations are part of the language's syntax rather than ordinary function calls. Resolution
recognizes the operation, validates operands, and annotates the AST with its ID and instantiated
signature. The compiler then lowers that resolved operation. For arrays, the resolver associates the
literal, indexing, assignment, and `.length` nodes with array-operation IDs. The compiler's
operation-level emitter dispatches by ID; see
[design-document-06_intrinsic-arrays.md](design-document-06_intrinsic-arrays.md) for array semantics.

### External Function Bindings

Ordinary Zeron functions have bodies and compile normally. An external function has a source
signature but no body; its implementation is selected by `FunctionBindingRegistry`. Resolution
verifies that the declaration signature matches the registered binding. Calls still use ordinary
function-call resolution and lowering; compilation emits a bridge for the external target.
Each compilation starts with the standard typed bindings and composes any caller-provided bindings
with them; conflicting qualified names are rejected. An intrinsic binding selects a registered
intrinsic ID and is checked against that ID's signature before its bridge is emitted.

The bundled output functions now compose ordinary member calls through the external-class facade:

```zeron
package zeron.io;

public fn print(value: Any?): Unit { ... }
public fn println(value: Any?): Unit { ... }
```

The facade declarations are:

```zeron
public external class System = "java.lang.System" {
    public static property out: &PrintStream?;
}

public external class PrintStream = "java.io.PrintStream" {
    public mut print(value: Any?): Unit;
    public mut println(value: Any?): Unit;
}

public external class File = "java.io.File" {
    public constructor new(path: String);
    public exists(): Boolean;
    public isFile(): Boolean;
    public isDirectory(): Boolean;
    public getName(): String?;
    public getPath(): String?;
    public getParent(): String?;
    public mkdir(): Boolean;
    public mkdirs(): Boolean;
    public delete(): Boolean;
}
```

Each declaration's qualified source name must equal its Java binary name. Constructors, instance
methods, and static properties must match public class-file metadata exactly; members not declared by
the facade are unavailable through that source-level facade. `System.out` remains nullable and
callers use flow refinement or safe navigation. JDK classes are looked up by explicit binary name
from exported module metadata; package scanning and wildcard discovery are not enabled. Public
external-class facades are preserved in compiled-library API indexes and revalidated against the
consumer's Java metadata.

## Current Registry

Stable identifiers are versioned independently of source spelling. The current intrinsic catalogue is:

| ID | Source binding | Signature / operation |
| --- | --- | --- |
| `zeron.array.literal.v1` | Array literal syntax | `array.literal<T>(elements: T...) -> &Array<T>` |
| `zeron.array.fill.v1` | Bundled list backing allocation | `array.fill<T>(size: Int, initial: T?) -> &Array<T?>` |
| `zeron.array.length.v1` | `array.length` / `.length` | `array.length<T>(array: Array<T>) -> Int` |
| `zeron.array.read.v1` | Index read syntax | `array.read<T>(array: Array<T>, index: Int) -> T` |
| `zeron.array.write.v1` | Index assignment syntax | `array.write<T>(array: &Array<T>, index: Int, value: T) -> Unit` |
| `zeron.option.unwrap-some.v1` | `zeron.internal.unwrapSome<T>` | `unwrapSome<T>(option: Option<T>) -> T` |
| `zeron.numeric.int-to-float.v1` | Private external helper `zeron.lang.intToFloat` | `intToFloat(value: Int) -> Float` |
| `zeron.numeric.float-to-int-option.v1` | Private external helper `zeron.lang.floatToInt` | `floatToInt(value: Float) -> Option<Int>`; truncates toward zero and fails outside representable `Int` results |

The `...` in the array-literal signature describes the registry's repeated-parameter rule; it is not
callable source syntax. `Array<T>` remains a built-in invariant type constructor. It is not declared
by this registry.

`OPTION_UNWRAP_SOME` remains in use by collection sequence implementations through
`zeron.internal.unwrapSome`; it must not be removed while those call sites exist.

External function bindings are separate from intrinsic IDs and are keyed by qualified source name.
The standard registry currently contains no output bindings; `zeron.io` calls compile from the
bundled Zeron function bodies and curated external-class declarations.

## External and Expected Declarations

An intrinsic operation and an external Java function binding are different mechanisms:

- An intrinsic ID selects implementation owned by Zeron's compiler or runtime and is attached to a
  resolved syntax operation.
- A Java classpath entry reads public class-file metadata from an ordered directory/JAR root and records a JVM owner and method
  descriptor for ordinary invocation; details are in
  [design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).
- A signature-only top-level external function binds through `FunctionBindingRegistry`. A direct
  static-method target is verified against classpath metadata.
- External-class syntax exposes only exact Java member declarations validated against each facade's
  named Java class. It is an explicit member allowlist, not a JDK package-discovery mechanism.

Do not use intrinsic IDs as a substitute for general Java symbol discovery. Conversely, do not make
all compiler-provided behavior depend on a class-file owner. A deliberately curated facade such as
`zeron.io` can be useful without claiming that the JDK is generally discoverable.

## Roadmap

1. **Internal operation registry: implemented baseline.** Array operations resolve to stable IDs and
   instantiated signatures; emission dispatches on those IDs.
2. **Typed external function bindings: implemented baseline.** Registered signature-only external
  functions validate against their binding signatures and lower through typed JVM call plans and
  generated bridges. The bundled `print` and `println` functions are ordinary Zeron functions using
  the curated external-class facade; the standard function-binding registry has no output bindings.
3. **Keep feature-specific semantics local.** Array typing stays in doc 06; library artifacts and
   Java method discovery stay in doc 11. This document owns only the shared binding model.
4. **External class facades: initial generalized slice implemented.** `System.out`,
   `PrintStream.print(Object)` / `println(Object)`, and the selected `java.io.File` constructor and
   instance methods, plus the NIO members used by the standard filesystem and text APIs, validate
   against JDK metadata and lower through ordinary Java interop. Facades are explicit member
   allowlists, and receiver mutability is enforced by the declared method
   modifier. Public classes in exported JDK packages are independently discoverable from module
   class-file metadata. Public declared Java fields with supported descriptors are validated and
   lowered as field instructions; mutable instance writes are allowed only for non-final fields.
   Static writes, inheritance, and Java generics remain deferred. Public facade metadata, including
   checked-exception-to-effect mappings, is carried in the API-index schema; consumers
   revalidate it against their available Java classes.
5. **Broaden external declarations only for a concrete need.** Preserve explicit owner identities and
   descriptor checking as the allowlist evolves.

## Acceptance Boundaries

- Every syntax intrinsic has a stable ID and checked source signature; external function bindings are
  keyed by qualified source name and validate their declared signatures.
- Resolution produces enough typed metadata for code generation to lower by ID without repeating
  semantic analysis.
- Syntax operations and external functions may have different lowering hooks while keeping binding
  selection out of source-level bytecode scripts.
- Calls to external functions remain ordinary calls at source level.
- Java class discovery remains independent from compiler-owned intrinsic registration.
- External top-level functions resolve only to public static methods with supported, exactly matching
  signatures; unsupported, missing, ambiguous, or mismatched targets fail during resolution.
  