# Query Engine Sample

This project is a small, runnable query engine intended to exercise a realistic Zeron project
build. It includes nominal IDs, sum types, generic codec witnesses, an expression/query AST, a
`CodeModel`-based DSL, typed schema views, and an in-memory executor.

Build and run it from this directory:

```powershell
zeron build
zeron run
```

The demo builds its query from inline lambdas over typed schema views:

```zeron
let query = from(TableId.new("users"))
    .join(from(TableId.new("orders")), "id", "user_id")
    .where(user -> user.age <= 19 or user.name == "Ada")
    .select(["name", "amount"])
    .orderBy(order -> order.amount);
```

`where` takes a `CodeModel<(User) -> Boolean>` and `orderBy` takes a
`CodeModel<(Order) -> Int>`. The compiler reifies each inline lambda into a code model, and
`query.dsl.predicates` translates its property reads, literals, comparisons, and logical
connectives into `query.ast.Expr` nodes. Execution still reads the dynamic `Row` by column name, so
`query.schema.User` and `query.schema.Order` are compile-time views rather than runtime records.
`whereExpr` remains available for a hand-built `Expr` (`column("age").gt(literal(...))`), and
`select` takes column names because projection lists have no single selector shape.

Output for the bundled fixtures (selected rows, ordered by amount):

```
{name=Text(Sam), amount=Integer(15)}
{name=Text(Lin), amount=Integer(27)}
{name=Text(Ada), amount=Integer(42)}
```

The source layout deliberately separates the value model, schema, AST, DSL, backend, execution
logic, and demo. It is a benchmark fixture, not a performance result: incremental-compilation
measurements should compare clean builds, no-op rebuilds, a private implementation edit, a public
API edit, and source addition/removal against clean output.
