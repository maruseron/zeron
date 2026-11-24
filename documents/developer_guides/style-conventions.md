# Zeron style and API conventions

These conventions are normative for the standard library under `src/main/resources/stdlib/`, the
sample programs under `sample_programs/`, and Zeron examples in repository documentation. Treat them
as strong defaults for third-party code.

This document owns naming and API shape. It does not redefine syntax, semantics, or whitespace:
`zeron fmt` and [`style.toml`](../../src/main/resources/style.toml) are authoritative for layout, and
[Formatting and diagnostics](../../guide/29-formatting-and-diagnostics.md) covers the formatter and
diagnostic surfaces. When a rule here conflicts with the compiler or formatter, the compiler and
formatter win.

Use **follow** for rules that reviewers should enforce, **prefer** for the normal choice, and
**avoid** for patterns that need a stated reason in review. Existing public names are not renamed
only to satisfy this document; rename them through the normal deprecation path when the change is
worth the break.

## Naming

| Element | Convention | Examples |
| --- | --- | --- |
| Package | Lowercase dotted nouns, named for a capability | `zeron.collections`, `zeron.concurrent`, `zeron.serialization.json` |
| Source file | `snake_case`, named for the primary type or feature | `array_list.zn`, `http_uri.zn`, `collections.zn` |
| Type, contract, effect, namespace, extension | `PascalCase` | `ArrayList`, `Iterable`, `Await`, `Money`, `String` |
| Function, method, property | `camelCase` | `sortBy`, `removeAt`, `isEmpty`, `elementCount` |
| Parameter, local | `camelCase` | `values`, `initial`, `elementCount` |
| Type parameter | Short uppercase, meaningful when several appear | `T`, `K`/`V`, `R`, `E` for an effect row |
| Module-level constant | `SCREAMING_SNAKE_CASE` | `PI`, `TAU`, `MAX_ATTEMPTS` |

Follow these naming rules:

- Name by domain concept, not by implementation detail. `ArrayList` describes the abstraction;
  `GrowableObjectArray` describes a particular representation.
- Write acronyms as words in identifiers: `Json`, `HttpUri`, `IoFailure`, `UserId`. Do not use
  `JSON`, `HTTPURI`, `IOFailure`, or `UserID`.
- Booleans read as predicates: `isSome`, `hasNext`, `canRetry`. A mutator may return a Boolean
  status, as in `ArrayList.add`, but the method name still describes the action rather than the
  result.
- Use `toX` when a value is converted or materialized, and `asX` when the result is a view or
  adapter. A narrowing conversion returns `Option`, as in `Int.toByte`.
- Use `new` for the canonical constructor. Use `of` for variadic or aggregate construction, `from`
  for a same-type copy or adapter, `empty` for an empty value, and specific names such as
  `fromArray` when the source shape matters. Avoid `create`, `make`, and `getInstance`.
- Use imperative verbs for mutators (`add`, `insert`, `removeAt`, `replaceAt`) and noun-like names
  for read-only accessors (`count`, `first`, `at`).
- Raised effects are `PascalCase` nouns. An effect that reports a failure ends in `Failure`
  (`IoFailure`, `DisposeFailure`); a data type that represents an error result ends in `Error`
  (`JsonError`). Effect rows use `E`.
- Public constants are immutable and use `SCREAMING_SNAKE_CASE`. Conventional mathematical names
  keep their established spelling (`PI`, `TAU`, `E`).

Avoid abbreviations that are not established in the domain, and avoid encoding a type in a variable
name. Prefer `values` over `valueList` when the declared type already says `List`.

## Packages, files, and feature grouping

Follow these grouping rules:

- A package is a capability or a coherent feature, not a code layer. `zeron.collections` and
  `zeron.serialization.json` are features; `zeron.utils`, `zeron.helpers`, `zeron.common`, and
  `zeron.misc` are not acceptable homes for new public API.
- A subpackage exists when its users can consume it independently or when it has a distinct
  contract. Do not split a package only because it has several files.
- `internal` is for implementation details. Public declarations must not expose internal types in
  their signatures, and public API must not require callers to import an internal package.
  Zeron has no module hiding or export mechanism, so `zeron.internal` declarations are necessarily
  public in the API index. They are compiler and runtime support, not documented API: keep them out
  of guides and sample programs, and do not rely on them from application code.
- Give a file one primary exported abstraction, or one cohesive feature when the declarations are
  small and inseparable. A file named after a type should contain that type and its closest
  companions; algorithms over many types belong in a feature file such as `collections.zn`.
- A type's companion namespace and extension use the same name in the same package. A
  `namespace Money` and `extension Money` are the two receiverless and receiver-style halves of the
  same surface. Keep them discoverable together, splitting into files only when their size requires
  it.
- Group public members by the user's task, not by the order in which the implementation was
  written. Constructors and factories belong near the type; conversions and terminal operations
  belong with the operation they finish.
- Do not create a namespace as a miscellaneous bucket. If a namespace no longer describes one type
  or contract, move the unrelated members to a feature package.

### Core and opt-in surfaces

The standard library has two layers. `zeron.lang` is the core, automatically shipped surface that
forms part of the language's basic vocabulary. Other packages, including `zeron.collections`, are
opt-in: callers import the capability when they need it.

- Put an operation in `zeron.lang` only when every program needs it, when the compiler requires it,
  or when it completes a core type's fundamental contract. Keep the core small.
- Put richer conveniences over an intrinsic type in the opt-in package that owns that capability.
  For example, `Array<T>` iteration is core in `zeron.lang.array`; `append`, `fold`, `stream`, and
  array searching are opt-in extensions in `zeron.collections.array`.
- A type and its companion namespace stay in the same layer. Do not split one type's surface across
  a core package and an unrelated feature package merely to control imports.
- Document the layer when a type has extensions in both places, and name the opt-in import in the
  guide or API documentation.

## Choosing a declaration form

Use the narrowest form that matches the operation:

| Situation | Form |
| --- | --- |
| The operation reads or changes object state, or enforces an invariant | Instance method |
| The operation is receiver-first but the type is not owned here, or the capability is optional | Extension method |
| The operation is type-tied but has no receiver: factory, parser, validator, constants | Namespace named after the type or contract |
| The operation is a standalone algorithm, generic combinator, or has no natural subject | Free function in a feature package |
| Every conforming type must provide the operation and dispatch goes through a witness | Contract method |

Prefer the form that makes the call site read most directly. When more than one form fits, the
normal precedence is **instance method, extension, namespace, free function**. A namespace and a
free function can coexist only when they serve different call shapes; do not publish two equal
spellings of the same operation.

Use an instance method when the receiver's state or invariant is part of the operation. Do not add a
method that only forwards to a free function on one of its arguments; that creates two homes for the
same behavior.

Use an extension for a receiver-first operation that reads better as a chain and does not need
private state. Extensions are also the right place for a capability that only some callers import.
Do not use an extension to bypass visibility or to mutate global state.

Use a namespace for receiverless operations that belong to a type: `Money.zero()`, `HttpUri.parse`,
`Json.stringify`. A namespace may share a type's name, as described in
[Packages, namespaces, and imports](../../guide/11-packages-namespaces-and-imports.md) and
[Extensions](../../guide/12-extensions.md). Do not create a class whose only purpose is to hold
static functions; a namespace is the Zeron form for that role.

Use a free function for an algorithm that is genuinely independent of a receiver, or for a
combinator over functions. `zeron.function.compose` and `zeron.function.pipe` are the model: their
inputs are functions, not a subject value. If the first parameter is consistently the subject, an
extension or method is usually the better surface.

Use a contract method when the operation is part of conformance and callers should reach it through
the contract. Do not duplicate a contract method as a free function with the same semantics.

## Data modeling

Choose the data form by the role of the type, not by its syntax:

| Form | Use when | Avoid when |
| --- | --- | --- |
| Product (`type Name = (...)`) | The value is inert immutable data with named components and no behavior or invariants | The type needs methods beyond accessors, derived state, or initialization rules |
| Data class | The type has canonical components plus behavior, derived or auxiliary fields, `deriving`, or initialization effects | The type is only a field bag, or it needs identity or mutable lifecycle |
| Class | The type has identity, mutable state, resources, or a lifecycle | The type is a pure record |
| Sum type | The value is one of a closed set of alternatives | The alternatives are open or independently extensible |
| Newtype | The representation is shared but the meaning must be distinct | The type is only a documentation alias for the same meaning |

```zeron
public type Money = (amount: Int, currency: String);

public data class LineItem(name: String, price: Int, quantity: Int) {
    public total(): Int = price * quantity;
}
```

`Money` is a product: it groups components and has no behavior. `LineItem` is a data class:
`total()` gives it behavior that belongs to the type. Follow the rules in
[Products, data classes, and sum types](../../guide/17-sum-types-and-products.md), and apply these
tests:

- If every member is a canonical component and there is no behavior beyond equality and accessors,
  use a product. Public payloads, coordinates, pairs, and event data usually belong here.
- If the type computes from its components, carries derived state, needs initialization rules, or
  requests `Hashable`, use a data class. The behavior is part of the type's meaning.
- If the type has identity, mutation, or resource ownership, use a class. Do not model a mutable
  entity as a data class merely to get a generated constructor.
- Keep canonical component order stable. It is the positional pattern order and the constructor
  order, so changing it is an API break even when the component types are unchanged.
- Name sum alternatives as `PascalCase` nouns or noun phrases. Give payload components names that
  describe the case, not their position.
- Use a newtype for a distinct meaning over a shared representation, such as a validated identifier
  or a unit-bearing number. Do not use a type alias when the distinction must survive substitution.
- Do not create an anemic data class. If the type has no behavior and no invariants, a product is
  clearer and smaller.

## Parameters and call shape

Follow this order unless the domain gives a stronger reason:

1. The receiver, if it is an explicit parameter. Extensions and methods hide it.
2. The required subject or source value.
3. Required configuration and mode values.
4. Optional configuration, usually as one options product.
5. The callback, predicate, transform, or accumulator, last.

This order keeps trailing-lambda calls natural and makes a call read from the subject outward:

```zeron
public fn fold<T admits mutable, R admits mutable>(
    values: Iterable<T>,
    initial: R,
    combine: (R, T) -> R
): R {
    let mut result = initial;
    for (let value in values) result = combine(result, value);
    return result;
}
```

Follow these additional rules:

- Put a comparator or predicate last for the same reason: `sort(values, compare)`,
  `filter(predicate)`, `fold(initial, accumulator)`.
- Keep pair order stable across an API: `left, right`; `source, target`; `from, to`. Do not accept
  one order in a constructor and the reverse in an update method.
- Do not use output parameters. Return a product, a tuple, `Option`, or `Result`.
- Avoid Boolean mode flags. Prefer a separate operation, a named constructor, or a configuration
  product whose fields name the choice.
- Put generic parameters before value parameters. A trailing lambda is still the final value
  parameter, not a generic parameter.
- A mutating receiver is explicit through `&T` and comes first. A command returns `Unit`, a status,
  or the previous value; it does not return `this`.
- When an operation has both a simple and a configured form, keep the simple form as a named
  constructor or a separate function rather than adding a Boolean or a long optional tail.

## Fluent chaining and functional style

Prefer **fluent method or extension chaining for data pipelines**, and **free functions for function
composition**.

For a pipeline over a collection, stream, `Option`, or `Result`, chain receiver-first operations so
the subject stays on the left and each step reads in execution order:

```zeron
public fn activeNames(people: Iterable<Person>): ArrayList<String> =
    Stream.from(people)
        .filter(person -> person.active)
        .map(person -> person.name)
        .toList();
```

Follow these rules for fluent surfaces:

- Intermediate operations return a new immutable value or a lazy wrapper. They do not mutate the
  receiver and return it.
- Terminal operations consume or finish the pipeline and have names that say so: `toList`, `fold`,
  `count`, `collectWith`. Intermediate and terminal operations should not share a name.
- Do not publish both `stream.map(transform)` and `map(stream, transform)` as equal spellings. If a
  compatibility form exists, make one delegate to the other and document the canonical form.
- Keep a chain to the point where it still reads as one operation. Use a local `let` step when a
  chain needs a different subject, error handling, or a free-function combinator in the middle.
- Do not use point-free composition in application logic when a named local or a chain is clearer.
  `compose`, `pipe`, `curry`, `flip`, and `memoize` belong in `zeron.function` and in library code
  that genuinely operates on functions.

Mutable commands and builders are not fluent. A mutating collection reports the result of the
mutation and does not return the collection. A builder mutates itself through a receiver-lambda
block and exposes a terminal `build()`:

```zeron
return Json.build {
    put("name", "Ada");
    put("active", true);
};
```

An operation that returns a wrapper around the receiver, such as a filtered log sink, may chain, but
it returns a new wrapper rather than mutating the original. Name the result for what it is; do not
imply that the original was changed.

## Public surface and documentation

- Make public only what a caller needs. Package-private and private declarations are the default for
  implementation details.
- Document every public declaration's purpose, receiver expectations, raised effects, and failure
  modes with a `///` comment. Explain a non-obvious parameter order or a returned wrapper.
- Keep examples in documentation consistent with these conventions. An example that teaches an
  older spelling should say so explicitly.
- Do not expose a generated, internal, or runtime type in a public signature when a standard-library
  type or contract expresses the same idea.
- When deprecating a public name, provide the replacement and keep the old spelling as a documented
  bridge for the deprecation window. Do not silently keep both spellings indefinitely.

## Review checklist

Use this checklist in code review:

- Names match their kind, acronyms are words, and no identifier encodes an unnecessary type.
- The package names a capability, not a layer, and the file has one clear owner.
- The declaration form is the narrowest one that fits; there is one canonical spelling.
- A product is used only for inert data; a data class earns its behavior; a class owns identity,
  mutation, or resources.
- Required values come before configuration, and the callback is last.
- Pair order is consistent and no output parameter or Boolean mode flag was added.
- Fluent chains return new values; mutable commands and builders do not return `this`.
- Public declarations are documented and expose no internal types.
- New code follows these rules; touched legacy code is migrated when the change is scoped and does
  not create an unrelated public break.

## Deliberate exceptions

Some surfaces keep a name or shape that these conventions would otherwise change. Record the reason
at the declaration so a later reviewer does not "fix" it by accident:

- `HttpVersion.Http1_1` keeps its version label. `Http11` would read as eleven, not one-point-one,
  so self-evident domain notation outranks the acronym rule.
- Java interop declarations mirror the Java type and member names they bind, including names that
  would be poor Zeron style. Renaming them would break the binding, not improve the API.
- `com.maruseron.zeron.runtime` names are implementation identifiers that generated bytecode and
  intrinsics reference. They are not part of the Zeron naming surface.
- An encapsulated value type may keep a class with a private constructor and named factories
  instead of becoming a data class when construction must validate or normalize the representation,
  as with `Instant`, `Duration`, `Path`, and `Rational`. The factory, not the raw components, is the
  public constructor.
- Function combinators in `zeron.function` keep the conventional `f`, `g`, and `a` parameter names,
  and math extensions keep the parameter names of the operation they expose (`exponent`, `min`,
  `max`). In these signatures the short conventional names carry the meaning.

## Related

- [Functions](../../guide/06-functions.md)
- [Packages, namespaces, and imports](../../guide/11-packages-namespaces-and-imports.md)
- [Extensions](../../guide/12-extensions.md)
- [Collections and iteration](../../guide/15-collections-and-iteration.md)
- [Products, data classes, and sum types](../../guide/17-sum-types-and-products.md)
- [Functional programming essentials](../../guide/36-functional-programming-essentials.md)
- [Formatting and diagnostics](../../guide/29-formatting-and-diagnostics.md)
- [`style.toml`](../../src/main/resources/style.toml)
