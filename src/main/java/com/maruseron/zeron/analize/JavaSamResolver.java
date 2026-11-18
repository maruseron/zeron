package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

/** Discovers Java functional interfaces and builds Zeron callback signatures for them. */
final class JavaSamResolver {
    record SamShape(JavaClassPath.JavaClass rootClass,
                    JavaClassPath.JavaMethod method,
                    FunctionDescriptor functionType) {}

    private record Candidate(String key, JavaClassPath.JavaMethod method,
                             FunctionDescriptor functionType) {}

    private JavaSamResolver() {}

    static JavaSamTarget probe(final ResolutionContext context,
                               final TypeDescriptor targetType,
                               final Token where) {
        try {
            return resolve(context, targetType, where);
        } catch (final ResolutionError _) {
            return null;
        }
    }

    static JavaSamTarget resolve(final ResolutionContext context,
                                 final TypeDescriptor targetType,
                                 final Token where) {
        final var unwrapped = unwrap(targetType);
        final var nominal = nominal(unwrapped);
        if (nominal == null) return null;
        final var javaClass = context.javaClassPath.find(nominal.name());
        if (javaClass == null || !javaClass.isInterface()) return null;
        final var shape = context.javaSamShapes
                .computeIfAbsent(javaClass.binaryName(), _ -> discover(context, javaClass))
                .orElse(null);
        if (shape == null) return null;

        final Map<TypeParameterDescriptor, TypeDescriptor> substitutions = new LinkedHashMap<>();
        if (unwrapped instanceof GenericDescriptor generic) {
            if (generic.typeParameters().size() != shape.rootClass().typeParameters().size()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, where,
                        "A Java functional interface requires a complete type-argument list."));
            }
            for (int index = 0; index < generic.typeParameters().size(); index++) {
                final var argument = generic.typeParameters().get(index);
                if (argument instanceof TypeProjectionDescriptor) {
                    Zeron.resolutionError(new ResolutionError(
                            DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, where,
                            "Java SAM conversion does not support projected type arguments."));
                }
                substitutions.put(shape.rootClass().typeParameters().get(index), argument);
            }
        } else if (!shape.rootClass().typeParameters().isEmpty()) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, where,
                    "A Java functional interface requires explicit type arguments."));
        }

        final var functionType = (FunctionDescriptor) TypeSubstitution.substitute(
                shape.functionType(), substitutions);
        if (TypeSubstitution.containsTypeParameter(functionType)) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, where,
                    "A Java SAM method must be monomorphic after interface substitution."));
        }
        final var instantiated = methodType(functionType, shape.method().descriptor().returnType(), where);
        return new JavaSamTarget(targetType, shape.method().name(), shape.method().descriptor(),
                instantiated, functionType);
    }

    private static Optional<SamShape> discover(final ResolutionContext context,
                                               final JavaClassPath.JavaClass rootClass) {
        final var candidates = new LinkedHashMap<String, Candidate>();
        if (!collect(context, rootClass, identitySubstitutions(rootClass), candidates,
                new HashSet<>())) return Optional.empty();
        if (candidates.size() != 1) return Optional.empty();
        final var candidate = candidates.values().iterator().next();
        return Optional.of(new SamShape(rootClass, candidate.method(), candidate.functionType()));
    }

    private static boolean collect(final ResolutionContext context,
                                   final JavaClassPath.JavaClass javaClass,
                                   final Map<TypeParameterDescriptor, TypeDescriptor> substitutions,
                                   final Map<String, Candidate> candidates,
                                   final Set<String> visited) {
        if (!visited.add(javaClass.binaryName())) return true;
        for (final var method : javaClass.methods()) {
            if (!method.isAbstract()) continue;
            if (isObjectMethod(method)) continue;
            final var signature = methodSignature(context, javaClass, method, substitutions);
            if (signature == null) return false;
            final var key = method.name() + method.descriptor().descriptorString();
            candidates.putIfAbsent(key, new Candidate(key, method, signature));
        }
        for (final var interfaceType : javaClass.interfaceTypes()) {
            final var substituted = TypeSubstitution.substitute(interfaceType, substitutions);
            final var nominal = nominal(unwrap(substituted));
            if (nominal == null) continue;
            final var parent = context.javaClassPath.find(nominal.name());
            if (parent == null) continue;
            final var parentSubstitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
            if (substituted instanceof NullableDescriptor nullable) {
                addInterfaceSubstitutions(parent, nullable.baseType(), parentSubstitutions);
            } else {
                addInterfaceSubstitutions(parent, substituted, parentSubstitutions);
            }
            if (!collect(context, parent, parentSubstitutions, candidates, visited)) return false;
        }
        return true;
    }

    private static void addInterfaceSubstitutions(final JavaClassPath.JavaClass parent,
                                                  final TypeDescriptor type,
                                                  final Map<TypeParameterDescriptor, TypeDescriptor> target) {
        if (type instanceof GenericDescriptor generic
                && generic.typeParameters().size() == parent.typeParameters().size()) {
            for (int index = 0; index < parent.typeParameters().size(); index++) {
                target.put(parent.typeParameters().get(index), generic.typeParameters().get(index));
            }
            return;
        }
        for (final var parameter : parent.typeParameters()) target.put(parameter, parameter);
    }

    private static FunctionDescriptor methodSignature(final ResolutionContext context,
                                                      final JavaClassPath.JavaClass javaClass,
                                                      final JavaClassPath.JavaMethod method,
                                                      final Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {
        try {
            if (method.genericSignature() != null) {
                final var parsed = JavaGenericSignatureParser.parseMethodSignature(
                        method.genericSignature(), javaClass.typeParameters());
                if (!parsed.typeParameters().isEmpty()) return null;
                if (parsed.parameterTypes().size() != method.descriptor().parameterCount()) return null;
                final var parameters = parsed.parameterTypes().stream()
                        .map(type -> TypeSubstitution.substitute(type, substitutions)).toList();
                final var returnType = TypeSubstitution.substitute(parsed.returnType(), substitutions);
                return callbackType(method.name(), returnType, parameters);
            }
            final var parameters = new ArrayList<TypeDescriptor>(method.descriptor().parameterCount());
            for (int index = 0; index < method.descriptor().parameterCount(); index++) {
                final var mapped = JavaTypeMapping.toZeronParameterType(
                        method.descriptor().parameterType(index));
                if (mapped == null) return null;
                parameters.add(mapped);
            }
            final var returnType = JavaTypeMapping.toZeronReturnType(method.descriptor().returnType());
            if (returnType == null) return null;
            return callbackType(method.name(), returnType, parameters);
        } catch (final IllegalArgumentException _) {
            return null;
        }
    }

    private static FunctionDescriptor callbackType(final String name,
                                                   final TypeDescriptor returnType,
                                                   final List<TypeDescriptor> parameters) {
        return TypeDescriptor.functionWithEffectsOf(name, returnType, parameters,
                List.of(), List.of());
    }

    private static MethodTypeDesc methodType(final FunctionDescriptor functionType,
                                             final ClassDesc erasedReturnType,
                                             final Token where) {
        final var returnType = erasedReturnType.equals(ClassDesc.ofDescriptor("V"))
                ? erasedReturnType
                : TypeDescriptor.toJavaClassDesc(TypeSubstitution.erase(functionType.returnType()));
        final var parameters = new ClassDesc[functionType.parameters().size()];
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = TypeDescriptor.toJavaClassDesc(
                    TypeSubstitution.erase(functionType.parameters().get(index)));
        }
        try {
            return MethodTypeDesc.of(returnType, parameters);
        } catch (final IllegalArgumentException _) {
            Zeron.resolutionError(new ResolutionError(
                    DiagnosticCatalog.UNSUPPORTED_JAVA_INTEROP_FEATURE, where,
                    "The Java SAM method signature cannot be represented by Zeron types."));
            return null;
        }
    }

    private static boolean isObjectMethod(final JavaClassPath.JavaMethod method) {
        final var descriptor = method.descriptor().descriptorString();
        return method.name().equals("equals") && descriptor.equals("(Ljava/lang/Object;)Z")
                || method.name().equals("hashCode") && descriptor.equals("()I")
                || method.name().equals("toString") && descriptor.equals("()Ljava/lang/String;");
    }

    private static Map<TypeParameterDescriptor, TypeDescriptor> identitySubstitutions(
            final JavaClassPath.JavaClass javaClass) {
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (final var parameter : javaClass.typeParameters()) {
            substitutions.put(parameter, parameter);
        }
        return substitutions;
    }

    private static TypeDescriptor unwrap(final TypeDescriptor type) {
        var current = type;
        while (current instanceof NullableDescriptor nullable) current = nullable.baseType();
        while (current instanceof ReferenceDescriptor reference) current = reference.baseType();
        return current;
    }

    private static NominalDescriptor nominal(final TypeDescriptor type) {
        if (type instanceof GenericDescriptor generic) return generic.baseType();
        if (type instanceof NominalDescriptor nominal) return nominal;
        return null;
    }
}
