package com.maruseron.zeron.domain;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.analize.Bind;
import com.maruseron.zeron.analize.ResolutionError;
import com.maruseron.zeron.analize.Width;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.scan.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SymbolTable {
    /*

    Symbol Table: ideally this structure looks a bit like
    |---name---|--decl |--lvt--|---type---|--width--|--init--|--final--|
    | varname1 | token |   -1  | typedesc |  single |   true |   true  |
    | varname2 | token |    0  | typedesc |  double |   true |   true  |
    | varname3 | token |    2  | typedesc |  single |  false |   true  |
    | varname4 | token |    3  | typedesc |  single |   true |  false  |

    notes regarding fields:
    decl  -> the identifier token for error reporting
    lvt   -> a -1 in the lvt field means global
    lvt   -> 2 skip in the lvt field between two entries means the former is double width
    init  -> initialized. used so variables don't refer to themselves during initialization
    final -> prevents reassignment

    Scope Nodes and the Local Variable Table:
        the point of this is to have a linked list of scopes to easily
    remove N amount of locals equal to the size of the popped scope

         scope { size 1 scope { size 2 scope { size 1 null } } total  size 4
         lvt   [ 0 ............ 1 2 .......... 3 ........... ] lvt    size 4
                                               pop             remove size 1
         scope { size 1 scope { size 2 null } } total  size 3
         lvt   [ 0 ............ 1 2 ......... ] lvt    size 3
                                pop             remove size 2
         scope { size 1 scope null } total  size 1
         lvt   [ 0 ............... ] lvt    size 1
                 pop                 remove size 1
         null     total size 0
         lvt   [] lvt   size 0
     */

    public static final int GLOBAL = -1;

    private final Map<String, TypeDescriptor> types = new HashMap<>();
    private final Map<String, Bind> functions = new HashMap<>();
    private final Map<String, Bind> symbols = new HashMap<>();

    static class Scope { int size; Scope enclosing; }
    private Scope scope = null;

    /*
    Local Variable Table: we can think of this array as a table from integer to string.
       the index of a variable name corresponds to its slot on the stack. this is mostly
    useless information since you would normally access an lvt index through the symbol
    table: `symbols.get(name).index()`, but it could be useful if i ever need to retrieve
    a variable through its index `locals[inx] == name`
     |  idx  |   name   |
     | ----- | -------- |
     |     0 | varname1 |
     |     1 | varname2 | TODO       : how to deal with double width? high-low is unnecessary
     |     2 | varname2 | TODO (cont.) since this doesn't really map to a real value but a name
     |     3 | varname3 | TODO (cont.) in the symbol table instead. i could also repeat the name
     |     4 | varname4 | TODO (cont.) to signify both slots belong to varname2

     */
    private final List<String> locals = new ArrayList<>();

    public boolean containsFunction(final Token name) {
        return functions.containsKey(name.lexeme());
    }

    public boolean containsSymbol(final Token name) {
        return symbols.containsKey(name.lexeme());
    }

    public Bind getFunction(final Token name) {
        if (!functions.containsKey(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Unknown symbol: " + name.lexeme()));
        }

        return functions.get(name.lexeme());
    }

    public FunctionDescriptor getFunctionType(final Token name) {
        if (!functions.containsKey(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Unknown symbol: " + name.lexeme()));
        }

        return (FunctionDescriptor) functions.get(name.lexeme()).type();
    }

    public Bind getSymbol(final Token name) {
        if (!symbols.containsKey(name.lexeme())) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Unknown symbol."));
        }

        return symbols.get(name.lexeme());
    }

    public String localName(final int index) {
        return locals.get(index);
    }

    public List<String> getLocals() {
        return locals;
    }

    public void declareFunction(final Stmt declaration,
                                final Token name,
                                final TypeDescriptor type) {
        if (containsFunction(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Already a function bound to this name."));
            return;
        }

        functions.put(name.lexeme(), new Bind(
                declaration,
                name,
                GLOBAL,
                type,
                Width.FUNCTION,
                true,
                true));
    }

    public int declareSymbol(final Stmt declaration,
                             final Token name,
                             final TypeDescriptor type,
                             final boolean isFinal) {
        if (scope == null)
            return declareGlobal(declaration, name, type, isFinal); // -1

        return declareLocal(declaration, name, type, isFinal);      // last lvt idx
    }

    private int declareGlobal(final Stmt declaration,
                              final Token name,
                              final TypeDescriptor type,
                              final boolean isFinal) {
        if (containsSymbol(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Already a symbol bound to this name."));
            return -2;
        }

        symbols.put(name.lexeme(), new Bind(
                declaration,
                name,
                GLOBAL,
                type,
                type.isDoubleWidth() ? Width.DOUBLE : Width.SINGLE,
                false,
                isFinal));
        return GLOBAL;
    }

    private int declareLocal(final Stmt declaration,
                             final Token name,
                             final TypeDescriptor type,
                             final boolean isFinal) {
        if (containsSymbol(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Already a symbol bound to this name."));
            return -2;
        }

        final var lvt = locals.size();
        symbols.put(name.lexeme(), new Bind(
                declaration,
                name,
                lvt,
                type,
                type.isDoubleWidth() ? Width.DOUBLE : Width.SINGLE,
                false,
                isFinal));

        if (type.isDoubleWidth()) {
            scope.size += 2;
            locals.add(name.lexeme());
            locals.add(name.lexeme());
        } else {
            scope.size++;
            locals.add(name.lexeme());
        }

        return lvt;
    }

    public void define(Token name) {
        if (!containsSymbol(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Unknown symbol: " + name.lexeme()));
            return;
        }

        symbols.computeIfPresent(name.lexeme(), (_, v) -> v.init());
    }

    public void setResolvedType(final Token name, final TypeDescriptor resolvedType) {
        if (!containsSymbol(name) || !(getSymbol(name).type() instanceof InferDescriptor)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Cannot resolve type of non-inferred bind."));
        }

        symbols.computeIfPresent(name.lexeme(), (_, v) -> v.withType(resolvedType));
    }

    public void setResolvedReturnType(final Token name, final TypeDescriptor resolvedType) {
        functions.computeIfPresent(
                name.lexeme(),
                (_, v) -> v.withType(((FunctionDescriptor)v.type()).toReturnType(resolvedType)));
    }

    public void beginScope() {
        final var parent = scope;
        scope = new Scope();
        scope.enclosing = parent;
    }

    public void endScope() {
        for (int i = 0; i < scope.size; i++) {
            // pop local
            final var name = locals.removeLast();
            // remove from table
            symbols.remove(name);
        }
        // return to parent
        scope = scope.enclosing;
    }

    void verify() {
        var current = scope;
        var size = 0;
        while (current != null) {
            size += current.size;
            current = current.enclosing;
        }

        if (size == locals.size()) return;
        throw new IllegalStateException("Desynchronized symbol table");
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder("[ >>= Top level functions ]\n");
        for (final var entry : functions.entrySet()) {
            sb.append(">  ").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("[ >>= Top level symbols ]\n");
        for (final var entry : symbols.entrySet()) {
            sb.append(">  ").append(entry.getKey())
              .append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}
