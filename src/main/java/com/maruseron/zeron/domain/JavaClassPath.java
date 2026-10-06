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

    public record JavaField(String name, java.lang.constant.ClassDesc descriptor, boolean isStatic) {}

    public record JavaClass(String binaryName,
                            boolean isInterface,
                            boolean isAbstract,
                            boolean hasGenericSignature,
                            List<JavaMethod> methods,
                            List<JavaField> fields) {
        public JavaClass {
            methods = List.copyOf(methods);
            fields = List.copyOf(fields);
        }
    }

    private final List<Path> roots;
    private final Map<String, JavaClass> cache = new HashMap<>();

    public JavaClassPath(final List<Path> roots) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public JavaClass find(final String binaryName) {
        if (cache.containsKey(binaryName)) return cache.get(binaryName);
        final var curated = findCuratedJdkClass(binaryName);
        if (curated != null) {
            cache.put(binaryName, curated);
            return curated;
        }
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
                final var fields = model.fields().stream()
                        .filter(field -> field.flags().has(AccessFlag.PUBLIC))
                        .map(field -> new JavaField(field.fieldName().stringValue(), field.fieldTypeSymbol(),
                                field.flags().has(AccessFlag.STATIC)))
                        .toList();
                final var javaClass = new JavaClass(binaryName, flags.has(AccessFlag.INTERFACE),
                        flags.has(AccessFlag.ABSTRACT), model.findAttribute(Attributes.signature()).isPresent(),
                        methods, fields);
                cache.put(binaryName, javaClass);
                return javaClass;
            } catch (final IOException exception) {
                throw new IllegalStateException("Unable to read Java class file " + classFile, exception);
            }
        }
        cache.put(binaryName, null);
        return null;
    }

    private static JavaClass findCuratedJdkClass(final String binaryName) {
        if (!binaryName.equals("java.lang.System") && !binaryName.equals("java.io.PrintStream")) return null;
        try {
            final var javaType = Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
            final var methods = java.util.Arrays.stream(javaType.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .filter(method -> binaryName.equals("java.io.PrintStream")
                            && (method.getName().equals("print") || method.getName().equals("println"))
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0] == Object.class)
                    .map(method -> new JavaMethod(method.getName(),
                            java.lang.constant.MethodTypeDesc.ofDescriptor(
                                    java.lang.invoke.MethodType.methodType(method.getReturnType(),
                                            method.getParameterTypes()).descriptorString()),
                            java.lang.reflect.Modifier.isStatic(method.getModifiers()), false,
                            false, false))
                    .toList();
            final var fields = java.util.Arrays.stream(javaType.getDeclaredFields())
                    .filter(field -> binaryName.equals("java.lang.System") && field.getName().equals("out"))
                    .filter(field -> java.lang.reflect.Modifier.isPublic(field.getModifiers())
                            && java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(field -> new JavaField(field.getName(),
                            java.lang.constant.ClassDesc.ofDescriptor(field.getType().descriptorString()), true))
                    .toList();
            return new JavaClass(binaryName, javaType.isInterface(),
                    java.lang.reflect.Modifier.isAbstract(javaType.getModifiers()), false, methods, fields);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }
}
