package com.maruseron.zeron.domain;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class JavaGenericSignatureParserTest {
    @Test
    public void mapsExtendsSuperAndUnboundedWildcards() {
        final var upper = genericArgument("Ljava/util/List<+Ljava/lang/String;>;");
        final var lower = genericArgument("Ljava/util/List<-Ljava/lang/Number;>;");
        final var unbounded = genericArgument("Ljava/util/List<*>;");

        assertEquals(TypeProjectionDescriptor.Kind.OUT,
                ((TypeProjectionDescriptor) upper).kind());
        assertEquals(TypeProjectionDescriptor.Kind.IN,
                ((TypeProjectionDescriptor) lower).kind());
        assertEquals(TypeProjectionDescriptor.Kind.OUT,
                ((TypeProjectionDescriptor) unbounded).kind());
        assertEquals(TypeDescriptor.ofAny().toNullable(),
                ((TypeProjectionDescriptor) unbounded).bound());
    }

    @Test
    public void substitutesGenericOwnerParametersInMethodSignatures() {
        final var parameters = JavaGenericSignatureParser.parseClassTypeParameters(
                "<E:Ljava/lang/Object;>Ljava/lang/Object;");
        final var signature = JavaGenericSignatureParser.parseMethodSignature("(TE;)TE;", parameters);

        assertEquals(parameters.getFirst(), signature.parameterTypes().getFirst());
        assertEquals(parameters.getFirst(), signature.returnType());
        assertTrue(parameters.getFirst().admitsNullableArguments());
        assertTrue(!signature.hasMethodTypeParameters());
    }

    @Test
    public void parsesMethodTypeParametersAndBounds() {
        final var signature = JavaGenericSignatureParser.parseMethodSignature(
                "<T:Ljava/lang/String;>(TT;)TT;^Ljava/io/IOException;", List.of());

        assertEquals(1, signature.typeParameters().size());
        assertEquals(TypeDescriptor.ofString().toNullable(),
                signature.typeParameters().getFirst().bounds().getFirst());
        assertTrue(signature.typeParameters().getFirst().admitsNullableArguments());
        assertEquals(signature.typeParameters().getFirst(), signature.parameterTypes().getFirst());
        assertEquals(signature.typeParameters().getFirst(), signature.returnType());
        assertEquals("java.io.IOException", signature.throwsTypes().getFirst().name());
        assertTrue(signature.hasMethodTypeParameters());
    }

    @Test
    public void retainsGenericThrowsTypeVariablesForRejection() {
        final var signature = JavaGenericSignatureParser.parseMethodSignature(
                "<E:Ljava/lang/Exception;>()V^TE;", List.of());

        assertEquals(signature.typeParameters().getFirst(), signature.throwsTypes().getFirst());
    }

    @Test
    public void parsesNestedParameterizedTypesUsingMethodTypeParameters() {
        final var signature = JavaGenericSignatureParser.parseMethodSignature(
                "<T:Ljava/lang/Object;>(Ljava/util/List<Ljava/util/List<+TT;>;>;)TT;", List.of());
        final var outer = (NullableDescriptor) signature.parameterTypes().getFirst();
        final var outerGeneric = (GenericDescriptor) outer.baseType();
        final var inner = (NullableDescriptor) outerGeneric.typeParameters().getFirst();
        final var innerGeneric = (GenericDescriptor) inner.baseType();
        final var projection = (TypeProjectionDescriptor) innerGeneric.typeParameters().getFirst();

        assertEquals(signature.typeParameters().getFirst(), projection.bound());
        assertEquals(signature.typeParameters().getFirst(), signature.returnType());
    }

    private static TypeDescriptor genericArgument(final String signature) {
        final var parsed = JavaGenericSignatureParser.parseFieldType(signature, List.of());
        return ((GenericDescriptor) ((NullableDescriptor) parsed).baseType())
                .typeParameters().getFirst();
    }
}
