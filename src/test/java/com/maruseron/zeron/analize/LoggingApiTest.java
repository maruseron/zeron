package com.maruseron.zeron.analize;

import com.maruseron.zeron.StandardLibrary;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.compile.CompilationService;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LoggingApiTest {
    @Test
    public void filtersRecordsAndRendersStructuredFields() throws Exception {
        final var className = "LoggingApi" + UUID.randomUUID().toString().replace("-", "");
        final var output = Files.createTempDirectory(Path.of("target"), "zeron-log-");
        final var unit = parse("LoggingApi.zn", """
                package loggingfixture;
                import zeron.collections.HashMap;
                import zeron.lang.Option;
                import zeron.log.Level;
                import zeron.log.Level.severity;
                import zeron.log.LogFormatter;
                import zeron.log.LogSink;
                import zeron.log.LogSink.minLevel;
                import zeron.log.Logger;
                import zeron.log.Record;
                import zeron.log.Value;
                import zeron.resource.DisposeFailure;
                import zeron.time.Clock;
                import zeron.time.Instant;

                public class RecordingSink is LogSink {
                    writes: Int;
                    lastRecord: Record?;
                    flushed: Boolean;

                    private constructor new;

                    public constructor create() = RecordingSink.new(0, null, false);

                    public enabled(level: Level, target: String): Boolean = true;

                    public mut write(record: Record): Unit {
                        this.writes = this.writes + 1;
                        this.lastRecord = record;
                    }

                    public writeCount(): Int = this.writes;

                    public last(): Record? = this.lastRecord;

                    public mut flush(): Unit {
                        this.flushed = true;
                    }

                    public wasFlushed(): Boolean = this.flushed;

                    public mut dispose(): Unit raises DisposeFailure = ();
                }

                fn filteringStopsBelowMinimum(): Boolean {
                    let sink = RecordingSink.create();
                    let logger = Logger.of("app", sink.minLevel(Level.Warn()));
                    logger.info(() -> "ignored");
                    logger.warn(() -> "kept");
                    return sink.writeCount() == 1;
                }

                fn fixedClockStampsRecord(): Boolean {
                    let sink = RecordingSink.create();
                    let instant: Instant = Instant.now();
                    Logger.withClock("app", sink, Clock.fixed(instant)).info(() -> "hello");
                    let record = sink.last();
                    if (record == null) return false;
                    return record.time == instant
                        and record.level.severity == Level.Info().severity;
                }

                fn contextIsCopied(): Boolean {
                    let fields = HashMap<String, Value>.empty();
                    fields.put("env", Value.Text("test"));
                    let sink = RecordingSink.create();
                    let logger = Logger.of("app", sink).with(fields);
                    fields.put("late", Value.Flag(true));
                    logger.info(() -> "hello");
                    let record = sink.last();
                    if (record == null) return false;
                    return record.fields.containsKey("env")
                        and not record.fields.containsKey("late");
                }

                fn flushReachesSink(): Boolean {
                    let sink = RecordingSink.create();
                    Logger.of("app", sink).flush();
                    return sink.wasFlushed();
                }

                fn formattedRecordIncludesFields(): String {
                    let items: Array<Value> = [Value.Integer(1L), Value.Flag(true)];
                    let fields = HashMap<String, Value>.empty();
                    fields.put("key", Value.Text("value"));
                    fields.put("list", Value.List(items));
                    return LogFormatter.plain().value(
                        Record.new(Instant.now(), Level.Info(), "app", "message", fields,
                            Option.None()));
                }
                """);

        try {
            final var compiler = CompilationService.forCompilationUnits(
                    StandardLibrary.withBundledUnits(List.of(unit)), className, "loggingfixture",
                    List.of(), true, List.of(), output);
            compiler.resolve();
            compiler.compile();

            try (final var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    getClass().getClassLoader())) {
                final var generated = loader.loadClass(className);
                assertTrue((Boolean) generated.getMethod("filteringStopsBelowMinimum").invoke(null));
                assertTrue((Boolean) generated.getMethod("fixedClockStampsRecord").invoke(null));
                assertTrue((Boolean) generated.getMethod("contextIsCopied").invoke(null));
                assertTrue((Boolean) generated.getMethod("flushReachesSink").invoke(null));

                final var formatted = (String) generated.getMethod("formattedRecordIncludesFields")
                        .invoke(null);
                assertTrue(formatted, formatted.startsWith("INFO [app] message {"));
                assertTrue(formatted, formatted.contains("key=\"value\""));
                assertTrue(formatted, formatted.contains("list=[1, true]"));
                assertEquals('}', formatted.charAt(formatted.length() - 1));
            }
        } finally {
            deleteTree(output);
        }
    }

    private static CompilationUnit parse(final String sourceName, final String source) {
        return Parser.of(Scanner.from(source, sourceName).scanTokens())
                .parseCompilationUnit(sourceName);
    }

    private static void deleteTree(final Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (final var paths = Files.walk(path)) {
            for (final var entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
