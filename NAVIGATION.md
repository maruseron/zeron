# Code Organization

## Compiler pipeline

The pipeline is scanner ([`scan/`](./src/main/java/com/maruseron/zeron/scan/)) ->
parser/AST ([`ast/`](./src/main/java/com/maruseron/zeron/ast/)) ->
resolver ([`analize/`](./src/main/java/com/maruseron/zeron/analize/)) ->
bytecode compiler ([`compile/`](./src/main/java/com/maruseron/zeron/compile/)). Keep language
semantics in the resolver and type descriptors; keep JVM representation decisions in the compiler.

## Resolution

Resolution entry point and orchestration live in
[`ResolutionService`](./src/main/java/com/maruseron/zeron/analize/ResolutionService.java) and
[`Resolver`](./src/main/java/com/maruseron/zeron/analize/Resolver.java).
`ResolutionService` creates a per-run `ResolutionContext`, and `Resolver` performs
unit/declaration orchestration and returns a `ResolutionResult`; detailed expression and statement
rules belong in their focused resolver modules.

Resolver responsibilities are split by phase and language construct:

- `DeclarationRegistry` registers symbols and declarations.
- `ImportResolver` validates per-unit imports.
- `TopLevelValuePlanner` orders top-level value resolution.
- `DeclarationResolver` handles declarations.
- `StatementResolver` handles statements and control flow.
- `ExpressionFlowResolver` handles expression typing and flow-sensitive state.
- `CallResolver`, `MemberInteropResolver`, `LambdaResolver`, `TypeResolver`, and
  `IntrinsicResolver` handle their respective expression and type domains.

`ResolutionContext` owns per-run symbols, type/declaration maps, import environment, diagnostics,
and resolver collaborators. `ResolutionDiagnostics` collects errors for the returned result. Pass
the context explicitly between resolver modules, and keep semantic decisions here rather than in
bytecode generation.

## Compilation

The compilation entry point is
[`CompilationService`](./src/main/java/com/maruseron/zeron/compile/CompilationService.java). It
owns one `CompilationContext` across `resolve()` and `compile()` so resolution output, planning
data, configuration, and method-emission state remain scoped to one compilation. `Compiler` is a
stateless phase facade; its static methods accept that context.

Compiler emission modules are organized by responsibility:

- `InitializationPlanner` orders top-level initializers.
- `CompilationMetadata` indexes declaration owners and generated-holder metadata.
- `RuntimeSupportEmitter` emits `Unit` and function-shape support classes.
- `DeclarationEmitter` emits top-level declarations and file-holder methods.
- `NominalTypeEmitter` emits classes/contracts, constructors, properties, and bridges.
- `StatementEmitter` emits statements and loops.
- `ExpressionFlowEmitter` emits expression control-flow constructs (logical/coalescing expressions,
  conditionals, matches, type tests, casts, and comparisons).
- `LambdaSupportEmitter` emits lambda bodies, references, and adapters.
- `ClassSignatureEmitter` encodes JVM generic signatures.
- `BytecodeEmitter` coordinates file emission and retains the remaining expression, call,
  conversion, and shared bytecode operations.

Keep cross-module emission operations static and pass `CompilationContext` explicitly. Put new code
in the narrowest responsible module; avoid reintroducing compiler instance state or broad
callback/operations bridges. Preserve the distinction between resolver expression-flow logic
(`analize/ExpressionFlowResolver`) and compiler expression-flow bytecode lowering
(`compile/ExpressionFlowEmitter`).

Type descriptors model Zeron types. Do not make structural function types depend on the generated
JVM interface name for semantic equality or resolution.

Current lambda lowering emits one SAM interface per function shape, one private static helper per
lambda body, and uses `LambdaMetafactory` at `invokedynamic` call sites. See
[`design-document-03_lambda-lowering.md`](./documents/design-document-03_lambda-lowering.md)
before changing this behavior.

`target/` contains Maven build output, and `dist/` contains generated Zeron program artifacts. Do
not edit generated class files as source; regenerate them from Java/Zeron sources.
