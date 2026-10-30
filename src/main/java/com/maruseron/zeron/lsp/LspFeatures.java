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
import java.util.Set;
import java.util.regex.Pattern;

final class LspFeatures {
    private static final int TYPE_HOVER_CHARACTER_LIMIT = 180;
    private static final List<String> KEYWORDS = List.of(
            "as", "and", "break", "case", "class", "contract", "constructor", "continue",
            "default", "else", "effect", "extension", "external", "false", "fn", "for",
            "handle", "if", "implement", "import", "in", "is", "let", "loop",
            "match", "mut", "namespace", "newtype", "not", "null", "object", "or",
            "package", "pattern", "property", "public", "raise", "return", "sealed",
            "super", "this", "throw", "true", "try", "type", "using", "var", "while",
            "witness");
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
        final var importItems = extensionImportMembers(snapshot, path, beforeCursor, prefix, symbols);
        if (importItems != null) return new CompletionList(false, importItems);
        final var items = receiverMembers(snapshot, path, beforeCursor, prefix, symbols);
        if (items != null) return new CompletionList(false, items);
        if (!beforeCursor.isEmpty() && beforeCursor.getLast().type() == TokenType.DOT) {
            return new CompletionList(false, List.of());
        }

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
        for (final var type : BuiltinTypes.COMPLETION_CANDIDATES) {
            if (type.startsWith(prefix)) {
                final var item = new CompletionItem(type);
                item.setKind(CompletionItemKind.Class);
                unique.putIfAbsent("builtin:" + type, item);
            }
        }
        return new CompletionList(false, List.copyOf(unique.values()));
    }

    private static List<CompletionItem> extensionImportMembers(
            final ProjectAnalysisWorkspace.Snapshot snapshot, final Path path,
            final List<Token> beforeCursor, final String prefix, final SymbolIndex symbols) {
        var importIndex = -1;
        for (var index = beforeCursor.size() - 1; index >= 0; index--) {
            final var type = beforeCursor.get(index).type();
            if (type == TokenType.SEMICOLON) break;
            if (type == TokenType.IMPORT) {
                importIndex = index;
                break;
            }
        }
        if (importIndex < 0 || beforeCursor.getLast().type() != TokenType.DOT) return null;

        final var qualifiedNamespace = new StringBuilder();
        var expectIdentifier = true;
        for (var index = importIndex + 1; index < beforeCursor.size(); index++) {
            final var token = beforeCursor.get(index);
            if (expectIdentifier && token.type() == TokenType.IDENTIFIER) {
                if (!qualifiedNamespace.isEmpty()) qualifiedNamespace.append('.');
                qualifiedNamespace.append(token.lexeme());
                expectIdentifier = false;
            } else if (!expectIdentifier && token.type() == TokenType.DOT) {
                expectIdentifier = true;
            } else {
                return null;
            }
        }
        if (qualifiedNamespace.isEmpty() || !expectIdentifier) return null;

        final var resolution = snapshot.resolution().orElse(null);
        final var unit = symbols.unitFor(path);
        if (resolution == null) return List.of();
        final var namespaceTarget = unit == null ? null
                : resolveNamespaceTarget(qualifiedNamespace.toString(), unit, snapshot.units(), resolution);
        final var packageTarget = isKnownPackage(qualifiedNamespace.toString(), snapshot.units())
                ? qualifiedNamespace.toString() : null;
        final var items = new LinkedHashMap<String, CompletionItem>();
        if (namespaceTarget != null || packageTarget != null) {
            final var wildcard = new CompletionItem("*");
            wildcard.setKind(CompletionItemKind.Keyword);
            wildcard.setInsertText("*");
            items.put("*", wildcard);
        }
        if (namespaceTarget != null) {
            final var separator = namespaceTarget.lastIndexOf('.');
            final var packageName = separator < 0 ? "" : namespaceTarget.substring(0, separator);
            final var memberNamespace = separator < 0 ? namespaceTarget
                    : namespaceTarget.substring(separator + 1);
            for (final var candidates : symbols.byName.values()) {
                for (final var symbol : candidates) {
                    if ((symbol.isPublic() || symbol.packageName().equals(unit.packageName()))
                            && symbol.packageName().equals(packageName)
                            && Objects.equals(symbol.namespaceName(), memberNamespace)
                            && symbol.kind() != CompletionItemKind.Method
                            && symbol.kind() != CompletionItemKind.Property
                            && symbol.kind() != CompletionItemKind.Constructor
                            && symbol.name().startsWith(prefix)) {
                        items.putIfAbsent(symbol.qualifiedName(), completionItem(symbol));
                    }
                }
            }
        }
        for (final var extension : resolution.extensions()) {
            final var qualifiedName = resolution.functionNamesByDeclaration().get(extension.name());
            final var separator = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
            final var extensionNamespace = separator < 0 ? null : qualifiedName.substring(0, separator);
            final var namespaceSeparator = namespaceTarget == null ? -1 : namespaceTarget.lastIndexOf('.');
            final var namespacePackage = namespaceSeparator < 0 ? ""
                    : namespaceTarget.substring(0, namespaceSeparator);
            final var namespaceMatch = namespaceTarget != null
                    && namespaceTarget.equals(extensionNamespace);
            final var packageMatch = namespaceTarget == null && packageTarget != null
                    && extensionNamespace != null && extensionNamespace.startsWith(packageTarget + ".");
            if ((!namespaceMatch && !packageMatch)
                    || !extension.isPublic() && (!namespaceMatch || unit == null
                    || !namespacePackage.equals(unit.packageName()))
                    || !extension.name().lexeme().startsWith(prefix)) continue;
            if (items.containsKey(extension.name().lexeme())) continue;
            if (extension.property()) {
                final var item = new CompletionItem(extension.name().lexeme());
                item.setDetail(TypeFormatter.format(extension.typeDescriptor().returnType()));
                item.setKind(CompletionItemKind.Property);
                item.setInsertText(extension.name().lexeme());
                item.setSortText("1" + extension.name().lexeme());
                items.putIfAbsent(qualifiedName, item);
                continue;
            }
            final var methodType = extension.typeDescriptor();
            final var parameters = methodType.parameters().isEmpty()
                    ? List.<TypeDescriptor>of() : methodType.parameters().subList(1, methodType.parameters().size());
            final var visibleType = TypeDescriptor.functionWithEffectsOf(
                    methodType.name(), methodType.returnType(), parameters,
                    methodType.typeParameters(), methodType.raisedEffects());
            final var parameterNames = extension.parameters().stream().skip(1)
                    .map(Token::lexeme).toList();
            final var item = new CompletionItem(callableLabel(
                    extension.name().lexeme(), visibleType, parameterNames));
            item.setInsertText(extension.name().lexeme());
            item.setDetail(TypeFormatter.format(visibleType.returnType()));
            item.setKind(CompletionItemKind.Method);
            item.setSortText("1" + extension.name().lexeme());
            items.putIfAbsent(qualifiedName == null
                    ? "extension:" + extension.receiverType().name() + "." + extension.name().lexeme()
                    : qualifiedName, item);
        }
        return List.copyOf(items.values());
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
        if (resolution == null || catalog == null) {
            return List.of();
        }
        final var type = receiverType(beforeCursor, resolution);
        if (type == null) {
            final var typeToken = typeReceiverToken(beforeCursor);
            final var sourceType = typeToken == null ? null : symbols.sourceType(path, typeToken.lexeme());
            if (sourceType != null) {
                final var items = new LinkedHashMap<String, CompletionItem>();
                for (final var item : sourceTypeConstructors(sourceType)) {
                    if (item.getLabel().startsWith(prefix)) items.putIfAbsent(item.getLabel(), item);
                }
                if (receiver.type() == TokenType.IDENTIFIER) {
                    for (final var symbol : symbols.namespaceMembers(path, receiver.lexeme())) {
                        if (symbol.name().startsWith(prefix)) {
                            items.putIfAbsent(symbol.qualifiedName(), completionItem(symbol));
                        }
                    }
                }
                return List.copyOf(items.values());
            }
            final var typeSymbol = typeToken == null ? null : symbols.resolve(path, typeToken);
            if (typeSymbol != null && typeSymbol.kind() == CompletionItemKind.Class) {
                final var view = catalog.findType(typeSymbol.qualifiedName()).orElse(null);
                if (view != null) {
                    return view.directMembers().stream()
                            .filter(member -> member.kind() == PublicMemberCatalog.MemberKind.CONSTRUCTOR)
                            .filter(member -> member.name().startsWith(prefix))
                            .map(LspFeatures::memberCompletionItem)
                            .toList();
                }
            }
        }
        if (type == null && receiver.type() == TokenType.IDENTIFIER) {
            return symbols.namespaceMembers(path, receiver.lexeme()).stream()
                    .filter(symbol -> symbol.name().startsWith(prefix))
                    .map(LspFeatures::completionItem)
                    .toList();
        }
        if (type == null) return List.of();
        final var generic = unwrap(type);
        final var sourceTypeName = switch (generic) {
            case GenericDescriptor applied -> applied.baseType().name();
            case NominalDescriptor nominal -> nominal.name();
            default -> null;
        };
        final var sourceType = sourceTypeName == null
                ? null : symbols.sourceType(path, sourceTypeName);
        if (sourceType != null) {
            return sourceTypeMembers(snapshot, path, sourceType, symbols, prefix);
        }
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
            if (member.kind() == PublicMemberCatalog.MemberKind.CONSTRUCTOR
                    || !member.name().startsWith(prefix)
                    || !extensionIsActive(member, unit, resolution)) continue;
            final var item = memberCompletionItem(member);
            final var visibleName = extensionName(member, unit);
            item.setLabel(member.kind() == PublicMemberCatalog.MemberKind.PROPERTY
                    ? visibleName : visibleName + item.getLabel().substring(member.name().length()));
            item.setInsertText(visibleName);
            item.setSortText(member.origin() == PublicMemberCatalog.MemberOrigin.EXTENSION
                    ? "2" + member.name() : "1" + member.name());
            items.putIfAbsent(member.declarationId(), item);
        }
        return List.copyOf(items.values());
    }

    private static List<CompletionItem> sourceTypeMembers(
            final ProjectAnalysisWorkspace.Snapshot snapshot, final Path path,
            final Stmt declaration, final SymbolIndex symbols, final String prefix) {
        final var unit = symbols.unitFor(path);
        final var items = new LinkedHashMap<String, CompletionItem>();
        if (declaration instanceof Stmt.ClassDecl classDecl) {
            classDecl.methods().stream().filter(Stmt.Method::isPublic)
                    .filter(method -> method.name().lexeme().startsWith(prefix))
                    .forEach(method -> items.putIfAbsent(method.name().lexeme(),
                            completionItemForMethod(method.name().lexeme(),
                                    method.typeDescriptor(), method.parameters())));
            classDecl.properties().stream().filter(Stmt.Property::isPublic)
                    .filter(property -> property.name().lexeme().startsWith(prefix))
                    .forEach(property -> {
                        final var item = new CompletionItem(property.name().lexeme());
                        item.setDetail(TypeFormatter.format(property.type()));
                        item.setKind(CompletionItemKind.Property);
                        items.putIfAbsent(property.name().lexeme(), item);
                    });
        }
        final var resolution = snapshot.resolution().orElse(null);
        if (resolution == null) return List.copyOf(items.values());
        for (final var extension : resolution.extensions()) {
            if (!extension.name().lexeme().startsWith(prefix)
                    || !extensionIsActive(extension, path, unit, symbols)) continue;
            if (!sameTypeName(extension.receiverType(), SymbolIndex.typeName(declaration))) continue;
            final var descriptor = extension.typeDescriptor();
            final var parameters = descriptor.parameters().isEmpty()
                    ? List.<TypeDescriptor>of()
                    : descriptor.parameters().subList(1, descriptor.parameters().size());
            final var visibleType = TypeDescriptor.functionWithEffectsOf(
                    descriptor.name(), descriptor.returnType(), parameters, descriptor.typeParameters(),
                    descriptor.raisedEffects());
            final var visibleParameterNames = extension.parameters().isEmpty()
                    ? List.<Token>of() : extension.parameters().subList(1, extension.parameters().size());
            final var item = extension.property()
                    ? new CompletionItem(extension.name().lexeme())
                    : completionItemForMethod(extension.name().lexeme(), visibleType, visibleParameterNames);
            if (extension.property()) {
                item.setKind(CompletionItemKind.Property);
                item.setDetail(TypeFormatter.format(extension.typeDescriptor().returnType()));
            }
            item.setSortText("2" + extension.name().lexeme());
            items.putIfAbsent(extension.name().lexeme(), item);
        }
        return List.copyOf(items.values());
    }

    private static boolean extensionIsActive(final Stmt.ExtensionMethod extension, final Path path,
                                             final CompilationUnit unit, final SymbolIndex symbols) {
        final var declarationPath = extension.name().span().sourcePath() == null
                ? null : symbols.snapshot.filesByCompilerPath()
                        .get(extension.name().span().sourcePath());
        if (Objects.equals(declarationPath, path)) return true;
        if (unit == null) return false;
        final var resolution = symbols.snapshot.resolution().orElse(null);
        final var qualifiedName = resolution == null
                ? null : resolution.functionNamesByDeclaration().get(extension.name());
        if (qualifiedName == null) return false;
        final var separator = qualifiedName.lastIndexOf('.');
        final var namespace = separator < 0 ? "" : qualifiedName.substring(0, separator);
        return unit.imports().stream().anyMatch(imported -> {
            if (!imported.onDemand()) return qualifiedName.equals(imported.qualifiedName());
            final var namespaceTarget = resolveNamespaceTarget(
                    imported.qualifiedName(), unit, symbols.snapshot.units(), resolution);
            final var packageSeparator = namespace.lastIndexOf('.');
            final var extensionPackage = packageSeparator < 0 ? "" : namespace.substring(0, packageSeparator);
            return namespaceTarget != null
                    ? namespace.equals(namespaceTarget) && extension.isPublic()
                    : extensionPackage.equals(imported.qualifiedName());
        });
    }

    private static boolean extensionIsActive(final PublicMemberCatalog.Member member,
                                             final CompilationUnit unit,
                                             final ResolutionResult resolution) {
        if (member.origin() != PublicMemberCatalog.MemberOrigin.EXTENSION) return true;
        if (unit == null || member.activationImport() == null) return false;
        return unit.imports().stream().anyMatch(imported -> {
            if (!imported.onDemand()) return member.activationImport().equals(imported.qualifiedName());
            final var separator = member.activationImport().lastIndexOf('.');
            final var namespace = separator < 0 ? "" : member.activationImport().substring(0, separator);
            final var namespaceTarget = resolveNamespaceTarget(imported.qualifiedName(), unit, List.of(), resolution);
            if (namespaceTarget != null) return namespace.equals(namespaceTarget);
            final var packageSeparator = namespace.lastIndexOf('.');
            final var extensionPackage = packageSeparator < 0 ? "" : namespace.substring(0, packageSeparator);
            return extensionPackage.equals(imported.qualifiedName());
        });
    }

    private static String resolveNamespaceTarget(final String target, final CompilationUnit unit,
                                                 final List<CompilationUnit> units,
                                                 final ResolutionResult resolution) {
        final Set<String> namespaces = new LinkedHashSet<>();
        for (final var candidate : units) {
            for (final var member : NamespaceMembers.flatten(candidate.declarations())) {
                if (member.namespaceName() != null) {
                    namespaces.add(candidate.packageName().isEmpty()
                            ? member.namespaceName()
                            : candidate.packageName() + "." + member.namespaceName());
                }
            }
        }
        if (resolution != null) {
            for (final var extension : resolution.extensions()) {
                final var qualifiedName = resolution.functionNamesByDeclaration().get(extension.name());
                if (qualifiedName != null) {
                    final var separator = qualifiedName.lastIndexOf('.');
                    if (separator > 0) namespaces.add(qualifiedName.substring(0, separator));
                }
            }
        }
        if (namespaces.contains(target)) return target;
        final var localNamespace = unit.packageName().isEmpty()
                ? target : unit.packageName() + "." + target;
        return namespaces.contains(localNamespace) ? localNamespace : null;
    }

    private static boolean isKnownPackage(final String name, final List<CompilationUnit> units) {
        return units.stream().anyMatch(unit -> unit.packageName().equals(name));
    }

    private static boolean sameTypeName(final TypeDescriptor receiverType, final String typeName) {
        final var receiver = unwrap(receiverType);
        final var baseName = receiver instanceof GenericDescriptor generic
                ? generic.baseType().name() : receiver.name();
        final var receiverSimpleName = baseName.substring(baseName.lastIndexOf('.') + 1);
        return receiverSimpleName.equals(typeName);
    }

    private static CompletionItem completionItemForMethod(final String name,
                                                           final FunctionDescriptor descriptor,
                                                           final List<Token> parameterNames) {
        final var item = new CompletionItem(callableLabel(name, descriptor,
                parameterNames.stream().map(Token::lexeme).toList()));
        item.setInsertText(name);
        item.setDetail(TypeFormatter.format(descriptor.returnType()));
        item.setKind(CompletionItemKind.Method);
        return item;
    }

    private static String callableLabel(final String name, final FunctionDescriptor descriptor,
                                        final List<String> parameterNames) {
        final var parameters = new ArrayList<String>();
        for (var index = 0; index < descriptor.parameters().size(); index++) {
            final var parameter = descriptor.parameters().get(index);
            final var parameterName = index < parameterNames.size()
                    ? parameterNames.get(index) + ": " : "";
            parameters.add(parameterName + TypeFormatter.format(parameter));
        }
        return name + parameters.stream()
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    private static List<CompletionItem> sourceTypeConstructors(final Stmt declaration) {
        if (declaration instanceof Stmt.SumDecl sum) {
            final var resultType = sum.typeParameters().isEmpty()
                    ? TypeDescriptor.ofName(sum.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(sum.name().lexeme()),
                            sum.typeParameters().stream().map(parameter -> (TypeDescriptor) parameter).toList());
            return sum.cases().stream().map(sumCase -> {
                final var parameters = sumCase.payloads().stream()
                        .map(payload -> payload.name().lexeme() + ": " + TypeFormatter.format(payload.type()))
                        .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
                return constructorCompletion(sumCase.name().lexeme() + parameters,
                        TypeFormatter.format(new ReferenceDescriptor(resultType)));
            }).toList();
        }
        if (declaration instanceof Stmt.ClassDecl classDecl) {
            final var ownerType = classDecl.typeParameters().isEmpty()
                    ? TypeDescriptor.ofName(classDecl.name().lexeme())
                    : TypeDescriptor.genericOf(TypeDescriptor.ofName(classDecl.name().lexeme()),
                            classDecl.typeParameters().stream()
                                    .map(parameter -> (TypeDescriptor) parameter).toList());
            final var constructors = new ArrayList<CompletionItem>();
            if (classDecl.constructor().isPublic()) {
                final var parameters = canonicalConstructorParameters(classDecl);
                constructors.add(constructorCompletion("new" + parameters,
                        TypeFormatter.format(new ReferenceDescriptor(ownerType))));
            }
            classDecl.namedConstructors().stream().filter(Stmt.NamedConstructor::isPublic)
                    .forEach(constructor -> constructors.add(constructorCompletion(
                            callableLabel(constructor.name().lexeme(), constructor.typeDescriptor(),
                                    constructor.parameters().stream().map(Token::lexeme).toList()),
                            TypeFormatter.format(constructor.typeDescriptor().returnType()))));
            return List.copyOf(constructors);
        }
        return List.of();
    }

    private static String canonicalConstructorParameters(final Stmt.ClassDecl declaration) {
        final var parameters = new ArrayList<String>();
        declaration.fields().stream().filter(field -> field.initializer() == null)
                .forEach(field -> parameters.add(field.name().lexeme() + ": "
                        + TypeFormatter.format(field.type())));
        declaration.properties().stream().filter(Stmt.Property::canonicalConstructorParameter)
                .forEach(property -> parameters.add(property.name().lexeme() + ": "
                        + TypeFormatter.format(property.type())));
        return parameters.stream().collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    private static List<String> canonicalConstructorParameterNames(final Stmt.ClassDecl declaration) {
        final var names = new ArrayList<String>();
        declaration.fields().stream().filter(field -> field.initializer() == null)
                .map(field -> field.name().lexeme()).forEach(names::add);
        declaration.properties().stream().filter(Stmt.Property::canonicalConstructorParameter)
                .map(property -> property.name().lexeme()).forEach(names::add);
        return List.copyOf(names);
    }

    private static CompletionItem constructorCompletion(final String label, final String returnType) {
        final var item = new CompletionItem(label);
        final var parenthesis = label.indexOf('(');
        item.setInsertText(parenthesis < 0 ? label : label.substring(0, parenthesis));
        item.setDetail(returnType);
        item.setKind(CompletionItemKind.Constructor);
        return item;
    }

    private static Token typeReceiverToken(final List<Token> beforeCursor) {
        var index = beforeCursor.size() - 2;
        final var receiverType = beforeCursor.get(index).type();
        if (receiverType == TokenType.IDENTIFIER) return beforeCursor.get(index);
        var depth = switch (receiverType) {
            case GREATER -> 1;
            case SHIFT_RIGHT -> 2;
            case UNSIGNED_SHIFT_RIGHT -> 3;
            default -> 0;
        };
        if (depth == 0) return null;
        for (index--; index >= 0; index--) {
            final var tokenType = beforeCursor.get(index).type();
            depth += switch (tokenType) {
                case GREATER -> 1;
                case SHIFT_RIGHT -> 2;
                case UNSIGNED_SHIFT_RIGHT -> 3;
                case LESS -> -1;
                default -> 0;
            };
            if (depth == 0) {
                final var nameIndex = index - 1;
                return nameIndex >= 0 && beforeCursor.get(nameIndex).type() == TokenType.IDENTIFIER
                        ? beforeCursor.get(nameIndex) : null;
            }
        }
        return null;
    }

    private static CompletionItem memberCompletionItem(final PublicMemberCatalog.Member member) {
        final var label = member.kind() == PublicMemberCatalog.MemberKind.PROPERTY
                ? member.name()
                : member.name() + member.appliedParameters().stream()
                        .map(parameter -> parameter.name().map(name -> name + ": ").orElse("")
                                + formatType(parameter.type()))
                        .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
        final var item = new CompletionItem(label);
        item.setInsertText(member.name());
        final var memberType = member.kind() == PublicMemberCatalog.MemberKind.PROPERTY
                ? member.appliedValueType().or(() -> member.valueType())
                : member.appliedReturnType().or(() -> member.returnType());
        memberType.ifPresent(type -> item.setDetail(formatType(type)));
        item.setKind(switch (member.kind()) {
            case CONSTRUCTOR -> CompletionItemKind.Constructor;
            case METHOD -> CompletionItemKind.Method;
            case PROPERTY -> CompletionItemKind.Property;
        });
        return item;
    }

    private static String formatType(final PublicMemberCatalog.TypeRef type) {
        return switch (type) {
            case PublicMemberCatalog.NamedType named -> named.name();
            case PublicMemberCatalog.GenericType generic -> generic.name()
                    + generic.arguments().stream().map(LspFeatures::formatType)
                    .collect(java.util.stream.Collectors.joining(", ", "<", ">"));
            case PublicMemberCatalog.TypeVariable variable -> variable.name();
            case PublicMemberCatalog.ProjectedType projection ->
                    (projection.kind() == TypeProjectionDescriptor.Kind.OUT ? "out " : "in ")
                            + formatType(projection.bound());
            case PublicMemberCatalog.ArrayType array -> "Array<" + formatType(array.elementType()) + ">";
            case PublicMemberCatalog.NullableType nullable -> formatType(nullable.baseType()) + "?";
            case PublicMemberCatalog.ReferenceType reference -> "&" + formatType(reference.baseType());
            case PublicMemberCatalog.FunctionType function -> function.parameters().stream()
                    .map(LspFeatures::formatType)
                    .collect(java.util.stream.Collectors.joining(", ", "(", ") -> "))
                    + formatType(function.returnType());
            case PublicMemberCatalog.HandlerType handler -> formatType(handler.returnType()) + " ~> "
                    + formatTypeSet(handler.handledEffects()) + " raises "
                    + formatTypeSet(handler.raisedEffects());
        };
    }

    private static String formatTypeSet(final List<PublicMemberCatalog.TypeRef> types) {
        return types.stream().map(LspFeatures::formatType).sorted()
                .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    private static TypeDescriptor receiverType(final List<Token> beforeCursor,
                                               final ResolutionResult resolution) {
        final var receiver = beforeCursor.get(beforeCursor.size() - 2);
        if (receiver.type() == TokenType.IDENTIFIER || receiver.type() == TokenType.THIS) {
            final var type = resolution.referenceType(receiver);
            if (type != null) return type;
        }
        if (receiver.type() != TokenType.RIGHT_PAREN) return null;
        var depth = 0;
        for (var index = beforeCursor.size() - 2; index >= 0; index--) {
            final var token = beforeCursor.get(index);
            if (token.type() == TokenType.RIGHT_PAREN) {
                depth++;
            } else if (token.type() == TokenType.LEFT_PAREN) {
                depth--;
                if (depth == 0) {
                    if (index == 0) return null;
                    final var callee = beforeCursor.get(index - 1);
                    final var signature = resolution.callSiteFunctionType(callee);
                    return signature == null ? null : signature.returnType();
                }
            }
        }
        return null;
    }

    static Hover hover(final ProjectAnalysisWorkspace.Snapshot snapshot,
                       final Path path,
                       final HoverParams params) {
        final var text = snapshot.sourceText().get(path);
        final var tokens = snapshot.tokens().get(path);
        if (text == null || tokens == null) return null;
        final var offset = DocumentCoordinates.offsetAt(text, params.getPosition());
        final var token = tokenAt(tokens, offset);
        if (token == null || token.type() != TokenType.IDENTIFIER
                && token.type() != TokenType.THIS) return null;
        final var symbols = new SymbolIndex(snapshot);
        final var resolution = snapshot.resolution().orElse(null);
        final var type = resolution == null ? null : resolution.referenceType(token);
        final var callSiteType = resolution == null ? null : resolution.callSiteFunctionType(token);
        final var target = resolution == null ? null : resolution.referenceTarget(token);
        final var witnessMethod = target != null && target.lexeme().startsWith("$zeron$witness$");
        final var isDeclaration = type != null && resolution.referenceTarget(token) == null;
        final var typeNamePosition = isTypeNamePosition(tokens, token);
        var symbol = !isDeclaration && typeNamePosition
                ? symbols.resolveType(path, token)
                : isDeclaration
                ? symbols.byToken(token)
                : target == null ? symbols.resolve(path, token) : symbols.byToken(target);
        if (symbol == null && typeNamePosition) {
            symbol = symbols.resolveType(path, token);
        }
        if (symbol == null && type == null && callSiteType == null) return null;
        final var details = new ArrayList<String>();
        if (callSiteType != null) {
            final var rawSignature = SymbolIndex.signature(token.lexeme(), callSiteType);
            final var signature = symbol != null && symbol.kind() == CompletionItemKind.Constructor
                    ? rawSignature.replaceFirst("^fn ", "constructor ") : rawSignature;
            if (witnessMethod) {
                if (symbol != null && symbol.detail().startsWith("extension on ")) {
                    details.add(symbolHeader(symbol.detail()));
                } else {
                    details.add("witness method " + signature.substring("fn ".length()));
                }
                details.add(signature);
            } else if (symbol != null && symbol.detail().startsWith("extension on ")) {
                details.add(symbolHeader(symbol.detail()));
                details.add(signature);
            } else if (symbol != null && symbol.detail().startsWith("external ")) {
                details.add("external " + signature);
            } else {
                details.add(signature);
            }
        } else if (witnessMethod) {
        if (symbol != null && symbol.detail().startsWith("extension on ")) {
            details.add(symbolHeader(symbol.detail()));
        } else {
            details.add("witness method " + token.lexeme());
        }
        } else if (symbol != null) {
            details.add(symbol.detail());
            details.addAll(typeEvidenceDetails(snapshot, path, symbol));
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
                "```zeron\n" + String.join("\n", details) + "\n```"
                        + documentation(snapshot, symbol));
        final var hover = new Hover(contents);
        hover.setRange(DocumentCoordinates.rangeOf(text, token));
        return hover;
    }

    private static String documentation(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                        final SymbolIndex.Symbol symbol) {
        if (symbol == null) return "";
        if (symbol.path() == null) {
            final var libraryDocumentation = snapshot.libraryDocumentation().get(symbol.qualifiedName());
            return libraryDocumentation == null || libraryDocumentation.isBlank()
                    ? "" : "\n\n" + libraryDocumentation;
        }
        if (symbol.token() == null) return "";
        final var unit = snapshot.units().stream()
                .filter(candidate -> Objects.equals(candidate.sourcePath(),
                        snapshot.filesByCompilerPath().entrySet().stream()
                                .filter(entry -> entry.getValue().equals(symbol.path()))
                                .map(Map.Entry::getKey).findFirst().orElse(null)))
                .findFirst().orElse(null);
        if (unit == null) return "";
        final var markdown = unit.documentation().get(symbol.token());
        return markdown == null || markdown.isBlank() ? "" : "\n\n" + markdown;
    }

    private static String symbolHeader(final String detail) {
        final var newline = detail.indexOf('\n');
        final var description = detail.indexOf(": ");
        final var boundary = newline < 0 ? description
                : description < 0 ? newline : Math.min(newline, description);
        return boundary < 0 ? detail : detail.substring(0, boundary);
    }

    private static List<String> typeEvidenceDetails(final ProjectAnalysisWorkspace.Snapshot snapshot,
                                                    final Path path,
                                                    final SymbolIndex.Symbol symbol) {
        if (symbol.kind() != CompletionItemKind.Class || symbol.token() == null) return List.of();
        final var declarationUnit = snapshot.units().stream()
                .filter(unit -> Objects.equals(snapshot.filesByCompilerPath().get(unit.sourcePath()), symbol.path()))
                .filter(unit -> NamespaceMembers.flatten(unit.declarations()).stream()
                        .anyMatch(member -> sourceTypeName(member.declaration()) == symbol.token()))
                .findFirst().orElse(null);
        if (declarationUnit == null) return List.of();
        final var declaration = NamespaceMembers.flatten(declarationUnit.declarations()).stream()
                .map(NamespaceMembers.Member::declaration)
                .filter(candidate -> sourceTypeName(candidate) == symbol.token())
                .findFirst().orElse(null);
        if (declaration == null) return List.of();

        final var currentUnit = snapshot.units().stream()
                .filter(unit -> Objects.equals(snapshot.filesByCompilerPath().get(unit.sourcePath()), path))
                .findFirst().orElse(null);
        if (currentUnit == null) return List.of();
        final var visibleWitnesses = new LinkedHashSet<String>();
        if (witnessPackageIsVisible(currentUnit, declarationUnit.packageName())) {
            visibleWitnesses.addAll(derivedEvidenceDetails(declaration, snapshot, currentUnit));
        }
        for (final var unit : snapshot.units()) {
            if (!witnessPackageIsVisible(currentUnit, unit.packageName())) continue;
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (!(member.declaration() instanceof Stmt.Witness witness)
                        || !witnessTargetsType(witness.targetType(), witness.typeParameters(), declaration)) {
                    continue;
                }
                visibleWitnesses.add(shortTypeNames(
                        TypeFormatter.format(witness.contractType()), snapshot, currentUnit));
            }
        }
        if (visibleWitnesses.isEmpty()) return List.of();
        final var details = new ArrayList<String>();
        details.add("\nEvidence available here:");
        visibleWitnesses.stream().sorted().map(contract -> "  " + contract).forEach(details::add);
        return List.copyOf(details);
    }

    private static List<String> derivedEvidenceDetails(final Stmt declaration,
                                                       final ProjectAnalysisWorkspace.Snapshot snapshot,
                                                       final CompilationUnit contextUnit) {
        final var requested = new LinkedHashSet<String>();
        final var derivedContracts = switch (declaration) {
            case Stmt.ClassDecl value -> value.derivedContracts();
            case Stmt.ProductDecl value -> value.derivedContracts();
            case Stmt.SumDecl value -> value.derivedContracts();
            case Stmt.NewtypeDecl value -> value.derivedContracts();
            default -> List.<Token>of();
        };
        derivedContracts.stream().map(Token::lexeme).forEach(requested::add);
        if (declaration instanceof Stmt.ClassDecl classDeclaration && classDeclaration.dataClass()) {
            requested.add("Eq");
        }
        if (requested.contains("Codec")) {
            requested.add("Encode");
            requested.add("Decode");
        }
        if (requested.contains("Encode") && requested.contains("Decode")) {
            requested.add("Codec");
        }

        final var qualifiedContractNames = new LinkedHashSet<String>();
        for (final var derived : requested) {
            final var contractName = switch (derived) {
                case "Eq" -> "zeron.lang.Eq";
                case "Hashable" -> "zeron.lang.Hashable";
                case "Encode" -> "zeron.serialization.Encode";
                case "Decode" -> "zeron.serialization.Decode";
                case "Codec" -> "zeron.serialization.Codec";
                default -> null;
            };
            if (contractName != null) qualifiedContractNames.add(contractName);
        }
        final var name = sourceTypeName(declaration).lexeme();
        final var parameters = sourceTypeParameters(declaration);
        final TypeDescriptor target = parameters.isEmpty()
                ? TypeDescriptor.ofName(name)
                : TypeDescriptor.genericOf(TypeDescriptor.ofName(name),
                        parameters.stream().map(parameter -> (TypeDescriptor) parameter).toList());
        return qualifiedContractNames.stream()
                .map(contractName -> TypeDescriptor.genericOf(TypeDescriptor.ofName(contractName), List.of(target)))
                .map(TypeFormatter::format)
                .map(formatted -> shortTypeNames(formatted, snapshot, contextUnit))
                .toList();
    }

    private static Token sourceTypeName(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl value -> value.name();
            case Stmt.ExternalClass value -> value.name();
            case Stmt.ProductDecl value -> value.name();
            case Stmt.SumDecl value -> value.name();
            case Stmt.NewtypeDecl value -> value.name();
            default -> null;
        };
    }

    private static List<TypeParameterDescriptor> sourceTypeParameters(final Stmt declaration) {
        return switch (declaration) {
            case Stmt.ClassDecl value -> value.typeParameters();
            case Stmt.ProductDecl value -> value.typeParameters();
            case Stmt.SumDecl value -> value.typeParameters();
            default -> List.of();
        };
    }

    private static boolean witnessPackageIsVisible(final CompilationUnit unit, final String witnessPackage) {
        if (unit.packageName().equals(witnessPackage)) return true;
        return unit.imports().stream().anyMatch(imported -> {
            if (imported.onDemand()) return imported.qualifiedName().equals(witnessPackage);
            final var separator = imported.qualifiedName().lastIndexOf('.');
            final var importedPackage = separator < 0 ? "" : imported.qualifiedName().substring(0, separator);
            return importedPackage.equals(witnessPackage);
        });
    }

    private static boolean witnessTargetsType(final TypeDescriptor targetType,
                                              final List<TypeParameterDescriptor> witnessParameters,
                                              final Stmt declaration) {
        final var target = targetType instanceof ReferenceDescriptor reference
                ? reference.baseType() : targetType;
        final TypeDescriptor base;
        final List<TypeDescriptor> arguments;
        if (target instanceof GenericDescriptor generic) {
            base = generic.baseType();
            arguments = generic.typeParameters();
        } else {
            base = target;
            arguments = List.of();
        }
        final var declaredName = sourceTypeName(declaration);
        final var declarationParameters = sourceTypeParameters(declaration);
        if (declaredName == null
                || !simpleTypeName(base.name()).equals(simpleTypeName(declaredName.lexeme()))
                || arguments.size() != declarationParameters.size()) return false;
        for (var index = 0; index < arguments.size(); index++) {
            if (!(arguments.get(index) instanceof TypeParameterDescriptor argument)
                    || witnessParameters.stream().noneMatch(parameter ->
                            parameter.name().equals(argument.name()))) return false;
        }
        return true;
    }

    private static String shortTypeNames(final String type) {
        return type.replaceAll("(?<![A-Za-z0-9_$])(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+"
                + "([A-Za-z_$][A-Za-z0-9_$]*)", "$1");
    }

    private static String shortTypeNames(final String type,
                                         final ProjectAnalysisWorkspace.Snapshot snapshot,
                                         final CompilationUnit contextUnit) {
        final var qualifiedNamesBySimpleName = new LinkedHashMap<String, Set<String>>();
        for (final var unit : snapshot.units()) {
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var name = switch (member.declaration()) {
                    case Stmt.ClassDecl declaration -> declaration.name().lexeme();
                    case Stmt.ContractDecl declaration -> declaration.name().lexeme();
                    case Stmt.SumDecl declaration -> declaration.name().lexeme();
                    case Stmt.ProductDecl declaration -> declaration.name().lexeme();
                    case Stmt.NewtypeDecl declaration -> declaration.name().lexeme();
                    case Stmt.ExternalClass declaration -> declaration.name().lexeme();
                    default -> null;
                };
                if (name == null || !typeIsVisible(contextUnit, unit.packageName(), name)) continue;
                final var simpleName = simpleTypeName(name);
                final var qualifiedName = unit.packageName().isEmpty()
                        ? simpleName : unit.packageName() + "." + simpleName;
                qualifiedNamesBySimpleName.computeIfAbsent(simpleName, _ -> new LinkedHashSet<>())
                        .add(qualifiedName);
            }
        }
        var shortened = type;
        for (final var entry : qualifiedNamesBySimpleName.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            final var qualifiedName = entry.getValue().iterator().next();
            shortened = Pattern.compile("(?<![A-Za-z0-9_$.])" + Pattern.quote(qualifiedName)
                            + "(?![A-Za-z0-9_$])")
                    .matcher(shortened).replaceAll(entry.getKey());
        }
        return shortened;
    }

    private static boolean typeIsVisible(final CompilationUnit contextUnit,
                                         final String candidatePackage,
                                         final String typeName) {
        if (contextUnit.packageName().equals(candidatePackage)) return true;
        final var qualifiedName = candidatePackage.isEmpty() ? simpleTypeName(typeName)
                : candidatePackage + "." + simpleTypeName(typeName);
        return contextUnit.imports().stream().anyMatch(imported -> imported.onDemand()
                ? imported.qualifiedName().equals(candidatePackage)
                : imported.qualifiedName().equals(qualifiedName));
    }

    private static String simpleTypeName(final String name) {
        final var separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static boolean isTypeNamePosition(final List<Token> tokens, final Token token) {
        final var index = tokens.indexOf(token);
        if (index < 0) return false;
        var previous = index - 1;
        while (previous >= 0 && (tokens.get(previous).type().isComment()
                || tokens.get(previous).type() == TokenType.NEWLINE)) previous--;
        if (previous < 0) return false;
        return switch (tokens.get(previous).type()) {
            case TokenType.COLON, TokenType.LESS, TokenType.COMMA, TokenType.IS, TokenType.AS -> true;
            default -> false;
        };
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
        var declaration = target == null ? null : symbols.byToken(target);
        if (declaration == null && (resolution == null || resolution.referenceType(token) == null
                || isTypeNamePosition(tokens, token))) {
            declaration = symbols.resolveType(path, token);
        }
        if (declaration == null) declaration = symbols.resolve(path, token);
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
            case TypeProjectionDescriptor projection -> new PublicMemberCatalog.ProjectedType(
                    projection.kind(), typeRef(projection.bound()));
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
        final var item = new CompletionItem(symbol.completionLabel());
        item.setInsertText(symbol.name());
        item.setDetail(symbol.completionDetail());
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
                              Token token, Path path, boolean isPublic, String completionLabel,
                              String completionDetail) {
            private Symbol(final String name, final String qualifiedName, final String packageName,
                           final String namespaceName, final String detail, final CompletionItemKind kind,
                           final Token token, final Path path, final boolean isPublic) {
                this(name, qualifiedName, packageName, namespaceName, detail, kind, token, path,
                        isPublic, name, detail);
            }

            private Symbol(final String name, final String qualifiedName, final String packageName,
                           final String namespaceName, final String detail, final CompletionItemKind kind,
                           final Token token, final Path path, final boolean isPublic,
                           final String completionLabel) {
                this(name, qualifiedName, packageName, namespaceName, detail, kind, token, path,
                        isPublic, completionLabel, detail);
            }
        }

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
                final var parameterNames = declaration instanceof Stmt.FunctionDeclaration function
                        ? function.parameters().stream().map(Token::lexeme).toList() : List.<String>of();
                add(new Symbol(declarationToken.lexeme(), qualifiedName, packageName, namespace,
                        signature(declarationToken.lexeme(), functionType), CompletionItemKind.Function,
                        declarationToken, null, isPublic,
                        callableLabel(declarationToken.lexeme(), functionType, parameterNames),
                        TypeFormatter.format(functionType.returnType())));
            }
        }

        private void indexUnit(final CompilationUnit unit, final Path path) {
            final var witnessMethods = new IdentityHashMap<Token, String>();
            final var indexedWitnesses = java.util.Collections.newSetFromMap(
                    new IdentityHashMap<Stmt.Witness, Boolean>());
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                if (member.declaration() instanceof Stmt.Witness witness
                        && indexedWitnesses.add(witness)) {
                    indexWitnessMethods(witness, witness.targetType(), witnessMethods);
                } else if (member.declaration() instanceof Stmt.ClassDecl declaration) {
                    declaration.higherKindedImplementations().forEach(witness ->
                            {
                                if (indexedWitnesses.add(witness)) {
                                    indexWitnessMethods(witness, witness.targetType(), witnessMethods);
                                }
                            });
                }
            }
            for (final var member : NamespaceMembers.flatten(unit.declarations())) {
                final var declaration = member.declaration();
                final var namespace = member.namespaceName();
                if (declaration instanceof Stmt.ClassDecl value) {
                    if (value.isEffect()) {
                        addType(value.name(), unit.packageName(), value.isPublic(), path,
                                "effect", formatTypeParameters(value.typeParameters()));
                    } else {
                        final var implementations = java.util.stream.Stream.concat(
                                        value.contractUses().stream(),
                                        value.higherKindedContractUses().stream())
                                .map(contractUse -> formatContractUse(contractUse, unit))
                                .collect(java.util.stream.Collectors.joining(", "));
                        final var typeParameters = formatTypeParameters(value.typeParameters());
                        final var components = value.dataClass()
                                ? value.dataComponents().stream()
                                        .map(component -> component.name().lexeme() + ": "
                                                + TypeFormatter.format(component.type()))
                                        .collect(java.util.stream.Collectors.joining(", ", "(", ")"))
                                : "";
                        final var declarationKind = value.dataClass() ? "data class " : "class ";
                        addType(value.name(), unit.packageName(), value.isPublic(), path,
                                value.dataClass() ? "data class" : "class", typeParameters,
                                declarationKind + simpleTypeName(value.name().lexeme())
                                        + typeParameters + components + (implementations.isBlank() ? "" : " is ")
                                        + implementations);
                    }
                    if (value.constructor() != null) {
                        final var ownerType = value.typeParameters().isEmpty()
                                ? TypeDescriptor.ofName(value.name().lexeme())
                                : TypeDescriptor.genericOf(TypeDescriptor.ofName(value.name().lexeme()),
                                        value.typeParameters().stream()
                                                .map(parameter -> (TypeDescriptor) parameter).toList());
                        final var constructorType = TypeDescriptor.genericFunctionOf("new",
                                new ReferenceDescriptor(ownerType),
                                value.canonicalConstructorTypes(),
                                value.typeParameters());
                        addConstructor(value.constructor().name(), constructorType,
                                unit.packageName(), value.name().lexeme(), path,
                                value.constructor().isPublic());
                    }
                    value.methods().stream().filter(Stmt.Method::isPublic)
                            .forEach(method -> addMethod(method.name(), method.typeDescriptor(),
                                    unit.packageName(), value.name().lexeme(), path,
                                    method.parameters().stream().map(Token::lexeme).toList()));
                    value.properties().stream().filter(Stmt.Property::isPublic)
                            .forEach(property -> add(new Symbol(property.name().lexeme(),
                                    value.name().lexeme() + "." + property.name().lexeme(),
                                    unit.packageName(), null,
                                    "property " + property.name().lexeme() + ": "
                                            + TypeFormatter.format(property.type()),
                                    CompletionItemKind.Property, property.name(), path, true,
                                    property.name().lexeme(), TypeFormatter.format(property.type()))));
                    value.namedConstructors().forEach(constructor -> addConstructor(constructor.name(),
                                    constructor.typeDescriptor(), unit.packageName(),
                                    value.name().lexeme(), path, constructor.isPublic()));
                } else if (declaration instanceof Stmt.ExternalClass value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path,
                            "external class", "", "external class " + simpleTypeName(value.name().lexeme())
                                    + " = " + value.javaBinaryName());
                    value.constructors().stream()
                            .forEach(constructor -> {
                                final var raisedEffects = constructor.exceptionMappings().stream()
                                        .map(Stmt.ExternalExceptionMapping::effectType).distinct().toList();
                                final var type = TypeDescriptor.functionWithEffectsOf(
                                        constructor.name().lexeme(), TypeDescriptor.ofName(value.name().lexeme()),
                                        constructor.parameterTypes(), List.of(), raisedEffects);
                                addConstructor(constructor.name(), type, unit.packageName(),
                                        value.name().lexeme(), path, "external ",
                                        constructor.isPublic());
                            });
                    value.methods().stream().filter(Stmt.ExternalMethod::isPublic)
                            .forEach(method -> addMethod(method.name(), method.typeDescriptor(),
                                    unit.packageName(), value.name().lexeme(), path,
                                    method.parameters().stream().map(Token::lexeme).toList(), "external "));
                } else if (declaration instanceof Stmt.ContractDecl value) {
                    final var requirements = value.requiredContracts().stream()
                            .map(contractUse -> formatContractUse(contractUse, unit))
                            .collect(java.util.stream.Collectors.joining(", "));
                    final var typeParameters = formatTypeParameters(value.typeParameters());
                    addType(value.name(), unit.packageName(), value.isPublic(), path,
                            "contract", typeParameters, "contract " + simpleTypeName(value.name().lexeme())
                                    + typeParameters + (requirements.isEmpty() ? "" : " : " + requirements));
                    value.methods().forEach(method -> addMethod(method.name(), method.typeDescriptor(),
                            unit.packageName(), value.name().lexeme(), path,
                            method.parameters().stream().map(Token::lexeme).toList()));
                    value.properties().forEach(property -> add(new Symbol(property.name().lexeme(),
                            value.name().lexeme() + "." + property.name().lexeme(),
                            unit.packageName(), null,
                            "property " + property.name().lexeme() + ": "
                                    + TypeFormatter.format(property.type()),
                            CompletionItemKind.Property, property.name(), path, true,
                            property.name().lexeme(), TypeFormatter.format(property.type()))));
                } else if (declaration instanceof Stmt.SumDecl value) {
                    final var typeParameters = formatTypeParameters(value.typeParameters());
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "type",
                            typeParameters, sumTypeDetail(value));
                    for (final var sumCase : value.cases()) {
                        final var resultType = value.typeParameters().isEmpty()
                                ? TypeDescriptor.ofName(value.name().lexeme())
                                : TypeDescriptor.genericOf(TypeDescriptor.ofName(value.name().lexeme()),
                                        value.typeParameters().stream()
                                                .map(parameter -> (TypeDescriptor) parameter).toList());
                        final var constructorType = TypeDescriptor.genericFunctionOf(
                                sumCase.name().lexeme(), resultType,
                                sumCase.payloads().stream().map(Stmt.SumPayload::type).toList(),
                                value.typeParameters());
                        addConstructor(sumCase.name(), constructorType, unit.packageName(),
                                value.name().lexeme(), path, value.isPublic());
                    }
                } else if (declaration instanceof Stmt.ProductDecl value) {
                    addType(value.name(), unit.packageName(), value.isPublic(), path, "type",
                            formatTypeParameters(value.typeParameters()), productTypeDetail(value));
                    value.components().forEach(component -> add(new Symbol(component.name().lexeme(),
                            value.name().lexeme() + "." + component.name().lexeme(),
                            unit.packageName(), null,
                            "property " + component.name().lexeme() + ": "
                                    + TypeFormatter.format(component.type()),
                            CompletionItemKind.Property, component.name(), path, true,
                            component.name().lexeme(), TypeFormatter.format(component.type()))));
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
                            value.name(), path, value.isPublic(),
                            extension.property() ? value.name().lexeme()
                                    : callableLabel(value.name().lexeme(), value.typeDescriptor(),
                                            value.parameters().stream().skip(1).map(Token::lexeme).toList()),
                            TypeFormatter.format(value.typeDescriptor().returnType())));
                } else if (declaration instanceof Stmt.FunctionDeclaration value) {
                    final var qualified = namespace == null
                            ? qualify(unit.packageName(), value.name().lexeme())
                            : NamespaceMembers.qualifiedName(unit.packageName(), namespace,
                                    value.name().lexeme());
                    final var witnessDetail = witnessMethods.get(value.name());
                    final var functionSignature = signature(value);
                    final var detail = witnessDetail == null
                            ? (value instanceof Stmt.ExternalFunction ? "external " : "")
                            + functionSignature
                            : witnessDetail + "\n" + functionSignature;
                    add(new Symbol(value.name().lexeme(), qualified, unit.packageName(), namespace,
                            detail, CompletionItemKind.Function, value.name(), path, value.isPublic(),
                            callableLabel(value.name().lexeme(), value.typeDescriptor(),
                                    value.parameters().stream().map(Token::lexeme).toList()),
                            TypeFormatter.format(value.typeDescriptor().returnType())));
                } else if (declaration instanceof Stmt.Var value) {
                    final var qualified = namespace == null
                            ? qualify(unit.packageName(), value.name().lexeme())
                            : NamespaceMembers.qualifiedName(unit.packageName(), namespace,
                                    value.name().lexeme());
                    final var isHandler = value.initializer() instanceof com.maruseron.zeron.ast.Expr.HandlerLiteral;
                    add(new Symbol(value.name().lexeme(), qualified, unit.packageName(), namespace,
                            isHandler ? "handler " + value.name().lexeme()
                                    : "value " + value.name().lexeme() + ": "
                                            + TypeFormatter.format(value.type()),
                            CompletionItemKind.Variable, value.name(), path, value.isPublic(),
                            value.name().lexeme(), TypeFormatter.format(value.type())));
                }
            }
        }

        private void indexWitnessMethods(final Stmt.Witness witness, final TypeDescriptor targetType,
                                         final Map<Token, String> witnessMethods) {
            final var detail = "extension on " + TypeFormatter.format(targetType)
                    + " via " + TypeFormatter.format(witness.contractType());
            for (final var method : witness.methods()) {
                final var sourceMethod = method.methodSignature();
                final var symbol = new Symbol(sourceMethod.name().lexeme(),
                        "witness:" + detail + "." + sourceMethod.name().lexeme(),
                        "", null, detail + "\n" + signature(sourceMethod),
                        CompletionItemKind.Method, sourceMethod.name(), null, false);
                add(symbol);
                byToken.put(method.implementation().name(), symbol);
                byToken.put(sourceMethod.name(), symbol);
                witnessMethods.put(method.implementation().name(), detail);
            }
        }

        private void addType(final Token token, final String packageName,
                             final boolean isPublic, final Path path, final String kind) {
            addType(token, packageName, isPublic, path, kind, "");
        }

        private void addType(final Token token, final String packageName,
                             final boolean isPublic, final Path path, final String kind,
                             final String typeParameters) {
            addType(token, packageName, isPublic, path, kind, typeParameters,
                    kind + " " + simpleTypeName(token.lexeme()) + typeParameters);
        }

        private void addType(final Token token, final String packageName,
                             final boolean isPublic, final Path path, final String kind,
                             final String typeParameters, final String detail) {
            final var name = simpleTypeName(token.lexeme());
            add(new Symbol(name, qualify(packageName, name), packageName, null,
                    detail,
                    CompletionItemKind.Class, token, path, isPublic));
        }

        private static String formatTypeParameters(final List<TypeParameterDescriptor> parameters) {
            if (parameters.isEmpty()) return "";
            return parameters.stream()
                    .map(parameter -> (parameter.variance() == TypeParameterDescriptor.Variance.COVARIANT
                                    ? "out " : "")
                            + parameter.name() + (parameter.isHigherKinded() ? "<_>" : "")
                            + typeParameterConstraints(parameter))
                    .collect(java.util.stream.Collectors.joining(", ", "<", ">"));
        }

        private static String typeParameterConstraints(final TypeParameterDescriptor parameter) {
            final var constraints = new ArrayList<String>();
            if (parameter.admitsNullableArguments()) constraints.add("nullable");
            if (parameter.isEffectKind()) constraints.add("effect");
            if (parameter.admitsMutableArguments()) constraints.add("mut");
            parameter.bounds().stream().map(TypeFormatter::format)
                    .map(LspFeatures::shortTypeNames).forEach(constraints::add);
            return constraints.isEmpty() ? "" : ": " + String.join(" + ", constraints);
        }

        private String formatContractUse(final Stmt.ContractUse contractUse,
                                         final CompilationUnit unit) {
            final var arguments = contractUse.typeArguments().stream()
                    .map(TypeFormatter::format)
                    .map(type -> shortTypeNames(type, snapshot, unit))
                    .collect(java.util.stream.Collectors.joining(", ", "<", ">"));
            return simpleTypeName(contractUse.name().lexeme())
                    + (contractUse.typeArguments().isEmpty() ? "" : arguments);
        }

        private void addMethod(final Token token, final FunctionDescriptor type,
                               final String packageName, final String owner, final Path path,
                               final List<String> parameterNames) {
            addMethod(token, type, packageName, owner, path, parameterNames, "");
        }

        private void addMethod(final Token token, final FunctionDescriptor type,
                               final String packageName, final String owner, final Path path,
                               final List<String> parameterNames, final String modifier) {
            add(new Symbol(token.lexeme(), qualify(packageName, owner) + "." + token.lexeme(),
                    packageName, null, modifier + signature(token.lexeme(), type), CompletionItemKind.Method,
                    token, path, true, callableLabel(token.lexeme(), type, parameterNames),
                    TypeFormatter.format(type.returnType())));
        }

        private void addConstructor(final Token token, final FunctionDescriptor type,
                                    final String packageName, final String owner, final Path path) {
            addConstructor(token, type, packageName, owner, path, "", true);
        }

        private void addConstructor(final Token token, final FunctionDescriptor type,
                                    final String packageName, final String owner, final Path path,
                                    final String modifier) {
            addConstructor(token, type, packageName, owner, path, modifier, true);
        }

        private void addConstructor(final Token token, final FunctionDescriptor type,
                                    final String packageName, final String owner, final Path path,
                                    final boolean isPublic) {
            addConstructor(token, type, packageName, owner, path, "", isPublic);
        }

        private void addConstructor(final Token token, final FunctionDescriptor type,
                                    final String packageName, final String owner, final Path path,
                                    final String modifier, final boolean isPublic) {
            final var signature = signature(token.lexeme(), type);
            add(new Symbol(token.lexeme(), qualify(packageName, owner) + "." + token.lexeme(),
                    packageName, null, modifier + signature.replaceFirst("^fn ", "constructor "),
                    CompletionItemKind.Constructor, token, path, isPublic));
        }

        private void add(final Symbol symbol) {
            byName.computeIfAbsent(symbol.name(), _ -> new ArrayList<>()).add(symbol);
            byQualifiedName.computeIfAbsent(symbol.qualifiedName(), _ -> new ArrayList<>()).add(symbol);
            if (symbol.token() != null) byToken.put(symbol.token(), symbol);
        }

        CompilationUnit unitFor(final Path path) {
            return unitsByPath.get(path);
        }

        Stmt sourceType(final Path path, final String name) {
            final var unit = unitsByPath.get(path);
            if (unit == null) return null;
            final var declarations = snapshot.units().stream()
                    .filter(candidate -> candidate.packageName().equals(unit.packageName()))
                    .flatMap(candidate -> NamespaceMembers.flatten(candidate.declarations()).stream())
                    .map(NamespaceMembers.Member::declaration)
                    .filter(SymbolIndex::isTypeDeclaration)
                    .filter(declaration -> typeName(declaration).equals(name))
                    .toList();
            if (!declarations.isEmpty()) return declarations.getFirst();

            final var visiblePackages = new LinkedHashSet<String>();
            final var resolution = snapshot.resolution().orElse(null);
            for (final var imported : unit.imports()) {
                if (imported.onDemand()) {
                    if (resolveNamespaceTarget(imported.qualifiedName(), unit,
                            snapshot.units(), resolution) == null) {
                        visiblePackages.add(imported.qualifiedName());
                    }
                } else if (imported.localName().equals(name)) {
                    final var separator = imported.qualifiedName().lastIndexOf('.');
                    if (separator >= 0) visiblePackages.add(imported.qualifiedName().substring(0, separator));
                }
            }
            visiblePackages.add("zeron.lang");
            visiblePackages.add("zeron.io");
            return snapshot.units().stream()
                    .filter(candidate -> visiblePackages.contains(candidate.packageName()))
                    .flatMap(candidate -> NamespaceMembers.flatten(candidate.declarations()).stream())
                    .map(NamespaceMembers.Member::declaration)
                    .filter(SymbolIndex::isTypeDeclaration)
                    .filter(declaration -> typeName(declaration).equals(name))
                    .filter(SymbolIndex::isPublicType)
                    .findFirst().orElse(null);
        }

        private static boolean isTypeDeclaration(final Stmt declaration) {
            return declaration instanceof Stmt.ClassDecl
                    || declaration instanceof Stmt.ExternalClass
                    || declaration instanceof Stmt.ContractDecl
                    || declaration instanceof Stmt.SumDecl
                    || declaration instanceof Stmt.ProductDecl
                    || declaration instanceof Stmt.NewtypeDecl;
        }

        private static boolean isPublicType(final Stmt declaration) {
            return switch (declaration) {
                case Stmt.ClassDecl value -> value.isPublic();
                case Stmt.ExternalClass value -> value.isPublic();
                case Stmt.ContractDecl value -> value.isPublic();
                case Stmt.SumDecl value -> value.isPublic();
                case Stmt.ProductDecl value -> value.isPublic();
                case Stmt.NewtypeDecl value -> value.isPublic();
                default -> false;
            };
        }

        private static String typeName(final Stmt declaration) {
            return simpleTypeName(switch (declaration) {
                case Stmt.ClassDecl value -> value.name().lexeme();
                case Stmt.ExternalClass value -> value.name().lexeme();
                case Stmt.ContractDecl value -> value.name().lexeme();
                case Stmt.SumDecl value -> value.name().lexeme();
                case Stmt.ProductDecl value -> value.name().lexeme();
                case Stmt.NewtypeDecl value -> value.name().lexeme();
                default -> "";
            });
        }

        private static String simpleTypeName(final String name) {
            final var separator = name.lastIndexOf('.');
            return separator < 0 ? name : name.substring(separator + 1);
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

        Symbol resolveType(final Path path, final Token token) {
            final var direct = byToken(token);
            if (direct != null && direct.kind() == CompletionItemKind.Class) return direct;
            final var sourceType = sourceType(path, token.lexeme());
            final var sourceTypeName = sourceTypeNameToken(sourceType);
            if (sourceTypeName != null) {
                final var sourceTypeSymbol = byToken(sourceTypeName);
                if (sourceTypeSymbol != null) return sourceTypeSymbol;
            }
            final var unit = unitsByPath.get(path);
            if (unit != null) {
                final var imported = unit.imports().stream()
                        .filter(entry -> !entry.onDemand() && entry.localName().equals(token.lexeme()))
                        .flatMap(entry -> byQualifiedName.getOrDefault(entry.qualifiedName(), List.of())
                                .stream())
                        .filter(symbol -> symbol.kind() == CompletionItemKind.Class)
                        .toList();
                if (imported.size() == 1) return imported.getFirst();
                final var samePackage = byName.getOrDefault(token.lexeme(), List.of()).stream()
                        .filter(symbol -> symbol.kind() == CompletionItemKind.Class)
                        .filter(symbol -> symbol.packageName().equals(unit.packageName()))
                        .toList();
                if (samePackage.size() == 1) return samePackage.getFirst();
            }
            final var candidates = visibleSymbols(path).stream()
                    .filter(symbol -> symbol.kind() == CompletionItemKind.Class)
                    .filter(symbol -> symbol.name().equals(token.lexeme()))
                    .toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        private static Token sourceTypeNameToken(final Stmt declaration) {
            if (declaration == null) return null;
            return switch (declaration) {
                case Stmt.ClassDecl value -> value.name();
                case Stmt.ContractDecl value -> value.name();
                case Stmt.SumDecl value -> value.name();
                case Stmt.ProductDecl value -> value.name();
                case Stmt.NewtypeDecl value -> value.name();
                case Stmt.ExternalClass value -> value.name();
                default -> null;
            };
        }

        List<Symbol> visibleSymbols(final Path path) {
            final var unit = unitsByPath.get(path);
            if (unit == null) return List.of();
            final var packageName = unit.packageName();
            final var explicitImports = new LinkedHashMap<String, String>();
            final var onDemand = new ArrayList<String>(List.of("zeron.lang", "zeron.io"));
            final var importedNamespaces = new LinkedHashSet<String>();
            final var resolution = snapshot.resolution().orElse(null);
            for (final var imported : unit.imports()) {
                if (imported.onDemand()) {
                    final var namespace = resolveNamespaceTarget(imported.qualifiedName(), unit,
                            snapshot.units(), resolution);
                    if (namespace == null) {
                        onDemand.add(imported.qualifiedName());
                    } else {
                        importedNamespaces.add(namespace);
                    }
                }
                else explicitImports.put(imported.localName(), imported.qualifiedName());
            }
            final var visible = new LinkedHashMap<String, Symbol>();
            for (final var candidates : byName.values()) {
                for (final var symbol : candidates) {
                    if (symbol.kind() == CompletionItemKind.Method
                            || symbol.kind() == CompletionItemKind.Property
                            || symbol.kind() == CompletionItemKind.Constructor) continue;
                    final var qualifiedNamespace = symbol.namespaceName() == null ? null
                            : qualify(symbol.packageName(), symbol.namespaceName());
                    final var importedName = explicitImports.get(symbol.name());
                    final var samePackageTopLevel = symbol.packageName().equals(packageName)
                            && symbol.namespaceName() == null;
                    final var samePackageNamespace = symbol.packageName().equals(packageName)
                            && qualifiedNamespace != null && importedNamespaces.contains(qualifiedNamespace);
                    final var importedNamespaceMember = symbol.isPublic() && qualifiedNamespace != null
                            && importedNamespaces.contains(qualifiedNamespace);
                    final var accessible = samePackageTopLevel || samePackageNamespace
                            || symbol.isPublic() && Objects.equals(importedName, symbol.qualifiedName())
                            || importedNamespaceMember
                            || symbol.namespaceName() == null && symbol.isPublic()
                                    && onDemand.stream().anyMatch(packagePrefix ->
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
                                symbol.isPublic(), imported.getKey()
                                        + symbol.completionLabel().substring(symbol.name().length()),
                                symbol.completionDetail())));
            }
            return List.copyOf(visible.values());
        }

        List<Symbol> namespaceMembers(final Path path, final String namespaceName) {
            final var unit = unitsByPath.get(path);
            if (unit == null) return List.of();
            final var packageName = unit.packageName();
            final var imported = unit.imports();
            final var resolution = snapshot.resolution().orElse(null);
            return byName.values().stream().flatMap(List::stream)
                    .filter(symbol -> Objects.equals(symbol.namespaceName(), namespaceName))
                    .filter(symbol -> symbol.packageName().equals(packageName)
                            || symbol.isPublic() && imported.stream().anyMatch(entry -> entry.onDemand()
                                    ? Objects.equals(resolveNamespaceTarget(entry.qualifiedName(), unit,
                                            snapshot.units(), resolution),
                                            qualify(symbol.packageName(), namespaceName))
                                            || resolveNamespaceTarget(entry.qualifiedName(), unit,
                                                    snapshot.units(), resolution) == null
                                                    && symbol.packageName().equals(entry.qualifiedName())
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
            final var raises = descriptor.raisedEffects().isEmpty() ? ""
                    : descriptor.raisedEffects().stream().map(TypeFormatter::format).sorted()
                            .collect(java.util.stream.Collectors.joining(", ", " raises {", "}"));
            return "fn " + name + TypeFormatter.format(descriptor) + raises;
        }

        private static String sumTypeDetail(final Stmt.SumDecl declaration) {
            final var prefix = "type " + simpleTypeName(declaration.name().lexeme())
                    + formatTypeParameters(declaration.typeParameters()) + " = ";
            final var cases = declaration.cases().stream().map(sumCase -> sumCase.name().lexeme()
                    + (sumCase.payloads().isEmpty() ? "" : sumCase.payloads().stream()
                            .map(payload -> TypeFormatter.format(payload.type()))
                            .collect(java.util.stream.Collectors.joining(", ", "(", ")"))))
                    .collect(java.util.stream.Collectors.joining(" | "));
            final var detail = prefix + cases;
            return truncateTypeDetail(detail);
        }

        private static String productTypeDetail(final Stmt.ProductDecl declaration) {
            final var members = declaration.components().stream()
                    .map(component -> component.name().lexeme() + ": "
                            + TypeFormatter.format(component.type()))
                    .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
            final var detail = "type " + simpleTypeName(declaration.name().lexeme())
                    + formatTypeParameters(declaration.typeParameters()) + " = " + members;
            return truncateTypeDetail(detail);
        }

        private static String truncateTypeDetail(final String detail) {
            return detail.length() <= TYPE_HOVER_CHARACTER_LIMIT ? detail
                    : detail.substring(0, TYPE_HOVER_CHARACTER_LIMIT - 3) + "...";
        }
    }
}
