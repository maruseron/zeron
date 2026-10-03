package com.maruseron.zeron.domain;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JavaClassPath {
    public record JavaMethod(String name,
                             MethodTypeDesc descriptor,
                             boolean isStatic,
                             boolean isVarArgs,
                             boolean hasGenericSignature,
                             boolean isConstructor) {}

    public record JavaClass(String binaryName,
                            boolean isInterface,
                            boolean isAbstract,
                            boolean hasGenericSignature,
                            List<JavaMethod> methods) {
        public JavaClass {
            methods = List.copyOf(methods);
        }
    }

    private final List<Path> roots;
    private final Map<String, JavaClass> cache = new HashMap<>();

    public JavaClassPath(final List<Path> roots) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public JavaClass find(final String binaryName) {
        if (cache.containsKey(binaryName)) return cache.get(binaryName);
        for (final var root : roots) {
            final var classFile = root.resolve(binaryName.replace('.', '/') + ".class");
            if (!Files.isRegularFile(classFile)) continue;
            try {
                final var model = ClassFile.of().parse(classFile);
                final var flags = model.flags();
                if (!flags.has(AccessFlag.PUBLIC)
                        || flags.has(AccessFlag.ANNOTATION)
                        || flags.has(AccessFlag.ENUM)
                        || flags.has(AccessFlag.MODULE)) {
                    cache.put(binaryName, null);
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
                                method.methodName().equalsString("<init>")))
                        .toList();
                final var javaClass = new JavaClass(binaryName, flags.has(AccessFlag.INTERFACE),
                        flags.has(AccessFlag.ABSTRACT), model.findAttribute(Attributes.signature()).isPresent(),
                        methods);
                cache.put(binaryName, javaClass);
                return javaClass;
            } catch (final IOException exception) {
                throw new IllegalStateException("Unable to read Java class file " + classFile, exception);
            }
        }
        cache.put(binaryName, null);
        return null;
    }
}
