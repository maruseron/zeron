package com.maruseron.zeron.domain;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class TypeFormatterTest {
    @Test
    public void formatsNonGenericFunctionTypesWithoutGenericTerminator() {
        final var function = new FunctionDescriptor("", TypeDescriptor.ofString(),
                List.of(TypeDescriptor.ofInt()));

        assertEquals("(Int) -> String", TypeFormatter.format(function));
    }

    @Test
    public void formatsGenericFunctionTypeParameters() {
        final var parameter = new TypeParameterDescriptor(1, "T");
        final var function = new FunctionDescriptor("", parameter, List.of(parameter),
                List.of(parameter));

        assertEquals("<T>(T) -> T", TypeFormatter.format(function));
    }

    @Test
    public void formatsMutableAdmissibilityMarker() {
        final var parameter = new TypeParameterDescriptor(1, "T", List.of(), false,
                TypeParameterDescriptor.Variance.INVARIANT, false, false, true);
        final var function = new FunctionDescriptor("", parameter, List.of(parameter),
                List.of(parameter));

        assertEquals("<T: mut>(T) -> T", TypeFormatter.format(function));
    }

    @Test
    public void formatsUseSiteProjections() {
        final var type = TypeDescriptor.genericOf(TypeDescriptor.ofName("Cell"), List.of(
                new TypeProjectionDescriptor(TypeProjectionDescriptor.Kind.IN, TypeDescriptor.ofString())));

        assertEquals("Cell<in String>", TypeFormatter.format(type));
    }

    @Test
    public void formatsHandlerShapesWithCanonicalEffectSets() {
        final var failure = TypeDescriptor.ofName("effects.Failure");
        final var audit = TypeDescriptor.ofName("effects.Audit");
        final var first = TypeDescriptor.handlerOf(TypeDescriptor.ofString(),
                List.of(failure, audit), List.of(audit));
        final var reordered = TypeDescriptor.handlerOf(TypeDescriptor.ofString(),
                List.of(audit, failure), List.of(audit));

        assertEquals("String ~> {effects.Audit, effects.Failure} raises {effects.Audit}",
                TypeFormatter.format(first));
        assertEquals("String ~> {effects.Failure}",
                TypeFormatter.format(TypeDescriptor.handlerOf(TypeDescriptor.ofString(),
                        List.of(failure), List.of())));
        assertEquals(first, reordered);
        assertEquals(first.hashCode(), reordered.hashCode());
    }
}
