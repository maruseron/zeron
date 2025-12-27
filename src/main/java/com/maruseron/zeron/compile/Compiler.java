package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.scan.Token;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.*;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Paths;
import java.util.*;

public final class Compiler {
    // private final List<Local> locals = new ArrayList<>();
    // private Map<String, Bind> globals = null;
    private final ClassFile classFile = ClassFile.of();
    private final MethodHandles.Lookup lookup = MethodHandles.lookup();
    private final Resolver resolver = new Resolver();
    private final List<Stmt> declarations;
    private final String MAIN_NAME = "ZeronMain";
    private SymbolTable symbols = null;
    private TypeDescriptor lastEmittedType = null;
    private FunctionModel currentFunction = null;

    public Compiler(List<Stmt> declarations) {
        this.declarations = declarations;
    }

    public void resolve() {
        resolver.resolve(declarations);
        symbols = resolver.symbols;
    }

    public void compile() throws IOException {
        classFile.buildTo(
                Paths.get(MAIN_NAME + ".class").toAbsolutePath(),
                ClassDesc.of(MAIN_NAME),
                cb -> {
                    generateClass(cb, declarations);
                });
    }

    public void generateClass(final ClassBuilder classBuilder, final List<Stmt> declarations) {
        record Initializer(Token name, TypeDescriptor type, Expr initializer) {}

        var hasMain = false;
        final var initializers = new ArrayList<Initializer>();
        // pass to emit declarations: variables as static fields, functions as static methods
        for (final var declaration : declarations) {
            switch (declaration) {
                case Stmt.Var(Token name, _, Expr initializer, boolean isFinal) -> {
                    // attempt to prefold the initializer to set as a constant value attribute in
                    // case it's applicable
                    final ConstantDesc value = tryFold(initializer);
                    final var type = symbols.getSymbol(name).type();
                    // set field
                    classBuilder.withField(
                            name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(type),
                            fieldBuilder -> {
                                fieldBuilder.withFlags(isFinal
                                        ? ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
                                        : ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);

                                // if the initializer could be folded to a constant expression,
                                // set it as a constant value attribute of the field.
                                // else, add it to the unfolded initializer list
                                if (value != null) {
                                    fieldBuilder.with(
                                            ConstantValueAttribute.of(value));
                                } else {
                                    initializers.add(new Initializer(name, type, initializer));
                                }
                            });
                }
                case Stmt.Function(Token name, List<Token> parameters,
                                   FunctionDescriptor typeDescriptor, List<Stmt> body) -> {
                    if (name.lexeme().equals("main")) hasMain = true;
                    classBuilder.withMethodBody(
                            name.lexeme(),
                            toJavaMethodDescriptor((FunctionDescriptor)symbols.getFunction(name).type()),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                            composer -> {
                                beginScope();
                                final var paramTypes = typeDescriptor.parameters();
                                for (int i = 0; i < parameters.size(); i++) {
                                    symbols.declareSymbol(
                                            declaration,
                                            parameters.get(i),
                                            paramTypes.get(i),
                                            true);
                                    symbols.define(parameters.get(i));
                                }

                                currentFunction = new FunctionModel(
                                        name.lexeme(),
                                        symbols.getFunctionType(name));

                                composer.transforming(
                                        (builder, element) -> {
                                            if (element instanceof Instruction i) {
                                                currentFunction.add(i, i.opcode().kind(), null, null, null);
                                            }
                                            builder.with(element);
                                        },
                                        builder -> emitStmts(builder, body));

                                if ((symbols.getFunctionType(name)).returnType() instanceof UnitDescriptor) {
                                    composer.aconst_null();
                                    composer.areturn();
                                }

                                endScope();
                                System.out.println(currentFunction);
                            });
                }
                default -> {}
            }
        }
        if (hasMain) {
            classBuilder.withMethodBody(
                    "main",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        composer.invokestatic(composer.constantPool().methodRefEntry(
                                ClassDesc.of(MAIN_NAME),
                                "main",
                                MethodTypeDesc.of(ConstantDescs.CD_Void)));
                        composer.return_();
                    });
        }
        // static initializer !
        if (!initializers.isEmpty()) {
            classBuilder.withMethodBody(
                    "<clinit>",
                    emptyVoidMethod(),
                    ClassFile.ACC_STATIC,
                    composer -> {
                        // pass to emit static initializers:
                        for (final var pair : initializers) {
                            final var name = pair.name();
                            final var type = pair.type();
                            final var initializer = pair.initializer();

                            // if fails, just emit the expression normally
                            emitExpr(composer, initializer);
                            composer.putstatic(ClassDesc.of(MAIN_NAME), name.lexeme(),
                                    TypeDescriptor.toJavaClassDesc(type));
                        }
                        composer.return_();
                    });
        }
    }

    public void emitStmts(final CodeBuilder builder, final List<Stmt> statements) {
        for (final var statement : statements) {
            emitStmt(builder, statement);
        }
    }

    public void emitStmt(final CodeBuilder composer, final Stmt statement) {
        switch (statement) {
            case Stmt.Block(List<Stmt> statements) -> {
                beginScope();
                emitStmts(composer, statements);
                endScope();
            }
            case Stmt.If(Token paren, Expr condition, Stmt thenBranch, Stmt elseBranch) -> {
                // see if condition is foldable to true or false
                final var value = (Integer)tryFold(condition);
                // on successful fold:
                if (value != null) {
                    // if value is true, only emit thenBranch
                    if (value == 1) {
                        emitStmt(composer, thenBranch);
                    }
                    // if false, only emit elseBranch
                    else {
                        emitStmt(composer, elseBranch);
                    }
                }
                // condition must be emitted
                else {
                    emitExpr(composer, condition);
                    if (elseBranch != null) {
                        composer.ifThenElse(
                                c -> emitStmt(c, thenBranch),
                                c -> emitStmt(c, elseBranch));
                    } else {
                        composer.ifThen(c -> emitStmt(c, thenBranch));
                    }
                }
            }
            case Stmt.Print(Expr expression) -> {
                composer.getstatic(getStdOut(composer.constantPool()));
                emitExpr(composer, expression);
                composer.invokevirtual(getPrintln(composer.constantPool()));
            }
            case Stmt.Expression(Expr expression) ->
                    emitExpr(composer, expression);
            case Stmt.Return(Expr expression) -> {
                emitExpr(composer, expression);
                composer.return_(TypeKind.fromDescriptor(lastEmitted().javaType().descriptorString()));
            }
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer, boolean isFinal) -> {
                // if there is an initializer,
                if (initializer != null) {
                    ConstantDesc value = tryFold(initializer);
                    if (value != null) {
                        emitConstant(composer, value);
                    } else {
                        emitExpr(composer, initializer);
                    }
                    final var lvt = symbols.declareSymbol(statement, name, lastEmittedType, isFinal);
                    composer.storeLocal(
                            TypeKind.fromDescriptor(TypeDescriptor.toJavaClassDesc(lastEmittedType).descriptorString()),
                            lvt);
                    symbols.define(name);
                } else {
                    symbols.declareSymbol(statement, name, type, isFinal);
                }
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitExpr(final CodeBuilder composer, final Expr expr) {
        switch (expr) {
            case Expr.Binary binary -> {
                switch (TypeDescriptor.toJavaClassDesc(binary.getType()).descriptorString()) {
                    case "I" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.iadd();
                            case MINUS -> composer.isub();
                            case STAR -> composer.imul();
                            case SLASH -> composer.idiv();
                            default -> throw new IllegalStateException();
                        }
                        lastEmittedType = TypeDescriptor.ofInt();
                    }
                    case "D" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        switch (binary.operator.type()) {
                            case PLUS -> composer.dadd();
                            case MINUS -> composer.dsub();
                            case STAR -> composer.dmul();
                            case SLASH -> composer.ddiv();
                            default -> throw new IllegalStateException();
                        }
                        lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case "Ljava/lang/String;" -> {
                        emitExpr(composer, binary.left);
                        emitExpr(composer, binary.right);
                        // this has to be a plus
                        final var handle = MethodHandleDesc.of(
                                DirectMethodHandleDesc.Kind.STATIC,
                                ClassDesc.of("java.lang.invoke.StringConcatFactory"),
                                "makeConcatWithConstants",
                                MethodTypeDesc.of(
                                        ClassDesc.of("java.lang.invoke.CallSite"),
                                        ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                                        ConstantDescs.CD_String,
                                        ClassDesc.of("java.lang.invoke.MethodType"),
                                        ConstantDescs.CD_String,
                                        ConstantDescs.CD_Object.arrayType()).descriptorString());

                        final var dcsd = DynamicCallSiteDesc.of(
                                handle,
                                "makeConcatWithConstants",
                                MethodTypeDesc.ofDescriptor(
                                        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                                "\u0001\u0001");

                        composer.invokedynamic(dcsd);
                        lastEmittedType = TypeDescriptor.ofString();
                    }
                }
            }
            case Expr.Grouping grouping ->
                    emitExpr(composer, grouping.expression);
            case Expr.Literal literal -> {
                switch (literal.value) {
                    case String  s -> {
                        composer.ldc(s);
                        lastEmittedType = TypeDescriptor.ofString();
                    }
                    case Integer i -> {
                        composer.ldc(i);
                        lastEmittedType = TypeDescriptor.ofInt();
                    }
                    case Double  d -> {
                        composer.ldc(d);
                        lastEmittedType = TypeDescriptor.ofFloat();
                    }
                    case Boolean b -> {
                        if (b) { composer.iconst_1(); }
                        else   { composer.iconst_0(); }
                        composer.invokestatic(getAutoboxingFor(composer.constantPool(), "B"));
                        lastEmittedType = TypeDescriptor.ofBoolean();
                    }
                    case UnitLiteral _ -> {
                        composer.aconst_null();
                        lastEmittedType = TypeDescriptor.ofUnit();
                    }
                    default -> throw new IllegalStateException("Unsupported value");
                }
            }
            case Expr.Unary unary ->
                    todo("unary: implement resolver");
            case Expr.Variable variable -> {
                final var bind = symbols.getSymbol(variable.name);
                if (bind.lvt() == SymbolTable.GLOBAL) {
                    // global
                    composer.getstatic(
                            ClassDesc.of(MAIN_NAME),
                            variable.name.lexeme(),
                            TypeDescriptor.toJavaClassDesc(symbols.getSymbol(variable.name).type()));
                } else {
                    // locals !
                    switch (TypeDescriptor.toJavaClassDesc(bind.type()).descriptorString()) {
                        case "I", "Z" -> composer.iload(bind.lvt());
                        case "D" -> composer.dload(bind.lvt());
                        default -> composer.aload(bind.lvt());
                    }
                }
                lastEmittedType = bind.type();
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitConstant(final CodeBuilder composer, final ConstantDesc value) {
        composer.loadConstant(value);
        lastEmittedType = getTypeForConstant(value); //currentFunction.code().getLast().zeronType();
    }

    private ConstantDesc tryFold(final Expr expr) {
        return switch (expr) {
            case Expr.Literal lit -> {
                if (lit.value instanceof Boolean b) yield b ? 1 : 0;
                if (lit.value instanceof Constable constable) yield constable.describeConstable().orElseThrow();
                else yield null;
            }
            case Expr.Binary bin -> {
                final var left = tryFold(bin.left);
                final var right = tryFold(bin.right);
                if (left == null || right == null) yield null;
                if (left.getClass() != right.getClass()) yield null;
                switch (left) {
                    case Integer li -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> li + (Integer) right;
                            case MINUS -> li - (Integer) right;
                            case STAR -> li * (Integer) right;
                            case SLASH -> li / (Integer) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case Double ld -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ld + (Double) right;
                            case MINUS -> ld - (Double) right;
                            case STAR -> ld * (Double) right;
                            case SLASH -> ld / (Double) right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    case String ls -> {
                        yield switch (bin.operator.type()) {
                            case PLUS -> ls + right;
                            default -> throw new IllegalStateException();
                        };
                    }
                    default -> {}
                }
                throw new IllegalStateException();
            }
            case null, default -> null;
        };
    }

    public void generateMain(final MethodBuilder methodBuilder, final List<Stmt> statements) {
        methodBuilder.withCode(cb -> {
            emitStmts(cb, statements);
            cb.return_(); // emit final main return
        });
    }

    private void beginScope() {
        symbols.beginScope();
    }

    private void endScope() {
        symbols.endScope();
    }

    private InstructionDescriptor lastEmitted() {
        return currentFunction.code().getLast();
    }

    private static MethodRefEntry getAutoboxingFor(final ConstantPoolBuilder cpb,
                                                   final String type) {
        return switch (type) {
            case "I" -> cpb.methodRefEntry(
                    Integer.class.describeConstable().orElseThrow(),
                    "valueOf",
                    MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
            case "D" -> cpb.methodRefEntry(
                    Double.class.describeConstable().orElseThrow(),
                    "valueOf",
                    MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
            case "B" -> cpb.methodRefEntry(
                    Boolean.class.describeConstable().orElseThrow(),
                    "valueOf",
                    MethodTypeDesc.ofDescriptor("(Z)Ljava/lang/Boolean;"));
            default  -> throw new IllegalArgumentException("Autoboxing not supported.");
        };
    }

    private static FieldRefEntry getStdOut(final ConstantPoolBuilder cpb) {
        return cpb.fieldRefEntry(
                ClassDesc.ofDescriptor(System.class.descriptorString()),
                "out",
                ClassDesc.ofDescriptor("Ljava/io/PrintStream;"));
    }

    private static MethodRefEntry getPrintln(final ConstantPoolBuilder cpb) {
        return cpb.methodRefEntry(
                ClassDesc.ofDescriptor(PrintStream.class.descriptorString()),
                "println",
                MethodType.methodType(void.class, Object.class)
                        .describeConstable()
                        .orElseThrow());
    }

    private static MethodTypeDesc emptyVoidMethod() {
        return MethodTypeDesc.ofDescriptor("()V");
    }

    private static TypeDescriptor getTypeForConstant(final ConstantDesc value) {
        return switch (value) {
            case Integer _ -> TypeDescriptor.ofInt();
            case Double  _ -> TypeDescriptor.ofFloat();
            default -> throw new IllegalStateException();
        };
    }

    private static MethodTypeDesc toJavaMethodDescriptor(FunctionDescriptor type) {
        final var returnType = TypeDescriptor.toJavaClassDesc(type.returnType());
        final var paramTypes = type.parameters().stream().map(TypeDescriptor::toJavaClassDesc).toList();
        return MethodTypeDesc.of(returnType, paramTypes);
    }

    private static void todo(final String message) {
        throw new UnsupportedOperationException(message);
    }
}
