# Query Engine Sample

This project is a small, runnable query engine intended to exercise a realistic Zeron project
build. It includes nominal IDs, sum types, generic codec witnesses, an expression/query AST,
extension-based query builders, and an in-memory executor.

Build and run it from this directory:

```powershell
zeron build
zeron run
```

The DSL constructs AST expressions explicitly (for example,
`column("age").gt(literal(encode(18)))`) rather than reifying lambda bodies into query nodes.

The source layout deliberately separates the value model, schema, AST, DSL, backend, execution
logic, and demo. It is a benchmark fixture, not a performance result: incremental-compilation
measurements should compare clean builds, no-op rebuilds, a private implementation edit, a public
API edit, and source addition/removal against clean output.
