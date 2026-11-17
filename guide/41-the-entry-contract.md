# Main

As you may have seen repeatedly throughout this guide, a Zeron program's entry point is a
`Unit`-returning function called `main`. As you _will_ see up next, the `main` function is actually
a lot more flexible than you'd initially give it credit for.

## The entry contract

The shape of a `main` function is determined by the `zeron.lang.Main` contract. It contains a
single function `run`, with a `Unit` return and a catch-all top type as the declared effect. For
those curious, it looks like this:

```zeron
public contract Main<A> {
    static run(value: A): Unit raises Effect;
}
```

When the Zeron compiler finds a `main` function in an entry point file, it mechanically transforms
the selected function into a synthetic Java static method with a `MethodHandle` pointing to the
selected `Main.run` evidence.

## Entry values

`main` may return `Unit`, as above, or a value with `Main<A>` evidence. The compiler synthesizes the
JVM launcher, evaluates `main`, and then calls `Main<A>.run` on the returned value. The standard
library supplies the common cases:

```zeron
fn main(): Int = 0;                         // 0 succeeds; another value exits with that status
fn main(): Result<Unit, String> = work();   // Err prints its message and exits 1
```

Your own types can opt in with a witness:

```zeron
class Job {
    public constructor new;
    public start(): Unit raises IoFailure = println("started");
}

public witness JobMain: Main<Job> for Job {
    static fn run(value: Job): Unit raises IoFailure = value.start();
}

fn main(): Job = Job.new();
```

A non-`Unit` `main` without exactly one applicable `Main` witness is an error. The generated
launcher has the usual `public static void main(String[])` shape, and effects raised while producing
or interpreting the entry value escape at that boundary.

The return type may be inferred from the body. Entry selection uses the resolved type, so a short
program can omit the annotation and the launcher's `raises` row:

```zeron
fn main() = println("hello");
```

## Standard input and output

The `zeron.io` helpers are ordinary functions with an explicit effect, not wrappers around an
action monad. `print`, `println`, `readln`, and `prompt` all raise `IoFailure`, so an ordinary
caller declares that effect in turn. An inferred `main` is the host boundary and may leave the
effect implicit:

```zeron
fn main(): Unit raises IoFailure {
    println("hello");
}
```

Use `handle` when the failure needs a local fallback. `Main<A>` describes how an entry _value_ is
interpreted; it does not make the functions that produce that value effect-free.
