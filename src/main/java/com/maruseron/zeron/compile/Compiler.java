package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.analize.Resolver;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.*;
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
import java.util.ArrayList;
import java.util.List;

public final class Compiler {
    private final ClassFile classFile = ClassFile.of();
    private final MethodHandles.Lookup lookup = MethodHandles.lookup();
    private final Resolver resolver = new Resolver();
    private final List<Stmt> declarations;
    private final String MAIN_NAME = "ZeronMain";

    public Compiler(List<Stmt> declarations) {
        this.declarations = declarations;
    }

    public void resolve() {
        resolver.resolve(declarations);
    }

    public void compile() throws IOException {
        classFile.buildTo(
                Paths.get(MAIN_NAME + ".class").toAbsolutePath(),
                ClassDesc.of(MAIN_NAME),
                cb -> generateClass(cb, declarations));
    }

    public void generateClass(final ClassBuilder classBuilder, final List<Stmt> declarations) {
        record Initializer(Token name, TypeDescriptor type, Expr initializer) {}
        final var initializers = new ArrayList<Initializer>();
        // pass to emit declarations: variables as static fields, functions as static methods
        for (final var declaration : declarations) {
            switch (declaration) {
                case Stmt.Var(Token name, _, Expr initializer, boolean isFinal) -> {
                    // attempt to prefold the initializer to set as a constant value attribute in
                    // case it's applicable
                    final ConstantDesc value = tryFold(initializer);
                    final var type = resolver.symbols.getSymbol(name).type();
                    // set field
                    classBuilder.withField(
                            name.lexeme(),
                            toJavaDescriptor(type),
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
                default -> {}
            }
        }
        // static initializer !
        /*
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
                            if (constExpr != null) {
                                emitConstant(composer, constExpr);
                                composer.putstatic(ClassDesc.of(MAIN_NAME), name.lexeme(),
                                        toJavaDescriptor(type));
                            }
                        }
                    });
        }
        */
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
                if (left instanceof Integer li && right instanceof Integer ri) {
                    yield switch (bin.operator.type()) {
                        case PLUS  -> li + ri;
                        case MINUS -> li - ri;
                        case STAR  -> li * ri;
                        case SLASH -> li / ri;
                        default -> throw new IllegalStateException();
                    };
                }
                if (left instanceof Double ld && right instanceof Double rd) {
                    yield switch (bin.operator.type()) {
                        case PLUS  -> ld + rd;
                        case MINUS -> ld - rd;
                        case STAR  -> ld * rd;
                        case SLASH -> ld / rd;
                        default -> throw new IllegalStateException();
                    };
                }
                if (left instanceof String ls && right instanceof String rs) {
                    yield switch (bin.operator.type()) {
                        case PLUS  -> ls + rs;
                        default -> throw new IllegalStateException();
                    };
                }
                throw new IllegalStateException();
            }
            case null, default -> null;
        };
    }

    public void generateMain(final MethodBuilder methodBuilder, final List<Stmt> statements) {
        methodBuilder.withCode(cb -> {
            emitBytecode(cb, statements);
            cb.return_(); // emit final main return
        });
    }

    public void emitBytecode(final CodeBuilder builder, final List<Stmt> statements) {
        for (final var statement : statements) {
            emitBytecode(builder, statement);
        }
    }

    public void emitBytecode(final CodeBuilder builder, final Stmt statement) {
        switch (statement) {
            case Stmt.Var(Token name, TypeDescriptor type, Expr initializer, boolean isFinal) ->
                    todo("letdecl: implement resolver");
            case Stmt.Print(Expr expression) -> {
                builder.getstatic(getStdOut(builder.constantPool()));
                emitExpr(builder, expression);
                builder.invokevirtual(getPrintln(builder.constantPool()));
            }
            case Stmt.Expression(Expr expression) ->
                    emitExpr(builder, expression);
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitExpr(final CodeBuilder builder, final Expr expr) {
        switch (expr) {
            case Expr.Binary binary ->
                    todo("binary: implement resolver");
            case Expr.Grouping grouping ->
                    emitExpr(builder, grouping.expression);
            case Expr.Literal literal -> {
                switch (literal.value) {
                    case String  s -> builder.ldc(s);
                    case Integer i -> builder.ldc(i);
                    case Double  d -> builder.ldc(d);
                    case Boolean b -> {
                        if (b) {
                            builder.iconst_1();
                        } else {
                            builder.iconst_0();
                        }
                        builder.invokestatic(getAutoboxingFor(builder.constantPool(), "B"));
                    }
                    case UnitLiteral _ -> builder.aconst_null();
                    default -> throw new IllegalStateException("Unsupported value");
                }
            }
            case Expr.Unary unary ->
                    todo("unary: implement resolver");
            case Expr.Variable name ->
                    todo("variable: implement resolver");
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitConstant(final CodeBuilder composer, final Constable c) {
        switch (c) {
            case Boolean b -> {
                if (b) {
                    composer.iconst_1();
                } else {
                    composer.iconst_0();
                }
            }
            case Integer i -> {
                composer.iconst_0();
            }
            default -> throw new UnsupportedOperationException();
        }
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

    private void todo(final String message) {
        throw new UnsupportedOperationException(message);
    }

    private ClassDesc toJavaDescriptor(final TypeDescriptor typeDescriptor) {
        return switch (typeDescriptor) {
            case Infer _, Never _ -> throw new IllegalStateException();
            case Nominal n -> switch (n.name()) {
                    case "String"  -> ConstantDescs.CD_String;
                    case "Boolean" -> ConstantDescs.CD_boolean;
                    case "Int"     -> ConstantDescs.CD_int;
                    case "Float"   -> ConstantDescs.CD_double;
                    default -> null;
            };
            case Unit _ -> ConstantDescs.CD_Void;
            default -> throw new UnsupportedOperationException();
        };
    }
}
