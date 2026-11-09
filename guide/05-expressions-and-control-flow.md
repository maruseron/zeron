# Expressions and control flow

In the [last chapter](04-values-types-and-variables.md), you saw that `1 + 2` is `3`. Duh, right?
Hold on - I'm getting somewhere. That bit of code is an expression: something that Zeron can turn
into a value. `"chocolate" + " cake"` is an expression, too. Even a lone `3` is an expression.

Let's look at this line from earlier:

```zeron
let total = 1 + 2;
```

The initializer for `total` is an expression (`1 + 2`). The whole line, though, is a _statement_:
a complete instruction, ending with a semicolon. It tells the program to do something, in this
case, to create a variable. Every program you have written so far has been a list of statements,
which Zeron runs one after another, from top to bottom.

That is enough for a program that does the same thing every time. Real programs need to react to
inputs: show one message if a number is big and another if it's small, or repeat something until
it's done. Choosing which statements to run, and how many times, is called _control flow_, which
will also be covered in this chapter.

## Operators

Operators are the symbols or words that combine values or change how they are used. The operands
are the values around an operator. Here are the basic ones:

| Purpose | Operators | Example |
| --- | --- | --- |
| Arithmetic | `+`, `-`, `*`, `/`, `%` | `17 % 5` produces the remainder `2`. |
| Unary numeric | `+`, `-` | `-count` is the negative of `count`. |
| Comparison | `<`, `<=`, `>`, `>=` | `age >= 18` produces a Boolean. |
| Value equality | `==`, `!=` | `name == "Ada"` checks whether values are equal. |
| Boolean logic | `not`, `and`, `or` | `ready and not finished` combines conditions. |
| Bitwise and shifts | `&`, `\|`, `^`, `~`, `<<`, `>>`, `>>>` | `flags & 1` checks an integer bit. |
| Assignment | `=`, `+=`, `-=`, `*=`, `/=`, `%=` | `total += 1` adds to and updates `total`. |

Comparisons, equality and boolean logic operators produce `Boolean` values: `true` or `false`.
The boolean logic operators `and` and `or` have a nifty feature - not only they combine Boolean
conditions, but they also short-circuit: if the left side already determines the answer, the right
side is not evaluated. For example, `hasValue and value > 0` does not test `value > 0` when
`hasValue` is false.

The table wasn't entirely honest with you: Zeron has two sets of equality operators. The value
equality operators `==` and `!=` compare the value of the operands - they're built-in for simple
data types such as `Int` and `String`, but user-defined types need to define the comparison rule.
The referential equality operators `===` and `!==` check whether two operands are the very same
object (by occupying the same spot in your computer's memory).

Zeron also has bitwise and shift operators for integer values. These are useful for low-level
integer work, and you can skip them for now. If you'd still like to know, `&`, `|`, and `^` combine
bits, `~` inverts them, and `<<`, `>>`, and `>>>` shift them. The unsigned right shift `>>>` fills
from the left with zeroes.

## Choosing what runs

An `if` statement chooses whether to run a block. Its condition must produce a Boolean:

```zeron
let temperature = -2;
if (temperature < 0) {
    println("Below freezing");
} else {
    println("Not below freezing");
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
produce a value. Both forms test a `Boolean` condition, but their punctuation and purpose differ.

## Working with types

Some operators ask questions about a value instead of combining it with another one. The is
operator tests whether a value has a given type, and produces a `Boolean`:

```zeron
let value: String? = "Ada";
println(value is String);
println(value is not String);
```

```text
true
false
```

`is not` is simply the opposite test. Here, value is declared as `String?`, so it could hold text
or null. The test looks at what it holds right now: this time it’s text, so value is `String` is
true.

Two related operators convert a value to another type instead of testing it. `as` converts the
value and fails at runtime if the conversion is impossible, while `as?` produces null in that case
instead of failing. There is also matches, which tests a value against a pattern. All three become
much more useful once you’ve met [classes](09-classes-and-constructors.md) and
[pattern matching](18-pattern-matching.md), so we’ll come back to them there.

## Working with nullable values

In the [previous chapter](04-values-types-and-variables.md), you learned that a `String?` can be
either text or `null`. Suppose you want to greet someone, using "stranger" when there’s no name.
With what you know so far, you could write:

```zeron
let name: String? = null;
let who = if (name is not null) then name else "stranger";
println("Hello, " + who);
```

It works, but this pattern is so common that Zeron has a shorter way to say it. The `??` operator
takes the value on its left, unless that value is `null`, in which case it takes the value on its
right:

```zeron
let who = name ?? "stranger";
```

Try changing name to "Ada" and running the program again.

A string also knows how long it is. Writing a dot after a value reads one of its properties, so
`"Ada".length` is `3`. But there’s a catch: you can’t do that on a `String?`, because the value
might be `null` and have no length at all. The `?.` operator handles this. It reads the property if
the value is there, and produces `null` if it isn’t:

```zeron
let name: String? = "Ada";
let length = name?.length;
```

The type of length is `Int?`: a number, or `null` if `name` was `null`. Combine it with `??` to get
rid of the null possibility:

```zeron
let safeLength = name?.length ?? 0;
```

Sometimes you know a nullable value must be present, even if its type doesn’t say so. The `!!`
operator tells Zeron to treat it as non-null:

```zeron
let sure = name!!;
```

Now sure has the type `String`. This is a promise you make to the compiler: if name turned out to
be `null`, the compiler will be angry at you, and your computer will catch on fire. Just kidding -
the program would simply stop with a runtime failure. Use `!!` sparingly, and prefer `??` when a
sensible fallback exists.

Finally, `??=` combines `??` with assignment. It assigns a value only if the target is currently `null`. Since it updates a name, the name has to be declared with `let mut`:

```zeron
let mut nickname: String? = null;
nickname ??= "friend";
```

After these lines, `nickname` holds `"friend"`. Running `nickname ??= "buddy"` afterwards would
change nothing, because `nickname` is no longer `null`.

## Repeating work

Suppose you want your program to print `"Hello"` three times. You could write the `println` line
three times, but what about a thousand? Repeating work is what loops are for.

The most fundamental loop is `while`. It checks its condition before each repetition, and keeps running the body for as long as that condition is `true`:

```zeron
let mut count = 0;
while (count < 3) {
    println("Hello");
    count += 1;
}
```

```text
Hello
Hello
Hello
```

Because the body changes `count`, the condition eventually becomes false and the loop stops. If
the condition is already false at the start, the body doesn’t run at all. Try changing `3` to `5`,
then to `0`.

Counting up like this is so common that Zeron has a dedicated loop for it. A `for` loop visits a
series of values one at a time, creating a new immutable name for each one:

```zeron
let mut total = 0;
for (let number in 1..5) {
    total += number;
}
println(total);
```

```text
15
```

`1..5` is a range, and it’s an expression like any other: Zeron evaluates it to produce a value,
in this case the series from `1` to `5`. Unlike in some languages, a range includes both endpoints,
so `number` takes the values `1`, `2`, `3`, `4`, and `5` in turn. There’s no counter to update by
hand; `for` takes care of it.

Sometimes you don’t know in advance how many repetitions you need. `loop` repeats its body without
any condition, forever, until something inside it stops it. That something is `break`, which exits
the nearest enclosing loop immediately:

```zeron
let mut step = 0;
loop {
    if (step == 3) {
        break;
    }
    step += 1;
}
```

Its companion is `continue`, which skips the rest of the current repetition and jumps to the next
one. Here they’re used together:

```zeron
let mut total = 0;
for (let number in 1..5) {
    if (number == 2) {
        continue;
    }
    if (number == 4) {
        break;
    }
    total += number;
}
println(total);
```

```text
4
```

Follow along: `1` is added, `2` is skipped by continue, `3` is added, and at `4` the loop stops
with `break`, so total ends up as 1 + 3 = 4. Without a break or some other way out, a `loop` runs
forever.

One more loop completes the family. `until` is the mirror image of `while`: it repeats while its
condition is `false`, and stops once it becomes `true`. Like `while`, it checks before running the
body:

```zeron
let mut attempts = 0;
until (attempts == 3) {
    attempts += 1;
}
```

You’ll rarely need `until`, because any `until` can be written as a `while` with the condition
reversed. Use whichever reads more naturally.

## Where to go from here

`for` isn’t limited to ranges: it can walk through arrays, strings, and any other collection that
supports iteration. Collections and how they work are covered in
[collections and iteration](15-collections-and-iteration.md), and there is also a version of `for`
that produces a value instead of performing an action, described in
[for-expressions](16-for-expressions.md).

For now, notice what your programs look like: everything lives inside `main`, one statement after
another. What if you could give a piece of logic a name, and use it as many times as you like?
That’s what _functions_ are for, and they’re the subject of the [next chapter](06-functions.md).
You’ll also meet another control flow statement there: `return`, which exits a function and hands
a result back to whoever called it.
