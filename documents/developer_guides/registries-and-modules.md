# Registries and module conventions

## Choosing the right registry

Most standard-library functions are ordinary Zeron functions. Add them to a `.zn` module under
`src/main/resources/stdlib/`, using a package declaration that matches the module path; do not add
them to a compiler registry merely because they are part of the standard library. Maven generates
the bundled source index from those resources.

[`FunctionBindingRegistry`](../../src/main/java/com/maruseron/zeron/domain/FunctionBindingRegistry.java)
maps qualified Zeron function names to JVM implementations or compiler intrinsics. Use it when a
Zeron declaration needs a host/runtime implementation:

1. Define the Zeron-facing function signature, normally as an external function in the appropriate
   standard-library module.
2. Add a binding in `createStandard()` using the fully qualified Zeron name. The binding includes
   a matching `FunctionDescriptor` and a JVM target: a static/instance method, a supported
   static-field receiver, or an `IntrinsicBinding`.
3. For Java targets, ensure the owner, method, descriptor, accessibility, and exception mapping
   agree with the class-file metadata. Resolution validates the binding; it is not a place for
   unchecked name-based dispatch.
4. Add tests for resolution and for the generated call behavior, including raised-effect mappings
   where applicable.

The registry is immutable. Extend it through `withBinding`/`withBindings` for configured compiler
instances; do not mutate a shared global map. Add entries to the standard registry only for
compiler-provided standard bindings.

## Registering an intrinsic operation

An intrinsic is compiler-owned behavior, not just a JVM method alias. Its signature, type
substitution, semantic checks, and bytecode lowering must agree:

1. Add a stable, versioned ID to
   [`IntrinsicId.java`](../../src/main/java/com/maruseron/zeron/domain/IntrinsicId.java). Do not
   reuse an ID for different semantics.
2. Add its generic parameters, operand types, result type, variadic behavior, and optional property
   name to [`IntrinsicRegistry.java`](../../src/main/java/com/maruseron/zeron/domain/IntrinsicRegistry.java).
3. Route the source operation to that ID. For function-backed operations this may include a
   `FunctionBindingRegistry` `IntrinsicBinding`; syntax-backed operations resolve through
   [`IntrinsicResolver.java`](../../src/main/java/com/maruseron/zeron/analize/IntrinsicResolver.java).
   Preserve the instantiated signature in `ResolvedIntrinsicOperation`.
4. Add emission in the relevant `BytecodeEmitter` dispatch or a focused emitter. Keep stack shape,
   evaluation order, conversions, and exceptional behavior consistent with the resolved signature.
5. Test signature/arity/bounds errors, accepted resolution, emitted behavior, and execution on
   generated classes. If the intrinsic participates in compiled-library metadata, add compatibility
   and round-trip coverage there too.

## Module and package conventions

- Java source follows the existing layers under `com.maruseron.zeron`: `scan` and `ast` define
  lexical/source structure, `analize` resolves language meaning, `domain` holds descriptors and
  registries, `compile` emits JVM bytecode, and `runtime` holds runtime support. The spelling
  `analize` is established; do not create a parallel `analyze` package.
- Keep semantic logic in resolver modules and JVM layout/bytecode logic in compiler modules. Pass
  `ResolutionContext` or `CompilationContext` explicitly; avoid introducing broad mutable global
  state.
- Place Java tests under the matching `src/test/java/com/maruseron/zeron/...` package. Prefer
  focused resolver tests for semantics and compiler-backed integration tests for generated behavior.
- Place bundled Zeron source at
  `src/main/resources/stdlib/<package path>/<module>.zn`. Keep the declared package consistent
  with the public module and let the Maven resource-index generation include the file.
- Treat `target/` and `dist/` as generated output. Before tests that compile Zeron sources, remove
  only `dist/`; never edit or clean Maven's `target/` as part of that step.
- For changes that affect public library metadata, follow the schema/version and separate-compilation
  guidance in [the library guide](../../guide/24-libraries-and-separate-compilation.md).
