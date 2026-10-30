package com.maruseron.zeron.domain;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

public final class JavaClassPath {
    private static final URI JRT_URI = URI.create("jrt:/");
    private static final String INTERNAL_RUNTIME_PACKAGE_PREFIX = "com.maruseron.zeron.runtime.";
    private static final Path INTERNAL_RUNTIME_ROOT = internalRuntimeRoot();
    private static final Map<String, String> EXPORTED_JDK_PACKAGES = exportedJdkPackages();

    public record JavaMethod(String name,
                             MethodTypeDesc descriptor,
                             boolean isStatic,
                             boolean isVarArgs,
                             boolean hasGenericSignature,
                             String genericSignature,
                             boolean isConstructor,
                             List<String> exceptionTypes) {
        public JavaMethod {
            exceptionTypes = List.copyOf(exceptionTypes);
        }
    }

    public record JavaField(String name, java.lang.constant.ClassDesc descriptor,
                            boolean isStatic, boolean isFinal, boolean hasGenericSignature,
                            String genericSignature) {}

    public record JavaClass(String binaryName,
                            String superclassName,
                            boolean isInterface,
                            boolean isAbstract,
                            boolean hasTypeParameters,
                            String genericSignature,
                            List<TypeParameterDescriptor> typeParameters,
                            List<JavaMethod> methods,
                            List<JavaField> fields) {
        public JavaClass {
            typeParameters = List.copyOf(typeParameters);
            methods = List.copyOf(methods);
            fields = List.copyOf(fields);
        }
    }

    public static final class MetadataException extends RuntimeException {
        private final String binaryName;
        private final Path root;

        private MetadataException(final String binaryName, final Path root, final String message,
                                  final Throwable cause) {
            super(message, cause);
            this.binaryName = binaryName;
            this.root = root;
        }

        public String binaryName() { return binaryName; }
        public Path root() { return root; }
    }

    private final List<Path> roots;
    private final Map<String, JavaClass> cache = new HashMap<>();

    public JavaClassPath(final List<Path> roots) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public JavaClass find(final String binaryName) {
        if (cache.containsKey(binaryName)) return cache.get(binaryName);
        if (binaryName.equals("java.lang.System") || binaryName.equals("java.io.PrintStream")) {
            final var curatedJdkClass = findJdkClass(binaryName);
            if (curatedJdkClass != null) {
                cache.put(binaryName, curatedJdkClass);
                return curatedJdkClass;
            }
        }
        final var classEntry = binaryName.replace('.', '/') + ".class";
        for (final var root : roots) {
            final var model = readClass(binaryName, root, classEntry);
            if (model == null) continue;
            final var javaClass = toJavaClass(binaryName, root, classEntry, model);
            cache.put(binaryName, javaClass);
            return javaClass;
        }
        if (binaryName.startsWith(INTERNAL_RUNTIME_PACKAGE_PREFIX)) {
            final var model = readClass(binaryName, INTERNAL_RUNTIME_ROOT, classEntry);
            if (model != null) {
                final var internalRuntimeClass = toJavaClass(binaryName, INTERNAL_RUNTIME_ROOT, classEntry, model);
                cache.put(binaryName, internalRuntimeClass);
                return internalRuntimeClass;
            }
        }
        final var javaClass = findJdkClass(binaryName);
        if (javaClass != null) {
            cache.put(binaryName, javaClass);
            return javaClass;
        }

        cache.put(binaryName, null);
        return null;
    }

    private static Path internalRuntimeRoot() {
        final var codeSource = JavaClassPath.class.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IllegalStateException("The Zeron runtime code source is unavailable.");
        }
        try {
            return Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (final java.net.URISyntaxException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public boolean isSubtypeOf(final String binaryName, final String supertypeName) {
        var currentName = binaryName;
        final var visited = new java.util.HashSet<String>();
        while (currentName != null && visited.add(currentName)) {
            if (currentName.equals(supertypeName)) return true;
            final var current = find(currentName);
            if (current == null) return false;
            currentName = current.superclassName();
        }
        return false;
    }

    private static JavaClass toJavaClass(final String binaryName, final Path root, final String classEntry,
                                         final ClassModel model) {
        final var expectedInternalName = classEntry.substring(0, classEntry.length() - ".class".length());
        if (!model.thisClass().asInternalName().equals(expectedInternalName)) {
            throw metadataError(binaryName, root,
                    "Class entry name does not match the requested binary name.", null);
        }
        final var flags = model.flags();
        if (!flags.has(AccessFlag.PUBLIC)
                || flags.has(AccessFlag.ANNOTATION)
                || flags.has(AccessFlag.ENUM)
                || flags.has(AccessFlag.MODULE)) {
            return null;
        }
        final var methods = model.methods().stream()
                .filter(method -> method.flags().has(AccessFlag.PUBLIC))
                .filter(method -> !method.flags().has(AccessFlag.SYNTHETIC)
                        && !method.flags().has(AccessFlag.BRIDGE))
                .filter(method -> !method.methodName().equalsString("<clinit>"))
                .map(method -> new JavaMethod(method.methodName().stringValue(),
                        method.methodTypeSymbol(), method.flags().has(AccessFlag.STATIC),
                        method.flags().has(AccessFlag.VARARGS),
                        method.findAttribute(Attributes.signature()).isPresent(),
                        method.findAttribute(Attributes.signature())
                                .map(attribute -> attribute.signature().stringValue()).orElse(null),
                        method.methodName().equalsString("<init>"),
                        method.findAttribute(Attributes.exceptions())
                                .map(attribute -> attribute.exceptions().stream()
                                        .map(entry -> entry.asInternalName().replace('/', '.'))
                                        .toList())
                                .orElse(List.of())))
                .toList();
        final var fields = model.fields().stream()
                .filter(field -> field.flags().has(AccessFlag.PUBLIC))
                .map(field -> new JavaField(field.fieldName().stringValue(), field.fieldTypeSymbol(),
                        field.flags().has(AccessFlag.STATIC), field.flags().has(AccessFlag.FINAL),
                        field.findAttribute(Attributes.signature()).isPresent(),
                        field.findAttribute(Attributes.signature())
                                .map(attribute -> attribute.signature().stringValue()).orElse(null)))
                .toList();
        final var genericSignature = model.findAttribute(Attributes.signature())
                .map(attribute -> attribute.signature().stringValue()).orElse(null);
        final var typeParameters = JavaGenericSignatureParser.parseClassTypeParameters(genericSignature);
        return new JavaClass(binaryName,
                model.superclass().map(entry -> entry.asInternalName().replace('/', '.')).orElse(null),
                flags.has(AccessFlag.INTERFACE),
                flags.has(AccessFlag.ABSTRACT), !typeParameters.isEmpty(), genericSignature, typeParameters,
                methods, fields);
    }

    private static Map<String, String> exportedJdkPackages() {
        final var packages = new HashMap<String, String>();
        ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor())
                .sorted(java.util.Comparator.comparing(ModuleDescriptor::name))
                .forEach(descriptor -> descriptor.exports().stream()
                        .filter(export -> !export.isQualified())
                        .map(ModuleDescriptor.Exports::source)
                        .forEach(packageName -> packages.putIfAbsent(packageName, descriptor.name())));
        return Map.copyOf(packages);
    }

    private static JavaClass findJdkClass(final String binaryName) {
        final var separator = binaryName.lastIndexOf('.');
        if (separator < 0) return null;
        final var moduleName = EXPORTED_JDK_PACKAGES.get(binaryName.substring(0, separator));
        if (moduleName == null) return null;
        final var classEntry = binaryName.replace('.', '/') + ".class";
        final var root = Path.of("JDK-module-" + moduleName);
        try {
            final FileSystem jrt = FileSystems.getFileSystem(JRT_URI);
            final var classFile = jrt.getPath("/modules", moduleName, classEntry);
            if (!Files.isRegularFile(classFile)) return null;
            final var model = ClassFile.of().parse(Files.readAllBytes(classFile));
            return toJavaClass(binaryName, root, classEntry, model);
        } catch (final IOException | IllegalArgumentException exception) {
            throw metadataError(binaryName, root,
                    "Unable to read JDK module class-file entry '" + classEntry + "'.", exception);
        }
    }

    private static ClassModel readClass(final String binaryName, final Path root, final String classEntry) {
        if (Files.isDirectory(root)) {
            final var classFile = root.resolve(classEntry);
            if (!Files.isRegularFile(classFile)) return null;
            try {
                return ClassFile.of().parse(classFile);
            } catch (final IOException | IllegalArgumentException exception) {
                throw metadataError(binaryName, root,
                        "Unable to parse class-file entry '" + classEntry + "'.", exception);
            }
        }
        if (!Files.isRegularFile(root)
                || !root.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw metadataError(binaryName, root,
                    "Java classpath root is not an existing class directory or JAR.", null);
        }
        try (final var jar = new JarFile(root.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
            final var entry = jar.getJarEntry(classEntry);
            if (entry == null || entry.isDirectory()) return null;
            try (final var input = jar.getInputStream(entry)) {
                return ClassFile.of().parse(input.readAllBytes());
            } catch (final IllegalArgumentException exception) {
                throw metadataError(binaryName, root,
                        "Unable to parse JAR class-file entry '" + entry.getName() + "'.", exception);
            }
        } catch (final IOException exception) {
            throw metadataError(binaryName, root,
                    "Unable to read JAR class-file entry '" + classEntry + "'.", exception);
        }
    }

    private static MetadataException metadataError(final String binaryName, final Path root,
                                                   final String detail, final Throwable cause) {
        return new MetadataException(binaryName, root,
                "Java metadata for '" + binaryName + "' in '" + root + "': " + detail, cause);
    }

}
