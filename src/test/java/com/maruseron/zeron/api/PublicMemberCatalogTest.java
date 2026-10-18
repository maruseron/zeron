package com.maruseron.zeron.api;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.ast.CompilationUnit;
import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.domain.ZeronLibraryIndex;
import com.maruseron.zeron.scan.Scanner;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public final class PublicMemberCatalogTest {
    @Test
    public void sourceAndLibraryQueriesExposeEquivalentPublicSemantics() throws Exception {
        final var source = """
                package catalog;
                // 😀 offsets count UTF-16 code units.
                public effect Failure { public property message: String; }
                public contract Named<T> {
                    value(): T;
                    default count(): Int = 1;
                }
                public class Box<T> is Named<T> {
                    item: T;
                    public mut property writable: String;
                    public constructor new;
                    public value(): T = this.item;
                    public mut reset(): Unit {}
                    public identity<U: Named<Int>>(item: U): U raises Failure = item;
                    private hidden(): Unit {}
                }
                public extension Box<Int> {
                    public fn label(value: Int = 1): String = "int";
                }
                public extension Box<String> {
                    public fn label(value: String): String = value;
                }
                """;
        final CompilationUnit unit = Parser.of(Scanner.from(source, "Catalog.zn").scanTokens())
                .parseCompilationUnit("Catalog.zn");
        final var resolution = new ResolutionService("catalog").resolveUnitsWithDiagnostics(List.of(unit));
        assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
        final var sourceCatalog = PublicMemberCatalog.fromSource(resolution);
        final var index = ZeronLibraryIndex.fromCompilation(List.of(unit),
                _ -> "catalog.Extensions",
                function -> function.typeDescriptor(),
                variable -> variable.type(),
                _ -> "catalog.Values",
                "catalog.Init",
                true);
        final var indexPath = Path.of("target", "member-discovery-" + UUID.randomUUID() + ".bin");
        try {
            index.writeTo(indexPath);
            final var libraryCatalog = PublicMemberCatalog.fromLibrary(ZeronLibraryIndex.readFrom(indexPath));
            final var sourceType = sourceCatalog.findType("catalog.Box",
                    List.of(PublicMemberCatalog.TypeRef.named("Int")))
                    .orElseThrow();
            final var libraryType = libraryCatalog.findType("catalog.Box",
                    List.of(PublicMemberCatalog.TypeRef.named("Int")))
                    .orElseThrow();

            assertEquals(sourceType.directMembers().stream().map(PublicMemberCatalog.Member::declarationId).toList(),
                    libraryType.directMembers().stream().map(PublicMemberCatalog.Member::declarationId).toList());
            assertEquals(sourceType.directMembers().stream()
                            .map(member -> member.name() + ":" + member.canonicalSignature()).toList(),
                    libraryType.directMembers().stream()
                            .map(member -> member.name() + ":" + member.canonicalSignature()).toList());
            assertEquals(sourceType.contractMembers().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList(),
                    libraryType.contractMembers().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList());
            assertEquals(sourceType.extensions().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList(),
                    libraryType.extensions().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList());
            assertTrue(sourceType.directMembers().stream().noneMatch(member -> member.name().equals("hidden")));
            assertTrue(sourceType.directMembers().stream()
                    .anyMatch(member -> member.name().equals("reset") && member.mutating()));
            final var identity = sourceType.directMembers().stream()
                    .filter(member -> member.name().equals("identity")).findFirst().orElseThrow();
            assertEquals("U", identity.typeParameters().getFirst().name());
            assertEquals(List.of(PublicMemberCatalog.TypeRef.named("catalog.Failure")),
                    identity.raisedEffects());
            assertEquals("item", identity.parameters().getFirst().name().orElseThrow());
            assertEquals(PublicMemberCatalog.TypeRef.named("Int"), sourceType.appliedSubstitutions().get("T"));
            assertEquals(List.of(PublicMemberCatalog.TypeRef.named("Int")), sourceType.appliedArguments());
            final var writable = sourceType.directMembers().stream()
                    .filter(member -> member.name().equals("writable")).findFirst().orElseThrow();
            assertTrue(writable.readable());
            assertTrue(writable.writable());

            final var providedNames = sourceType.contractMembers().stream()
                    .map(PublicMemberCatalog.Member::name).toList();
            assertTrue(providedNames.contains("value"));
            assertTrue(providedNames.contains("count"));
            assertTrue(sourceType.contractMembers().stream()
                    .anyMatch(member -> member.name().equals("count")
                            && member.origin() == PublicMemberCatalog.MemberOrigin.CONTRACT_DEFAULT));
            assertTrue(sourceType.extensions().stream().allMatch(member ->
                    member.origin() == PublicMemberCatalog.MemberOrigin.EXTENSION));
            assertEquals(1, sourceType.extensions().size());
            assertEquals("catalog.Box.label", sourceType.extensions().getFirst().activationImport());
            assertEquals(0, sourceType.extensions().getFirst().minimumArity());
            assertTrue(sourceType.extensions().getFirst().parameters().getFirst().hasDefault());
            assertTrue(sourceType.extensions().getFirst().provenance().isPresent());
            assertTrue(libraryType.extensions().getFirst().provenance().isEmpty());
            assertTrue(sourceType.directMembers().stream()
                    .filter(member -> member.kind() == PublicMemberCatalog.MemberKind.CONSTRUCTOR)
                    .anyMatch(member -> member.parameters().getFirst().name().orElseThrow().equals("item")
                            && member.parameters().getFirst().type() instanceof PublicMemberCatalog.TypeVariable
                            && member.appliedParameters().getFirst().type()
                                    .equals(PublicMemberCatalog.TypeRef.named("Int"))));

            final var boxOffset = source.indexOf("Box<T>");
            final var provenance = sourceType.provenance().orElseThrow();
            final var range = provenance.range().orElseThrow();
            assertEquals(boxOffset, range.startOffset());
            assertEquals(source.substring(0, boxOffset).codePoints().filter(cp -> cp == '\n').count(),
                    range.startLine());
            assertEquals(13, range.startCharacter());

            final var reversed = new ZeronLibraryIndex(index.standardLibraryApiVersion(),
                    index.declarations().reversed());
            final var reversedCatalog = PublicMemberCatalog.fromLibrary(reversed);
            assertEquals(libraryCatalog.findType("catalog.Box",
                            List.of(PublicMemberCatalog.TypeRef.named("Int")))
                            .orElseThrow().directMembers().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList(),
                    reversedCatalog.findType("catalog.Box",
                                    List.of(PublicMemberCatalog.TypeRef.named("Int")))
                            .orElseThrow().directMembers().stream()
                            .map(PublicMemberCatalog.Member::declarationId).toList());
            assertEquals(sourceCatalog.extensions().stream().map(PublicMemberCatalog.Member::declarationId)
                            .distinct().count(),
                    sourceCatalog.extensions().size());
        } finally {
            Files.deleteIfExists(indexPath);
        }
    }

    @Test
    public void sourceCatalogOmitsPrivateTypesAndDoesNotExposeUnimplementedRequirements() {
        final var unit = Parser.of(Scanner.from("""
                package visibility;
                public contract Required { need(): Int; }
                public class Incomplete is Required {
                    public constructor new;
                }
                class Hidden { public constructor new; }
                """, "Visibility.zn").scanTokens()).parseCompilationUnit("Visibility.zn");
        final var result = new ResolutionService("visibility").resolveUnitsWithDiagnostics(List.of(unit));
        final var catalog = PublicMemberCatalog.fromSource(result);
        assertTrue(catalog.findType("visibility.Hidden").isEmpty());
        final var incomplete = catalog.findType("visibility.Incomplete").orElseThrow();
        assertTrue(incomplete.contractMembers().isEmpty());
    }

    @Test
    public void publicSumProductAndNewtypeRecordsMatchLibraryMetadata() throws Exception {
        final var unit = Parser.of(Scanner.from("""
                package shapes;
                public type Choice<T> = Some(value: T) | Empty;
                public type Pair<A, B> = (left: A, right: B);
                public newtype Meters = Float;
                """, "Shapes.zn").scanTokens()).parseCompilationUnit("Shapes.zn");
        final var resolution = new ResolutionService("shapes").resolveUnitsWithDiagnostics(List.of(unit));
        assertTrue(resolution.errors().toString(), resolution.errors().isEmpty());
        final var index = ZeronLibraryIndex.fromCompilation(List.of(unit), _ -> "shapes.Owner",
                function -> function.typeDescriptor(), variable -> variable.type(), _ -> "shapes.Values",
                "shapes.Init", true);
        final var path = Path.of("target", "member-types-" + UUID.randomUUID() + ".bin");
        try {
            index.writeTo(path);
            final var fromSource = PublicMemberCatalog.fromSource(resolution);
            final var fromLibrary = PublicMemberCatalog.fromLibrary(ZeronLibraryIndex.readFrom(path));
            for (final var name : List.of("shapes.Choice", "shapes.Pair", "shapes.Meters")) {
                final var sourceType = fromSource.findType(name).orElseThrow();
                final var libraryType = fromLibrary.findType(name).orElseThrow();
                assertEquals(sourceType.kind(), libraryType.kind());
                assertEquals(sourceType.directMembers().stream()
                                .map(PublicMemberCatalog.Member::declarationId).toList(),
                        libraryType.directMembers().stream()
                                .map(PublicMemberCatalog.Member::declarationId).toList());
            }
            assertEquals(PublicMemberCatalog.TypeKind.SUM,
                    fromSource.findType("shapes.Choice").orElseThrow().kind());
            assertEquals(PublicMemberCatalog.TypeKind.PRODUCT,
                    fromSource.findType("shapes.Pair").orElseThrow().kind());
            assertEquals(PublicMemberCatalog.TypeKind.NEWTYPE,
                    fromSource.findType("shapes.Meters").orElseThrow().kind());
            assertTrue(fromSource.findType("shapes.Choice").orElseThrow().directMembers().stream()
                    .anyMatch(member -> member.name().equals("Some")
                            && member.parameters().getFirst().name().orElseThrow().equals("value")));
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
