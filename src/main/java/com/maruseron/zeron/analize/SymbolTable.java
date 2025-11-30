package com.maruseron.zeron.analize;

import com.maruseron.zeron.Zeron;
import com.maruseron.zeron.domain.TypeDescriptor;
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

    public boolean contains(final Token name) {
        return symbols.containsKey(name.lexeme());
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

    public int declare(final Token name, final TypeDescriptor type, final boolean isFinal) {
        if (scope == null)
            return declareGlobal(name, type, isFinal); // -1

        return declareLocal(name, type, isFinal);      // last lvt idx
    }

    private int declareGlobal(final Token name, final TypeDescriptor type, final boolean isFinal) {
        if (contains(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Already a symbol bound to this name."));
            return -2;
        }

        symbols.put(name.lexeme(), new Bind(
                name,
                -1,
                type,
                type.isDoubleWidth() ? Width.DOUBLE : Width.SINGLE,
                false,
                isFinal));
        return -1;
    }

    private int declareLocal(final Token name, final TypeDescriptor type, final boolean isFinal) {
        if (contains(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Already a symbol bound to this name."));
            return -2;
        }

        final var lvt = locals.size();
        symbols.put(name.lexeme(), new Bind(
                name,
                lvt,
                type,
                type.isDoubleWidth() ? Width.DOUBLE : Width.SINGLE,
                false,
                isFinal));

        scope.size++;
        locals.add(name.lexeme());
        return lvt;
    }

    void define(Token name) {
        if (!contains(name)) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Unknown symbol."));
            return;
        }

        symbols.computeIfPresent(name.lexeme(), (_, v) -> v.init());
    }

    void setResolvedType(final Token name, final TypeDescriptor resolvedType) {
        if (!contains(name) || !getSymbol(name).type().isInferred()) {
            Zeron.resolutionError(new ResolutionError(name,
                    "Cannot resolve type of non-inferred bind."));
        }

        symbols.computeIfPresent(name.lexeme(), (_, v) -> v.withType(resolvedType));
    }

    void setResolvedReturnType(final Token name, final TypeDescriptor resolvedType) {
        symbols.computeIfPresent(
                name.lexeme(),
                (_, v) -> v.withType(v.type().withReturnType(resolvedType)));
    }

    void beginScope() {
        final var parent = scope;
        scope = new Scope();
        scope.enclosing = parent;
    }

    void endScope() {
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
        final var sb = new StringBuilder("[ >>= Symbols ]\n");
        for (final var entry : symbols.entrySet()) {
            sb.append(">  ").append(entry.getKey())
              .append(": ").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}
