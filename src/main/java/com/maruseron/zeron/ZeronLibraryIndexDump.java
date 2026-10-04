package com.maruseron.zeron;

import com.maruseron.zeron.domain.ArrayDescriptor;
import com.maruseron.zeron.domain.AnyDescriptor;
import com.maruseron.zeron.domain.BooleanDescriptor;
import com.maruseron.zeron.domain.FloatDescriptor;
import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.GenericDescriptor;
import com.maruseron.zeron.domain.InferDescriptor;
import com.maruseron.zeron.domain.IntDescriptor;
import com.maruseron.zeron.domain.NeverDescriptor;
import com.maruseron.zeron.domain.NominalDescriptor;
import com.maruseron.zeron.domain.NullDescriptor;
import com.maruseron.zeron.domain.NullableDescriptor;
import com.maruseron.zeron.domain.ReferenceDescriptor;
import com.maruseron.zeron.domain.StringDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.domain.TypeParameterDescriptor;
import com.maruseron.zeron.domain.UnitDescriptor;
import com.maruseron.zeron.domain.ZeronLibraryIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ZeronLibraryIndexDump {
    private ZeronLibraryIndexDump() {}

    public static void main(final String... args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: ZeronLibraryIndexDump <api-v4.bin|class-directory>");
            return;
        }
        final var path = Path.of(args[0]);
        final var index = Files.isDirectory(path)
                ? ZeronLibraryIndex.readFromDirectory(path)
                : ZeronLibraryIndex.readFrom(path);
        print(index);
    }

    private static void print(final ZeronLibraryIndex index) {
        System.out.println("Zeron public API index");
        System.out.println("  Format version: " + ZeronLibraryIndex.VERSION);
        System.out.println("  Standard-library API version: " + index.standardLibraryApiVersion());
        System.out.println("  Public declarations: " + index.declarations().size());

        for (final var declaration : index.declarations()) {
            System.out.println();
            switch (declaration) {
                case ZeronLibraryIndex.FunctionExport function -> {
                    System.out.println("function " + function.qualifiedName()
                            + formatTypeParameters(function.signature().typeParameters())
                            + formatParameters(function.signature()) + ": "
                            + formatType(function.signature().returnType()));
                    System.out.println("  JVM owner: " + function.jvmOwner());
                }
                case ZeronLibraryIndex.ClassExport classExport -> {
                    System.out.println("class " + classExport.qualifiedName()
                            + formatTypeParameters(classExport.typeParameters()));
                    if (!classExport.contracts().isEmpty()) {
                        final var contracts = classExport.contracts().stream()
                                .map(use -> use.qualifiedName() + formatTypeArguments(use.typeArguments()))
                                .collect(Collectors.joining(", "));
                        System.out.println("  conforms to: " + contracts);
                    }
                    if (classExport.canonicalConstructorPublic()) {
                        System.out.println("  public constructor new("
                                + classExport.canonicalConstructorParameters().stream()
                                        .map(ZeronLibraryIndexDump::formatType)
                                        .collect(Collectors.joining(", "))
                                + ")");
                    } else {
                        System.out.println("  private constructor new");
                    }
                    for (final var constructor : classExport.namedConstructors()) {
                        System.out.println("  public constructor " + constructor.name()
                                + formatParameters(constructor.signature()) + ": "
                                + formatType(constructor.signature().returnType()));
                    }
                    for (final var method : classExport.methods()) {
                        System.out.println("  public " + (method.mutating() ? "mut " : "") + method.name()
                                + formatParameters(method.signature()) + ": "
                                + formatType(method.signature().returnType()));
                    }
                    for (final var property : classExport.properties()) {
                        System.out.println("  public " + (property.mutating() ? "mut " : "")
                                + property.name() + ": " + formatType(property.type()));
                    }
                }
                case ZeronLibraryIndex.ContractExport contract -> {
                    System.out.println("contract " + contract.qualifiedName()
                            + formatTypeParameters(contract.typeParameters()));
                    for (final var method : contract.methods()) {
                        System.out.println("  " + (method.defaultMethod() ? "default " : "")
                                + (method.mutating() ? "mut " : "") + method.name()
                                + formatParameters(method.signature()) + ": "
                                + formatType(method.signature().returnType()));
                    }
                    for (final var property : contract.properties()) {
                        System.out.println("  " + (property.mutating() ? "mut " : "")
                                + property.name() + ": " + formatType(property.type()));
                    }
                }
            }
        }
    }

    private static String formatTypeParameters(final List<TypeParameterDescriptor> parameters) {
        if (parameters.isEmpty()) return "";
        return parameters.stream()
                .map(parameter -> parameter.name() + (parameter.bound() == null
                        ? ""
                        : ": " + formatType(parameter.bound())))
                .collect(Collectors.joining(", ", "<", ">"));
    }

    private static String formatTypeArguments(final List<TypeDescriptor> arguments) {
        if (arguments.isEmpty()) return "";
        return arguments.stream().map(ZeronLibraryIndexDump::formatType)
                .collect(Collectors.joining(", ", "<", ">"));
    }

    private static String formatParameters(final FunctionDescriptor function) {
        return function.parameters().stream()
                .map(ZeronLibraryIndexDump::formatType)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String formatType(final TypeDescriptor type) {
        return switch (type) {
            case NeverDescriptor _ -> "Never";
            case AnyDescriptor _ -> "Any";
            case UnitDescriptor _ -> "Unit";
            case IntDescriptor _ -> "Int";
            case FloatDescriptor _ -> "Float";
            case BooleanDescriptor _ -> "Boolean";
            case StringDescriptor _ -> "String";
            case NullDescriptor _ -> "null";
            case InferDescriptor _ -> "<inferred>";
            case NominalDescriptor nominal -> nominal.name();
            case TypeParameterDescriptor parameter -> parameter.name();
            case ArrayDescriptor array -> "Array<" + formatType(array.elementType()) + ">";
            case GenericDescriptor generic -> generic.baseType().name()
                    + formatTypeArguments(generic.typeParameters());
            case NullableDescriptor nullable -> formatType(nullable.baseType()) + "?";
            case ReferenceDescriptor reference -> "&" + formatType(reference.baseType());
            case FunctionDescriptor function -> formatTypeParameters(function.typeParameters())
                    + function.parameters().stream().map(ZeronLibraryIndexDump::formatType)
                            .collect(Collectors.joining(", ", "(", ") -> "))
                    + formatType(function.returnType());
        };
    }
}
