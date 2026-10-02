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

  This runs the compiler and emits `dist/test.class` plus generated lambda-shape interfaces directly under `dist/` in the default package. File compilation uses the source filename without its extension for the main class name. It does not execute the generated Zeron program.
- Pass `--debug` (or `-d`) to print parser, resolver, and compiler traces to stderr; normal compilation is quiet.
- To exercise the generated sample program, invoke its public zero-argument `main` method through JShell:

  ```powershell
  @('Class.forName("test").getMethod("main").invoke(null);', '/exit') | jshell --class-path dist --execution local --feedback concise
  ```

## Code Organization

- The pipeline is scanner (`scan`) -> parser/AST (`ast`) -> resolver (`analize`) -> bytecode compiler (`compile`). Keep language semantics in the resolver and descriptors; keep JVM representation decisions in the compiler.
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
- `documents/design-document-09_namespaces-packages-and-imports.md`: proposed namespaces, packages, imports, and staged JVM integration.
