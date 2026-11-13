# Install Zeron and create a project

This guide assumes JDK 25 or later is installed on your machine. Check that Java is available:

```text
java -version
```

The output should say Java 25 (or later).

If you have a portable Zeron distribution, extract it to a location you control. On Windows, use
`bin\zeron.cmd`; on macOS or Linux, use `bin/zeron`. Add the distribution's `bin` directory to
`PATH` to invoke `zeron` from anywhere.

From a Zeron source checkout, build with JDK 25 or later and Maven:

```powershell
mvn package
java -jar target/zeron-0.7.0.jar init
```

The JAR filename includes the project version from `pom.xml`.

## Create a project

In an empty folder, run:

```text
zeron init
```

This command creates `zeron.toml` and a `src` folder, whose only contents are a Zeron file called
`main.zn` . It will not to overwrite either file if it already exists. The starter entry file is:

```zeron
fn main() {

}
```

As you can see, it only contains an empty function `fn` called `main`. This is a special function
that will be automatically executed when you launch a Zeron program. It serves as the Zeron entry
point. If you don't know what any of that means, don't worry - we'll get there.

## Understand the manifest

The generated `zeron.toml` file describes the entry file, where this project's code is located,
the compilation output directory, and standard-library mode:

```toml
[project]
entry = "src/main.zn"
source-roots = ["src"]
output = "dist"

[platform]
stdlib = "compiled"
```

Paths are relative to the manifest file. `zeron init` writes `compiled` in an installed
distribution and `source` in a source checkout. When `stdlib` is omitted, compilation prefers the
compiled standard library bundled in the installation's `lib/` directory and falls back to bundled
sources when the artifact is missing or incompatible; `source` is mostly a tool for Zeron's
developer, but it can be useful for debugging.

Dependencies, when needed, can be listed as local class directories or JARs:

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

Generated classes are written to `dist/`. To build _and_ launch the entry point:

```text
zeron run
```

Both commands load `zeron.toml` from the current directory by default. Select another manifest with
`--manifest path/to/zeron.toml`. If any error shows up, try `zeron explain` with your error code
(such as `zeron explain ZR2014`) to read the explanation for a diagnostic code.

In the next subject, we will add a useful code to your main function and make the program do something
visible.
