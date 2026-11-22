package com.maruseron.zeron.domain;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Tracks the direct JVM carrier chosen for each newtype. Newtypes keep their nominal identity
/// in the type checker, but their values are represented by the carrier everywhere they cross a
/// JVM boundary.
public final class NewtypeRepresentations {
    public record Representation(List<TypeParameterDescriptor> typeParameters,
                                 TypeDescriptor underlyingType) {
        public Representation {
            typeParameters = List.copyOf(typeParameters);
        }
    }

    private static final Map<String, Representation> REPRESENTATIONS = new LinkedHashMap<>();

    private NewtypeRepresentations() {
    }

    public static void register(final String qualifiedName, final List<TypeParameterDescriptor> typeParameters,
                                final TypeDescriptor underlyingType) {
        REPRESENTATIONS.put(qualifiedName, new Representation(typeParameters, underlyingType));
    }

    /// Discards the representations registered by a previous compilation. Resolution re-registers
    /// every newtype visible to the compilation being resolved (including library-index units), so
    /// representations must not survive into an unrelated compilation that shares this JVM.
    public static void reset() {
        REPRESENTATIONS.clear();
    }

    public static boolean isNewtype(final String qualifiedName) {
        return REPRESENTATIONS.containsKey(qualifiedName);
    }

    public static Representation representation(final String qualifiedName) {
        return REPRESENTATIONS.get(qualifiedName);
    }

    /// Resolves a type that denotes a newtype to the carrier used at runtime. Non-newtype types are
    /// returned unchanged; nullable, reference, array and function wrappers are preserved so their
    /// element types can be resolved recursively.
    public static TypeDescriptor resolve(final TypeDescriptor type) {
        return resolve(type, new HashSet<>());
    }

    private static TypeDescriptor resolve(final TypeDescriptor type, final Set<String> expanding) {
        return switch (type) {
            case NominalDescriptor nominal when isNewtype(nominal.name()) -> {
                if (!expanding.add(nominal.name())) yield type;
                try {
                    yield resolve(representation(nominal.name()).underlyingType(), expanding);
                } finally {
                    expanding.remove(nominal.name());
                }
            }
            case GenericDescriptor generic when isNewtype(generic.baseType().name()) -> {
                if (!expanding.add(generic.baseType().name())) yield type;
                final var representation = representation(generic.baseType().name());
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                for (int index = 0; index < representation.typeParameters().size()
                        && index < generic.typeParameters().size(); index++) {
                    substitutions.put(representation.typeParameters().get(index),
                            generic.typeParameters().get(index));
                }
                try {
                    yield resolve(TypeSubstitution.substitute(
                            representation.underlyingType(), substitutions), expanding);
                } finally {
                    expanding.remove(generic.baseType().name());
                }
            }
            case NullableDescriptor nullable -> resolve(nullable.baseType(), expanding).toNullable();
            case ReferenceDescriptor reference ->
                    new ReferenceDescriptor(resolve(reference.baseType(), expanding));
            case ArrayDescriptor array ->
                    TypeDescriptor.arrayOf(resolve(array.elementType(), expanding));
            case FunctionDescriptor function -> new FunctionDescriptor(
                    function.name(),
                    function.receiverType() == null ? null
                            : resolve(function.receiverType(), expanding),
                    resolve(function.returnType(), expanding),
                    function.parameters().stream()
                            .map(parameter -> resolve(parameter, expanding)).toList(),
                    function.typeParameters(),
                    function.raisedEffects().stream()
                            .map(effect -> resolve(effect, expanding)).toList(),
                    function.handledEffects().stream()
                            .map(effect -> resolve(effect, expanding)).toList(),
                    function.isHandler()).withHiddenSlots(
                            function.hiddenEvidenceCount(), function.hiddenDescriptorCount());
            default -> type;
        };
    }

    /// Resolves a newtype in a read position to its carrier while leaving the surrounding shape
    /// intact. Unlike [#resolve], reference, array, and generic wrappers are not rewritten: a
    /// reference to a newtype stays a reference (so ordinary dereferencing still applies), and
    /// generic arguments remain invariant.
    public static TypeDescriptor resolveRead(final TypeDescriptor type) {
        return switch (type) {
            case NominalDescriptor nominal when isNewtype(nominal.name()) -> resolve(type);
            case GenericDescriptor generic when isNewtype(generic.baseType().name()) -> resolve(type);
            case NullableDescriptor nullable -> resolveRead(nullable.baseType()).toNullable();
            default -> type;
        };
    }

    public static boolean containsNewtype(final TypeDescriptor type) {
        return !resolve(type).equals(type);
    }
}
