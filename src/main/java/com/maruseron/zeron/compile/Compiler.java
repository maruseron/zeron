package com.maruseron.zeron.compile;

import com.maruseron.zeron.UnitLiteral;
import com.maruseron.zeron.ast.*;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Paths;
import java.util.List;

public final class Compiler {
    private final ClassFile classFile = ClassFile.of();
    private final MethodHandles.Lookup lookup = MethodHandles.lookup();

    public void compile(final List<Stmt> statements) throws IOException {
        classFile.buildTo(
                Paths.get("Test.class").toAbsolutePath(),
                ClassDesc.of("Test"),
                cb -> generateClass(cb, statements));
    }

    public void generateClass(final ClassBuilder classBuilder, final List<Stmt> statements) {
        // emit main
        classBuilder.withMethod(
                "main",
                emptyVoidMethod(),
                ClassFile.ACC_STATIC,
                mb -> generateMain(mb, statements));
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
                emitValue(builder, expression);
                builder.invokevirtual(getPrintln(builder.constantPool()));
            }
            case Stmt.Expression(Expr expression) ->
                    emitValue(builder, expression);
            default -> throw new UnsupportedOperationException();
        }
    }

    private void emitValue(final CodeBuilder builder, final Expr expr) {
        switch (expr) {
            case Expr.Binary(Expr left, Token operator, Expr right) ->
                    todo("binary: implement resolver");
            case Expr.Grouping(Token paren, Expr expression) ->
                    emitValue(builder, expression);
            case Expr.Literal(Object value) -> {
                switch (value) {
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
            case Expr.Unary(Token operator, Expr right) ->
                    todo("unary: implement resolver");
            case Expr.Variable(Token name) ->
                    todo("variable: implement resolver");
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

    private MethodRefEntry getPrintln(final ConstantPoolBuilder cpb) {
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
}
