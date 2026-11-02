package com.maruseron.zeron.analize;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.AnnotationConstant;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AnnotationTest {
    @Test
    public void resolvesDeclaredAnnotationsAndTheirConstantArguments() {
        final var source = Parser.of(Scanner.from("""
                package annotationstest;
                public annotation Entity on CLASS;
                public annotation Label(value: String, retries: Int = 2) on FUNCTION;
                @Entity
                public class User {}
                @Label("api", retries = 3)
                public fn api(): Unit {}
                namespace Tools {
                    @Label("namespaced")
                    public fn namespaced(): Unit {}
                }
                """).scanTokens()).parseCompilationUnit("src/main.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(source));

        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertTrue(source.annotationSites().stream()
                .anyMatch(site -> site.targetName().equals("annotationstest.Tools.namespaced")));
    }

    @Test
    public void preservesArrayNullableAndDecimalAnnotationConstants() {
        final var unit = Parser.of(Scanner.from("""
                package annotationconstants;
                annotation Metadata(tags: Array<String>, optional: String? = null,
                    ratio: Float = 1.5) on FUNCTION;
                @Metadata(["alpha", "beta"], ratio = -2.25)
                fn describe(): Unit {}
                """).scanTokens()).parseCompilationUnit("src/constants.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit));

        assertTrue(result.errors().toString(), result.errors().isEmpty());
        final var arguments = unit.annotationSites().getFirst().applications().getFirst().resolvedArguments();
        assertTrue(arguments.get(0).value() instanceof AnnotationConstant.ArrayValue);
        assertTrue(arguments.get(1).value() instanceof AnnotationConstant.NullValue);
        assertEquals(new java.math.BigDecimal("-2.25"),
                ((AnnotationConstant.DecimalValue) arguments.get(2).value()).value());
    }

    @Test
    public void reportsUnknownAnnotationsInvalidTargetsAndIllTypedArguments() {
        final var source = Parser.of(Scanner.from("""
                annotation Entity on CLASS;
                @Missing
                class Unknown {}
                @Entity
                fn WrongTarget(): Unit {}
                annotation Label(value: String) on FUNCTION;
                @Label(42)
                fn WrongValue(): Unit {}
                """).scanTokens()).parseCompilationUnit("src/main.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(source));

        assertEquals(3, result.errors().size());
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains("Unknown annotation")));
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains("does not target")));
        assertTrue(result.errors().stream().anyMatch(error -> error.message().contains("supported constant")));
    }

    @Test
    public void resolvesImportedPublicAnnotationDefinitions() {
        final var definitions = Parser.of(Scanner.from("""
                package annotationdefs;
                public annotation Label(value: String) on FUNCTION;
                """).scanTokens()).parseCompilationUnit("src/annotations.zn");
        final var usage = Parser.of(Scanner.from("""
                package annotationuser;
                import annotationdefs.Label;
                @Label("public")
                public fn api(): Unit {}
                """).scanTokens()).parseCompilationUnit("src/api.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(definitions, usage));

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void resolvesTheBundledCodeModelAnnotation() {
        final var usage = Parser.of(Scanner.from("""
                package codeModelUser;
                import zeron.compiler.CodeModel;
                public fn where(@CodeModel predicate: CodeModel<(Int) -> Boolean>): Unit {}
                """).scanTokens()).parseCompilationUnit("src/query.zn");

        final var units = StandardLibrary.withBundledUnits(List.of(usage));
        final var result = new ResolutionService().resolveUnitsWithDiagnostics(units);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void requiresCodeModelAnnotationParametersToUseCodeModelFunctionTypes() {
        final var usage = Parser.of(Scanner.from("""
                package codeModelUser;
                import zeron.compiler.CodeModel;
                public fn where<T>(@CodeModel predicate: T? -> Boolean): Unit {
                    predicate(null);
                }
                """).scanTokens()).parseCompilationUnit("src/query.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(
                StandardLibrary.withBundledUnits(List.of(usage)));

        assertTrue(result.errors().toString(), result.errors().stream()
                .anyMatch(error -> error.message().contains(
                        "@CodeModel parameters must use CodeModel<F>")));
    }

    @Test
    public void recordsAnnotationsOnMembersDataComponentsAndParameters() {
        final var unit = Parser.of(Scanner.from("""
                package annotationmembers;
                public annotation Marker(value: String = "metadata") on CLASS, FUNCTION, METHOD,
                    CONSTRUCTOR, FIELD, PROPERTY, DATA_COMPONENT, PARAMETER;
                @Marker
                public class User {
                    @Marker public constructor new;
                    @Marker public run(@Marker value: String): Unit {}
                    @Marker public property displayName: String;
                    @Marker internalId: String;
                }
                @Marker
                public fn entry(@Marker value: String): Unit {}
                """).scanTokens()).parseCompilationUnit("src/main.zn");
        final var external = Parser.of(Scanner.from("""
                package java.io;
                import annotationmembers.Marker;
                @Marker
                public external class File = "java.io.File" {
                    @Marker public constructor new(@Marker path: String);
                    @Marker public exists(): Boolean;
                }
                """).scanTokens()).parseCompilationUnit("src/external.zn");

        final var result = new ResolutionService().resolveUnitsWithDiagnostics(List.of(unit, external));

        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertTrue(unit.annotationSites().stream()
                .anyMatch(site -> site.target() == Stmt.AnnotationTarget.CONSTRUCTOR));
        assertTrue(unit.annotationSites().stream()
                .anyMatch(site -> site.target() == Stmt.AnnotationTarget.FIELD));
        assertTrue(unit.annotationSites().stream()
                .anyMatch(site -> site.target() == Stmt.AnnotationTarget.PROPERTY));
        assertTrue(unit.annotationSites().stream()
                .filter(site -> site.target() == Stmt.AnnotationTarget.PARAMETER)
                .count() >= 2);
        assertTrue(external.annotationSites().stream()
                .anyMatch(site -> site.target() == Stmt.AnnotationTarget.METHOD
                        && site.targetName().equals("java.io.File.exists")));
    }

    @Test
    public void recordsAnnotationsOnDataComponents() {
        final var unit = Parser.of(Scanner.from("""
                public annotation Marker on DATA_COMPONENT;
                public data class Record(@Marker id: Int) {}
                """).scanTokens()).parseCompilationUnit("src/main.zn");

        assertTrue(unit.annotationSites().stream()
                .anyMatch(site -> site.target() == Stmt.AnnotationTarget.DATA_COMPONENT));
    }
}
