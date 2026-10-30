package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.TypeSubstitution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContractComposition {
    private ContractComposition() {}

    public record Applied(Stmt.ContractDecl declaration, Stmt.ContractUse use,
                          Map<TypeParameterDescriptor, TypeDescriptor> substitutions) {}
    public record MethodSlot(Applied applied, Stmt.ContractMethod method, int index) {}

    static List<Applied> closure(final ResolutionContext context, final Stmt.ContractUse root) {
        return closure(context.contracts, root);
    }

    public static List<Applied> closure(final Map<String, Stmt.ContractDecl> contracts,
                                        final Stmt.ContractUse root) {
        final var result = new LinkedHashMap<String, Applied>();
        collect(contracts, root, null, result, new ArrayList<>());
        return List.copyOf(result.values());
    }

    static List<Applied> forClass(final ResolutionContext context, final Stmt.ClassDecl declaration) {
        return forClass(context.contracts, declaration);
    }

    public static List<Applied> forClass(final Map<String, Stmt.ContractDecl> contracts,
                                         final Stmt.ClassDecl declaration) {
        final var result = new LinkedHashMap<String, Applied>();
        for (final var use : declaration.contractUses()) {
            for (final var applied : closure(contracts, use)) {
                result.putIfAbsent(key(applied.use()), applied);
            }
        }
        return List.copyOf(result.values());
    }

    static boolean entails(final ResolutionContext context, final Stmt.ContractUse root,
                           final String requiredName, final List<TypeDescriptor> requiredArguments) {
        return closure(context, root).stream().anyMatch(applied ->
                applied.use().name().lexeme().equals(requiredName)
                        && applied.use().typeArguments().equals(requiredArguments));
    }

    public static List<MethodSlot> methods(final ResolutionContext context, final Stmt.ContractUse root) {
        return methods(context.contracts, root);
    }

    public static List<MethodSlot> methods(final Map<String, Stmt.ContractDecl> contracts,
                                           final Stmt.ContractUse root) {
        final var methods = new ArrayList<MethodSlot>();
        for (final var applied : closure(contracts, root)) {
            for (final var method : applied.declaration().methods()) {
                methods.add(new MethodSlot(applied, method, methods.size()));
            }
        }
        return List.copyOf(methods);
    }

    static boolean hasStaticMethods(final ResolutionContext context, final Stmt.ContractUse root) {
        return methods(context, root).stream().anyMatch(slot -> slot.method().isStatic());
    }

    static boolean isShadowedByAncestor(final ResolutionContext context,
                                        final Stmt.ContractUse root,
                                        final Stmt.ContractUse target,
                                        final String memberName) {
        return isShadowedByAncestor(context.contracts, root, target, memberName);
    }

    public static boolean isShadowedByAncestor(final Map<String, Stmt.ContractDecl> contracts,
                                               final Stmt.ContractUse root,
                                               final Stmt.ContractUse target,
                                               final String memberName) {
        return isShadowedByAncestor(contracts, root, target, memberName,
                new ArrayList<>(), new ArrayList<>());
    }

    private static boolean isShadowedByAncestor(final Map<String, Stmt.ContractDecl> contracts,
                                                final Stmt.ContractUse current,
                                                final Stmt.ContractUse target,
                                                final String memberName,
                                                final List<Applied> ancestors,
                                                final List<String> path) {
        final var name = current.name().lexeme();
        final var contract = contracts.get(name);
        if (contract == null || path.contains(name)
                || current.typeArguments().size() != contract.typeParameters().size()) return false;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int index = 0; index < contract.typeParameters().size(); index++) {
            substitutions.put(contract.typeParameters().get(index), current.typeArguments().get(index));
        }
        final var applied = new Applied(contract, current, Map.copyOf(substitutions));
        if (key(current).equals(key(target))) {
            return ancestors.stream().anyMatch(ancestor -> ancestor.declaration().methods().stream()
                    .anyMatch(method -> method.name().lexeme().equals(memberName)));
        }
        final var nextAncestors = new ArrayList<>(ancestors);
        nextAncestors.add(applied);
        final var nextPath = new ArrayList<>(path);
        nextPath.add(name);
        for (final var required : contract.requiredContracts()) {
            final var arguments = required.typeArguments().stream()
                    .map(type -> TypeSubstitution.substitute(type, substitutions)).toList();
            if (isShadowedByAncestor(contracts,
                    new Stmt.ContractUse(required.name(), arguments), target, memberName,
                    nextAncestors, nextPath)) return true;
        }
        return false;
    }

    public static Stmt.ContractUse fromType(final TypeDescriptor type) {
        final var base = type instanceof com.maruseron.zeron.domain.ReferenceDescriptor reference
                ? reference.baseType() : type;
        final var name = switch (base) {
            case com.maruseron.zeron.domain.NominalDescriptor nominal -> nominal.name();
            case com.maruseron.zeron.domain.GenericDescriptor generic -> generic.baseType().name();
            default -> null;
        };
        if (name == null) return null;
        final var arguments = base instanceof com.maruseron.zeron.domain.GenericDescriptor generic
                ? generic.typeParameters() : List.<TypeDescriptor>of();
        return new Stmt.ContractUse(new com.maruseron.zeron.scan.Token(
                com.maruseron.zeron.scan.TokenType.IDENTIFIER, name, null,
                com.maruseron.zeron.diagnostic.SourceSpan.line(null, 1)), arguments);
    }

    private static void collect(final Map<String, Stmt.ContractDecl> contracts, final Stmt.ContractUse use,
                                final String parentPackage, final LinkedHashMap<String, Applied> result,
                                final List<String> path) {
        final var contract = findContract(contracts, use.name().lexeme(), parentPackage);
        if (contract == null || path.contains(contract.name().lexeme())
                || use.typeArguments().size() != contract.typeParameters().size()) return;
        final var resolvedUse = new Stmt.ContractUse(contract.name(), use.typeArguments());
        final var name = contract.name().lexeme();
        final var key = key(resolvedUse);
        if (result.containsKey(key)) return;
        final var substitutions = new LinkedHashMap<TypeParameterDescriptor, TypeDescriptor>();
        for (int i = 0; i < contract.typeParameters().size(); i++) {
            substitutions.put(contract.typeParameters().get(i), use.typeArguments().get(i));
        }
        result.put(key, new Applied(contract, resolvedUse, Map.copyOf(substitutions)));

        final var nestedPath = new ArrayList<>(path);
        nestedPath.add(name);
        for (final var required : contract.requiredContracts()) {
            final var arguments = required.typeArguments().stream()
                    .map(type -> TypeSubstitution.substitute(type, substitutions))
                    .toList();
            collect(contracts, new Stmt.ContractUse(required.name(), arguments),
                    packageName(name), result, nestedPath);
        }
    }

    private static Stmt.ContractDecl findContract(
            final Map<String, Stmt.ContractDecl> contracts, final String name, final String parentPackage) {
        if (name.contains(".")) return contracts.get(name);
        if (parentPackage != null && !parentPackage.isEmpty()) {
            final var samePackage = contracts.get(parentPackage + "." + name);
            if (samePackage != null) return samePackage;
        }
        final var exact = contracts.get(name);
        if (exact != null) return exact;
        final var matches = contracts.values().stream()
                .filter(contract -> simpleName(contract.name().lexeme()).equals(name))
                .distinct()
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String packageName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String simpleName(final String qualifiedName) {
        final var separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private static String key(final Stmt.ContractUse use) {
        return use.name().lexeme() + "<" + String.join(",", use.typeArguments().stream()
                .map(TypeDescriptor::toString).toList()) + ">";
    }
}
