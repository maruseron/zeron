# Binary data

Some data is not text: a file format header, a network frame, a hash, an image chunk, or a portable
encoding of a value. The `zeron.buffer` package provides the pieces for working with it:
`ByteBuffer` writes bytes, `ByteReader` reads them back with checked results, `Hex` and `Base64`
convert to and from text, `VarInt` handles compact integer encoding, and bit helpers expose the
integer bit representation.

A byte array is the interchange type. `ByteArray` is the host's `byte[]`, and `Array<Byte>` is an
ordinary Zeron array of bytes; both expose `length` and indexing.

## Writing bytes with `ByteBuffer`

`ByteBuffer` is a growable write-side buffer. Values append at the end, there is no read cursor, and
the backing array is never exposed. `toArray()` returns a copy of what has been written so far, so
continuing to write does not change an array you already took.

```zeron
import zeron.buffer.*;

let buffer = ByteBuffer.empty();
buffer.putInt(0x01020304);
buffer.putBoolean(true);
buffer.putFloat(1.5);
buffer.putVarInt(300);

let bytes = buffer.toArray();
```

Writes use the buffer's `order` property, which is `ByteOrder.BigEndian()` by default:

```zeron
buffer.order = ByteOrder.LittleEndian();
buffer.putInt(0x01020304); // bytes 04 03 02 01
```

`putInt` and `putLong` write four and eight bytes in the configured order, `putFloat` writes the raw
IEEE-754 representation, `putBoolean` writes one byte, and `putBytes` appends a whole array.
`putVarInt` and `putVarLong` write unsigned LEB128, which uses fewer bytes for small values.
`size` reports the number of written bytes, `isEmpty()` reports whether anything was written, and
`clear()` removes everything while keeping the buffer usable. Growth is geometric, so appending one
byte at a time does not copy the whole buffer each time.

## Reading bytes with `ByteReader`

`ByteReader` is a checked read cursor over one byte array. Every `get*` operation returns a
`Result`; on failure the cursor stays where it was, and on success it advances by exactly the bytes
it consumed.

```zeron
import zeron.buffer.*;

let reader = ByteReader.over(bytes);
reader.order = ByteOrder.LittleEndian();

let first = match (reader.getInt()) {
    case Result.Ok(value) -> value;
    case Result.Err(_) -> 0;
};
let rest = reader.getBytes(reader.remaining());
```

`position` is the current cursor, `remaining()` is how many bytes are left, `seek(position)` moves
the cursor and returns `false` when the position is outside the array, `slice(start, end)` returns a
copy of a range or `None` for an invalid one, and `remainder()` returns a copy of everything from the
cursor to the end.

A failed read reports `BufferError`. Its variants are `Truncated(needed, remaining)`,
`MalformedVarInt(offset)`, and `InvalidText(offset)`:

```zeron
let failure: BufferError = BufferError.Truncated(3, 1);
match (failure) {
    case BufferError.Truncated(needed, remaining) ->
        println("needed " + needed.toString() + ", had " + remaining.toString());
    case _ -> println("other failure");
};
```

`Truncated` reports how many bytes the read needed and how many were left. `MalformedVarInt` comes
from `getVarInt`/`getVarLong` when a LEB128 sequence does not terminate within the width of the
type. `InvalidText` is reported by text decoding over a byte buffer, where the bytes are not valid
in the expected encoding.

## Hexadecimal and Base64

`Hex` and `Base64` convert between bytes and text. Both directions are explicit: `encode` always
succeeds and `decode` returns a `Result`, because text can be malformed.

```zeron
import zeron.buffer.*;

let bytes = match (Hex.decode("ff00")) {
    case Result.Ok(value) -> value;
    case Result.Err(_) -> ByteBuffer.empty().toArray();
};

println(Hex.encode(bytes));       // ff00
println(Base64.encode(bytes));    // /wA=
let same = Base64.decode("/wA=");
```

`Hex.encode` writes two lowercase digits per byte and `Hex.decode` accepts an even number of
hexadecimal digits. `Base64` uses the standard alphabet with padding; `Base64.decode` accepts
optional padding but rejects URL-safe characters (`-` and `_`).

## Compact integers with `VarInt`

LEB128 encodes an integer in seven-bit groups, so a small number takes one byte and larger numbers
take more. `ByteBuffer.putVarInt` and `ByteReader.getVarInt` use it directly. The `VarInt` namespace
is for working with the encoding outside a buffer:

```zeron
import zeron.buffer.*;

let encoded = VarInt.encodeInt(300); // ac 02
let decoded = match (VarInt.decodeInt(encoded, 0)) {
    case Result.Ok(pair) -> pair.fst;
    case Result.Err(_) -> -1;
};
```

`decodeInt` and `decodeLong` take the array and a starting offset and return the value together with
the offset just past the encoding, which is what lets a reader walk a sequence of values.

LEB128 is unsigned. Signed values usually use zigzag first, which maps small negative numbers to
small unsigned ones:

```zeron
import zeron.buffer.*;

let packed = VarInt.zigZagEncodeInt(-7);
let value = VarInt.zigZagDecodeInt(packed); // -7
```

`VarInt.zigZagEncodeLong` and `VarInt.zigZagDecodeLong` are the 64-bit pair.

## Bit-level operations

Binary formats often describe individual bits. The `zeron.buffer` package adds receiver-style
helpers to `Int` and `Long`. Extensions are imported by receiver type and method:

```zeron
import zeron.buffer.Int.bitCount;
import zeron.buffer.Int.leadingZeros;
import zeron.buffer.Int.rotateLeft;

println(10.bitCount().toString());      // 2
println(1.leadingZeros().toString());   // 31
println(1.rotateLeft(1).toString());    // 2
```

`trailingZeros`, `rotateRight`, and `reverseBits` complete the `Int` set; `Long` has the same
operations over 64 bits, with `rotateLeft` and `rotateRight` returning `Long`.

## Binary files

`zeron.io.openReader` and `zeron.io.openWriter` return raw byte streams. They implement
`zeron.io.Reader` and `zeron.io.Writer`, which work on a `&ByteArray` buffer and report I/O failure
with `IoFailure`. Open them in a `using` scope so the file is closed on every exit path:

```zeron
import zeron.fs.Path;
import zeron.internal.allocateByteArray;
import zeron.io.IoFailure;
import zeron.io.Reader;
import zeron.io.openReader;
import zeron.resource.DisposeFailure;

fn readHeader(path: Path): Int performs IoFailure, DisposeFailure {
    using (let reader: &Reader = openReader(path)) {
        let mut buffer = allocateByteArray(16);
        return match (reader.read(buffer, 0, buffer.length)) {
            case Option.Some(count) -> count;
            case Option.None() -> 0;
        };
    }
}
```

`reader.read` returns `Some(count)` for the number of bytes read into the buffer, and `None` at the
end of the file. `allocateByteArray` in `zeron.internal` allocates the mutable `&ByteArray` buffer
that `read` fills; `arrayToByteArray` and `byteArrayToArray` in the same package convert between
`Array<Byte>` and `ByteArray` when a `ByteReader` or an array-based API needs the other shape.

`openWriter` mirrors this with `writer.write(buffer, offset, length)` and `flush()`. Binary streams
are the lower-level counterpart of the text helpers described in
[file system and text I/O](27-file-system-and-text-io.md); `readText` and `writeText` handle
encoding for you when the content is text.

## Related

- [Arrays and strings](07-arrays-and-strings.md)
- [File system and text I/O](27-file-system-and-text-io.md)
- [Option and Result](19-option-and-result.md)
