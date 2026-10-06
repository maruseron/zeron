# Intrinsics and External Bindings: Design and Roadmap

## Scope

This document covers compiler/runtime-provided operations whose source signatures are checked by
Zeron but whose implementations are not ordinary Zeron function or class bodies. It records the
internal intrinsic registry, the two current lowering paths, and the unresolved design for broader
external or expected declarations.

Array type and operation semantics remain in
[design-document-06_intrinsic-arrays.md](design-document-06_intrinsic-arrays.md). Project
compilation, library artifacts, and Java class-directory interop remain in
[design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).
An intrinsic is not a Java class lookup mechanism and does not imply JDK module discovery.

## Current Status

- `IntrinsicRegistry` associates stable `IntrinsicId` values with syntax-operation signatures.
- Resolver-annotated syntax operations carry a `ResolvedIntrinsicOperation` with the selected ID,
  instantiated parameter types, and result type. The compiler dispatches on that ID.
- `FunctionBindingRegistry` maps signature-only top-level external declarations to typed JVM targets
  by qualified source name. The resolver checks the declaration signature, and the compiler emits a
  Zeron bridge for the binding. Trailing defaults are supported on these declarations through
  generated shorter-arity Zeron wrappers; the registered target remains full arity.
- Array literal, fill, length, read, and write operations are intrinsics. `zeron.io.print` and
  `zeron.io.println` are ordinary Zeron functions implemented through the curated Java class facade.
- Intrinsic IDs and function bindings are internal. There is no source-level `intrinsic` keyword or
  expected-class declaration. A narrowly curated external-class form is implemented for the
  `System.out` printing facade only.

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
```

Only those exact owners and members are available through the built-in JDK provider. `System.out`
remains nullable and callers use flow refinement or safe navigation. No JDK package scanning or
general module discovery is enabled.

## Current Registry

Stable identifiers are versioned independently of source spelling. The current intrinsic catalogue is:

| ID | Source binding | Signature / operation |
| --- | --- | --- |
| `zeron.array.literal.v1` | Array literal syntax | `array.literal<T>(elements: T...) -> &Array<T>` |
| `zeron.array.fill.v1` | Bundled list backing allocation | `array.fill<T>(size: Int, initial: T?) -> &Array<T?>` |
| `zeron.array.length.v1` | `array.length` / `.length` | `array.length<T>(array: Array<T>) -> Int` |
| `zeron.array.read.v1` | Index read syntax | `array.read<T>(array: Array<T>, index: Int) -> T` |
| `zeron.array.write.v1` | Index assignment syntax | `array.write<T>(array: &Array<T>, index: Int, value: T) -> Unit` |

The `...` in the array-literal signature describes the registry's repeated-parameter rule; it is not
callable source syntax. `Array<T>` remains a built-in invariant type constructor. It is not declared
by this registry.

External function bindings are separate from intrinsic IDs and are keyed by qualified source name.
The standard registry currently contains no output bindings; `zeron.io` calls compile from the
bundled Zeron function bodies and curated external-class declarations.

## External and Expected Declarations

An intrinsic operation and an external Java function binding are different mechanisms:

- An intrinsic ID selects implementation owned by Zeron's compiler or runtime and is attached to a
  resolved syntax operation.
- A Java class-directory entry reads public class-file metadata and records a JVM owner and method
  descriptor for ordinary invocation; details are in
  [design-document-11_compilation-libraries-and-host-integration.md](design-document-11_compilation-libraries-and-host-integration.md).
- A signature-only top-level external function binds through `FunctionBindingRegistry`. A direct
  static-method target is verified against class-directory metadata.
- The curated external-class syntax exposes only exact Java member declarations validated against
  the two supported JDK facades. It does not provide general JDK class discovery or let source
  declarations broaden the approved member set.

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
4. **Curated external class facade: initial slice implemented.** `java.lang.System.out` and
   `java.io.PrintStream.print(Object)` / `println(Object)` validate against the JDK runtime metadata
   and lower as direct field/method instructions. General external class discovery, arbitrary fields,
   inheritance, JDK module lookup, and Java generics remain deferred.
5. **Broaden external declarations only for a concrete need.** Specify each additional type identity,
   visibility/import behavior, descriptor ownership, initialization, and interaction with contracts
   before expanding the supported facade set.

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