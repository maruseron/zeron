# 23. Java interoperability

Zeron can resolve a deliberately small Java API surface from explicit class-directory/JAR roots and
from public classes in JDK packages exported to the generated unnamed module. In a manifest project,
list non-JDK class directories or JARs under `[dependencies].java-classpath`; a command-line project
build can supply `--java-classpath`. Generated code also needs non-JDK implementation roots on its
runtime classpath, which `zeron run` arranges from the manifest.

Resolution is based on explicitly imported or qualified Java binary names. Classpath roots are
searched in order and looked up directly; Zeron does not scan packages or create implicit imports.
JDK metadata is read directly from exported system modules without loading or initializing the
classes. The compile-time classpath and runtime classpath have related but distinct purposes:
compilation needs type signatures, while generated code needs the implementation classes when it
runs.

The supported subset includes selected public declared constructors and methods, the supported
field-access forms, and expanded Java varargs calls when the component type is supported. Curated
external-class facades may expose static methods and may intentionally bind a fixed-argument call to
a Java varargs method while hiding its optional tail. Imports and signatures are checked during
resolution. Passing a prebuilt Java array as a varargs argument is not generally supported.

Interop is not Java reflection or a general Java type system. Generic Java types, inherited-member
search, Java fields beyond the implemented subset, arbitrary Java arrays, and callback/SAM
conversions are unsupported. The bundled source standard library also supplies an allowlisted
`java.io.File` facade:

```zeron
import java.io.File;

fn isFile(path: String): Boolean = File.new(path).isFile();
```

The facade exposes only its declared `File(String)` constructor and selected methods; calls to other
Java `File` members are rejected through the facade. External-class facade declarations are currently
included in compiled-library API indexes, so this allowlist also applies when consuming a compiled
standard library. When a Java surface is awkward or unsupported, a small Java adapter can expose a
simpler declared API to Zeron.

Treat interop as an explicit host boundary rather than a transparent import of the whole Java
object model. A small adapter can hide unsupported signatures and expose only the operations the
Zeron program needs. Java exceptions retain ordinary JVM behavior by default. Direct Java calls to
methods declaring checked exceptions are rejected; an external-class method can instead map its
declared checked exceptions explicitly:

```zeron
public effect FileFailure {
    public property cause: java.io.IOException;
    public constructor fromIOException(cause: java.io.IOException) =
        FileFailure.new(cause);
}

public external class Reader = "java.io.Reader" {
    public read(): Int
        throws java.io.IOException -> FileFailure::fromIOException;
}
```

The mapping's public effect constructor must take one payload matching the mapped Java exception
type. Every checked exception in the Java method's declared exception list must be mapped. If
multiple types match, the most-specific mapping is selected. `RuntimeException` descendants and
`Error`s always propagate unchanged, and checked-exception constructors are not yet supported.
Mappings and the derived raised-effect signatures are preserved in compiled-library API indexes.

The exact supported signatures and limitations are tracked in
[design document 11](../documents/design-document-11_compilation-libraries-and-host-integration.md).
