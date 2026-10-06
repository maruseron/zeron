package com.maruseron.zeron.domain;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FunctionBindingRegistry {
    public sealed interface JvmTarget permits StaticMethod, StaticFieldInstanceMethod {}

    public record StaticMethod(ClassDesc owner,
                               String methodName,
                               MethodTypeDesc descriptor) implements JvmTarget {
        public StaticMethod {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(descriptor);
        }
    }

    public record StaticFieldInstanceMethod(ClassDesc fieldOwner,
                                             String fieldName,
                                             ClassDesc fieldType,
                                             ClassDesc methodOwner,
                                             String methodName,
                                             MethodTypeDesc methodDescriptor) implements JvmTarget {
        public StaticFieldInstanceMethod {
            Objects.requireNonNull(fieldOwner);
            Objects.requireNonNull(fieldName);
            Objects.requireNonNull(fieldType);
            Objects.requireNonNull(methodOwner);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(methodDescriptor);
            if (!fieldType.equals(methodOwner)) {
                throw new IllegalArgumentException("Static field type must match the instance method owner.");
            }
        }
    }

    public record Binding(FunctionDescriptor signature, JvmTarget target) {
        public Binding {
            Objects.requireNonNull(signature);
            Objects.requireNonNull(target);
        }
    }

    private static final FunctionBindingRegistry STANDARD = createStandard();

    private final Map<String, Binding> bindings;

    private FunctionBindingRegistry(final Map<String, Binding> bindings) {
        this.bindings = Map.copyOf(new LinkedHashMap<>(bindings));
    }

    public static FunctionBindingRegistry standard() {
        return STANDARD;
    }

    public static FunctionBindingRegistry of(final Map<String, Binding> bindings) {
        return new FunctionBindingRegistry(bindings);
    }

    public FunctionBindingRegistry withBinding(final String qualifiedName, final Binding binding) {
        final var updated = new LinkedHashMap<>(bindings);
        if (updated.putIfAbsent(Objects.requireNonNull(qualifiedName), Objects.requireNonNull(binding)) != null) {
            throw new IllegalArgumentException("Duplicate function binding: " + qualifiedName);
        }
        return new FunctionBindingRegistry(updated);
    }

    public Binding find(final String qualifiedName) {
        return bindings.get(qualifiedName);
    }

    private static FunctionBindingRegistry createStandard() {
        return new FunctionBindingRegistry(Map.of());
    }
}