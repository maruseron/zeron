package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.ast.SyntheticNames;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;
import com.maruseron.zeron.domain.BuiltinTypes;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeFormatter;
import com.maruseron.zeron.domain.TypeSubstitution;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.List;
import java.util.Objects;

/// Resolves `Type::property` expressions: the owner type, the property declaration, the reader
/// closure, and the implicit conversion to the property's reader function.
final class PropertyReferenceResolver {
    private PropertyReferenceResolver() {}

    static TypeDescriptor resolve(final ResolutionContext context, final Expr.PropertyReference reference,
                                  final TypeDescriptor expectedType) {
        if (!reference.resolved()) resolveReference(context, reference);
        final var readerType = reference.reader().getType();
        if (reference.converted()) return reference.getType();
        if (matchesExpectedFunction(context, reference, readerType, expectedType)) {
            reference.markConverted();
            reference.setType(readerType);
            return readerType;
        }
        return reference.getType();
    }

    /// Resolves a property-reference literal used as a `@CodeModel` argument. The reader lambda the
    /// reference already carries is the equivalent of the explicit selector lambda, so it becomes the
    /// model's source and is validated against the code-model expression subset.
    static TypeDescriptor resolveCodeModel(final ResolutionContext context,
                                           final Expr.PropertyReference reference,
                                           final TypeDescriptor expectedType) {
        if (!reference.resolved()) resolveReference(context, reference);
        final var reader = reference.reader();
        if (reader == null || !(reader.getType() instanceof FunctionDescriptor readerType)) {
            return reference.getType();
        }
        final var expectedArgument = MemberInteropResolver.codeModelArgument(expectedType);
        if (expectedArgument instanceof FunctionDescriptor expectedFunction) {
            if (expectedFunction.arity() != readerType.arity()) {
                Zeron.resolutionError(new ResolutionError(
                        DiagnosticCatalog.TYPE_MISMATCH_OR_FAILED_INFERENCE, reference.operator,
                        "The property reader takes " + readerType.arity()
                                + " parameter(s), but the code model expects "
                                + expectedFunction.arity() + "."));
            }
            for (int index = 0; index < expectedFunction.arity()
                    && index < readerType.arity(); index++) {
                final var expectedParameter = expectedFunction.parameters().get(index);
                if (!TypeSubstitution.containsTypeParameter(expectedParameter)) {
                    Resolver.ensureAssignable(context, expectedParameter,
                            readerType.parameters().get(index), reference.operator);
                }
            }
            if (!TypeSubstitution.containsTypeParameter(expectedFunction.returnType())) {
                Resolver.ensureAssignable(context, expectedFunction.returnType(),
                        readerType.returnType(), reference.operator);
            }
        }
        final var modelType = TypeDescriptor.genericOf(
                TypeDescriptor.ofName("zeron.compiler.CodeModel"), readerType);
        reader.setCodeModelType(modelType);
        LambdaResolver.validateCodeModelLambda(context, reader,
                reader.parameterTokens(readerType.arity()));
        reference.setCodeModelType(modelType);
        reference.setType(modelType);
        return modelType;
    }

    private static void resolveReference(final ResolutionContext context,
                                         final Expr.PropertyReference reference) {
        final var owner = unwrapOwner(context, reference);
        requireTypeOperand(context, reference, owner);
        TypeResolver.validateType(context, owner, reference.operator);
        final var ownerName = Resolver.className(context, owner);
        final var classDeclaration = context.classes.get(ownerName);
        final var contractDeclaration = context.contracts.get(ownerName);
        if (classDeclaration == null && contractDeclaration == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.operator,
                    "The left side of '::' must name a class or contract type; found '"
                            + TypeFormatter.format(owner) + "'."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return;
        }
        final var property = MemberInteropResolver.findProperty(
                context, ownerName, reference.propertyName);
        if (property == null) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.propertyName,
                    "Type '" + TypeFormatter.format(owner) + "' has no property '"
                            + reference.propertyName.lexeme() + "'."));
            reference.markResolved();
            reference.setType(TypeDescriptor.ofInfer());
            return;
        }
        if (!property.isPublic()
                && !Objects.equals(context.frame.currentClassName, ownerName)) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INACCESSIBLE_DECLARATION,
                    reference.propertyName, "Property '" + reference.propertyName.lexeme()
                            + "' is not accessible at this reference site."));
        } else if (classDeclaration != null && !hasReadableGetter(classDeclaration, property.name())) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.propertyName, "Property '" + reference.propertyName.lexeme()
                            + "' has no getter, so it cannot be referenced."));
        }

        final var propertyType = MemberInteropResolver.resolvedPropertyType(
                context, ownerName, property, owner);
        reference.setOwnerType(owner);
        reference.setDeclaringTypeName(ownerName);
        reference.setIdentity(ownerName + "#" + property.name());
        reference.setPropertyToken(property.nameToken());
        context.recordReference(reference.propertyName, property.nameToken(), propertyType);

        final var parameter = new Token(TokenType.IDENTIFIER, "$zeron$propertyRef$receiver", null,
                reference.propertyName.span());
        final var receiver = new Expr.Variable(parameter, owner);
        final var read = new Expr.Property(receiver, reference.propertyName,
                TypeDescriptor.ofInfer());
        final var reader = new Expr.Lambda(reference.operator, List.of(parameter),
                List.of(new Stmt.Return(read, reference.operator)), TypeDescriptor.ofInfer());
        final var readerType = TypeDescriptor.functionOf("", propertyType, owner);
        LambdaResolver.resolveLambda(context, reader, readerType);
        context.trackLambda(reader);
        reference.setReader(reader);
        reference.setType(TypeDescriptor.genericOf(
                TypeDescriptor.ofName(SyntheticNames.PROPERTY_REF_TYPE), owner, propertyType));
        reference.markResolved();
    }

    private static void requireTypeOperand(final ResolutionContext context,
                                           final Expr.PropertyReference reference,
                                           final TypeDescriptor owner) {
        if (!(owner instanceof NominalDescriptor nominal)) return;
        if (BuiltinTypes.NAMES.contains(nominal.name())) return;
        if (context.types.contains(nominal.name())
                || context.externalClasses.containsKey(nominal.name())
                || context.javaClassPath.find(nominal.name()) != null) {
            return;
        }
        Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                reference.operator,
                "The left side of '::' must name a type, and '" + nominal.name()
                        + "' is not a type in scope. Bound references such as 'value::property' "
                        + "are not supported; write a lambda instead."));
    }

    private static TypeDescriptor unwrapOwner(final ResolutionContext context,
                                              final Expr.PropertyReference reference) {
        final var owner = reference.ownerType();
        TypeResolver.resolveTypeNames(context, owner, reference.operator);
        final var dereferenced = owner instanceof ReferenceDescriptor referenceType
                ? referenceType.baseType() : owner;
        if (dereferenced instanceof NullableDescriptor nullable) {
            Zeron.resolutionError(new ResolutionError(DiagnosticCatalog.INVALID_MEMBER_ACCESS,
                    reference.operator,
                    "The left side of '::' must name a type, not a nullable value."));
            return nullable.baseType();
        }
        return dereferenced;
    }

    private static boolean hasReadableGetter(final Stmt.ClassDecl declaration,
                                             final String propertyName) {
        return declaration.properties().stream()
                .filter(property -> property.name().lexeme().equals(propertyName))
                .findFirst()
                .map(property -> !property.isCustom() || property.getterBody() != null)
                .orElse(true);
    }

    private static boolean matchesExpectedFunction(final ResolutionContext context,
                                                   final Expr.PropertyReference reference,
                                                   final TypeDescriptor readerType,
                                                   final TypeDescriptor expectedType) {
        if (expectedType == null || expectedType.isNullable()) return false;
        final var expectedFunction = LambdaResolver.functionType(
                context, expectedType instanceof ReferenceDescriptor referenceType
                        ? referenceType.baseType() : expectedType);
        if (expectedFunction == null || expectedFunction.receiverType() != null) return false;
        if (!(readerType instanceof FunctionDescriptor reader)) return false;
        // A function-shaped expectation converts the reference even when its own types are not yet
        // concrete, such as the `(T) -> B` parameter of a generic selector; mismatches surface in
        // the ordinary assignability check instead of being rejected here.
        return expectedFunction.arity() == reader.arity()
                && expectedFunction.isHandler() == reader.isHandler();
    }
}
