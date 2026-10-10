package com.maruseron.zeron.diagnostic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DiagnosticCatalog {
    public record Entry(DiagnosticCode code, String title, Severity severity, String explanationResource) {
        public Entry {
            Objects.requireNonNull(code);
            Objects.requireNonNull(title);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(explanationResource);
        }
    }

    public static final Entry UNEXPECTED_CHARACTER =
            entry("ZR1001", "Unexpected character");
    public static final Entry UNTERMINATED_STRING =
            entry("ZR1002", "Unterminated string literal");

    public static final Entry EXPECTED_SYNTAX =
            entry("ZR1101", "Expected syntax");
    public static final Entry INVALID_DECLARATION =
            entry("ZR1102", "Invalid declaration");
    public static final Entry INVALID_VISIBILITY =
            entry("ZR1103", "Invalid visibility");
    public static final Entry INVALID_EXTERNAL_FUNCTION_DECLARATION =
            entry("ZR1104", "Invalid external function declaration");
    public static final Entry INVALID_GENERIC_DECLARATION =
            entry("ZR1105", "Invalid generic declaration");
    public static final Entry TOO_MANY_PARAMETERS_OR_ARGUMENTS =
            entry("ZR1106", "Too many parameters or arguments");
    public static final Entry DUPLICATE_DECLARATION_COMPONENT =
            entry("ZR1107", "Duplicate declaration component");
    public static final Entry INVALID_DECLARATION_STRUCTURE =
            entry("ZR1108", "Invalid declaration structure");
    public static final Entry INVALID_PROPERTY_DECLARATION =
            entry("ZR1109", "Invalid property declaration");
    public static final Entry INVALID_SEALED_CONTRACT_DECLARATION =
            entry("ZR1110", "Invalid sealed contract declaration");
    public static final Entry INVALID_ARRAY_TYPE_OR_LITERAL =
            entry("ZR1111", "Invalid array type or literal");
    public static final Entry CONTROL_STATEMENT_OUTSIDE_CONTEXT =
            entry("ZR1112", "Control statement outside its context");
    public static final Entry INVALID_ASSIGNMENT_FORM =
            entry("ZR1113", "Invalid assignment form");
    public static final Entry INVALID_MATCH_EXPRESSION =
            entry("ZR1114", "Invalid match expression");
    public static final Entry INVALID_PIPELINE_OPERATION = 
            entry("ZR1115", "Invalid for expression operation");
    public static final Entry INVALID_CONTRACT_MEMBER =
            entry("ZR1116", "Invalid contract member declaration");

    public static final Entry NAME_NOT_FOUND =
            entry("ZR2001", "Name not found");
    public static final Entry DUPLICATE_OR_CONFLICTING_NAME =
            entry("ZR2002", "Duplicate or conflicting name");
    public static final Entry INVALID_OR_CONFLICTING_IMPORT =
            entry("ZR2003", "Invalid or conflicting import");
    public static final Entry INACCESSIBLE_DECLARATION =
            entry("ZR2004", "Inaccessible declaration");
    public static final Entry INVALID_GENERIC_USE_OR_INFERENCE =
            entry("ZR2005", "Invalid generic use or inference");
    public static final Entry ARGUMENT_OR_PARAMETER_COUNT_MISMATCH =
            entry("ZR2006", "Argument or parameter count mismatch");
    public static final Entry UNSUPPORTED_OR_INVALID_JAVA_INTEROP =
            entry("ZR2007", "Unsupported or invalid Java interop");
    public static final Entry INVALID_SEALED_CONTRACT_OR_MATCH =
            entry("ZR2008", "Invalid sealed contract or match");
    public static final Entry INVALID_MEMBER_ACCESS =
            entry("ZR2009", "Invalid member access");
    public static final Entry INVALID_OPERATOR_CAST_OR_TYPE_TEST =
            entry("ZR2010", "Invalid operator, cast, or type test");
    public static final Entry INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW =
            entry("ZR2011", "Invalid control flow or initialization flow");
    public static final Entry MUTATION_NOT_PERMITTED =
            entry("ZR2012", "Mutation is not permitted");
    public static final Entry NULLABLE_VALUE_REQUIRES_HANDLING =
            entry("ZR2013", "Nullable value requires handling");
    public static final Entry TYPE_MISMATCH_OR_FAILED_INFERENCE =
            entry("ZR2014", "Type mismatch or failed inference");
    public static final Entry INVALID_DECLARATION_OR_PROGRAM_STRUCTURE =
            entry("ZR2015", "Invalid declaration or program structure");
    public static final Entry TOP_LEVEL_INITIALIZATION_CYCLE =
            entry("ZR2016", "Top-level initialization cycle");

    public static final Entry GENERATED_PROGRAM_NAME_CONFLICT =
            entry("ZR3001", "Generated program name conflicts with a declared type");
    public static final Entry PACKAGE_SOURCE_DIRECTORY_MISMATCH =
            entry("ZR4001", "Package does not match source directory");

    private static final List<Entry> ENTRIES = List.of(
            UNEXPECTED_CHARACTER,
            UNTERMINATED_STRING,
            EXPECTED_SYNTAX,
            INVALID_DECLARATION,
            INVALID_VISIBILITY,
            INVALID_EXTERNAL_FUNCTION_DECLARATION,
            INVALID_GENERIC_DECLARATION,
            TOO_MANY_PARAMETERS_OR_ARGUMENTS,
            DUPLICATE_DECLARATION_COMPONENT,
            INVALID_DECLARATION_STRUCTURE,
            INVALID_PROPERTY_DECLARATION,
            INVALID_SEALED_CONTRACT_DECLARATION,
            INVALID_ARRAY_TYPE_OR_LITERAL,
            CONTROL_STATEMENT_OUTSIDE_CONTEXT,
            INVALID_ASSIGNMENT_FORM,
            INVALID_MATCH_EXPRESSION,
            NAME_NOT_FOUND,
            DUPLICATE_OR_CONFLICTING_NAME,
            INVALID_OR_CONFLICTING_IMPORT,
            INACCESSIBLE_DECLARATION,
            INVALID_GENERIC_USE_OR_INFERENCE,
            ARGUMENT_OR_PARAMETER_COUNT_MISMATCH,
            UNSUPPORTED_OR_INVALID_JAVA_INTEROP,
            INVALID_SEALED_CONTRACT_OR_MATCH,
            INVALID_MEMBER_ACCESS,
            INVALID_OPERATOR_CAST_OR_TYPE_TEST,
            INVALID_CONTROL_FLOW_OR_INITIALIZATION_FLOW,
            MUTATION_NOT_PERMITTED,
            NULLABLE_VALUE_REQUIRES_HANDLING,
            TYPE_MISMATCH_OR_FAILED_INFERENCE,
            INVALID_DECLARATION_OR_PROGRAM_STRUCTURE,
            TOP_LEVEL_INITIALIZATION_CYCLE,
            GENERATED_PROGRAM_NAME_CONFLICT,
            PACKAGE_SOURCE_DIRECTORY_MISMATCH);

    private static final Map<DiagnosticCode, Entry> ENTRIES_BY_CODE;

    static {
        final var entriesByCode = new LinkedHashMap<DiagnosticCode, Entry>();
        for (final var entry : ENTRIES) {
            if (entriesByCode.putIfAbsent(entry.code(), entry) != null) {
                throw new ExceptionInInitializerError("Duplicate diagnostic code: " + entry.code());
            }
        }
        ENTRIES_BY_CODE = Collections.unmodifiableMap(entriesByCode);
    }

    private DiagnosticCatalog() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> find(final DiagnosticCode code) {
        return Optional.ofNullable(ENTRIES_BY_CODE.get(Objects.requireNonNull(code)));
    }

    private static Entry entry(final String code, final String title) {
        final var diagnosticCode = new DiagnosticCode(code);
        return new Entry(diagnosticCode, title, Severity.ERROR,
                "errorcode/" + diagnosticCode + ".md");
    }
}
