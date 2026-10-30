# Custom numeric literal suffixes

Libraries can define suffixes that construct typed values directly from numeric literals. A suffix
is statically resolved from an explicit import; it is not a macro or a global compiler registry.
The standard library's arbitrary-precision `Integer` uses the `n` suffix:

```zeron
import zeron.numeric.Integer;
import suffix zeron.numeric.Integer.n;

let amount: Integer = 123456789012345678901234567890n;
let total: Integer = amount + 5n;
let hexadecimal: Integer = 0xFF_FFn;
let negative: Integer = -42n;
```

The suffix must be adjacent to the literal: `20n` is a suffixed integer, while `20 n` is not.
Custom suffixes are imported by name and can be aliased; importing the owning class alone does not
activate its suffix. Suffix names are case-sensitive. The built-in `L` suffix remains reserved and
cannot be replaced or combined with a custom suffix.

## Declaring a suffix

A class associates a suffix with named public constructor factories and the literal categories they
accept. The `Integer` implementation receives the exact integer token spelling as a string:

```zeron
suffix n {
    integer: string fromLiteralText;
}
```

This class member refers to a public named constructor called `fromLiteralText`. The string input
preserves radix prefixes, separators, and large magnitudes without first narrowing the literal to
`Int` or `Long`. Other declarations can select converted integer or decimal inputs for the
categories they support. A suffix applies only to the numeric categories it declares; unknown or
unavailable suffixes produce diagnostics. The sign in `-42n` is a separate unary operator, not part
of the literal text passed to the factory.

Suffix declarations and their factory metadata are exported through compiled Zeron libraries.
See [Custom Numeric Literal Suffixes](../documents/zeps/ZEP-0043.md) for import, range-checking,
and library-compatibility rules.
