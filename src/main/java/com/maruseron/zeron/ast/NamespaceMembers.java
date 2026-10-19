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
                    add(members, namespace.name().lexeme(), member);
                }
            } else {
                add(members, null, declaration);
            }
        }
        return List.copyOf(members);
    }

    private static void add(final List<Member> members, final String namespaceName, final Stmt declaration) {
        members.add(new Member(namespaceName, declaration));
        if (declaration instanceof Stmt.Witness witness) {
            for (final var method : witness.methods()) {
                members.add(new Member(namespaceName, method.implementation()));
            }
        } else if (declaration instanceof Stmt.ClassDecl classDeclaration) {
            for (final var witness : classDeclaration.higherKindedImplementations()) {
                members.add(new Member(namespaceName, witness));
                for (final var method : witness.methods()) {
                    members.add(new Member(namespaceName, method.implementation()));
                }
            }
        }
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
