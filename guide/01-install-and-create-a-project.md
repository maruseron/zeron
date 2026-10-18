# 1. Install Zeron and create a project

This guide assumes JDK 25 or later. Check that Java is available:

```text
java -version
```

The output should identify Java 25 or later. Zeron targets stable Java 25 and does not require
preview features.

If you have a portable Zeron distribution, extract it to a location you control. On Windows, use
`bin\zeron.cmd`; on macOS or Linux, use `bin/zeron`. Add the distribution's `bin` directory to
`PATH` to invoke `zeron` from any directory.

From a Zeron source checkout, build with JDK 25 or later and Maven:

```powershell
mvn package
java -jar target/zeron-0.1-alpha.jar init
```

Use the versioned JAR name produced in `target/` if it differs from the example.

## Create a project

In an empty directory, run:

```text
zeron init
```

The command creates `zeron.toml` and `src/main.zn`. It refuses to overwrite either file if it
already exists. The starter entry file is:

```zeron
fn main() {
}
```

## Understand the manifest

The generated `zeron.toml` describes the entry source, source roots, output directory, and
standard-library mode:

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"

[platform]
stdlib = "source"
```

Paths are relative to the manifest file. The source-bundled standard library is the recommended
mode for new projects. Dependencies, when needed, can be listed as local class directories or JARs:

```toml
[dependencies]
zeron-libraries = ["lib/shared.jar"]
java-classpath = ["lib/host-api.jar"]
```

Zeron does not fetch dependencies automatically.

## Build and run

From the project directory, compile the project with:

```text
zeron build
```

Generated classes are written to `dist/`. To build and launch the entry point:

```text
zeron run
```

Both commands load `zeron.toml` from the current directory by default. Select another manifest with
`--manifest path/to/zeron.toml`. Use `zeron explain ZR2014` to read the explanation for a diagnostic
code.

In the next subject, we will add a function body and make the program do something visible.
