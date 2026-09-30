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

  This runs the compiler and emits `ZeronMain.class` in the working directory and generated lambda-shape interfaces under `target/classes`. It does not execute the generated Zeron program.
- To exercise the generated sample program, invoke its public zero-argument `main` method through JShell:

  ```powershell
  @('Class.forName("ZeronMain").getMethod("main").invoke(null);', '/exit') | jshell --class-path ".;target/classes" --execution local --feedback concise
  ```

## Code Organization

- The pipeline is scanner (`scan`) -> parser/AST (`ast`) -> resolver (`analize`) -> bytecode compiler (`compile`). Keep language semantics in the resolver and descriptors; keep JVM representation decisions in the compiler.
- Type descriptors model Zeron types. Do not make structural function types depend on the generated JVM interface name for semantic equality or resolution.
- Current lambda lowering emits one SAM interface per function shape, one private static helper per lambda body, and uses `LambdaMetafactory` at `invokedynamic` call sites. See `documents/design-document-03_lambda-lowering.md` before changing this behavior.
- `target/` and the root `ZeronMain.class` are generated outputs. Do not edit them as source; regenerate them from Java/Zeron sources.

## Validation

- For compiler or resolver changes, run `mvn test` (or compile all Java sources using the command above when Maven is unavailable).
- For function-shape identity changes, run the canonical key tests and compile `src/main/resources/test.zn`; inspect generated interface names and method descriptors with `javap` when bytecode identity matters.
- For lambda-lowering changes, compile `src/main/resources/test.zn`, invoke the generated program, and inspect `ZeronMain` plus generated shape interfaces with `javap` when bytecode shape matters.
- Keep changes scoped to the behavior being changed. Do not update Java/Maven versions or generated outputs as incidental cleanup.

## Design References

- `documents/design-document-00.md`: language overview and intended features.
- `documents/design-document-03_lambda-lowering.md`: current lambda lowering, known gaps, and roadmap.
