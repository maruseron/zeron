package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.compile.Compiler;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ControlFlowTest {
    @Test
    public void compilesWhileUntilLoopAndNestedBreak() throws Exception {
        final var className = "ControlFlowGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn whileOnce(): Int {
                    let mut count = 0;
                    while (count < 1) {
                        count += 1;
                    }
                    return count;
                }
                fn untilOnce(): Int {
                    let mut count = 0;
                    until (count == 3) {
                        count += 1;
                    }
                    return count;
                }
                fn nestedBreak(): Int {
                    let mut count = 0;
                    loop {
                        count += 1;
                        loop {
                            count += 10;
                            break;
                        }
                        break;
                    }
                    return count;
                }
                fn zeroPasses(): Int {
                    let mut count = 0;
                    while (false) {
                        count += 1;
                    }
                    return count;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(1, generated.getMethod("whileOnce").invoke(null));
                assertEquals(3, generated.getMethod("untilOnce").invoke(null));
                assertEquals(11, generated.getMethod("nestedBreak").invoke(null));
                assertEquals(0, generated.getMethod("zeroPasses").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void compilesForOverArraysAndInclusiveIntegerRanges() throws Exception {
        final var className = "ForLoopGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn arraySum(): Int {
                    let values: Array<Int> = [2, 3, 5];
                    let mut total = 0;
                    for (let value in values) {
                        total += value;
                    }
                    return total;
                }
                fn floatSum(): Float {
                    let values: Array<Float> = [1.5, 2.5];
                    let mut total = 0.0;
                    for (let value in values) {
                        total += value;
                    }
                    return total;
                }
                fn ascendingSum(): Int {
                    let mut total = 0;
                    for (let value in 1..3) {
                        total += value;
                    }
                    return total;
                }
                fn descendingSum(): Int {
                    let mut total = 0;
                    for (let value in 3..1) {
                        total += value;
                    }
                    return total;
                }
                fn singletonSum(): Int {
                    let mut total = 0;
                    for (let value in 7..7) {
                        total += value;
                    }
                    return total;
                }
                fn storedRangeSum(): Int {
                    let values = 1..3;
                    let mut total = 0;
                    for (let value in values) {
                        total += value;
                    }
                    return total;
                }
                fn maxEndpointCount(): Int {
                    let mut count = 0;
                    for (let value in 2147483646..2147483647) {
                        count += 1;
                    }
                    return count;
                }
                fn breaksFor(): Int {
                    let mut total = 0;
                    for (let value in 1..5) {
                        if (value == 3) break;
                        total += value;
                    }
                    return total;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(10, generated.getMethod("arraySum").invoke(null));
                assertEquals(4.0, generated.getMethod("floatSum").invoke(null));
                assertEquals(6, generated.getMethod("ascendingSum").invoke(null));
                assertEquals(6, generated.getMethod("descendingSum").invoke(null));
                assertEquals(7, generated.getMethod("singletonSum").invoke(null));
                assertEquals(6, generated.getMethod("storedRangeSum").invoke(null));
                assertEquals(2, generated.getMethod("maxEndpointCount").invoke(null));
                assertEquals(3, generated.getMethod("breaksFor").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsNonIterableForSources() {
        assertThrows(ResolutionError.class,
                () -> new Resolver().resolve(parse("fn invalid() { for (let value in 1) {} }")));
    }

    @Test
    public void resolvesForOverAUserDefinedIterable() {
        final var source = parse("""
                contract Iterator<T> {
                    hasNext(): Boolean;
                    mut next(): T;
                }
                contract Iterable<T> {
                    iterator(): &Iterator<T>;
                }
                class CounterIterator is Iterator<Int> {
                    index: Int;
                    public constructor new;
                    public hasNext(): Boolean = this.index < 3;
                    public mut next(): Int {
                        let value = this.index;
                        this.index = this.index + 1;
                        return value;
                    }
                }
                class Counter is Iterable<Int> {
                    public constructor new;
                    public iterator(): &Iterator<Int> = CounterIterator.new(0);
                }
                fn sum(): Int {
                    let mut result = 0;
                    for (let value in Counter.new()) {
                        result += value;
                    }
                    return result;
                }
                """);

        new Resolver().resolve(source);
    }

    @Test
    public void compilesForUserDefinedIterableWithBreakContinueAndSingleEvaluation() throws Exception {
        final var className = "IterableForGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var declarations = parse("""
                contract Iterator<T> {
                    hasNext(): Boolean;
                    mut next(): T;
                }
                contract Iterable<T> {
                    iterator(): &Iterator<T>;
                }
                class CounterIterator is Iterator<Int> {
                    index: Int;
                    public constructor new;
                    public hasNext(): Boolean = this.index < 5;
                    public mut next(): Int {
                        let value = this.index;
                        this.index = this.index + 1;
                        return value;
                    }
                }
                class Counter is Iterable<Int> {
                    public constructor new;
                    public iterator(): &Iterator<Int> = CounterIterator.new(0);
                }
                class SingleIterator<T> is Iterator<T> {
                    value: T;
                    ready: Boolean;
                    public constructor new;
                    public hasNext(): Boolean = this.ready;
                    public mut next(): T {
                        this.ready = false;
                        return this.value;
                    }
                }
                class Single<T> is Iterable<T> {
                    value: T;
                    public constructor new;
                    public iterator(): &Iterator<T> = SingleIterator<T>.new(this.value, true);
                }
                let mut sourceCalls = 0;
                fn makeCounter(): Iterable<Int> {
                    sourceCalls += 1;
                    return Counter.new();
                }
                fn sum(): Int {
                    let mut result = 0;
                    for (let value in makeCounter()) {
                        if (value == 1) continue;
                        if (value == 4) break;
                        result += value;
                    }
                    return result;
                }
                fn genericSum(): Int {
                    let mut result = 0;
                    for (let value in Single<Int>.new(7)) {
                        result += value;
                    }
                    return result;
                }
                fn getSourceCalls(): Int = sourceCalls;
                """);

        try {
            final var compiler = new Compiler(declarations, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(5, generated.getMethod("sum").invoke(null));
                assertEquals(7, generated.getMethod("genericSum").invoke(null));
                assertEquals(1, generated.getMethod("getSourceCalls").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsBreakAcrossLambdaBoundary() {
        final var statements = parse("""
                fn invalid() {
                    loop {
                        let action = () -> { break; };
                        break;
                    }
                }
                """);

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(statements));
    }

    @Test
    public void valuelessReturnsAreUnitAndRejectNonUnitFunctions() throws Exception {
        final var suffix = UUID.randomUUID().toString().replace("-", "");
        final var className = "ValuelessReturnGenerated" + suffix;
        final var unitReturnerName = "UnitReturner" + suffix;
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn earlyReturn(): Unit {
                    return;
                }
                class %s {
                    public constructor new;
                    public stop(): Unit {
                        return;
                    }
                }
                fn methodReturn(): Unit {
                    let value = %s.new();
                    value.stop();
                    return;
                }
                """.formatted(unitReturnerName, unitReturnerName));

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals("Unit", generated.getMethod("earlyReturn").invoke(null).toString());
                assertEquals("Unit", generated.getMethod("methodReturn").invoke(null).toString());
            }
        } finally {
            Files.deleteIfExists(classFile);
            Files.deleteIfExists(Path.of("dist", unitReturnerName + ".class"));
        }

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(parse("""
                fn invalid(): Int {
                    return;
                }
                """)));
    }

    @Test
    public void continueTargetsTheCorrectPointForEachLoopKind() throws Exception {
        final var className = "ContinueGenerated" + UUID.randomUUID().toString().replace("-", "");
        final var classFile = Path.of("dist", className + ".class");
        final var statements = parse("""
                fn whileContinue(): Int {
                    let mut index = 0;
                    let mut total = 0;
                    while (index < 5) {
                        index += 1;
                        if (index == 3) continue;
                        total += index;
                    }
                    return total;
                }
                fn untilContinue(): Int {
                    let mut index = 0;
                    let mut total = 0;
                    until (index == 4) {
                        index += 1;
                        if (index == 2) continue;
                        total += index;
                    }
                    return total;
                }
                fn loopContinue(): Int {
                    let mut index = 0;
                    loop {
                        index += 1;
                        if (index < 3) continue;
                        break;
                    }
                    return index;
                }
                fn rangeContinue(): Int {
                    let mut total = 0;
                    for (let value in 1..5) {
                        if (value == 3) continue;
                        total += value;
                    }
                    return total;
                }
                fn arrayContinue(): Int {
                    let values: Array<Int> = [1, 2, 3, 4];
                    let mut total = 0;
                    for (let value in values) {
                        if (value == 2) continue;
                        total += value;
                    }
                    return total;
                }
                fn nestedContinue(): Int {
                    let mut total = 0;
                    for (let outer in 1..2) {
                        for (let inner in 1..3) {
                            if (inner == 2) continue;
                            total += outer * 10 + inner;
                        }
                    }
                    return total;
                }
                """);

        try {
            final var compiler = new Compiler(statements, className);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(
                    new java.net.URL[]{Path.of("dist").toUri().toURL()}, getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertEquals(12, generated.getMethod("whileContinue").invoke(null));
                assertEquals(8, generated.getMethod("untilContinue").invoke(null));
                assertEquals(3, generated.getMethod("loopContinue").invoke(null));
                assertEquals(12, generated.getMethod("rangeContinue").invoke(null));
                assertEquals(8, generated.getMethod("arrayContinue").invoke(null));
                assertEquals(68, generated.getMethod("nestedContinue").invoke(null));
            }
        } finally {
            Files.deleteIfExists(classFile);
        }
    }

    @Test
    public void rejectsContinueAcrossLambdaBoundary() {
        final var statements = parse("""
                fn invalid() {
                    loop {
                        let action = () -> { continue; };
                        break;
                    }
                }
                """);

        assertThrows(ResolutionError.class, () -> new Resolver().resolve(statements));
    }

    private static List<Stmt> parse(final String source) {
        return Parser.of(Scanner.from(source).scanTokens()).parse();
    }
}