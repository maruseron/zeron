# Compiler change workflow

This guide describes the usual plumbing for adding syntax and semantics to the compiler. The
compiler pipeline and responsibility boundaries are summarized in
[`NAVIGATION.md`](../../NAVIGATION.md); keep semantic rules in resolution and JVM representation
decisions in compilation.

## Adding a statement

1. Add a node to the sealed `Stmt` hierarchy in
   [`Stmt.java`](../../src/main/java/com/maruseron/zeron/ast/Stmt.java). Keep the AST focused on
   source structure and tokens; store resolved types or operations only when later phases need
   them.
2. Teach [`Parser.java`](../../src/main/java/com/maruseron/zeron/ast/Parser.java) to recognize the
   syntax and construct the node. If it introduces a keyword, update tokenization and relevant
   syntax-highlighting surfaces as well.
3. Add semantic validation and flow-state handling in
   [`StatementResolver.java`](../../src/main/java/com/maruseron/zeron/analize/StatementResolver.java)
   or a focused resolver it delegates to. Diagnostics should be reported during resolution, not
   deferred to bytecode generation.
4. Emit the resolved behavior in
   [`StatementEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/StatementEmitter.java)
   or the narrowest responsible emitter. Preserve evaluation order and control-flow semantics.
5. Check cross-cutting tools when the syntax is user-visible: the formatter, ANSI highlighter,
   VS Code TextMate grammar, language server, and compiled-library metadata may also need updates.
6. Add parser, resolver, and compiler-backed tests. Test both accepted behavior and invalid forms,
   including flow and effect behavior where relevant.

## Adding a declaration

1. Add the source-shape node to `Stmt` and parse it in `Parser`. For declarations that introduce
   types, use the existing type-registration and declaration-resolution paths instead of adding
   duplicate symbol tables.
2. Register names and validate duplicates, visibility, and declaration conflicts through
   [`DeclarationRegistry.java`](../../src/main/java/com/maruseron/zeron/analize/DeclarationRegistry.java)
   and its collaborators. Implement declaration semantics in
   [`DeclarationResolver.java`](../../src/main/java/com/maruseron/zeron/analize/DeclarationResolver.java)
   or the focused resolver for that declaration kind.
3. Add class-file output in [`DeclarationEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/DeclarationEmitter.java),
   [`NominalTypeEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/NominalTypeEmitter.java),
   or another narrow emission module. Keep resolution decisions out of the emitter.
4. If public declarations are stored in a library index, update index writing, reading, validation,
   and round-trip tests. Bump `API_INDEX_SCHEMA_VERSION` only when the serialized schema changes,
   and consolidate such changes within the release cycle.
5. Test duplicate and inaccessible declarations, normal compilation, and separate compilation
   through a compiled library when the declaration is part of the public API.

## Adding an expression

1. Add a node to the sealed `Expr` hierarchy in
   [`Expr.java`](../../src/main/java/com/maruseron/zeron/ast/Expr.java). Add parser support at the
   correct precedence level in `Parser`; syntax shape and operator precedence are part of the
   language contract.
2. Resolve type, effects, flow refinements, and operand evaluation in
   [`ExpressionFlowResolver.java`](../../src/main/java/com/maruseron/zeron/analize/ExpressionFlowResolver.java)
   or a focused resolver such as `CallResolver`. Attach resolved metadata to the node or compilation
   context when code generation needs it.
3. Emit ordinary expression behavior in
   [`BytecodeEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/BytecodeEmitter.java).
   Use [`ExpressionFlowEmitter.java`](../../src/main/java/com/maruseron/zeron/compile/ExpressionFlowEmitter.java)
   for constructs that need explicit control-flow lowering.
4. Add parser and resolver tests, then compiler-backed tests that execute the generated program or
   inspect bytecode when the JVM shape is part of the requirement.

The AST hierarchies are sealed and phase dispatch often uses exhaustive switches. A new node should
be handled at every relevant phase rather than hidden behind a fallback. Maven compilation and
tests help reveal missing dispatch sites. Keep test names and packages aligned with the feature's
compiler layer.
