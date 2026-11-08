# Model declarations

A `model` describes how a nominal type maps to an external representation: a database table, the
keys of a document, or any other format a library defines. The mapping lives beside the type
instead of inside it, so one type can have several representations and the type stays untouched.

Models are one of Zeron's two serialization entry points; the other is the streaming
`Encoder`/`Decoder` codec path, which reads and writes a type's declared shape without a model.
[Products, data classes, and sum types](./17-sum-types-and-products.md#two-serialization-entry-points)
compares the two and shows how the JSON library uses both.

```zeron
public class User {
    public property name: String;
    public property age: Int;
    public constructor new;
}

public contract SqlModel<T: nullable + mut> requires zeron.lang.Model<T> {
    property tableName: String;
}

public model UserSql is SqlModel<User> {
    tableName = "users";

    name -> "user_name";
    age  -> "user_age";
}
```

## Clauses

A model body has two kinds of clause, told apart by their operator:

- `property -> "external"` maps a property of the target type to the name it has in the external
  representation. The left side is resolved like `User::name`, with the same accessibility rule: it
  must name a property whose getter is reachable where the model is declared.
- `property = value` assigns a property of the model's parent contract, such as `tableName`. The
  value must be a constant — a literal or a property reference — so a model has no
  initialization-order effects, and every contract property other than the ones `Model` itself
  declares must be assigned.

The parent contract must require `zeron.lang.Model<T>`, and `T` is the mapped type.

## Models are values

A model declares one value, like a named handler. Its name is not a type:

```zeron
let m: SqlModel<User> = UserSql;
database.from(UserSql);
```

A public model is recorded in a compiled library's API index, so another compilation can import
it, look up its mappings, and pass it where its contract is expected. Lookup still uses property
identity, which is stable across compilations.

Pass the contract (`SqlModel<User>`), not the model, wherever a consumer accepts a model. There is
no implicit lookup of a model from a type: a model is always passed explicitly.

## Reading the mapping

`Model<T>` is an ordinary contract in the standard library, so a consumer reads a model through it:

```zeron
match (UserSql.field(User::name)) {
    case Option.Some(field) -> field.get(user);      // typed read of a mapped property
    case Option.None() -> "unmapped";
}
```

- `field(User::name)` returns `Option<Field<T, V>>`, keeping the property's value type.
- `fieldOf(id)` returns `Option<AnyField<T>>` for a `PropertyId`, with the value type erased. Use it
  when you have an identity, for example from a code model, but no reference.
- `fields` enumerates every mapping as `AnyField<T>`, giving access to `externalName` and
  `sourceId` without knowing the value types.

Lookups use property identity, so a contract property named `name` does not find a mapping of a
class property named `name`. `Field.sourceId` equals `field.source.id`.

## What the compiler checks

- the target type and every mapped property exist, with an accessible getter;
- the same property is not mapped twice;
- the parent contract's property assignments match their declared types and are constants;
- every contract property outside `Model<T>` is assigned.

Duplicate **external** names are not checked: the compiler does not know what they mean, and a
consumer may reject them. Models are partial — a model may map a subset of the target's properties,
because a model never constructs the target type.

## Writable mappings

A mapped property with an accessible setter also exposes a writer, so a consumer can update an
existing value through the model. `Field.writer` is `Option.Some` for such a property and
`Option.None` for a read-only one.

## Current limits

- Annotations on clauses are not accepted, and a compiler-checked `total` modifier does not exist.
