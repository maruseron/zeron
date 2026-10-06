# Project Guidance

## Environment and Build

- Use JDK 26 with preview features enabled. The compiler currently uses Java 26 preview APIs, including the ClassFile API.
- `pom.xml` targets Java 26 and enables preview features. Use Maven to build when it is available:

  ```powershell
  mvn compile
  ```

- If Maven is unavailable, this direct compiler command has been verified:

  ```powershell
  $sourceFiles = Get-ChildItem -Recurse -Path src/main/java -Filter *.java | ForEach-Object { $_.FullName }
  javac --enable-preview --source 26 -d target/classes $sourceFiles
  ```

- Compile the sample Zeron source with:

  ```powershell
  java --enable-preview -cp target/classes com.maruseron.zeron.Zeron src/main/resources/test.zn
  ```

  This runs the compiler and emits `dist/test.class`, generated lambda-shape interfaces, the `zeron.lang.Unit` singleton class, and the versioned public API index at `dist/META-INF/zeron/api-v8.bin`. File compilation uses the source filename without its extension for the main class name. It does not execute the generated Zeron program.
- Before every compilation of Zeron source (including tests that generate Zeron programs), remove the existing `dist/` directory so stale generated classes and interfaces do not accumulate. The compiler recreates it as needed; do not remove Maven's `target/` directory for this cleanup.
- Pass `--debug` (or `-d`) to print parser, resolver, and compiler traces to stderr; normal compilation is quiet.
- Pass one or more `--library <class-directory-or-jar>` arguments with source files or project options to load compiled Zeron APIs from `META-INF/zeron/api-v8.bin`. Generated programs using an external library also need that library on their runtime classpath.
- Pass one or more `--java-classpath <class-directory>` arguments with source files or project options to resolve the initial Java interop subset. Expanded Java varargs calls are supported when their component type is supported; signature-only top-level `external fn` declarations use compiler-configured typed bindings. Prepacked/ordinary Java arrays, JARs, Java generics, fields, inherited members, JDK module discovery, and SAM conversions are not implemented. Generated programs also need those class directories on their runtime classpath.
- Build the bundled standard library as a separate class directory with `java --enable-preview -cp target/classes com.maruseron.zeron.Zeron --build-stdlib target/zeron-stdlib`. To build the standard library into a JAR, add `--jar-output target/zeron-stdlib.jar`. Consumers can opt in with `--stdlib compiled --library target/zeron-stdlib.jar`; the default remains bundled source mode. The artifact includes the compiler-known `zeron.lang.Unit` singleton class. Keep this output separate from `dist/` so normal Zeron-source cleanup does not remove it.
- Inspect a library API index file, class directory, or JAR with `java --enable-preview -cp target/classes com.maruseron.zeron.ZeronLibraryIndexDump target/zeron-stdlib.jar` (or pass `target/zeron-stdlib/META-INF/zeron/api-v8.bin` directly).
- To exercise the generated sample program, invoke its public zero-argument `main` method through JShell:

  ```powershell
  @('Class.forName("test").getMethod("main").invoke(null);', '/exit') | jshell --class-path dist --execution local --feedback concise
  ```

## Code Organization

- The pipeline is scanner (`scan`) -> parser/AST (`ast`) -> resolver (`analize`) -> bytecode compiler (`compile`). Keep language semantics in the resolver and descriptors; keep JVM representation decisions in the compiler.
- Resolution entry point and orchestration live in `src/main/java/com/maruseron/zeron/analize/ResolutionService.java` and `Resolver.java`. `ResolutionService` creates a per-run `ResolutionContext`, and `Resolver` performs unit/declaration orchestration and returns a `ResolutionResult`; it is not the home for detailed expression or statement rules.
- Resolver responsibilities are split by phase and language construct: `DeclarationRegistry` registers symbols and declarations; `ImportResolver` validates per-unit imports; `TopLevelValuePlanner` orders top-level value resolution; `DeclarationResolver` handles declarations; `StatementResolver` handles statements and control flow; `ExpressionFlowResolver` handles expression typing and flow-sensitive state; `CallResolver`, `MemberInteropResolver`, `LambdaResolver`, `TypeResolver`, and `IntrinsicResolver` handle their respective expression/type domains. `ResolutionContext` owns per-run symbols, type/declaration maps, import environment, diagnostics, and resolver collaborators; `ResolutionDiagnostics` collects errors for the returned result. Pass the context explicitly between resolver modules, and keep semantic decisions here rather than in bytecode generation.
- Compilation entry point is `src/main/java/com/maruseron/zeron/compile/CompilationService.java`. It owns one `CompilationContext` across `resolve()` and `compile()` so resolution output, planning data, configuration, and method-emission state remain scoped to one compilation. `Compiler` is a stateless phase facade; its static methods accept that context.
- Compiler emission modules are organized by responsibility: `InitializationPlanner` orders top-level initializers; `CompilationMetadata` indexes declaration owners and generated-holder metadata; `RuntimeSupportEmitter` emits `Unit` and function-shape support classes; `DeclarationEmitter` emits top-level declarations and file-holder methods; `NominalTypeEmitter` emits classes/contracts, constructors, properties, and bridges; `StatementEmitter` emits statements and loops; `ExpressionFlowEmitter` emits expression control-flow constructs (logical/coalescing expressions, conditionals, matches, type tests, casts, and comparisons); `LambdaSupportEmitter` emits lambda bodies, references, and adapters; `ClassSignatureEmitter` encodes JVM generic signatures. `BytecodeEmitter` coordinates file emission and retains the remaining expression, call, conversion, and shared bytecode operations.
- Keep cross-module emission operations static and pass `CompilationContext` explicitly. Put new code in the narrowest responsible module; avoid reintroducing compiler instance state or broad callback/operations bridges. Preserve the distinction between resolver expression-flow logic (`analize/ExpressionFlowResolver`) and compiler expression-flow bytecode lowering (`compile/ExpressionFlowEmitter`).
- Type descriptors model Zeron types. Do not make structural function types depend on the generated JVM interface name for semantic equality or resolution.
- Current lambda lowering emits one SAM interface per function shape, one private static helper per lambda body, and uses `LambdaMetafactory` at `invokedynamic` call sites. See `documents/design-document-03_lambda-lowering.md` before changing this behavior.
- `target/` contains Maven build output, and `dist/` contains generated Zeron program artifacts. Do not edit generated class files as source; regenerate them from Java/Zeron sources.

## Validation

- For compiler or resolver changes, run `mvn -DargLine=--enable-preview test` (or compile all Java sources using the command above when Maven is unavailable).
- For function-shape identity changes, run the canonical key tests and compile `src/main/resources/test.zn`; inspect generated interface names and method descriptors with `javap` when bytecode identity matters.
- For lambda-lowering changes, compile `src/main/resources/test.zn`, invoke the generated program, and inspect `test` plus generated shape interfaces with `javap -classpath dist` when bytecode shape matters.
- Keep changes scoped to the behavior being changed. Do not update Java/Maven versions or generated outputs as incidental cleanup.

## Design References

- `documents/design-document-01_grammar.cfgr`: implemented source grammar.
- Retired, archive only: `documents/design-document-02_type-grammar.cfgr`. Its encoded descriptor grammar is not authoritative for current source syntax or type representation.
- `documents/design-document-00.md`: language overview and intended features.
- `documents/design-document-03_lambda-lowering.md`: current lambda lowering, known gaps, and roadmap.
- `documents/design-document-04_mutability.md`: mutability semantics and implementation.
- `documents/design-document-05_classes-and-contracts.md`: classes and contracts.
- `documents/design-document-06_intrinsic-arrays.md`: intrinsic array types and operations.
- `documents/design-document-07_generic-functions.md`: generic functions.
- `documents/design-document-08_flow-typing-type-tests-and-casts.md`: flow typing, type tests, and casts.
- `documents/design-document-09_namespaces-packages-and-imports.md`: source-level names, packages, imports, and visibility.
- `documents/design-document-10_small-miscelaneous.md`: low-priority null and equality feature discussion.
- `documents/design-document-11_compilation-libraries-and-host-integration.md`: project compilation, libraries, and Java interop.
- `documents/design-document-12_intrinsics-and-external-bindings.md`: compiler intrinsic registry and external declaration design.
