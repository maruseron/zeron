package com.maruseron.zeron.compile;

import com.maruseron.zeron.analize.ResolutionService;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.DiagnosticCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Compiler {
    private Compiler() {}

    static com.maruseron.zeron.analize.ResolutionResult resolve(final CompilationContext context) {
        context.resolution = new ResolutionService("", context.javaClassPathRoots, context.functionBindings)
                .resolveUnitsWithDiagnostics(context.compilationUnits);
        context.symbols = context.resolution.globalSymbolTable();
        context.refreshResolvedDeclarations();
        return context.resolution;
    }

    static CompilationResult compile(final CompilationContext context) throws IOException {
        if (context.resolution == null || context.symbols == null) {
            throw new IllegalStateException("Compilation must be resolved before bytecode emission.");
        }
        final var diagnostics = new ArrayList<Diagnostic>();
        context.initializationPlan = new InitializationPlanner().plan(context.compilationUnits,
                context.topLevelDeclarations);
        diagnostics.addAll(context.initializationPlan.diagnostics());
        for (final var contract : context.resolution.contracts().values()) {
            if (context.metadata.metadataTypeNames().contains(contract.name().lexeme())) continue;
            if (contract.name().lexeme().equals(context.mainClassName)) {
                diagnostics.add(Diagnostic.atToken(DiagnosticCatalog.GENERATED_PROGRAM_NAME_CONFLICT,
                        contract.name(), context.sourcePath(contract.name()),
                        "Contract name conflicts with generated program class: "
                                + contract.name().lexeme()));
            }
        }
        for (final var declaration : context.resolution.classes().values()) {
            if (context.metadata.metadataTypeNames().contains(declaration.name().lexeme())) continue;
            if (declaration.name().lexeme().equals(context.mainClassName)) {
                diagnostics.add(Diagnostic.atToken(DiagnosticCatalog.GENERATED_PROGRAM_NAME_CONFLICT,
                        declaration.name(), context.sourcePath(declaration.name()),
                        "Class name conflicts with generated program class: "
                                + declaration.name().lexeme()));
            }
        }
        if (!diagnostics.isEmpty()) return new CompilationResult(diagnostics);
        BytecodeEmitter.emit(context);
        return new CompilationResult(List.of());
    }
}
