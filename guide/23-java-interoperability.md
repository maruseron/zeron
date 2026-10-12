# 23. Java interoperability

Zeron can resolve a deliberately small Java API surface when its compiled classes are supplied on an
explicit Java classpath root. In a manifest project, list class directories or JARs under
`[dependencies].java-classpath`; a command-line project build can supply `--java-classpath`.
Generated code also needs those roots on its runtime classpath, which `zeron run` arranges from the
manifest.

Resolution is based on declared Java types visible from those roots. Provide the class directory or
JAR containing the API; Zeron does not search arbitrary dependencies or automatically discover all
classes in the JDK. The compile-time classpath and runtime classpath have related but distinct
purposes: compilation needs type signatures, while generated code needs the implementation classes
when it runs.

The supported subset includes selected public declared constructors and methods, the supported
field-access forms, and expanded Java varargs calls when the component type is supported. Imports
and signatures are checked during resolution. For Java APIs with varargs, Zeron expands individual
arguments; passing a prebuilt Java array as a varargs argument is not generally supported.

Interop is not Java reflection or a general Java type system. Java generics, inherited-member
search, Java fields beyond the implemented subset, arbitrary Java arrays, and callback/SAM
conversions are unsupported. JDK module discovery is also not automatic; supply compatible class
directories explicitly. When a Java surface is awkward or unsupported, a small Java adapter can
expose a simpler declared API to Zeron.

Treat interop as an explicit host boundary rather than a transparent import of the whole Java
object model. A small adapter can hide unsupported signatures and expose only the operations the
Zeron program needs. Java exceptions retain ordinary JVM behavior; they are not automatically
declared as or converted into Zeron raised effects.

The exact supported signatures and limitations are tracked in
[design document 11](../documents/design-document-11_compilation-libraries-and-host-integration.md).
