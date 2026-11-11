package zeron.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Immutable runtime identity of an applied type.
 *
 * <p>This is the prototype representation described by ZEP-0015. Identity is exact and
 * structural: the qualified owner name, recursively applied arguments, nullability, mutability,
 * and array shape. Aliases are expanded by the compiler before a descriptor is created, and
 * function types are rejected at that boundary rather than modeled here.
 *
 * <p>Descriptors are values. Two descriptors constructed independently are equal exactly when
 * their structural identity is equal, and the hash is computed once at construction. A compiler
 * emits monomorphic descriptors as constants; forwarding one through a {@code reified} parameter
 * therefore copies a reference and performs no allocation.
 */
public final class AppliedTypeDescriptor {
    private final String owner;
    private final List<AppliedTypeDescriptor> arguments;
    private final AppliedTypeDescriptor component;
    private final boolean nullable;
    private final boolean mutable;
    private final int hash;

    private AppliedTypeDescriptor(final String owner,
                                  final List<AppliedTypeDescriptor> arguments,
                                  final AppliedTypeDescriptor component,
                                  final boolean nullable,
                                  final boolean mutable) {
        this.owner = owner;
        this.arguments = arguments;
        this.component = component;
        this.nullable = nullable;
        this.mutable = mutable;
        this.hash = computeHash();
    }

    /**
     * A nominal type with no type arguments.
     */
    public static AppliedTypeDescriptor of(final String qualifiedName) {
        return of(qualifiedName, List.of());
    }

    /**
     * A nominal type with the supplied applied arguments.
     */
    public static AppliedTypeDescriptor of(final String qualifiedName,
                                           final AppliedTypeDescriptor... arguments) {
        return of(qualifiedName, List.of(arguments));
    }

    /**
     * A nominal type with the supplied applied arguments.
     */
    public static AppliedTypeDescriptor of(final String qualifiedName,
                                           final List<AppliedTypeDescriptor> arguments) {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        if (qualifiedName.isBlank()) {
            throw new IllegalArgumentException("A type owner must not be blank.");
        }
        if (qualifiedName.indexOf('<') >= 0 || qualifiedName.indexOf('[') >= 0
                || qualifiedName.endsWith("?") || qualifiedName.endsWith("!")) {
            throw new IllegalArgumentException(
                    "A type owner must be an expanded qualified name, not rendered type syntax: "
                            + qualifiedName);
        }
        return new AppliedTypeDescriptor(qualifiedName, List.copyOf(arguments), null, false, false);
    }

    /**
     * An array of the supplied component type.
     */
    public static AppliedTypeDescriptor arrayOf(final AppliedTypeDescriptor component) {
        Objects.requireNonNull(component, "component");
        return new AppliedTypeDescriptor(null, List.of(), component, false, false);
    }

    /**
     * This type with additional type arguments.
     */
    public AppliedTypeDescriptor withArguments(final AppliedTypeDescriptor... arguments) {
        return withArguments(List.of(arguments));
    }

    /**
     * This type with additional type arguments.
     */
    public AppliedTypeDescriptor withArguments(final List<AppliedTypeDescriptor> arguments) {
        if (isArray()) {
            throw new IllegalStateException("An array type cannot have type arguments.");
        }
        return new AppliedTypeDescriptor(owner, List.copyOf(arguments), null, nullable, mutable);
    }

    /**
     * A nullable form of this type.
     */
    public AppliedTypeDescriptor nullable() {
        return nullable ? this : new AppliedTypeDescriptor(owner, arguments, component, true, mutable);
    }

    /**
     * A mutable form of this type.
     */
    public AppliedTypeDescriptor mutable() {
        return mutable ? this : new AppliedTypeDescriptor(owner, arguments, component, nullable, true);
    }

    /**
     * An array whose component is this type.
     */
    public AppliedTypeDescriptor array() {
        return arrayOf(this);
    }

    public boolean isArray() {
        return component != null;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isMutable() {
        return mutable;
    }

    /**
     * The expanded qualified owner name, or {@code null} for an array.
     */
    public String owner() {
        return owner;
    }

    /**
     * The applied type arguments, empty for arrays.
     */
    public List<AppliedTypeDescriptor> arguments() {
        return arguments;
    }

    /**
     * The component type of an array, or {@code null} for a nominal type.
     */
    public AppliedTypeDescriptor component() {
        return component;
    }

    /**
     * The canonical source-like rendering of this descriptor.
     */
    public String rendered() {
        final var text = isArray()
                ? component.rendered() + "[]"
                : owner + (arguments.isEmpty() ? "" : "<" + String.join(", ",
                        arguments.stream().map(AppliedTypeDescriptor::rendered).toList()) + ">");
        return text + (nullable ? "?" : "") + (mutable ? "!" : "");
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (!(other instanceof AppliedTypeDescriptor descriptor)) return false;
        return hash == descriptor.hash
                && nullable == descriptor.nullable
                && mutable == descriptor.mutable
                && Objects.equals(owner, descriptor.owner)
                && Objects.equals(component, descriptor.component)
                && arguments.equals(descriptor.arguments);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return rendered();
    }

    private int computeHash() {
        var result = Objects.hashCode(owner);
        result = 31 * result + arguments.hashCode();
        result = 31 * result + Objects.hashCode(component);
        result = 31 * result + Boolean.hashCode(nullable);
        result = 31 * result + Boolean.hashCode(mutable);
        return result;
    }
}
