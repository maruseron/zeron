package com.maruseron.zeron.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return switch (type) {
            case NominalDescriptor nominal when isNewtype(nominal.name()) ->
                    representation(nominal.name()).underlyingType();
            case GenericDescriptor generic when isNewtype(generic.baseType().name()) -> {
                final var representation = representation(generic.baseType().name());
                final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
                for (int index = 0; index < representation.typeParameters().size()
                        && index < generic.typeParameters().size(); index++) {
                    substitutions.put(representation.typeParameters().get(index),
                            generic.typeParameters().get(index));
                }
                yield TypeSubstitution.substitute(representation.underlyingType(), substitutions);
            }
            case NullableDescriptor nullable -> resolve(nullable.baseType()).toNullable();
            case ReferenceDescriptor reference -> new ReferenceDescriptor(resolve(reference.baseType()));
            case ArrayDescriptor array -> TypeDescriptor.arrayOf(resolve(array.elementType()));
            default -> type;
        };
    }

    public static boolean containsNewtype(final TypeDescriptor type) {
        return !resolve(type).equals(type);
    }
}
