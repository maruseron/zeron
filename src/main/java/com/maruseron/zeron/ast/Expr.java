package com.maruseron.zeron.ast;

import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.JavaCallTarget;
import com.maruseron.zeron.domain.JavaFieldTarget;
import com.maruseron.zeron.domain.ResolvedIntrinsicOperation;
import com.maruseron.zeron.scan.Token;

import java.util.List;
import java.util.Objects;

public sealed interface Expr {

    TypeDescriptor getType();
    void setType(final TypeDescriptor type);

    final class MemberCall implements Expr {
        public final Expr receiver;
        public final Token name;
        public final Token paren;
        public final List<Expr> arguments;
        public final List<TypeDescriptor> explicitTypeArguments;
        private final boolean safeNavigation;
        private TypeDescriptor type;
        private FunctionDescriptor resolvedDescriptor;
        private Stmt.Method resolvedSourceMethod;
        private Stmt.ContractMethod resolvedContractMethod;
        private Stmt.ExtensionMethod resolvedExtensionMethod;
        private Call namespaceCall;
        private String resolvedClassName;
        private String resolvedOwnerName;
        private boolean receiverRequiresCast;
        private JavaCallTarget javaCallTarget;
        private TypeDescriptor variadicElementType;
        private int variadicFixedArity = -1;

        public MemberCall(Expr receiver, Token name, Token paren, List<Expr> arguments,
                          List<TypeDescriptor> explicitTypeArguments, TypeDescriptor type) {
            this(receiver, name, paren, arguments, explicitTypeArguments, type, false);
        }

        public MemberCall(Expr receiver, Token name, Token paren, List<Expr> arguments,
                          List<TypeDescriptor> explicitTypeArguments, TypeDescriptor type,
                          boolean safeNavigation) {
            this.receiver = receiver;
            this.name = name;
            this.paren = paren;
            this.arguments = List.copyOf(arguments);
            this.explicitTypeArguments = List.copyOf(explicitTypeArguments);
            this.type = type;
            this.safeNavigation = safeNavigation;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public boolean safeNavigation() { return safeNavigation; }
        public FunctionDescriptor resolvedDescriptor() { return resolvedDescriptor; }
        public void setResolvedDescriptor(FunctionDescriptor descriptor) { resolvedDescriptor = descriptor; }
        public Stmt.Method resolvedSourceMethod() { return resolvedSourceMethod; }
        public void setResolvedSourceMethod(final Stmt.Method method) { resolvedSourceMethod = method; }
        public Stmt.ContractMethod resolvedContractMethod() { return resolvedContractMethod; }
        public void setResolvedContractMethod(final Stmt.ContractMethod method) { resolvedContractMethod = method; }
        public Stmt.ExtensionMethod resolvedExtensionMethod() { return resolvedExtensionMethod; }
        public void setResolvedExtensionMethod(final Stmt.ExtensionMethod method) {
            resolvedExtensionMethod = method;
        }
        public Call namespaceCall() { return namespaceCall; }
        public void setNamespaceCall(final Call call) { namespaceCall = call; }
        public String resolvedClassName() { return resolvedClassName; }
        public void setResolvedClassName(String className) { resolvedClassName = className; }
        public String resolvedOwnerName() { return resolvedOwnerName; }
        public void setResolvedOwnerName(String name) { resolvedOwnerName = name; }
        public boolean receiverRequiresCast() { return receiverRequiresCast; }
        public void setReceiverRequiresCast(boolean value) { receiverRequiresCast = value; }
        public JavaCallTarget javaCallTarget() { return javaCallTarget; }
        public void setJavaCallTarget(final JavaCallTarget target) { javaCallTarget = target; }
        public TypeDescriptor variadicElementType() { return variadicElementType; }
        public int variadicFixedArity() { return variadicFixedArity; }
        public void setVariadic(final TypeDescriptor elementType, final int fixedArity) {
            variadicElementType = elementType;
            variadicFixedArity = fixedArity;
        }
    }

    final class Property implements Expr {
        public final Expr receiver;
        public final Token name;
        private final boolean safeNavigation;
        private TypeDescriptor type;
        private ResolvedIntrinsicOperation intrinsicOperation;
        private String resolvedOwnerName;
        private boolean resolvedAsProperty;
        private JavaFieldTarget javaFieldTarget;
        private Token namespaceValueSymbol;
        private Stmt.Var namespaceValueDeclaration;

        public Property(Expr receiver, Token name, TypeDescriptor type) {
            this(receiver, name, type, false);
        }

        public Property(Expr receiver, Token name, TypeDescriptor type, boolean safeNavigation) {
            this.receiver = receiver;
            this.name = name;
            this.type = type;
            this.safeNavigation = safeNavigation;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public boolean safeNavigation() { return safeNavigation; }
        public ResolvedIntrinsicOperation intrinsicOperation() { return intrinsicOperation; }
        public void setIntrinsicOperation(ResolvedIntrinsicOperation operation) { intrinsicOperation = operation; }
        public String resolvedOwnerName() { return resolvedOwnerName; }
        public void setResolvedOwnerName(final String ownerName) { resolvedOwnerName = ownerName; }
        public boolean resolvedAsProperty() { return resolvedAsProperty; }
        public void setResolvedAsProperty(final boolean value) { resolvedAsProperty = value; }
        public JavaFieldTarget javaFieldTarget() { return javaFieldTarget; }
        public void setJavaFieldTarget(final JavaFieldTarget target) { javaFieldTarget = target; }
        public Token namespaceValueSymbol() { return namespaceValueSymbol; }
        public Stmt.Var namespaceValueDeclaration() { return namespaceValueDeclaration; }
        public void setNamespaceValue(final Token symbol, final Stmt.Var declaration) {
            namespaceValueSymbol = symbol;
            namespaceValueDeclaration = declaration;
        }
    }

    final class PropertyAssignment implements Expr {
        public final Property property;
        public final Expr value;
        private TypeDescriptor type;

        public PropertyAssignment(Property property, Expr value, TypeDescriptor type) {
            this.property = property;
            this.value = value;
            this.type = type;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
    }

    final class PropertyCompoundAssignment implements Expr {
        public final Property property;
        public final Token operator;
        public final Expr value;
        private TypeDescriptor type;
        private Binary resolvedOperation;

        public PropertyCompoundAssignment(Property property, Token operator, Expr value) {
            this.property = property;
            this.operator = operator;
            this.value = value;
            this.type = TypeDescriptor.ofUnit();
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public Binary resolvedOperation() { return resolvedOperation; }
        public void setResolvedOperation(final Binary operation) { resolvedOperation = operation; }
    }

    final class Assignment implements Expr {
        public final Token name;
        public final Expr value;
        private TypeDescriptor type;
        private Token resolvedSymbolToken;

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
        public Token resolvedSymbolToken() { return resolvedSymbolToken == null ? name : resolvedSymbolToken; }
        public void setResolvedSymbolToken(final Token token) { resolvedSymbolToken = token; }

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

    final class CoalesceAssignment implements Expr {
        public final Token name;
        public final Expr value;
        private TypeDescriptor type;

        public CoalesceAssignment(final Token name, final Expr value) {
            this.name = name;
            this.value = value;
            this.type = TypeDescriptor.ofInfer();
        }

        public TypeDescriptor getType() { return type; }
        public void setType(final TypeDescriptor resolvedType) { type = resolvedType; }
    }

    final class ArrayLiteral implements Expr {
        public final List<Expr> elements;
        private TypeDescriptor type;
        private ResolvedIntrinsicOperation intrinsicOperation;

        public ArrayLiteral(List<Expr> elements, TypeDescriptor type) {
            this.elements = List.copyOf(elements);
            this.type = type;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public ResolvedIntrinsicOperation intrinsicOperation() { return intrinsicOperation; }
        public void setIntrinsicOperation(ResolvedIntrinsicOperation operation) { intrinsicOperation = operation; }
    }

    final class Index implements Expr {
        public final Expr array;
        public final Expr index;
        private TypeDescriptor type;
        private ResolvedIntrinsicOperation intrinsicOperation;

        public Index(Expr array, Expr index, TypeDescriptor type) {
            this.array = array;
            this.index = index;
            this.type = type;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public ResolvedIntrinsicOperation intrinsicOperation() { return intrinsicOperation; }
        public void setIntrinsicOperation(ResolvedIntrinsicOperation operation) { intrinsicOperation = operation; }
    }

    final class IndexAssignment implements Expr {
        public final Expr array;
        public final Expr index;
        public final Expr value;
        private TypeDescriptor type;
        private ResolvedIntrinsicOperation intrinsicOperation;

        public IndexAssignment(Expr array, Expr index, Expr value, TypeDescriptor type) {
            this.array = array;
            this.index = index;
            this.value = value;
            this.type = type;
        }

        public TypeDescriptor getType() { return type; }
        public void setType(TypeDescriptor type) { this.type = type; }
        public ResolvedIntrinsicOperation intrinsicOperation() { return intrinsicOperation; }
        public void setIntrinsicOperation(ResolvedIntrinsicOperation operation) { intrinsicOperation = operation; }
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

    final class TypeTest implements Expr {
        public final Expr value;
        public final Token operator;
        public final TypeDescriptor targetType;
        private TypeDescriptor type = TypeDescriptor.ofBoolean();

        public TypeTest(final Expr value, final Token operator, final TypeDescriptor targetType) {
            this.value = value;
            this.operator = operator;
            this.targetType = targetType;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(final TypeDescriptor type) {
            this.type = type;
        }
    }

    final class Cast implements Expr {
        public final Expr value;
        public final Token operator;
        public final TypeDescriptor targetType;
        public final boolean safe;
        private TypeDescriptor type;

        public Cast(final Expr value, final Token operator,
                    final TypeDescriptor targetType, final boolean safe) {
            this.value = value;
            this.operator = operator;
            this.targetType = targetType;
            this.safe = safe;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(final TypeDescriptor type) {
            this.type = type;
        }
    }

    final class MatchArm {
        private final Token keyword;
        private final TypeDescriptor patternType;
        private final Token alias;
        private final Token namedPattern;
        private final Token binding;
        private final Expr guard;
        private final boolean wildcard;
        private final Expr expression;
        private TypeDescriptor declaredPatternType;
        private TypeDescriptor resolvedPatternType;

        public MatchArm(Token keyword, TypeDescriptor patternType, Token alias,
                        Token namedPattern, Token binding, Expr guard,
                        boolean wildcard, Expr expression) {
            this.keyword = keyword;
            this.patternType = patternType;
            this.alias = alias;
            this.namedPattern = namedPattern;
            this.binding = binding;
            this.guard = guard;
            this.wildcard = wildcard;
            this.expression = expression;
        }

        public Token keyword() { return keyword; }
        public TypeDescriptor patternType() { return patternType; }
        public Token alias() { return alias; }
        public Token namedPattern() { return namedPattern; }
        public Token binding() { return binding; }
        public Expr guard() { return guard; }
        public boolean wildcard() { return wildcard; }
        public Expr expression() { return expression; }
        public TypeDescriptor declaredPatternType() { return declaredPatternType; }
        public TypeDescriptor resolvedPatternType() { return resolvedPatternType; }

        public void setResolvedPatternTypes(TypeDescriptor declaredType, TypeDescriptor resolvedType) {
            declaredPatternType = declaredType;
            resolvedPatternType = resolvedType;
        }
    }

    final class Match implements Expr {
        public final Token keyword;
        public final Expr scrutinee;
        public final List<MatchArm> arms;
        private TypeDescriptor type = TypeDescriptor.ofInfer();

        public Match(final Token keyword, final Expr scrutinee, final List<MatchArm> arms) {
            this.keyword = keyword;
            this.scrutinee = scrutinee;
            this.arms = List.copyOf(arms);
        }

        public TypeDescriptor getType() { return type; }
        public void setType(final TypeDescriptor resolvedType) { type = resolvedType; }
    }

    final class Call implements Expr {
        public final Token callee;
        public final Token paren;
        public final List<Expr> arguments;
        public final List<TypeDescriptor> explicitTypeArguments;
        private TypeDescriptor type;
        private FunctionDescriptor genericFunctionType;
        private String resolvedFunctionName;
        private Stmt.FunctionDeclaration resolvedFunctionDeclaration;
        private Token resolvedSymbolToken;
        private MemberCall implicitMemberCall;
        private ResolvedIntrinsicOperation intrinsicOperation;
        private TypeDescriptor variadicElementType;
        private int variadicFixedArity = -1;

        public Call(Token callee, Token paren, List<Expr> arguments,
                    List<TypeDescriptor> explicitTypeArguments, TypeDescriptor type) {
            this.callee = callee;
            this.paren = paren;
            this.arguments = arguments;
            this.explicitTypeArguments = List.copyOf(explicitTypeArguments);
            this.type = type;
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public FunctionDescriptor genericFunctionType() {
            return genericFunctionType;
        }

        public String resolvedFunctionName() { return resolvedFunctionName; }
        public void setResolvedFunctionName(final String name) { resolvedFunctionName = name; }
        public Stmt.FunctionDeclaration resolvedFunctionDeclaration() { return resolvedFunctionDeclaration; }
        public void setResolvedFunctionDeclaration(final Stmt.FunctionDeclaration declaration) {
            resolvedFunctionDeclaration = declaration;
        }
        public Token resolvedSymbolToken() { return resolvedSymbolToken == null ? callee : resolvedSymbolToken; }
        public void setResolvedSymbolToken(final Token token) { resolvedSymbolToken = token; }
        public MemberCall implicitMemberCall() { return implicitMemberCall; }
        public void setImplicitMemberCall(final MemberCall call) { implicitMemberCall = call; }
        public ResolvedIntrinsicOperation intrinsicOperation() { return intrinsicOperation; }
        public void setIntrinsicOperation(final ResolvedIntrinsicOperation operation) {
            intrinsicOperation = operation;
        }
        public TypeDescriptor variadicElementType() { return variadicElementType; }
        public int variadicFixedArity() { return variadicFixedArity; }
        public void setVariadic(final TypeDescriptor elementType, final int fixedArity) {
            variadicElementType = elementType;
            variadicFixedArity = fixedArity;
        }

        public void setGenericFunctionType(final FunctionDescriptor functionType) {
            this.genericFunctionType = functionType;
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
        public final List<Token> params;
        public final List<Stmt> body;
        private TypeDescriptor type;

        public Lambda(Token arrow, List<Token> params, List<Stmt> body, TypeDescriptor type) {
            this.arrow = arrow;
            this.params = params == null ? List.of() : params;
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
                    Objects.equals(this.params, that.params) &&
                    Objects.equals(this.body,  that.body)  &&
                    Objects.equals(this.type,  that.type)  ;
        }

        public int hashCode() {
            return Objects.hash(arrow, params, body, type);
        }

        public String toString() {
            return "Lambda[" +
                    "arrow=" + arrow + ", " +
                    "params=" + params + ", " +
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

    final class Coalesce implements Expr {
        public final Expr left;
        public final Token operator;
        public final Expr right;
        private TypeDescriptor leftNonNullType;
        private TypeDescriptor type;

        public Coalesce(final Expr left, final Token operator, final Expr right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
            this.leftNonNullType = TypeDescriptor.ofInfer();
            this.type = TypeDescriptor.ofInfer();
        }

        public TypeDescriptor leftNonNullType() { return leftNonNullType; }
        public void setLeftNonNullType(final TypeDescriptor resolvedType) { leftNonNullType = resolvedType; }
        public TypeDescriptor getType() { return type; }
        public void setType(final TypeDescriptor resolvedType) { type = resolvedType; }
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
        public final List<TypeDescriptor> explicitFunctionTypeArguments;
        private TypeDescriptor type;
        private String resolvedFunctionName;
        private Stmt.FunctionDeclaration resolvedFunctionDeclaration;
        private FunctionDescriptor sourceFunctionType;
        private FunctionDescriptor specializedFunctionType;
        private FunctionDescriptor storedFunctionType;
        private Token resolvedSymbolToken;
        private Stmt.Var resolvedValueDeclaration;
        private Expr implicitFieldReceiver;
        private String implicitFieldOwner;
        private TypeDescriptor implicitFieldType;

        public Variable(Token name, TypeDescriptor type) {
            this(name, type, List.of());
        }

        public Variable(Token name, TypeDescriptor type, List<TypeDescriptor> explicitFunctionTypeArguments) {
            this.name = name;
            this.type = type;
            this.explicitFunctionTypeArguments = List.copyOf(explicitFunctionTypeArguments);
        }

        public TypeDescriptor getType() {
            return type;
        }

        public void setType(TypeDescriptor type) {
            this.type = type;
        }

        public String resolvedFunctionName() { return resolvedFunctionName; }
        public void setResolvedFunctionName(final String name) { resolvedFunctionName = name; }
        public Stmt.FunctionDeclaration resolvedFunctionDeclaration() { return resolvedFunctionDeclaration; }
        public void setResolvedFunctionDeclaration(final Stmt.FunctionDeclaration declaration) {
            resolvedFunctionDeclaration = declaration;
        }
        public FunctionDescriptor sourceFunctionType() { return sourceFunctionType; }
        public void setSourceFunctionType(final FunctionDescriptor type) { sourceFunctionType = type; }
        public FunctionDescriptor specializedFunctionType() { return specializedFunctionType; }
        public void setSpecializedFunctionType(final FunctionDescriptor type) { specializedFunctionType = type; }
        public FunctionDescriptor storedFunctionType() { return storedFunctionType; }
        public void setStoredFunctionType(final FunctionDescriptor type) { storedFunctionType = type; }
        public Token resolvedSymbolToken() { return resolvedSymbolToken == null ? name : resolvedSymbolToken; }
        public void setResolvedValue(final Token symbolToken, final Stmt.Var declaration) {
            resolvedSymbolToken = symbolToken;
            resolvedValueDeclaration = declaration;
        }
        public Stmt.Var resolvedValueDeclaration() { return resolvedValueDeclaration; }
        public Expr implicitFieldReceiver() { return implicitFieldReceiver; }
        public String implicitFieldOwner() { return implicitFieldOwner; }
        public TypeDescriptor implicitFieldType() { return implicitFieldType; }
        public void setImplicitFieldRead(final Expr receiver, final String owner, final TypeDescriptor fieldType) {
            implicitFieldReceiver = receiver;
            implicitFieldOwner = owner;
            implicitFieldType = fieldType;
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
