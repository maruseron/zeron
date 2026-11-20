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
    record DiscoveredTest(String qualifiedName, boolean ignored, String ignoreReason) {}

    private TestRunner() {}

    static List<DiscoveredTest> discover(final List<CompilationUnit> units,
                                         final Set<String> testSourcePaths) {
        final var tests = new ArrayList<DiscoveredTest>();
        for (final var unit : units) {
            if (!testSourcePaths.contains(unit.sourcePath())) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.FunctionDeclaration function)) continue;
                final var test = member.annotations().stream()
                        .filter(TestRunner::isAnnotation)
                        .filter(annotation -> annotationName(annotation).equals("Test"))
                        .findFirst();
                if (test.isEmpty()) continue;
                final var ignore = member.annotations().stream()
                        .filter(TestRunner::isAnnotation)
                        .filter(annotation -> annotationName(annotation).equals("Ignore"))
                        .findFirst();
                final var name = member.namespaceName() == null
                        ? qualified(memberPrefix(unit.packageName()), function.name().lexeme())
                        : qualified(qualified(unit.packageName(), member.namespaceName()),
                                function.name().lexeme());
                var reason = "";
                if (ignore.isPresent()) {
                    final var argument = ignore.get().arguments().stream()
                            .filter(candidate -> candidate.name() == null
                                    || candidate.name().lexeme().equals("reason"))
                            .findFirst();
                    if (argument.isPresent() && argument.get().value() instanceof Expr.Literal literal
                            && literal.value instanceof String text) {
                        reason = text;
                    }
                }
                tests.add(new DiscoveredTest(name, ignore.isPresent(), reason));
            }
        }
        tests.sort(Comparator.comparing(DiscoveredTest::qualifiedName));
        return List.copyOf(tests);
    }

    static String generate(final List<DiscoveredTest> tests, final String targetName) {
        final var source = new StringBuilder();
        source.append("package zeron.generated.test;\n\n");
        source.append("import zeron.lang.Effect;\n");
        source.append("import zeron.io.println;\n");
        source.append("import zeron.test.TestFailure;\n");
        for (final var test : tests) {
            if (test.ignored()) continue;
            source.append("import ").append(test.qualifiedName()).append(";\n");
        }
        source.append("\npublic fn main(): Int {\n");
        source.append("    let mut passed = 0;\n");
        source.append("    let mut failed = 0;\n");
        source.append("    let mut errors = 0;\n");
        source.append("    let mut skipped = 0;\n");
        source.append("    println(\"running ").append(tests.size()).append(" tests in target '")
                .append(targetName.replace("'", "")).append("'\");\n");
        if (tests.isEmpty()) {
            source.append("    println(\"no tests discovered\");\n");
        }
        var index = 0;
        for (final var test : tests) {
            final var result = "status" + index++;
            if (test.ignored()) {
                source.append("    println(\"test ").append(test.qualifiedName())
                        .append(" ... skipped");
                if (!test.ignoreReason().isEmpty()) {
                    source.append(": ").append(escape(test.ignoreReason()));
                }
                source.append("\");\n");
                source.append("    skipped = skipped + 1;\n");
                continue;
            }
            source.append("    let mut ").append(result).append(" = 0;\n");
            source.append("    handle (").append(test.qualifiedName()).append("()) with {\n");
            source.append("        case TestFailure.message(message) -> ").append(result).append(" = 1;\n");
            source.append("        case Effect -> ").append(result).append(" = 2;\n");
            source.append("    };\n");
            source.append("    if (").append(result).append(" == 0) {\n");
            source.append("        println(\"test ").append(test.qualifiedName())
                    .append(" ... ok\");\n");
            source.append("        passed = passed + 1;\n");
            source.append("    } else {\n");
            source.append("        println(\"test ").append(test.qualifiedName())
                    .append(" ... FAILED\");\n");
            source.append("        if (").append(result).append(" == 2) {\n");
            source.append("            errors = errors + 1;\n");
            source.append("        } else {\n");
            source.append("            failed = failed + 1;\n");
            source.append("        }\n");
            source.append("    }\n");
        }
        source.append("    println(passed.toString() + \" passed, \" + failed.toString()")
.append(" + \" failed, \" + skipped.toString() + \" skipped\");\n");
        source.append("    return if (failed + errors > 0) then 1 else 0;\n");
        source.append("}\n");
        return source.toString();
    }

    private static boolean isAnnotation(final Stmt.AnnotationApplication annotation) {
        final var name = annotationName(annotation);
        return name.equals("Test") || name.equals("Ignore");
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
