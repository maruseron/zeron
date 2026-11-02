package com.maruseron.zeron.ast;

import java.util.ArrayList;
import java.util.List;

public final class NamespaceMembers {
    public record Member(String namespaceName, Stmt declaration,
                         List<Stmt.AnnotationApplication> annotations) {
        public Member {
            annotations = List.copyOf(annotations);
        }

        public Member(final String namespaceName, final Stmt declaration) {
            this(namespaceName, declaration, List.of());
        }
    }

    private NamespaceMembers() {}

    public static List<Member> flatten(final List<Stmt> declarations) {
        final var members = new ArrayList<Member>();
        for (final var declaration : declarations) {
            final var annotations = declaration instanceof Stmt.AnnotatedDeclaration annotated
                    ? annotated.annotations() : List.<Stmt.AnnotationApplication>of();
            final var unwrapped = declaration instanceof Stmt.AnnotatedDeclaration annotated
                    ? annotated.declaration() : declaration;
            if (unwrapped instanceof Stmt.Namespace namespace) {
                for (final var member : namespace.members()) {
                    add(members, namespace.name().lexeme(), member,
                            annotations.isEmpty() ? List.of() : annotations);
                }
            } else {
                add(members, null, unwrapped, annotations);
            }
        }
        return List.copyOf(members);
    }

    private static void add(final List<Member> members, final String namespaceName, final Stmt declaration) {
        add(members, namespaceName, declaration, List.of());
    }

    private static void add(final List<Member> members, final String namespaceName, final Stmt declaration,
                            final List<Stmt.AnnotationApplication> annotations) {
        final var unwrapped = declaration instanceof Stmt.AnnotatedDeclaration annotated
                ? annotated.declaration() : declaration;
        final var combinedAnnotations = declaration instanceof Stmt.AnnotatedDeclaration annotated
                ? annotated.annotations() : annotations;
        members.add(new Member(namespaceName, unwrapped, combinedAnnotations));
        if (unwrapped instanceof Stmt.Witness witness) {
            for (final var method : witness.methods()) {
                members.add(new Member(namespaceName, method.implementation()));
            }
        } else if (unwrapped instanceof Stmt.ClassDecl classDeclaration) {
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
