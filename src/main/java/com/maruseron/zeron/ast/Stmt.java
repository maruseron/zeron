package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Stmt {
    static String propertyGetterName(final String name) {
        return "$zeron$get$" + name;
    }

    static String propertySetterName(final String name) {
        return "$zeron$set$" + name;
    }

    static String propertyBackingFieldName(final String name) {
        return "$zeron$property$" + name;
    }

    sealed interface Decl {}

    sealed interface FunctionDeclaration extends Stmt, Decl
            permits Function, ExternalFunction {
        Token name();
        List<Token> parameters();
        FunctionDescriptor typeDescriptor();
        boolean isPublic();
    }

    record Block(List<Stmt> statements) implements Stmt {}

    record ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                     List<ContractUse> contractUses, List<Field> fields, List<Property> properties,
                     Constructor constructor, List<NamedConstructor> namedConstructors,
                     List<Method> methods, boolean isPublic) implements Stmt, Decl {
        public ClassDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                         List<ContractUse> contractUses, List<Field> fields,
                         Constructor constructor, List<NamedConstructor> namedConstructors,
                         List<Method> methods, boolean isPublic) {
            this(name, typeParameters, contractUses, fields, List.of(), constructor,
                    namedConstructors, methods, isPublic);
        }

        public List<Token> contractNames() {
            return contractUses.stream().map(ContractUse::name).toList();
        }

        public List<TypeDescriptor> canonicalConstructorTypes() {
            final var types = new java.util.ArrayList<TypeDescriptor>();
            fields.stream().filter(field -> field.initializer() == null)
                    .map(Field::type).forEach(types::add);
            properties.stream().filter(Property::canonicalConstructorParameter)
                    .map(Property::type).forEach(types::add);
            return List.copyOf(types);
        }
    }

    record ContractUse(Token name, List<TypeDescriptor> typeArguments) {
        public ContractUse {
            typeArguments = List.copyOf(typeArguments);
        }
    }

    record Constructor(Token name, boolean isPublic) {}

    record NamedConstructor(Token name, List<Token> parameters,
                            FunctionDescriptor typeDescriptor, boolean isPublic,
                            List<Stmt> body) {}

    record ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                        List<ContractMethod> methods, List<ContractProperty> properties,
                        boolean isPublic, boolean isSealed,
                        List<ContractUse> permittedClasses) implements Stmt, Decl {
        public ContractDecl {
            permittedClasses = List.copyOf(permittedClasses);
        }

        public ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                            List<ContractMethod> methods, boolean isPublic) {
            this(name, typeParameters, methods, List.of(), isPublic, false, List.of());
        }

        public ContractDecl(Token name, List<TypeParameterDescriptor> typeParameters,
                            List<ContractMethod> methods, List<ContractProperty> properties,
                            boolean isPublic) {
            this(name, typeParameters, methods, properties, isPublic, false, List.of());
        }
    }

    record ContractMethod(Token name, List<Token> parameters,
                          FunctionDescriptor typeDescriptor, boolean isMutating,
                          boolean isDefault, List<Stmt> body) {
        public ContractMethod(Token name, List<Token> parameters,
                              FunctionDescriptor typeDescriptor, boolean isMutating) {
            this(name, parameters, typeDescriptor, isMutating, false, List.of());
        }
    }

    record ContractProperty(Token name, TypeDescriptor type, boolean isMutating) {}

    record Property(Token name, TypeDescriptor type, Expr initializer,
                    boolean isPublic, boolean isMutating,
                    List<Stmt> getterBody, Token setterParameter, List<Stmt> setterBody,
                    boolean canonicalConstructorParameter) {
        public Property(Token name, TypeDescriptor type, Expr initializer,
                        boolean isPublic, boolean isMutating,
                        List<Stmt> getterBody, Token setterParameter, List<Stmt> setterBody) {
            this(name, type, initializer, isPublic, isMutating,
                    getterBody, setterParameter, setterBody,
                    initializer == null && getterBody == null && setterBody == null);
        }

        public boolean isCustom() {
            return getterBody != null || setterBody != null;
        }
    }

    record Field(Token name, TypeDescriptor type, Expr initializer) {
        public Field(final Token name, final TypeDescriptor type) {
            this(name, type, null);
        }
    }

    record Break(Token keyword) implements Stmt {}
    record Continue(Token keyword) implements Stmt {}

    record Expression(Expr expression) implements Stmt {}

    record For(Token iterationBind, Token in, Expr iterable, Stmt body) implements Stmt {}

    record Function(Token name, List<Token> parameters,
                    FunctionDescriptor typeDescriptor, List<Stmt> body,
                    boolean isPublic) implements FunctionDeclaration {}

    record ExternalFunction(Token name, List<Token> parameters,
                            FunctionDescriptor typeDescriptor, boolean isPublic) implements FunctionDeclaration {}

    record Method(Token name, List<Token> parameters,
                  FunctionDescriptor typeDescriptor, boolean isPublic,
                  boolean isMutating, List<Stmt> body) {}

    record If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}

    record Return(Expr value) implements Stmt {}

    record Var(Token name, TypeDescriptor type, Expr initializer,
               BindingMutability mutability) implements Stmt, Decl {}

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {}
}
