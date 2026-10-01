package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.BindingMutability;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;

public sealed interface Stmt {
    sealed interface Decl {}

    record Block(List<Stmt> statements) implements Stmt {}

    record ClassDecl(Token name, Token contractName, List<Field> fields,
                     Constructor constructor, List<Method> methods) implements Stmt, Decl {}

    record Constructor(Token name, boolean isPublic) {}

    record ContractDecl(Token name, List<ContractMethod> methods) implements Stmt, Decl {}

    record ContractMethod(Token name, List<Token> parameters,
                          FunctionDescriptor typeDescriptor, boolean isMutating) {}

    record Field(Token name, TypeDescriptor type) {}

    record Break(Token keyword) implements Stmt {}

    record Expression(Expr expression) implements Stmt {}

    record For(Token iterationBind, Token in, Expr iterable, Stmt body) implements Stmt {}

    record Function(Token name, List<Token> parameters,
                    FunctionDescriptor typeDescriptor, List<Stmt> body) implements Stmt, Decl {}

    record Method(Token name, List<Token> parameters,
                  FunctionDescriptor typeDescriptor, boolean isPublic,
                  boolean isMutating, List<Stmt> body) {}

    record If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) implements Stmt {}

    record Print(Expr expression) implements Stmt {}

    record Return(Expr value) implements Stmt {}

    record Var(Token name, TypeDescriptor type, Expr initializer,
               BindingMutability mutability) implements Stmt, Decl {}

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {}
}
