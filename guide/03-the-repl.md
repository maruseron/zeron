# The REPL

Run `zeron` with no arguments to start the interactive prompt. Enter an expression to compile and
evaluate it:

```text
> 1 + 2
3
```

Bindings and imports remain available in later submissions:

```text
> let answer = 40 + 2
> answer
42
```

The REPL prints values for expressions but does not treat every declaration as a value to display.
Use a follow-up expression to inspect the result of a binding or call. For example, define a
function in one submission and call it in the next:

```text
> fn square(n: Int): Int = n * n
> square(6)
36
```

Use `let mut` for a binding that you intend to reassign. Imports entered at the prompt are also
persistent; to use a library type, import it before the expression that refers to it. Redeclaring
an existing binding is rejected so a later submission cannot silently change what a name means.
For example, after importing `zeron.collections.List`, you can create a list in one submission,
then inspect its `size` or call one of its read operations in another. Use a collected
for-expression when you want to build a list from an iterable.

A failed submission reports its diagnostic without ending the session, so you can correct the
input and continue. Multiline declarations can be entered as a single submission; the prompt waits
while the input is syntactically incomplete. This is still the Zeron compiler and runtime, not a
separate scripting language, so ordinary typing, visibility, imports, and language limitations
apply.

The prompt provides `:help` for commands and `:quit` to leave. It evaluates Zeron expressions; it
is not a JavaScript-style shell, and names still need to be imported if they are not in scope. For
example, to use the standard-library `List`, first enter `import zeron.collections.List;`.
Collected for-expressions can then use that type as a collector, as shown in
[subject 16](16-for-expressions.md).

Use the REPL for short experiments: check a type, try a library operation, or evaluate a small
expression before placing it in a project. Declarations and values live only for the current REPL
session; put reusable program behavior in source files when you want it to be built and shipped
with the project.
