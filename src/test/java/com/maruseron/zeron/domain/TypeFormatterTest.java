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
}
