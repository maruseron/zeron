# File system and text I/O

The standard library provides `zeron.fs.Path` for path values and `zeron.io.text` for synchronous
whole-file text operations. A path is a lexical location; creating a `Path` does not check whether
anything exists there.

```zeron
import zeron.fs.Path;
import zeron.io.IoFailure;
import zeron.io.text.readText;
import zeron.io.text.writeText;
import zeron.resource.DisposeFailure;

fn updateNotes(): String performs IoFailure, DisposeFailure {
    let notes = Path.from("notes/today.txt");
    writeText(notes, "Remember to review the design.");
    return readText(notes);
}
```

## Paths

`Path.from(String)` constructs a path. `resolve(String)` appends or replaces with a child path,
`normalize()` removes redundant lexical components, `toAbsolute()` resolves against the process
working directory, and `fileName()` and `parent()` return `Option` values. `toString()` returns the
path spelling. Path equality uses the host path provider's lexical equality; it does not access the
filesystem, normalize automatically, or resolve symbolic links.

`exists()`, `isFile()`, and `isDirectory()` are status queries. They return `false` when the host
cannot confirm the requested condition, as with the underlying `java.io.File` queries. Use
operations that perform `IoFailure` when distinguishing an I/O failure matters.

`list()` returns this directory's immediate children, and `walk()` returns this path together with
every entry beneath it, recursively. Both return an `Array<Path>` and perform `IoFailure` when the
filesystem cannot be read; `list()` also fails when the path is not a directory. Entries are
returned eagerly, and their order is not specified.

```zeron
import zeron.fs.Path;
import zeron.io.IoFailure;

fn entries(root: Path): Array<Path> performs IoFailure = root.walk();
```

`createDirectory()` requires the target's parent to exist and fails if the target already exists.
`createDirectories()` creates missing parent directories. `deleteIfExists()` returns `true` when it
deletes an entry and `false` when the entry was absent. These operations perform `zeron.io.IoFailure`
for checked Java I/O failures.

## Whole-file text

`readText(path)` reads the entire file as UTF-8. `writeText(path, text)` creates or truncates the
file and writes the supplied text as UTF-8. Neither operation changes line endings or adds a newline.
Both operations are synchronous and perform `IoFailure` for checked I/O failures.

`writeText` closes its writer with a `using` scope. If writing fails and closing also fails, the
write failure remains primary and the cleanup outcome is suppressed. If closing alone fails, the
call performs `DisposeFailure`; its `cause` contains the original Java `IOException`. Callers may
declare or handle both effects:

```zeron
import zeron.fs.Path;
import zeron.io.IoFailure;
import zeron.resource.DisposeFailure;

fn save(path: Path, text: String): Unit performs IoFailure, DisposeFailure =
    writeText(path, text);
```

Unchecked Java exceptions retain their ordinary host behavior. The text helpers do not provide
append options or asynchronous operations, and directory listing is eager. Byte-oriented streams
are available through the lower-level `zeron.io.Reader` and `zeron.io.Writer` contracts, which this
chapter does not cover.

## Logging

`zeron.log` provides severity levels, structured fields, and explicit sinks. Configuration is passed
by the application rather than stored in a process-global registry, so libraries can accept a logger
without reconfiguring their host.

```zeron
import zeron.collections.HashMap;
import zeron.log.Level;
import zeron.log.LogFormatter;
import zeron.log.LogSink;
import zeron.log.LogSink.minLevel;
import zeron.log.Logger;
import zeron.log.Value;

fn billingLogger(): Logger {
    let sink = LogSink.console(LogFormatter.plain()).minLevel(Level.Info());
    let fields = HashMap<String, Value>.empty();
    fields.put("service", Value.Text("billing"));
    return Logger.of("billing", sink).with(fields);
}

fn recordOrder(logger: Logger, id: Long): Unit =
    logger.info(() -> "order " + id.toString());
```

`LogSink.console` writes to standard output and routes `Warn` and `Error` to standard error.
`LogSink.minLevel` layers a filter over any sink; both factories return mutable sinks because
writing and filtering mutate sink state. Import the extension explicitly to use it.

`Logger.of(name, sink)` creates a logger, `named(child)` appends a dotted child name, and `with`
copies the supplied fields into a fresh context map. `log` evaluates its message thunk only when the
sink accepts the level, and `isEnabled` lets callers skip building expensive messages:

```zeron
fn maybeDump(logger: Logger, details: () -> String): Unit {
    if (logger.isEnabled(Level.Debug())) logger.debug(details);
}
```

`LogFormatter.plain` renders the level, logger name, message, and structured fields; text is quoted
and escaped, and nested lists render recursively. Custom sinks implement `LogSink`, whose `write`
receives a `Record` with the time, level, logger name, message, fields, and an optional cause.
`Logger.errorWith` attaches an `Effect` as that cause. Writes are synchronous and preserve call
order per sink; the API does not add cross-thread locking.

## Injectable time

`zeron.time.Clock` is the source of the monotonic instants used for logging and elapsed-time
measurement. `Clock.system()` samples the same clock as `Instant.now()`, while
`Clock.fixed(instant)` always reports the supplied instant. `Logger.fromClock(name, sink, clock)`
accepts a clock; `Logger.of` defaults to `Clock.system()`.

```zeron
import zeron.log.LogSink;
import zeron.log.Logger;
import zeron.time.Clock;
import zeron.time.Instant;

fn deterministicLogger(sink: &LogSink): Logger =
    Logger.fromClock("app", sink, Clock.fixed(Instant.now()));
```

`Instant` remains a monotonic elapsed-time value; `zeron.time` does not provide wall-clock
timestamps.
