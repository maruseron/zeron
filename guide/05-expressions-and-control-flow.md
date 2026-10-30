# Expressions and control flow

An expression is a piece of code that produces a value. Literals such as `12`, `"hello"`, and
`true` are expressions. So are calculations, comparisons, and calls:

```zeron
12 + 5
"hello" + " there"
3 < 8
```

Expressions can be combined. In `12 + 5 * 2`, multiplication happens before addition, so the
result is `22`. Parentheses let you choose a different grouping, as in `(12 + 5) * 2`. You can
store an expression's result in a binding or use it as part of a larger expression. If an
expression is difficult to read, give an intermediate result a name with `let`.

Statements describe actions or direct what the program should do next. An expression followed by
a semicolon is an expression statement; for example, a call to `println(...)` performs an action.
Statements in a block normally run from top to bottom, unless a control-flow statement chooses a
different path. The sections below show statement fragments intended to be used inside a block of
program code.

## Operators

Operators are the symbols or words that combine values or change how they are used. The operands
are the values around an operator.

| Purpose | Operators | Example |
| --- | --- | --- |
| Arithmetic | `+`, `-`, `*`, `/`, `%` | `17 % 5` produces the remainder `2`. |
| Unary numeric | `+`, `-` | `-count` is the negative of `count`. |
| Comparison | `<`, `<=`, `>`, `>=` | `age >= 18` produces a Boolean. |
| Value equality | `==`, `!=` | `name == "Ada"` checks whether values are equal. |
| Boolean logic | `not`, `and`, `or` | `ready and not finished` combines conditions. |
| Bitwise and shifts | `&`, `\|`, `^`, `~`, `<<`, `>>`, `>>>` | `flags & 1` checks an integer bit. |
| Assignment | `=`, `+=`, `-=`, `*=`, `/=`, `%=` | `total += 1` adds to and updates `total`. |

Comparisons and equality produce `Boolean` values, `true` or `false`. The words `and` and `or`
combine Boolean conditions and short-circuit: if the left side already determines the answer, the
right side is not evaluated. For example, `hasValue and value > 0` does not test `value > 0` when
`hasValue` is false.

Built-in arithmetic operators work on numeric values. `+` also joins strings, so
`"Hello, " + "Ada"` produces `"Hello, Ada"`. Numeric types opt into arithmetic through the
`Numeric<T>` contract; `/` requires `Fractional<T>`. Generic functions must declare the
corresponding bound explicitly:

```zeron
import zeron.numeric.Numeric;
fn add<T: Numeric<T>>(left: T, right: T): T = left + right;
```

`Integral<T>` adds quotient and remainder operations, while `Fractional<T>` adds division and
reciprocal. Unsuffixed numeric literals use their expected numeric type when one is available;
without context, integer literals still default to `Int` and fractional literals to `Float`.
`String + T` keeps its existing display behavior. See
[Numeric hierarchy and exact literals](33-numeric-hierarchy-and-exact-literals.md) for details.

Zeron also has bitwise and shift operators for integer values: `&`, `|`, and `^` combine bits,
`~` flips them, and `<<`, `>>`, and `>>>` shift them. The unsigned right shift `>>>` fills from
the left with zeroes. These are useful for low-level integer work, but most beginner programs can
start with arithmetic and Boolean operators. There are no `++` or `--` operators; use `+= 1` or
`-= 1`.

For nullable values, `??` supplies a fallback when the value on its left is `null`. The compound
form `??=` assigns a fallback only when the target is null. The `===` operator checks whether two
references are the very same object, which is different from `==` value equality. Type tests use
`is` and `is not`; `matches` tests a value against a pattern. `as` and `as?` convert a value to
another type. A checked `as` cast fails at runtime if the value cannot be converted, while `as?`
produces a value that may be `null` on failure. These operators have more specific rules that are
introduced with the relevant features later in the guide.

The `raise` expression is also available for typed raised effects; it transfers control rather
than calculating an ordinary value. See [raised effects](22-raised-effects.md) for when to use it.

Assignments update a binding, property, or array slot. In addition to the forms in the table,
compound assignment supports `&=`, `|=`, `^=`, `<<=`, `>>=`, `>>>=`, and `??=`. For example,
`count += 1` is a shorter way to add one to `count` and store the result. The binding or target
must permit the update; see [values, types, and variables](04-values-types-and-variables.md) and
[references and mutation](08-references-and-mutation.md).

## Choosing what runs: `if`

An `if` statement chooses whether to run a block. Its condition must produce a Boolean:

```zeron
let temperature = -2;
let mut message = "Not below freezing";
if (temperature < 0) {
    message = "Below freezing";
} else {
    message = "Not below freezing";
}
```

The `else` part is optional when there is nothing to do if the condition is false. When one `if`
is placed inside another, braces help make it clear which `else` belongs to which condition.

An `if` can also be an expression that produces a value. This form uses `then` and `else`, and
both branches must provide a result:

```zeron
let count = 1;
let label = if (count == 1) then "one" else "many";
```

Use the statement form to choose actions, and the expression form when the choice itself should
produce a value. Both forms test a Boolean condition, but their punctuation and purpose differ.

## Repeating work

`while` checks its condition before each repetition. It keeps running the body while that condition
is true:

```zeron
let mut count = 0;
while (count < 3) {
    count += 1;
}
```

Because the body changes `count`, this loop eventually stops. If its condition is false at the
start, the body does not run at all.

`until` is the complementary form: it repeats while its condition is false, and stops once the
condition becomes true. Like `while`, it checks before running the body:

```zeron
let mut attempts = 0;
until (attempts == 3) {
    attempts += 1;
}
```

Use `loop` when repetition should continue without a condition at the start:

```zeron
let mut step = 0;
loop {
    if (step == 3) break;
    step += 1;
}
```

`break` exits the nearest enclosing loop immediately. `continue` skips the rest of the current
iteration and starts the next one:

```zeron
let mut total = 0;
for (let value in 1..5) {
    if (value == 2) continue;
    if (value == 4) break;
    total += value;
}
```

These statements are useful when a loop needs an early exit or should skip particular items.
The range includes both endpoints, so `1..5` visits `1`, `2`, `3`, `4`, and `5` unless the loop
exits earlier. In this example, `continue` skips the addition for `2`, while `break` stops before
adding `4`. Without a condition or a `break`, a `loop` keeps running.

## Iterating over values

The `for` statement visits the values in an iterable one at a time. It creates a new immutable
binding for each item:

```zeron
let values: Array<Int> = [2, 3, 5];
let mut total = 0;
for (let value in values) {
    total += value;
}
```

Use this form when the goal is to perform an action for each element. Arrays and ranges can be
iterated this way, as can standard-library and user-defined iterables. The iteration protocol is
explained in [collections and iteration](15-collections-and-iteration.md).

A for-expression instead transforms values and produces a result. Without a collector it creates
a lazy `Stream`; with `collect` it fills a sink. See [for-expressions](16-for-expressions.md)
when you are ready to turn iteration into a data transformation.

One more control statement is `return`: inside a function it exits that function, optionally
providing its result. Functions and their return values are introduced in the next chapter.
