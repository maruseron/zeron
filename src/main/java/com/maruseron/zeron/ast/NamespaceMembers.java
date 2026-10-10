package com.maruseron.zeron.ast;

import java.util.ArrayList;
import java.util.List;

public final class NamespaceMembers {
    public record Member(String namespaceName, Stmt declaration) {}

    private NamespaceMembers() {}

    public static List<Member> flatten(final List<Stmt> declarations) {
        final var members = new ArrayList<Member>();
        for (final var declaration : declarations) {
            if (declaration instanceof Stmt.Namespace namespace) {
                for (final var member : namespace.members()) {
                    members.add(new Member(namespace.name().lexeme(), member));
                }
            } else {
                members.add(new Member(null, declaration));
                if (declaration instanceof Stmt.Witness witness) {
                    for (final var method : witness.methods()) {
                        members.add(new Member(null, method.implementation()));
                    }
                }
            }
        }
        return List.copyOf(members);
    }

    public static String qualifiedName(final String packageName, final String namespaceName,
                                       final String memberName) {
        final var prefix = packageName == null || packageName.isEmpty()
                ? namespaceName
                : packageName + "." + namespaceName;
        return prefix + "." + memberName;
    }

    public static String storageName(final String namespaceName, final String memberName) {
        return namespaceName == null ? memberName : "$zeron$ns$" + namespaceName + "$" + memberName;
    }
}
