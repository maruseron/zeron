package com.maruseron.zeron.lsp;

import com.maruseron.zeron.ProjectAnalysisWorkspace;
import com.maruseron.zeron.analize.ResolutionResult;
import com.maruseron.zeron.api.PublicMemberCatalog;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.NamespaceMembers;
import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.*;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class LspFeatures {
    private static final List<String> KEYWORDS = List.of(
            "as", "and", "break", "case", "class", "contract", "constructor", "continue",
            "default", "else", "effect", "extension", "external", "false", "fn", "for",
            "get", "handle", "if", "implement", "import", "in", "is", "let", "loop",
            "match", "mut", "namespace", "newtype", "not", "null", "object", "or",
            "package", "pattern", "property", "public", "raise", "return", "sealed",
            "super", "this", "throw", "true", "try", "type", "using", "var", "while",
            "witness");
    private static final List<String> BUILTIN_TYPES = List.of(
            "Any", "Array", "Boolean", "Byte", "ByteArray", "Float", "Int", "Long",
            "Never", "String", "Unit");

    private LspFeatures() {}

    static CompletionList completion(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                     final Path path,
                                     final Position position) {
        final var text = snapshot.sourceText().get(path);
        final var tokens = snapshot.tokens().get(path);
        if (text == null || tokens == null) return new CompletionList(false, List.of());
        final var offset = DocumentCoordinates.offsetAt(text, position);
        final var prefix = identifierPrefix(text, offset);
        final var prefixStart = offset - prefix.length();
        final var beforeCursor = tokens.stream()
                .filter(token -> token.span().end().offset() != null
                        && token.span().end().offset() <= prefixStart
                        && token.type() != TokenType.EOF)
                .toList();
        final var symbols = new SymbolIndex(snapshot);
        final var items = receiverMembers(snapshot, path, beforeCursor, prefix, symbols);
        if (items != null) return new CompletionList(false, items);

        final var unique = new LinkedHashMap<String, CompletionItem>();
        for (final var symbol : symbols.visibleSymbols(path)) {
            if (!symbol.name().startsWith(prefix)) continue;
            unique.putIfAbsent(symbol.qualifiedName(), completionItem(symbol));
        }
        for (final var keyword : KEYWORDS) {
            if (keyword.startsWith(prefix)) {
                final var item = new CompletionItem(keyword);
                item.setKind(CompletionItemKind.Keyword);
                unique.putIfAbsent("keyword:" + keyword, item);
            }
        }
        for (final var type : BUILTIN_TYPES) {
            if (type.startsWith(prefix)) {
                final var item = new CompletionItem(type);
                item.setKind(CompletionItemKind.Class);
                unique.putIfAbsent("builtin:" + type, item);
            }
        }
        return new CompletionList(false, List.copyOf(unique.values()));
    }

    private static List<CompletionItem> receiverMembers(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                                        final Path path,
                                                        final List<Token> beforeCursor,
                                                        final String prefix,
                                                        final SymbolIndex symbols) {
        if (beforeCursor.size() < 2
                || beforeCursor.getLast().type() != TokenType.DOT) return null;
        final var receiver = beforeCursor.get(beforeCursor.size() - 2);
        final var resolution = snapshot.resolution().orElse(null);
        final var catalog = snapshot.memberCatalog().orElse(null);
        if (resolution == null || catalog == null || receiver.type() != TokenType.IDENTIFIER) {
            return List.of();
        }
        final var type = resolution.referenceType(receiver);
        if (type == null) {
            return symbols.namespaceMembers(path, receiver.lexeme()).stream()
                    .filter(symbol -> symbol.name().startsWith(prefix))
                    .map(LspFeatures::completionItem)
                    .toList();
        }
        final var generic = unwrap(type);
        final String typeName;
        final List<PublicMemberCatalog.TypeRef> arguments;
        if (generic instanceof GenericDescriptor applied) {
            typeName = qualifiedTypeName(resolution, applied.baseType().name(), symbols.packageFor(path));
            arguments = applied.typeParameters().stream().map(LspFeatures::typeRef).toList();
        } else if (generic instanceof NominalDescriptor nominal) {
            typeName = qualifiedTypeName(resolution, nominal.name(), symbols.packageFor(path));
            arguments = List.of();
        } else {
            return List.of();
        }
        final var view = catalog.findType(typeName, arguments).orElse(null);
        if (view == null) return List.of();
        final var unit = symbols.unitFor(path);
        final var items = new LinkedHashMap<String, CompletionItem>();
        for (final var member : concat(view.directMembers(), view.contractMembers(), view.extensions())) {
            if (!member.name().startsWith(prefix) || !extensionIsActive(member, unit)) continue;
            final var item = new CompletionItem(extensionName(member, unit));
            item.setDetail(member.canonicalSignature());
            item.setKind(member.kind() == PublicMemberCatalog.MemberKind.PROPERTY
                    ? CompletionItemKind.Property : CompletionItemKind.Method);
            item.setSortText(member.origin() == PublicMemberCatalog.MemberOrigin.EXTENSION
                    ? "2" + member.name() : "1" + member.name());
            items.putIfAbsent(member.declarationId(), item);
        }
        return List.copyOf(items.values());
    }

    static Hover hover(final ProjectAnalysisWorkspace.Snapshot snapshot,
                       final Path path,
                       final HoverParams params) {
        final var text = snapshot.sourceText().get(path);
        final var tokens = snapshot.tokens().get(path);
        if (text == null || tokens == null) return null;
        final var offset = DocumentCoordinates.offsetAt(text, params.getPosition());
        final var token = tokenAt(tokens, offset);
        if (token == null || token.type() != TokenType.IDENTIFIER) return null;
        final var symbols = new SymbolIndex(snapshot);
        final var resolution = snapshot.resolution().orElse(null);
        final var type = resolution == null ? null : resolution.referenceType(token);
        final var callSiteType = resolution == null ? null : resolution.callSiteFunctionType(token);
        final var target = resolution == null ? null : resolution.referenceTarget(token);
        final var witnessMethod = target != null && target.lexeme().startsWith("$zeron$witness$");
        final var isDeclaration = type != null && resolution.referenceTarget(token) == null;
        final var symbol = isDeclaration ? symbols.byToken(token) : symbols.resolve(path, token);
        if (symbol == null && type == null && callSiteType == null) return null;
        final var details = new ArrayList<String>();
        if (callSiteType != null) {
            final var signature = SymbolIndex.signature(token.lexeme(), callSiteType);
            if (witnessMethod) {
                details.add("witness method " + signature.substring("fn ".length()));
            } else if (symbol != null && symbol.detail().startsWith("extension on ")) {
                details.add(symbol.detail().substring(0, symbol.detail().indexOf(':')));
                details.add(signature);
            } else {
                details.add(signature);
            }
        } else if (witnessMethod) {
            details.add("witness method " + token.lexeme());
        } else if (symbol != null) {
            details.add(symbol.detail());
        }
        if (type != null && callSiteType == null) {
            final var formatted = TypeFormatter.format(type);
            if (details.stream().noneMatch(detail -> detail.contains(formatted))) {
                details.add(isDeclaration
                        ? "value " + token.lexeme() + ": " + formatted
                        : "type: " + formatted);
            }
        }
        final var contents = new MarkupContent(MarkupKind.MARKDOWN,
                "```zeron\n" + String.join("\n", details) + "\n```");
        final var hover = new Hover(contents);
        hover.setRange(DocumentCoordinates.rangeOf(text, token));
        return hover;
    }

    static Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> definition(
            final ProjectAnalysisWorkspace.Snapshot snapshot,
            final Path path,
            final DefinitionParams params) {
        final var text = snapshot.sourceText().get(path);
        final var tokens = snapshot.tokens().get(path);
        if (text == null || tokens == null) return Either.forLeft(List.of());
        final var offset = DocumentCoordinates.offsetAt(text, params.getPosition());
        final var token = tokenAt(tokens, offset);
        if (token == null || token.type() != TokenType.IDENTIFIER) return Either.forLeft(List.of());

        final var symbols = new SymbolIndex(snapshot);
        final var resolution = snapshot.resolution().orElse(null);
        final var target = resolution == null ? null : resolution.referenceTarget(token);
        final var declaration = target == null ? symbols.resolve(path, token) : symbols.byToken(target);
        final var targetToken = declaration == null ? target : declaration.token();
        if (targetToken == null) return Either.forLeft(List.of());
        final var sourcePath = targetToken.span().sourcePath();
        final var targetPath = sourcePath == null ? null : snapshot.filesByCompilerPath().get(sourcePath);
        final var targetText = targetPath == null ? null : snapshot.sourceText().get(targetPath);
        if (targetPath == null || targetText == null) return Either.forLeft(List.of());
        final var uri = targetPath.toUri().toString();
        final var location = new Location(uri, DocumentCoordinates.rangeOf(targetText, targetToken));
        return Either.forLeft(List.of(location));
    }

    private static String identifierPrefix(final String text, final int offset) {
        var start = offset;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) start--;
        return text.substring(start, offset);
    }

    private static boolean isIdentifierPart(final char character) {
        return character == '_' || Character.isUnicodeIdentifierPart(character);
    }

    private static Token tokenAt(final List<Token> tokens, final int offset) {
        return tokens.stream()
                .filter(token -> token.type() != TokenType.EOF)
                .filter(token -> token.span().start().offset() != null
                        && token.span().end().offset() != null
                        && token.span().start().offset() <= offset
                        && offset < token.span().end().offset())
                .min(Comparator.comparingInt(token -> token.span().end().offset()
                        - token.span().start().offset()))
                .orElse(null);
    }

    private static TypeDescriptor unwrap(final TypeDescriptor type) {
        return switch (type) {
            case ReferenceDescriptor reference -> unwrap(reference.baseType());
            case NullableDescriptor nullable -> unwrap(nullable.baseType());
            default -> type;
        };
    }

    private static String qualifiedTypeName(final ResolutionResult resolution,
                                            final String name,
                                            final String currentPackage) {
        if (name.contains(".")) return name;
        final var packageName = resolution.typePackages().get(name);
        if (packageName != null && !packageName.isEmpty()) return packageName + "." + name;
        return currentPackage.isEmpty() ? name : currentPackage + "." + name;
    }

    private static PublicMemberCatalog.TypeRef typeRef(final TypeDescriptor type) {
        return switch (type) {
            case GenericDescriptor generic -> new PublicMemberCatalog.GenericType(generic.baseType().name(),
                    generic.typeParameters().stream().map(LspFeatures::typeRef).toList());
            case ArrayDescriptor array -> new PublicMemberCatalog.ArrayType(typeRef(array.elementType()));
            case NullableDescriptor nullable -> new PublicMemberCatalog.NullableType(typeRef(nullable.baseType()));
            case ReferenceDescriptor reference -> new PublicMemberCatalog.ReferenceType(typeRef(reference.baseType()));
            case TypeParameterDescriptor parameter -> new PublicMemberCatalog.TypeVariable(parameter.name(),
                    parameter.bounds().stream().map(LspFeatures::typeRef).toList());
            case NominalDescriptor nominal -> new PublicMemberCatalog.NamedType(nominal.name());
            default -> new PublicMemberCatalog.NamedType(TypeFormatter.format(type));
        };
    }

    private static List<PublicMemberCatalog.Member> concat(final List<PublicMemberCatalog.Member> first,
                                                            final List<PublicMemberCatalog.Member> second,
                                                            final List<PublicMemberCatalog.Member> third) {
        final var members = new ArrayList<PublicMemberCatalog.Member>(
                first.size() + second.size() + third.size());
        members.addAll(first);
        members.addAll(second);
        members.addAll(third);
        return members;
    }

    private static boolean extensionIsActive(final PublicMemberCatalog.Member member,
                                             final CompilationUnit unit) {
        if (member.origin() != PublicMemberCatalog.MemberOrigin.EXTENSION) return true;
        if (unit == null || member.activationImport() == null) return false;
        return unit.imports().stream().anyMatch(imported -> imported.onDemand()
                ? member.activationImport().startsWith(imported.qualifiedName() + ".")
                : imported.qualifiedName().equals(member.activationImport()));
    }

    private static String extensionName(final PublicMemberCatalog.Member member,
                                        final CompilationUnit unit) {
        if (member.origin() != PublicMemberCatalog.MemberOrigin.EXTENSION || unit == null) return member.name();
        return unit.imports().stream()
                .filter(imported -> !imported.onDemand()
                        && imported.qualifiedName().equals(member.activationImport()))
                .map(imported -> imported.localName())
                .findFirst().orElse(member.name());
    }

    private static CompletionItem completionItem(final SymbolIndex.Symbol symbol) {
        final var item = new CompletionItem(symbol.name());
        item.setDetail(symbol.detail());
        item.setKind(symbol.kind());
        item.setSortText("1" + symbol.name());
        return item;
    }

    private static final class SymbolIndex {
        private final Map<String, List<Symbol>> byName = new LinkedHashMap<>();
        private final Map<String, List<Symbol>> byQualifiedName = new LinkedHashMap<>();
        private final Map<Token, Symbol> byToken = new IdentityHashMap<>();
        private final Map<Path, CompilationUnit> unitsByPath = new LinkedHashMap<>();
        private final Map<Path, String> packagesByPath = new LinkedHashMap<>();
        private final ProjectAnalysisWorkspace.Snapshot snapshot;

        private record Symbol(String name, String qualifiedName, String packageName,
                              String namespaceName, String detail, CompletionItemKind kind,
                              Token token, Path path, boolean isPublic) {}

        SymbolIndex(final ProjectAnalysisWorkspace.Snapshot snapshot) {
            this.snapshot = snapshot;
            for (final var unit : snapshot.units()) {
                final var path = snapshot.filesByCompilerPath().get(unit.sourcePath());
                if (path == null) continue;
                unitsByPath.put(path, unit);
                packagesByPath.put(path, unit.packageName());
                indexUnit(unit, path);
            }
            snapshot.resolution().ifPresent(this::indexResolvedFunctions);
            snapshot.memberCatalog().ifPresent(catalog -> catalog.typeNames().forEach(name -> {
                final var split = splitQualifiedName(name);
                add(new Symbol(split[1], name, split[0], null,
                        "type " + split[1], CompletionItemKind.Class, null, null, true));
            }));
        }

        private void indexResolvedFunctions(final ResolutionResult resolution) {
            for (final var entry : resolution.functionNamesByDeclaration().entrySet()) {
                final var declarationToken = entry.getKey();
                if (byToken.containsKey(declarationToken)) continue;
                final var qualifiedName = entry.getValue();
                final var symbolToken = resolution.functionSymbolToken(qualifiedName);
                if (symbolToken == null || !resolution.globalSymbolTable().containsFunction(symbolToken)) continue;
                final var binding = resolution.globalSymbolTable().getFunction(symbolToken);
                final var functionType = (FunctionDescriptor) binding.type();
                final var declaration = binding.declaration();
                final var isPublic = declaration instanceof Stmt.FunctionDeclaration function
                        && function.isPublic();
                final var parts = qualifiedName.split("\\.");
                if (parts.length == 1) continue;
                final var candidateNamespace = parts[parts.length - 2];
                final var ownerPackage = resolution.typePackages().get(candidateNamespace);
                final String packageName;
                final String namespace;
                if (ownerPackage != null) {
                    packageName = ownerPackage;
                    namespace = candidateNamespace;
                } else {
                    packageName = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
                    namespace = null;
                }
                add(new Symbol(declarationToken.lexeme(), qualifiedName, packageName, namespace,
                        signature(declarationToken.lexeme(), functionType), CompletionItemKind.Function,
                        declarationToken, null, isPublic));
            }
        }

        private void indexUnit(final CompilationUnit unit, final Path path) {
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = member.declaration();
                final var namespace = member.namespaceName();
                if (declaration instanceof Stmt.ClassDecl value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "class");
                    value.methods().stream().filter(Stmt.Method::isPublic)
                            .forEach(method -> addMethod(method.name(), method.typeDescriptor(),
                                    unit.packageName(), value.name().lexeme(), path));
                    value.properties().stream().filter(Stmt.Property::isPublic)
                            .forEach(property -> add(new Symbol(property.name().lexeme(),
                                    value.name().lexeme() + "." + property.name().lexeme(),
                                    unit.packageName(), null,
                                    "property " + property.name().lexeme() + ": "
                                            + TypeFormatter.format(property.type()),
                                    CompletionItemKind.Property, property.name(), path, true)));
                    value.namedConstructors().stream().filter(Stmt.NamedConstructor::isPublic)
                            .forEach(constructor -> addMethod(constructor.name(),
                                    constructor.typeDescriptor(), unit.packageName(),
                                    value.name().lexeme(), path));
                } else if (declaration instanceof Stmt.ContractDecl value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "contract");
                    value.methods().forEach(method -> addMethod(method.name(), method.typeDescriptor(),
                            unit.packageName(), value.name().lexeme(), path));
                    value.properties().forEach(property -> add(new Symbol(property.name().lexeme(),
                            value.name().lexeme() + "." + property.name().lexeme(),
                            unit.packageName(), null,
                            "property " + property.name().lexeme() + ": "
                                    + TypeFormatter.format(property.type()),
                            CompletionItemKind.Property, property.name(), path, true)));
                } else if (declaration instanceof Stmt.SumDecl value) {
                    final var typeParameters = value.typeParameters().stream()
                            .map(TypeParameterDescriptor::name)
                            .collect(java.util.stream.Collectors.joining(", ", "<", ">"));
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "sum",
                            value.typeParameters().isEmpty() ? "" : typeParameters);
                } else if (declaration instanceof Stmt.ProductDecl value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "product");
                    value.components().forEach(component -> add(new Symbol(component.name().lexeme(),
                            value.name().lexeme() + "." + component.name().lexeme(),
                            unit.packageName(), null,
                            "property " + component.name().lexeme() + ": "
                                    + TypeFormatter.format(component.type()),
                            CompletionItemKind.Property, component.name(), path, true)));
                } else if (declaration instanceof Stmt.NewtypeDecl value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "newtype");
                } else if (declaration instanceof Stmt.FunctionDeclaration value
                        && value instanceof Stmt.ExtensionMethod extension) {
                    final var qualified = "extension:" + TypeFormatter.format(extension.receiverType())
                            + "." + value.name().lexeme();
                    byToken.put(value.name(), new Symbol(value.name().lexeme(), qualified,
                            unit.packageName(), null,
                            "extension on " + TypeFormatter.format(extension.receiverType())
                                    + ": " + signature(value),
                            extension.property() ? CompletionItemKind.Property : CompletionItemKind.Method,
                            value.name(), path, value.isPublic()));
                } else if (declaration instanceof Stmt.FunctionDeclaration value) {
                    final var qualified = namespace == null
                            ? qualify(unit.packageName(), value.name().lexeme())
                            : NamespaceMembers.qualifiedName(unit.packageName(), namespace,
                                    value.name().lexeme());
                    add(new Symbol(value.name().lexeme(), qualified, unit.packageName(), namespace,
                            signature(value), CompletionItemKind.Function, value.name(), path,
                            value.isPublic()));
                } else if (declaration instanceof Stmt.Var value) {
                    final var qualified = namespace == null
                            ? qualify(unit.packageName(), value.name().lexeme())
                            : NamespaceMembers.qualifiedName(unit.packageName(), namespace,
                                    value.name().lexeme());
                    add(new Symbol(value.name().lexeme(), qualified, unit.packageName(), namespace,
                            "value " + value.name().lexeme() + ": " + TypeFormatter.format(value.type()),
                            CompletionItemKind.Variable, value.name(), path, value.isPublic()));
                }
            }
        }

        private void addType(final Token token, final String packageName,
                             final boolean isPublic, final Path path, final String kind) {
            addType(token, packageName, isPublic, path, kind, "");
        }

        private void addType(final Token token, final String packageName,
                             final boolean isPublic, final Path path, final String kind,
                             final String typeParameters) {
            add(new Symbol(token.lexeme(), qualify(packageName, token.lexeme()), packageName, null,
                    kind + " " + token.lexeme() + typeParameters,
                    CompletionItemKind.Class, token, path, isPublic));
        }

        private void addMethod(final Token token, final FunctionDescriptor type,
                               final String packageName, final String owner, final Path path) {
            add(new Symbol(token.lexeme(), qualify(packageName, owner) + "." + token.lexeme(),
                    packageName, null, signature(token.lexeme(), type), CompletionItemKind.Method,
                    token, path, true));
        }

        private void add(final Symbol symbol) {
            byName.computeIfAbsent(symbol.name(), _ -> new ArrayList<>()).add(symbol);
            byQualifiedName.computeIfAbsent(symbol.qualifiedName(), _ -> new ArrayList<>()).add(symbol);
            if (symbol.token() != null) byToken.put(symbol.token(), symbol);
        }

        CompilationUnit unitFor(final Path path) {
            return unitsByPath.get(path);
        }

        String packageFor(final Path path) {
            return packagesByPath.getOrDefault(path, "");
        }

        Symbol byToken(final Token token) {
            return byToken.get(token);
        }

        Symbol resolve(final Path path, final Token token) {
            final var direct = byToken(token);
            if (direct != null) return direct;
            final var resolution = snapshot.resolution().orElse(null);
            if (resolution != null) {
                final var target = resolution.referenceTarget(token);
                if (target != null) {
                    final var referenced = byToken(target);
                    if (referenced != null) return referenced;
                }
            }
            final var candidates = visibleSymbols(path).stream()
                    .filter(symbol -> symbol.name().equals(token.lexeme()))
                    .toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        List<Symbol> visibleSymbols(final Path path) {
            final var unit = unitsByPath.get(path);
            if (unit == null) return List.of();
            final var packageName = unit.packageName();
            final var explicitImports = new LinkedHashMap<String, String>();
            final var onDemand = new ArrayList<String>(List.of("zeron.lang", "zeron.io"));
            for (final var imported : unit.imports()) {
                if (imported.onDemand()) onDemand.add(imported.qualifiedName());
                else explicitImports.put(imported.localName(), imported.qualifiedName());
            }
            final var visible = new LinkedHashMap<String, Symbol>();
            for (final var candidates : byName.values()) {
                for (final var symbol : candidates) {
                    if (symbol.namespaceName() != null) continue;
                    final var importedName = explicitImports.get(symbol.name());
                    final var accessible = symbol.packageName().equals(packageName)
                            || symbol.isPublic() && Objects.equals(importedName, symbol.qualifiedName())
                            || symbol.isPublic() && onDemand.stream().anyMatch(packagePrefix ->
                                    symbol.packageName().equals(packagePrefix))
                            || symbol.isPublic() && symbol.packageName().equals("zeron.lang")
                                    || symbol.isPublic() && symbol.packageName().equals("zeron.io");
                    if (accessible) visible.putIfAbsent(symbol.qualifiedName(), symbol);
                }
            }
            for (final var imported : explicitImports.entrySet()) {
                final var qualified = imported.getValue();
                byQualifiedName.getOrDefault(qualified, List.of()).stream().findFirst().ifPresent(symbol ->
                        visible.putIfAbsent(qualified, new Symbol(imported.getKey(), qualified,
                                symbol.packageName(), symbol.namespaceName(),
                                symbol.detail(), symbol.kind(), symbol.token(), symbol.path(),
                                symbol.isPublic())));
            }
            return List.copyOf(visible.values());
        }

        List<Symbol> namespaceMembers(final Path path, final String namespaceName) {
            final var unit = unitsByPath.get(path);
            if (unit == null) return List.of();
            final var packageName = unit.packageName();
            final var imported = unit.imports();
            return byName.values().stream().flatMap(List::stream)
                    .filter(symbol -> Objects.equals(symbol.namespaceName(), namespaceName))
                    .filter(symbol -> symbol.packageName().equals(packageName)
                            || symbol.isPublic() && imported.stream().anyMatch(entry -> entry.onDemand()
                                    ? symbol.packageName().equals(entry.qualifiedName())
                                    : entry.qualifiedName().equals(symbol.qualifiedName())))
                    .toList();
        }

        private static String[] splitQualifiedName(final String qualifiedName) {
            final var separator = qualifiedName.lastIndexOf('.');
            return separator < 0
                    ? new String[]{"", qualifiedName}
                    : new String[]{qualifiedName.substring(0, separator),
                            qualifiedName.substring(separator + 1)};
        }

        private static String qualify(final String packageName, final String name) {
            return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
        }

        private static String signature(final Stmt.FunctionDeclaration declaration) {
            return signature(declaration.name().lexeme(), declaration.typeDescriptor());
        }

        private static String signature(final String name, final FunctionDescriptor descriptor) {
            return "fn " + name + descriptor.parameters().stream()
                    .map(TypeFormatter::format).collect(java.util.stream.Collectors.joining(", ", "(", ") -> "))
                    + TypeFormatter.format(descriptor.returnType());
        }
    }
}
