package zeron.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class AppliedTypeDescriptorTest {
    private static final AppliedTypeDescriptor INT = AppliedTypeDescriptor.of("zeron.lang.Int");

    @Test
    public void equalDescriptorsAreStructurallyEqualAcrossIndependentConstruction() {
        final var first = AppliedTypeDescriptor.of("zeron.lang.Map",
                AppliedTypeDescriptor.of("zeron.lang.String"),
                AppliedTypeDescriptor.of("zeron.lang.Box", INT));
        final var second = AppliedTypeDescriptor.of("zeron.lang.Map",
                AppliedTypeDescriptor.of("zeron.lang.String"),
                AppliedTypeDescriptor.of("zeron.lang.Box", INT));

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("zeron.lang.Map<zeron.lang.String, zeron.lang.Box<zeron.lang.Int>>",
                first.rendered());
    }

    @Test
    public void keepsNullabilityMutabilityAndArrayShapeDistinct() {
        final var plain = AppliedTypeDescriptor.of("zeron.lang.Box", INT);
        final var nullable = plain.nullable();
        final var mutable = plain.mutable();
        final var array = plain.array();
        final var nullableArray = array.nullable();

        assertNotEquals(plain, nullable);
        assertNotEquals(plain, mutable);
        assertNotEquals(plain, array);
        assertNotEquals(array, nullableArray);
        assertTrue(nullable.isNullable());
        assertTrue(mutable.isMutable());
        assertTrue(array.isArray());
        assertNull(array.owner());
        assertSame(plain, array.component());
        assertEquals("zeron.lang.Box<zeron.lang.Int>[]", array.rendered());
        assertEquals("zeron.lang.Box<zeron.lang.Int>[]?", nullableArray.rendered());
        assertEquals("zeron.lang.Box<zeron.lang.Int>!", mutable.rendered());
    }

    @Test
    public void flagsComposeWithoutLosingExistingIdentity() {
        final var descriptor = AppliedTypeDescriptor.of("zeron.lang.Box", INT).nullable().mutable();

        assertTrue(descriptor.isNullable());
        assertTrue(descriptor.isMutable());
        assertSame(descriptor, descriptor.nullable());
        assertSame(descriptor, descriptor.mutable());
        assertEquals(AppliedTypeDescriptor.of("zeron.lang.Box", INT).nullable().mutable(),
                descriptor);
    }

    @Test
    public void arrayOfArrayAndAppliedArgumentsCompose() {
        final var nested = AppliedTypeDescriptor.arrayOf(
                AppliedTypeDescriptor.of("zeron.lang.Box",
                        AppliedTypeDescriptor.of("zeron.lang.Pair",
                                INT, AppliedTypeDescriptor.of("zeron.lang.String")))).array();

        assertTrue(nested.isArray());
        assertTrue(nested.component().isArray());
        assertEquals("zeron.lang.Box<zeron.lang.Pair<zeron.lang.Int, zeron.lang.String>>[][]",
                nested.rendered());
    }

    @Test
    public void argumentsAreCopiedAtConstruction() {
        final var arguments = new ArrayList<AppliedTypeDescriptor>();
        arguments.add(INT);
        final var descriptor = AppliedTypeDescriptor.of("zeron.lang.Box", arguments);
        arguments.add(AppliedTypeDescriptor.of("zeron.lang.String"));

        assertEquals(List.of(INT), descriptor.arguments());
        assertEquals(AppliedTypeDescriptor.of("zeron.lang.Box", INT), descriptor);
        try {
            descriptor.arguments().add(INT);
            fail("Descriptor arguments must be immutable.");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void rejectsRenderedTypeSyntaxAndArrayArguments() {
        try {
            AppliedTypeDescriptor.of("zeron.lang.Box<zeron.lang.Int>");
            fail("Rendered type syntax must not be accepted as an owner.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        final var array = AppliedTypeDescriptor.arrayOf(INT);
        try {
            array.withArguments(INT);
            fail("Array types cannot have type arguments.");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void canonicalLookupInternsNestedFlagsAndArrays() {
        final var descriptor = AppliedTypeDescriptors.lookup(
                "zeron.lang.Map<zeron.lang.String,zeron.lang.Box<zeron.lang.Int>?>[]!");

        assertEquals(AppliedTypeDescriptor.of("zeron.lang.Map",
                        AppliedTypeDescriptor.of("zeron.lang.String"),
                        AppliedTypeDescriptor.of("zeron.lang.Box", INT).nullable()).array().mutable(),
                descriptor);
        assertSame(descriptor, AppliedTypeDescriptors.lookup(
                "zeron.lang.Map<zeron.lang.String,zeron.lang.Box<zeron.lang.Int>?>[]!"));
        assertEquals("zeron.lang.Map<zeron.lang.String, zeron.lang.Box<zeron.lang.Int>?>[]!",
                descriptor.rendered());
    }

    @Test
    public void canonicalLookupRejectsMalformedDescriptors() {
        try {
            AppliedTypeDescriptors.lookup("zeron.lang.Box<");
            fail("Malformed descriptors must be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void nullabilityAndMutabilityDoNotChangeUnderlyingArguments() {
        final var base = AppliedTypeDescriptor.of("zeron.lang.Box", INT);
        final var marked = base.nullable().mutable();

        assertEquals(base.arguments(), marked.arguments());
        assertFalse(marked.arguments().getFirst().isNullable());
        assertFalse(marked.arguments().getFirst().isMutable());
    }
}
