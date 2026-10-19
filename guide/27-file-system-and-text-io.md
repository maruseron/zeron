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

fn updateNotes(): String raises IoFailure, DisposeFailure {
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
operations that raise `IoFailure` when distinguishing an I/O failure matters.

`createDirectory()` requires the target's parent to exist and fails if the target already exists.
`createDirectories()` creates missing parent directories. `deleteIfExists()` returns `true` when it
deletes an entry and `false` when the entry was absent. These operations raise `zeron.io.IoFailure`
for checked Java I/O failures.

## Whole-file text

`readText(path)` reads the entire file as UTF-8. `writeText(path, text)` creates or truncates the
file and writes the supplied text as UTF-8. Neither operation changes line endings or adds a newline.
Both operations are synchronous and raise `IoFailure` for checked I/O failures.

`writeText` closes its writer with a `using` scope. If writing fails and closing also fails, the
write failure remains primary and the cleanup outcome is suppressed. If closing alone fails, the
call raises `DisposeFailure`; its `cause` contains the original Java `IOException`. Callers may
declare or handle both effects:

```zeron
import zeron.fs.Path;
import zeron.io.IoFailure;
import zeron.resource.DisposeFailure;

fn save(path: Path, text: String): Unit raises IoFailure, DisposeFailure =
    writeText(path, text);
```

Unchecked Java exceptions retain their ordinary host behavior. The current API does not provide
streaming readers or writers, directory iteration, byte-oriented I/O, append options, or asynchronous
operations.
