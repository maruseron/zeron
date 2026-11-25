# Text processing

Most Zeron programs touch text beyond displaying a value: they split input into fields, match a
pattern, build a message from a template, or read a configuration file. The `String` operations in
[arrays and strings](07-arrays-and-strings.md) cover the basics. This chapter adds the tools for
larger jobs: compiled regular expressions, placeholder formatting, and parsers for structured text.

## Regular expressions

`zeron.text.Regex` compiles a pattern once and reuses it. Compilation reports failure instead of
throwing, so an invalid pattern is an ordinary `Result`:

```zeron
import zeron.text.*;
import zeron.text.RegexError;

let compiled = Regex.compile("(?<word>[a-z]+)-([0-9]+)");
match (compiled) {
    case Result.Ok(regex) -> match (regex.find("abc-42")) {
        case Option.Some(found) ->
            println(found.text + "|" + found.group(1).getOrElse("missing")
                + "|" + found.namedGroup("word").getOrElse("missing")
                + "|" + found.start.toString());
        case Option.None() -> println("no match");
    };
    case Result.Err(RegexError.InvalidPattern(message, offset)) ->
        println(message + " at " + offset.toString());
};
```

This prints `abc-42|abc|abc|0`. The pattern dialect is the host's `java.util.regex.Pattern`
dialect. `Regex` does not expose the host pattern object.

A compiled regex offers three queries and two replacements:

```zeron
import zeron.text.*;

fn hasOnlyDigits(text: String): Boolean =
    match (Regex.compile("[0-9]+")) {
        case Result.Ok(regex) -> regex.matches(text);
        case Result.Err(_) -> false;
    };
```

The three queries are `matches(text)`, which requires the whole text to match, `find(text)`, which
returns `Option<Match>` for the first match, and `findAll(text)`, which returns every non-overlapping
match in order. `replaceFirst` and `replaceAll` return the text with the first or every match
replaced:

```zeron
let line = match (Regex.compile("[0-9]+")) {
    case Result.Ok(regex) -> regex.replaceAll("a1 b22", "#");
    case Result.Err(_) -> "";
};
println(line); // a# b#
```

`replaceFirst` and `replaceAll` accept `$1` or `${name}` in the replacement to refer to capture
groups.

`Match` describes one successful match. `text` is the matched text, `start` and `end` are UTF-16
offsets, and `groupCount` excludes the whole match. `group(index)` returns `None` when a group did
not participate, which is different from matching an empty string. `namedGroup(name)` does the same
for named groups.

## Formatting with placeholders

`String` concatenation builds small messages, but templates with several values read better when
widths and alignment are part of the format. `zeron.text.Formatter` replaces `{}` and `{n}` fields in
a template with the supplied arguments:

```zeron
import zeron.text.*;

let formatted = match (Formatter.format("{} {:>4} {{ok}}", ["a", "b"])) {
    case Result.Ok(value) -> value;
    case Result.Err(_) -> "error";
};
println(formatted); // a    b {ok}
```

`{}` consumes the next argument in order and `{n}` selects an argument by index, so an argument can
appear more than once:

```zeron
let explicit = match (Formatter.format("{0} {1} {0}", ["x", "y"])) {
    case Result.Ok(value) -> value;
    case Result.Err(_) -> "error";
};
println(explicit); // x y x
```

`{{` and `}}` produce literal braces. A field may carry `<`, `>`, or `^` and a width to align left,
right, or center, and `.n` truncates the rendered text to `n` characters.

`format` returns a `Result` because a template can disagree with its arguments. `FormatError` has
four variants: `MissingArgument(index)` when a field asks for an argument that was not supplied,
`IndexOutOfRange(index)` for an explicit index beyond the argument array, `MalformedField(offset)` for
a field that does not parse, and `LeftoverArgument(index)` when an argument is never consumed. Every
argument must be used exactly once.

Two fixed forms cover the common numeric cases without a template. The decimal separator is a
period regardless of the host locale:

```zeron
println(Formatter.fixed(1.25, 1));    // 1.3
println(Formatter.zeroPadded(42L, 4)); // 0042
```

`fixed(value, places)` rounds to `places` digits after the decimal point and never uses exponent
notation. `zeroPadded(value, width)` renders a `Long` in decimal with at least `width` digits.

## Structured text

Zeron ships parsers for two common text formats. Both return a `Result` with a positioned error, so
a malformed document never crashes the caller.

### TOML

`zeron.parsers.toml.Toml.parse` parses a TOML 1.0 document into a `TomlTable`:

```zeron
import zeron.parsers.toml.*;

let text = "title = \"demo\"\ncount = 3\n[server]\nport = 8080\n";
match (Toml.parse(text)) {
    case Result.Ok(table) -> println(table.keys().length.toString()); // 3
    case Result.Err(_) -> println("bad toml");
};
```

`TomlTable` preserves source key order in `keys()`, and `get(key)` returns an `Option` rather than a
default. `containsKey` and `size` complete the table API. Values are modeled by the `TomlValue` sum
type, which keeps TOML's distinctions instead of flattening them: `StringValue`, `IntegerValue`,
`FloatValue`, `BooleanValue`, `DateValue`, `TimeValue`, `DateTimeValue`, `OffsetDateTimeValue`,
`ArrayValue`, and `TableValue`.

A failure is a `TomlError.TomlParseError(message, line, column)`.

### XML

`zeron.parsers.xml.Xml.parse` parses an XML document into an `XmlDocument`:

```zeron
import zeron.parsers.xml.*;

let text = "<root><item id=\"1\">one</item><item id=\"2\">two</item></root>";
match (Xml.parse(text)) {
    case Result.Ok(document) -> println(document.root().elementName()); // root
    case Result.Err(_) -> println("bad xml");
};
```

Parsing disables DTDs and external resources. `root()` returns the document element.
`XmlElement` exposes the qualified name with `elementName()`, the namespace URI with
`namespaceUri()`, direct text and CDATA with `text()`, and attributes through `names()`,
`attribute(name)`, and indexed `child`/`childElements`/`childCount`.

An `XmlError` is either `XmlParseError(message, line, column)` for malformed input or
`DoctypeRejected(message)` when a document declares a DTD.

## Reading and writing text

`zeron.io.text.readText`, `writeText`, `openTextReader`, and `openTextWriter` handle encoding when
the content is text; they are covered in
[file system and text I/O](27-file-system-and-text-io.md). Use the `zeron.buffer` byte tools in
[binary data](44-binary-data.md) when the content is not text.

## Related

- [Arrays and strings](07-arrays-and-strings.md)
- [File system and text I/O](27-file-system-and-text-io.md)
- [Option and Result](19-option-and-result.md)
- [Pattern matching](18-pattern-matching.md)
