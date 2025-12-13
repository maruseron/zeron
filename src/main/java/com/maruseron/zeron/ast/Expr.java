package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public sealed interface Expr {

    TypeDescriptor getType();
    void setType(final TypeDescriptor type);

    final class Assignment implements Expr {
        public final Token name;
        public final Expr value;
        private TypeDescriptor type;

        public Assignment(Token name, Expr value, TypeDescriptor type) {
            this.name = name;
            this.value = value;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Assignment that)) return false;
            return  Objects.equals(this.name,  that.name)  &&
                    Objects.equals(this.value, that.value) &&
                    Objects.equals(this.type,  that.type)  ;
        }

        public int hashCode() {
            return Objects.hash(name, value, type);
        }

        public String toString() {
            return "Assignment[" +
                    "name=" + name + ", " +
                    "value=" + value + ", " +
                    "type=" + type + ']';
        }
    }

    final class Binary implements Expr {
        public final Expr left;
        public final Token operator;
        public final Expr right;
        private TypeDescriptor type;

        public Binary(Expr left, Token operator, Expr right, TypeDescriptor type) {
            this.left = left;
            this.operator = operator;
            this.right = right;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Binary that)) return false;
            return  Objects.equals(this.left,     that.left)     &&
                    Objects.equals(this.operator, that.operator) &&
                    Objects.equals(this.right,    that.right)    &&
                    Objects.equals(this.type,     that.type)     ;
        }

        public int hashCode() {
            return Objects.hash(left, operator, right, type);
        }

        public String toString() {
            return "Binary[" +
                    "left=" + left + ", " +
                    "operator=" + operator + ", " +
                    "right=" + right + ", " +
                    "type=" + type + ']';
        }
    }

    final class Call implements Expr {
        public final Token callee;
        public final Token paren;
        public final List<Expr> arguments;
        private TypeDescriptor type;

        public Call(Token callee, Token paren, List<Expr> arguments, TypeDescriptor type) {
            this.callee = callee;
            this.paren = paren;
            this.arguments = arguments;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Call that)) return false;
            return  Objects.equals(this.callee,    that.callee)    &&
                    Objects.equals(this.paren,     that.paren)     &&
                    Objects.equals(this.arguments, that.arguments) &&
                    Objects.equals(this.type,      that.type);
        }

        public int hashCode() {
            return Objects.hash(callee, paren, arguments, type);
        }

        public String toString() {
            return "Call[" +
                    "callee=" + callee + ", " +
                    "paren=" + paren + ", " +
                    "arguments=" + arguments + ", " +
                    "type=" + type + ']';
        }
    }

    final class Grouping implements Expr {
        public final Token paren;
        public final Expr expression;
        private TypeDescriptor type;

        public Grouping(Token paren, Expr expression, TypeDescriptor type) {
            this.paren = paren;
            this.expression = expression;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Grouping that)) return false;
            return  Objects.equals(this.paren,      that.paren)      &&
                    Objects.equals(this.expression, that.expression) &&
                    Objects.equals(this.type,       that.type)       ;
        }

        public int hashCode() {
            return Objects.hash(paren, expression, type);
        }

        public String toString() {
            return "Grouping[" +
                    "paren=" + paren + ", " +
                    "expression=" + expression + ", " +
                    "type=" + type + ']';
        }
    }

    final class If implements Expr {
        public final Token paren;
        public final Expr condition;
        public final Expr thenExpr;
        public final Expr elseExpr;
        private TypeDescriptor type;

        public If(Token paren, Expr condition, Expr thenExpr, Expr elseExpr, TypeDescriptor type) {
            this.paren = paren;
            this.condition = condition;
            this.thenExpr = thenExpr;
            this.elseExpr = elseExpr;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof If that)) return false;
            return  Objects.equals(this.paren,     that.paren)     &&
                    Objects.equals(this.condition, that.condition) &&
                    Objects.equals(this.thenExpr,  that.thenExpr)  &&
                    Objects.equals(this.elseExpr,  that.elseExpr)  &&
                    Objects.equals(this.type,      that.type)      ;
        }

        public int hashCode() {
            return Objects.hash(paren, condition, thenExpr, elseExpr, type);
        }

        public String toString() {
            return "If[" +
                    "paren=" + paren + ", " +
                    "condition=" + condition + ", " +
                    "thenExpr=" + thenExpr + ", " +
                    "elseExpr=" + elseExpr + ", " +
                    "type=" + type + ']';
        }
    }

    final class Lambda implements Expr {
        public final Token arrow;
        public final Token param;
        public final List<Stmt> body;
        private TypeDescriptor type;

        public Lambda(Token arrow, Token param, List<Stmt> body, TypeDescriptor type) {
            this.arrow = arrow;
            this.param = param;
            this.body = body;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Lambda that)) return false;
            return  Objects.equals(this.arrow, that.arrow) &&
                    Objects.equals(this.param, that.param) &&
                    Objects.equals(this.body,  that.body)  &&
                    Objects.equals(this.type,  that.type)  ;
        }

        public int hashCode() {
            return Objects.hash(arrow, param, body, type);
        }

        public String toString() {
            return "Lambda[" +
                    "arrow=" + arrow + ", " +
                    "param=" + param + ", " +
                    "body="  + body  + ", " +
                    "type="  + type  + ']';
        }
    }

    final class Literal implements Expr {
        public final Object value;
        private TypeDescriptor type;

        public Literal(Object value, TypeDescriptor type) {
            this.value = value;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Literal that)) return false;
            return  Objects.equals(this.value, that.value) &&
                    Objects.equals(this.type,  that.type)  ;
        }

        public int hashCode() {
            return Objects.hash(value, type);
        }

        public String toString() {
            return "Literal[" +
                    "value=" + value + ", " +
                    "type=" + type + ']';
        }
    }

    final class Logical implements Expr {
        public final Expr left;
        public final Token operator;
        public final Expr right;
        private TypeDescriptor type = TypeDescriptor.ofBoolean();

        public Logical(Expr left, Token operator, Expr right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Logical that)) return false;
            return  Objects.equals(this.left,     that.left)     &&
                    Objects.equals(this.operator, that.operator) &&
                    Objects.equals(this.right,    that.right)    ;
        }

        public int hashCode() {
            return Objects.hash(left, operator, right);
        }

        public String toString() {
            return "Logical[" +
                    "left=" + left + ", " +
                    "operator=" + operator + ", " +
                    "right=" + right + ']';
        }
    }

    final class Unary implements Expr {
        public final Token operator;
        public final Expr right;
        private TypeDescriptor type;

        public Unary(Token operator, Expr right, TypeDescriptor type) {
            this.operator = operator;
            this.right = right;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Unary that)) return false;
            return  Objects.equals(this.operator, that.operator) &&
                    Objects.equals(this.right,    that.right)    &&
                    Objects.equals(this.type,     that.type)     ;
        }

        public int hashCode() {
            return Objects.hash(operator, right, type);
        }

        public String toString() {
            return "Unary[" +
                    "operator=" + operator + ", " +
                    "right=" + right + ", " +
                    "type=" + type + ']';
        }
    }

    final class Variable implements Expr {
        public final Token name;
        private TypeDescriptor type;

        public Variable(Token name, TypeDescriptor type) {
            this.name = name;
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (!(obj instanceof Variable that)) return false;
            return  Objects.equals(this.name, that.name) &&
                    Objects.equals(this.type, that.type) ;
        }

        public int hashCode() {
            return Objects.hash(name, type);
        }

        public String toString() {
            return "Variable[" +
                    "name=" + name + ", " +
                    "type=" + type + ']';
        }
    }
}
