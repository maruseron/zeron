package com.maruseron.zeron;

import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Expr;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class TestRunner {
    private TestRunner() {}

    /// An expected-compilation-failure declaration. `fixture` is relative to a configured
    /// compile-failure root; `code` and `message` are optional assertions on the reported error.
    record CompileFailure(String fixture, String code, String message) {}

    record DiscoveredTest(String qualifiedName, boolean ignored, String ignoreReason,
                          CompileFailure compileFailure) {
        boolean isCompileFailure() {
            return compileFailure != null;
        }
    }

    static List<DiscoveredTest> discover(final List<CompilationUnit> units,
                                         final Set<String> testSourcePaths) {
        final var tests = new ArrayList<DiscoveredTest>();
        for (final var unit : units) {
            if (!testSourcePaths.contains(unit.sourcePath())) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.FunctionDeclaration function)) continue;
                final var annotations = member.annotations().stream()
                        .filter(TestRunner::isAnnotation)
                        .toList();
                final var test = annotations.stream()
                        .filter(annotation -> annotationName(annotation).equals("Test"))
                        .findFirst();
                final var compileFailure = annotations.stream()
                        .filter(annotation -> annotationName(annotation).equals("CompileFailure"))
                        .findFirst();
                if (test.isEmpty() && compileFailure.isEmpty()) continue;
                final var ignore = annotations.stream()
                        .filter(annotation -> annotationName(annotation).equals("Ignore"))
                        .findFirst();
                final var name = member.namespaceName() == null
                        ? qualified(memberPrefix(unit.packageName()), function.name().lexeme())
                        : qualified(qualified(unit.packageName(), member.namespaceName()),
                                function.name().lexeme());
                var reason = "";
                if (ignore.isPresent()) {
                    reason = stringArgument(ignore.get(), "reason");
                }
                tests.add(new DiscoveredTest(name, ignore.isPresent(), reason,
                        compileFailure.map(TestRunner::compileFailure).orElse(null)));
            }
        }
        tests.sort(Comparator.comparing(DiscoveredTest::qualifiedName));
        return List.copyOf(tests);
    }

    static List<DiscoveredTest> filter(final List<DiscoveredTest> tests, final String pattern) {
        if (pattern == null || pattern.isEmpty()) return tests;
        return tests.stream()
                .filter(test -> test.qualifiedName().contains(pattern))
                .toList();
    }

    static String generate(final List<DiscoveredTest> tests, final String targetName,
                           final boolean failFast) {
        final var ordinaryTests = tests.stream().filter(test -> !test.isCompileFailure()).toList();
        final var compileFailureCount = tests.size() - ordinaryTests.size();
        final var runnable = ordinaryTests.stream().anyMatch(test -> !test.ignored());
        final var source = new StringBuilder();
        source.append("package zeron.generated.test;\n\n");
        source.append("import zeron.io.println;\n");
        if (runnable) {
            source.append("import zeron.lang.Effect;\n");
            source.append("import zeron.test.TestFailure;\n");
        }
        for (final var test : ordinaryTests) {
            if (test.ignored()) continue;
            source.append("import ").append(test.qualifiedName()).append(";\n");
        }
        if (runnable) {
            source.append("\nfn reportTestFailure(name: String, message: String): Int = {\n");
            source.append("    println(\"test \" + name + \" ... FAILED\");\n");
            source.append("    if (not message.isEmpty()) {\n");
            source.append("        println(\"  \" + message);\n");
            source.append("    }\n");
            source.append("    yield 1;\n");
            source.append("};\n\n");
            source.append("fn reportUnexpectedEffect(name: String): Int = {\n");
            source.append("    println(\"test \" + name + \" ... FAILED\");\n");
            source.append("    println(\"  an unsupported effect escaped the test\");\n");
            source.append("    yield 2;\n");
            source.append("};\n\n");
        }
        source.append("public fn main(): Int {\n");
        source.append("    let mut passed = 0;\n");
        source.append("    let mut failed = 0;\n");
        source.append("    let mut errors = 0;\n");
        source.append("    let mut skipped = 0;\n");
        if (failFast) {
            source.append("    let mut stop = false;\n");
        }
        source.append("    println(\"running ").append(ordinaryTests.size())
                .append(" tests in target '").append(escape(targetName)).append("'\");\n");
        if (compileFailureCount > 0) {
            source.append("    println(\"").append(compileFailureCount)
                    .append(" expected-compilation-failure tests run by the host\");\n");
        }
        if (tests.isEmpty()) {
            source.append("    println(\"no tests discovered\");\n");
        }
        var index = 0;
        for (final var test : tests) {
            final var result = "status" + index++;
            if (test.isCompileFailure()) continue;
            if (test.ignored()) {
                source.append("    println(\"test ").append(escape(test.qualifiedName()))
                        .append(" ... skipped");
                if (!test.ignoreReason().isEmpty()) {
                    source.append(": ").append(escape(test.ignoreReason()));
                }
                source.append("\");\n");
                source.append("    skipped = skipped + 1;\n");
                continue;
            }
            final var body = new StringBuilder();
            body.append("handle (").append(test.qualifiedName()).append("()) with {\n");
            body.append("    case TestFailure.message(message) -> ").append(result)
                    .append(" = reportTestFailure(\"").append(escape(test.qualifiedName()))
                    .append("\", message);\n");
            body.append("    case Effect -> ").append(result)
                    .append(" = reportUnexpectedEffect(\"").append(escape(test.qualifiedName()))
                    .append("\");\n");
            body.append("};\n");
            body.append("if (").append(result).append(" == 0) {\n");
            body.append("    println(\"test ").append(escape(test.qualifiedName()))
                    .append(" ... ok\");\n");
            body.append("    passed = passed + 1;\n");
            body.append("} else {\n");
            body.append("    if (").append(result).append(" == 2) {\n");
            body.append("        errors = errors + 1;\n");
            body.append("    } else {\n");
            body.append("        failed = failed + 1;\n");
            body.append("    }\n");
            if (failFast) {
                body.append("    stop = true;\n");
            }
            body.append("}\n");
            source.append("    let mut ").append(result).append(" = 0;\n");
            if (failFast) {
                source.append("    if (not stop) {\n");
                source.append(indent(body, "        "));
                source.append("    }\n");
            } else {
                source.append(indent(body, "    "));
            }
        }
        if (failFast) {
            source.append("    if (stop) println(\"stopped after the first failure\");\n");
        }
        source.append("    println(passed.toString() + \" passed, \" + failed.toString()")
                .append(" + \" failed, \" + errors.toString() + \" errors, \"")
                .append(" + skipped.toString() + \" skipped\");\n");
        source.append("    return if (failed + errors > 0) then 1 else 0;\n");
        source.append("}\n");
        return source.toString();
    }

    private static String indent(final CharSequence text, final String prefix) {
        final var indented = new StringBuilder();
        for (final var line : text.toString().split("\n")) {
            if (line.isEmpty()) continue;
            indented.append(prefix).append(line).append('\n');
        }
        return indented.toString();
    }

    private static CompileFailure compileFailure(final Stmt.AnnotationApplication annotation) {
        return new CompileFailure(stringArgument(annotation, "fixture"),
                stringArgument(annotation, "code"), stringArgument(annotation, "message"));
    }

    private static String stringArgument(final Stmt.AnnotationApplication annotation,
                                         final String name) {
        var position = 0;
        for (final var argument : annotation.arguments()) {
            final var argumentName = argument.name() == null ? null : argument.name().lexeme();
            final var matches = argumentName == null
                    ? position == 0 && (name.equals("fixture") || name.equals("reason"))
                    : argumentName.equals(name);
            if (argumentName == null) position++;
            if (!matches) continue;
            if (argument.value() instanceof Expr.Literal literal && literal.value instanceof String text) {
                return text;
            }
            return "";
        }
        return "";
    }

    private static boolean isAnnotation(final Stmt.AnnotationApplication annotation) {
        final var name = annotationName(annotation);
        return name.equals("Test") || name.equals("Ignore") || name.equals("CompileFailure");
    }

    private static String annotationName(final Stmt.AnnotationApplication annotation) {
        final var resolved = annotation.resolvedName();
        final var name = resolved == null ? annotation.name().lexeme() : resolved;
        final var separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static String memberPrefix(final String packageName) {
        return packageName == null ? "" : packageName;
    }

    private static String qualified(final String prefix, final String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "." + name;
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
