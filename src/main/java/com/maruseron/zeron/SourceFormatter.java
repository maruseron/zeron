package com.maruseron.zeron;

import com.maruseron.zeron.ast.Parser;
import com.maruseron.zeron.diagnostic.Diagnostic;
import com.maruseron.zeron.diagnostic.Severity;
import com.maruseron.zeron.scan.ScanResult;
import com.maruseron.zeron.scan.Scanner;
import com.maruseron.zeron.scan.Token;
import com.maruseron.zeron.scan.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class SourceFormatter {
    record Result(Optional<String> formattedSource, List<Diagnostic> diagnostics) {
        Result {
            Objects.requireNonNull(formattedSource);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private SourceFormatter() {}

    static Result format(final String source, final String sourcePath, final CanonicalStyle style) {
        final ScanResult scan = Scanner.from(source, sourcePath).scanWithDiagnostics();
        final var parse = Parser.of(scan).parseCompilationUnitWithDiagnostics(sourcePath);
        if (parse.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR)) {
            return new Result(Optional.empty(), parse.diagnostics());
        }

        final var tokens = sortImports(scan.tokens());
        final var formatter = new TokenFormatter(tokens, style);
        final var formatted = formatter.format();
        final var newline = newlineOf(source);
        final var withLineEndings = formatted.replace("\n", newline);
        final var reparsed = Parser.of(Scanner.from(withLineEndings, sourcePath).scanWithDiagnostics())
                .parseCompilationUnitWithDiagnostics(sourcePath);
        if (reparsed.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR)) {
            return new Result(Optional.empty(), reparsed.diagnostics());
        }
        return new Result(Optional.of(withLineEndings), parse.diagnostics());
    }

    private static String newlineOf(final String source) {
        for (var index = 0; index < source.length(); index++) {
            final var character = source.charAt(index);
            if (character == '\r') {
                return index + 1 < source.length() && source.charAt(index + 1) == '\n' ? "\r\n" : "\r";
            }
            if (character == '\n') return "\n";
        }
        return "\n";
    }

    private static List<Token> sortImports(final List<Token> input) {
        final var tokens = input.stream().filter(token -> token.type() != TokenType.EOF).toList();
        var firstImport = -1;
        for (var index = 0; index < tokens.size(); index++) {
            final var token = tokens.get(index);
            if (token.type() == TokenType.IMPORT) {
                firstImport = index;
                break;
            }
            if (!token.type().isComment() && token.type() != TokenType.PACKAGE
                    && token.type() != TokenType.IDENTIFIER && token.type() != TokenType.DOT
                    && token.type() != TokenType.SEMICOLON) {
                break;
            }
        }
        if (firstImport < 0) return input;

        final var imports = new ArrayList<ImportSegment>();
        var importIndex = firstImport;
        while (importIndex < tokens.size() && tokens.get(importIndex).type() == TokenType.IMPORT) {
            var semicolonIndex = importIndex + 1;
            while (semicolonIndex < tokens.size()
                    && tokens.get(semicolonIndex).type() != TokenType.SEMICOLON) semicolonIndex++;
            if (semicolonIndex >= tokens.size()) return input;
            if (tokens.subList(importIndex, semicolonIndex + 1).stream()
                    .anyMatch(token -> token.type().isComment())) return input;
            final var importBody = tokens.subList(importIndex + 1, semicolonIndex);
            if (importBody.getLast().type() == TokenType.STAR) return input;
            var aliasIndex = -1;
            for (var bodyIndex = 0; bodyIndex < importBody.size(); bodyIndex++) {
                if (importBody.get(bodyIndex).type() == TokenType.AS) {
                    aliasIndex = bodyIndex;
                    break;
                }
            }
            final var name = importBody.stream()
                    .takeWhile(token -> token.type() != TokenType.AS)
                    .map(Token::lexeme)
                    .reduce("", String::concat);
            final var localName = importBody.get(aliasIndex >= 0 ? aliasIndex + 1 : importBody.size() - 1)
                    .lexeme();
            imports.add(new ImportSegment(importIndex, semicolonIndex, name, localName));
            var next = semicolonIndex + 1;
            while (next < tokens.size() && tokens.get(next).type().isComment()) next++;
            if (next >= tokens.size() || tokens.get(next).type() != TokenType.IMPORT) break;
            if (next != semicolonIndex + 1) return input;
            importIndex = next;
        }
        if (imports.size() < 2) return input;
        if (imports.stream().map(ImportSegment::localName).distinct().count() != imports.size()) return input;

        final var importTokens = new ArrayList<Token>();
        imports.stream().sorted(java.util.Comparator.comparing(ImportSegment::qualifiedName))
                .forEach(segment -> importTokens.addAll(tokens.subList(segment.start(), segment.end() + 1)));

        final var output = new ArrayList<Token>();
        output.addAll(tokens.subList(0, firstImport));
        output.addAll(importTokens);
        output.addAll(tokens.subList(imports.getLast().end() + 1, tokens.size()));
        output.add(input.getLast());
        return List.copyOf(output);
    }

    private record ImportSegment(int start, int end, String qualifiedName, String localName) {}

    private record Parenthesis(boolean wrapped) {}

    private static final class TokenFormatter {
        private final List<Token> tokens;
        private final CanonicalStyle style;
        private final Set<Integer> genericAngles;
        private final StringBuilder output = new StringBuilder();
        private final Deque<Parenthesis> parentheses = new ArrayDeque<>();
        private int blockIndent;
        private int continuationIndent;
        private int braceDepth;
        private int bracketDepth;
        private int lineLength;
        private int functionBraceDepth = -1;
        private boolean functionDeclaration;
        private boolean functionEqualsContinuation;
        private boolean sumDeclaration;
        private boolean typeDeclaration;
        private boolean currentImport;
        private boolean currentPackage;
        private boolean blankLineAfterTrailingComment;
        private String currentImportRoot = "";
        private TokenType previous;
        private int previousIndex = -1;

        private TokenFormatter(final List<Token> tokens, final CanonicalStyle style) {
            this.tokens = tokens;
            this.style = style;
            genericAngles = findGenericAngles(tokens);
        }

        private String format() {
            for (var index = 0; index < tokens.size(); index++) {
                final var token = tokens.get(index);
                if (token.type() == TokenType.EOF) break;
                if (token.type().isComment()) {
                    formatComment(token);
                    continue;
                }
                formatToken(index, token);
            }
            if (lineLength > 0) newline();
            while (output.length() > 1 && output.charAt(output.length() - 1) == '\n'
                    && output.charAt(output.length() - 2) == '\n') {
                output.setLength(output.length() - 1);
            }
            if (!style.insertFinalNewline() && output.length() > 0 && output.charAt(output.length() - 1) == '\n') {
                output.setLength(output.length() - 1);
            }
            return output.toString();
        }

        private void formatComment(final Token token) {
            if (token.type() == TokenType.LINE_COMMENT) {
                if (lineLength > 0) space();
                else indent();
                append(token.lexeme());
                newline();
                if (blankLineAfterTrailingComment) {
                    blankLine();
                    blankLineAfterTrailingComment = false;
                }
            } else {
                if (lineLength > 0) space();
                else indent();
                append(token.lexeme().replace("\r\n", "\n").replace('\r', '\n'));
                if (token.lexeme().contains("\n") || token.lexeme().contains("\r")) newline();
                else space();
            }
        }

        private void formatToken(final int index, final Token token) {
            final var type = token.type();
            final var next = nextSignificantType(index + 1);
            if (lineLength == 0 && type != TokenType.RIGHT_BRACE
                    && type != TokenType.RIGHT_PAREN && type != TokenType.RIGHT_BRACKET
                    && type != TokenType.SEMICOLON) {
                indent();
            }
            if (type == TokenType.RIGHT_BRACE) {
                closeBrace(index, next);
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.RIGHT_PAREN) {
                closeParenthesis();
                append(token.lexeme());
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.SEMICOLON) {
                trimSpace();
                append(token.lexeme());
                var blankAfter = false;
                if (braceDepth == 0) {
                    if (currentImport) {
                        blankAfter = hasNextImport(index)
                                ? !sameImportRoot(index)
                                : next != TokenType.EOF;
                    } else if (currentPackage) {
                        blankAfter = true;
                    } else {
                        blankAfter = true;
                    }
                }
                if (isTrailingLineComment(index)) blankLineAfterTrailingComment = blankAfter;
                else if (blankAfter) blankLine();
                else newline();
                if (functionDeclaration && braceDepth == functionBraceDepth) {
                    functionDeclaration = false;
                    functionBraceDepth = -1;
                }
                if (functionEqualsContinuation) {
                    continuationIndent = Math.max(0, continuationIndent - 1);
                    functionEqualsContinuation = false;
                }
                currentImport = false;
                currentPackage = false;
                currentImportRoot = "";
                sumDeclaration = false;
                typeDeclaration = false;
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.LEFT_BRACE) {
                if (lineLength > 0 && needsSpace(previous, type, index)) space();
                append(token.lexeme());
                blockIndent += style.indentWidth();
                braceDepth++;
                newline();
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.LEFT_PAREN) {
                final var wrapped = shouldWrapParenthesis(index);
                if (needsSpace(previous, type, index)) space();
                append(token.lexeme());
                parentheses.push(new Parenthesis(wrapped));
                if (wrapped) {
                    continuationIndent++;
                    newline();
                }
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.COMMA) {
                trimSpace();
                append(token.lexeme());
                if (!parentheses.isEmpty() && parentheses.peek().wrapped()) newline();
                else space();
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.EQUAL && typeDeclaration) {
                sumDeclaration = nextNonCommentType(index + 1) != TokenType.LEFT_PAREN;
                if (sumDeclaration) {
                    if (needsSpace(previous, type, index)) space();
                    append(token.lexeme());
                    newline();
                    indentBy(style.indentWidth() + 2);
                    previous = type;
                    previousIndex = index;
                    return;
                }
            }
            if (type == TokenType.PIPE && sumDeclaration) {
                newline();
                indentBy(style.indentWidth());
                append("| ");
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.EQUAL && functionDeclaration
                    && flatStatementLength(index + 1) + lineLength + 1 > style.maximumLineLength()) {
                if (needsSpace(previous, type, index)) space();
                append(token.lexeme());
                newline();
                continuationIndent++;
                functionEqualsContinuation = true;
                previous = type;
                previousIndex = index;
                return;
            }
            if (type == TokenType.PACKAGE || type == TokenType.IMPORT) {
                if (type == TokenType.PACKAGE && output.length() > 0) blankLine();
                if (type == TokenType.IMPORT && output.length() > 0 && !isOnEmptyLine()) blankLine();
                currentPackage = type == TokenType.PACKAGE;
                currentImport = type == TokenType.IMPORT;
                if (currentImport) currentImportRoot = importRoot(index);
            }
            if (type == TokenType.TYPE && braceDepth == 0) {
                typeDeclaration = true;
            }
            if (type == TokenType.FN) {
                functionDeclaration = true;
                functionBraceDepth = braceDepth;
            }
            if (type == TokenType.RIGHT_BRACKET) {
                bracketDepth = Math.max(0, bracketDepth - 1);
                trimSpace();
                append(token.lexeme());
                previous = type;
                previousIndex = index;
                return;
            }
            if (isExpressionBreakOperator(type) && !genericAngles.contains(index)
                    && !(type == TokenType.MINUS && unaryPosition())
                    && !(type == TokenType.PLUS && unaryPosition())
                    && lineLength + flatStatementLength(index) >= style.maximumLineLength()) {
                newline();
                continuationIndent++;
                indent();
                continuationIndent--;
                append(token.lexeme());
                space();
                previous = type;
                previousIndex = index;
                return;
            }
            if (needsSpace(previous, type, index)) space();
            append(token.lexeme());
            if (type == TokenType.COLON) space();
            if (type == TokenType.LEFT_BRACKET) bracketDepth++;
            previous = type;
            previousIndex = index;
        }

        private void closeBrace(final int index, final TokenType next) {
            if (lineLength > 0) newline();
            blockIndent = Math.max(0, blockIndent - style.indentWidth());
            braceDepth = Math.max(0, braceDepth - 1);
            indent();
            append("}");
            if (functionDeclaration && !functionEqualsContinuation && braceDepth == functionBraceDepth) {
                functionDeclaration = false;
                functionBraceDepth = -1;
            }
            if (isTrailingLineComment(index)) {
                blankLineAfterTrailingComment = braceDepth == 0;
            } else if (next == TokenType.ELSE) space();
            else if (next == TokenType.SEMICOLON || next == TokenType.RIGHT_PAREN
                    || next == TokenType.RIGHT_BRACKET || next == TokenType.DOT
                    || next == TokenType.HUH_DOT || next == TokenType.COLON_COLON
                    || next == TokenType.COMMA) {
                if (next == TokenType.COMMA) space();
            } else if (braceDepth == 0) blankLine();
            else newline();
        }

        private void closeParenthesis() {
            if (parentheses.isEmpty()) {
                trimSpace();
                return;
            }
            final var context = parentheses.pop();
            if (context.wrapped()) {
                if (lineLength > 0) newline();
                continuationIndent = Math.max(0, continuationIndent - 1);
                indent();
            } else {
                trimSpace();
            }
        }

        private boolean shouldWrapParenthesis(final int openIndex) {
            if (functionDeclaration && braceDepth == functionBraceDepth) {
                return functionHeaderLength(openIndex) + lineLength > style.maximumLineLength();
            }
            return delimitedLength(openIndex, TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN)
                    + lineLength > style.maximumLineLength();
        }

        private int functionHeaderLength(final int openIndex) {
            var length = 0;
            for (var index = functionStart(openIndex); index < tokens.size(); index++) {
                final var type = tokens.get(index).type();
                if (type == TokenType.EQUAL || type == TokenType.LEFT_BRACE
                        || (type == TokenType.SEMICOLON && index > openIndex)) break;
                if (!tokens.get(index).type().isComment()) length += tokens.get(index).lexeme().length() + 1;
            }
            return length;
        }

        private int functionStart(final int openIndex) {
            for (var index = openIndex; index >= 0; index--) {
                if (tokens.get(index).type() == TokenType.FN) return index;
            }
            return openIndex;
        }

        private int delimitedLength(final int openIndex,
                                    final TokenType opening,
                                    final TokenType closing) {
            var depth = 0;
            var length = 0;
            for (var index = openIndex; index < tokens.size(); index++) {
                final var token = tokens.get(index);
                if (token.type().isComment()) continue;
                if (token.type() == opening) depth++;
                if (token.type() == closing) {
                    depth--;
                    if (depth == 0) return length + token.lexeme().length();
                }
                length += token.lexeme().length() + 1;
            }
            return length;
        }

        private int flatStatementLength(final int start) {
            var braces = braceDepth;
            var parentheses = this.parentheses.size();
            var brackets = bracketDepth;
            var length = 0;
            for (var index = start; index < tokens.size(); index++) {
                final var token = tokens.get(index);
                if (token.type() == TokenType.EOF) break;
                if (token.type() == TokenType.SEMICOLON && braces == braceDepth
                        && parentheses == this.parentheses.size() && brackets == bracketDepth) break;
                if ((token.type() == TokenType.RIGHT_PAREN && parentheses == this.parentheses.size()
                        && this.parentheses.size() > 0)
                        || (token.type() == TokenType.RIGHT_BRACKET && brackets == bracketDepth
                        && bracketDepth > 0)) break;
                if (token.type().isComment()) continue;
                if (token.type() == TokenType.LEFT_BRACE) braces++;
                if (token.type() == TokenType.RIGHT_BRACE) braces--;
                if (token.type() == TokenType.LEFT_PAREN) parentheses++;
                if (token.type() == TokenType.RIGHT_PAREN) parentheses--;
                if (token.type() == TokenType.LEFT_BRACKET) brackets++;
                if (token.type() == TokenType.RIGHT_BRACKET) brackets--;
                length += token.lexeme().length() + 1;
            }
            return length;
        }

        private TokenType nextSignificantType(final int start) {
            for (var index = start; index < tokens.size(); index++) {
                if (!tokens.get(index).type().isComment()) return tokens.get(index).type();
            }
            return TokenType.EOF;
        }

        private TokenType nextNonCommentType(final int start) {
            return nextSignificantType(start);
        }

        private boolean hasNextImport(final int index) {
            for (var next = index + 1; next < tokens.size(); next++) {
                if (tokens.get(next).type().isComment()) continue;
                return tokens.get(next).type() == TokenType.IMPORT;
            }
            return false;
        }

        private boolean isTrailingLineComment(final int index) {
            final var next = index + 1;
            return next < tokens.size()
                    && tokens.get(next).type() == TokenType.LINE_COMMENT
                    && tokens.get(next).span().start().line() == tokens.get(index).span().end().line();
        }

        private boolean sameImportRoot(final int index) {
            for (var next = index + 1; next < tokens.size(); next++) {
                if (tokens.get(next).type().isComment()) continue;
                if (tokens.get(next).type() == TokenType.IMPORT)
                    return currentImportRoot.equals(importRoot(next));
                return true;
            }
            return true;
        }

        private String importRoot(final int importIndex) {
            for (var index = importIndex + 1; index < tokens.size(); index++) {
                final var token = tokens.get(index);
                if (token.type().isComment()) continue;
                return token.type() == TokenType.IDENTIFIER
                        ? token.lexeme()
                        : "";
            }
            return "";
        }

        private boolean needsSpace(final TokenType before, final TokenType after, final int currentIndex) {
            if (before == null || before == TokenType.LEFT_PAREN || before == TokenType.LEFT_BRACKET
                    || before == TokenType.DOT || before == TokenType.HUH_DOT
                    || before == TokenType.COLON_COLON || before == TokenType.BANG
                    || before == TokenType.TILDE || before == TokenType.DOT_DOT
                    || before == TokenType.ELLIPSIS) return false;
            if (after == TokenType.RIGHT_PAREN || after == TokenType.RIGHT_BRACKET
                    || after == TokenType.COMMA || after == TokenType.SEMICOLON
                    || after == TokenType.DOT || after == TokenType.HUH_DOT
                    || after == TokenType.COLON_COLON || after == TokenType.DOT_DOT
                    || after == TokenType.ELLIPSIS) return false;
            if (after == TokenType.LEFT_PAREN) {
                return before == TokenType.IF || before == TokenType.FOR || before == TokenType.WHILE
                        || before == TokenType.UNTIL || before == TokenType.MATCH || before == TokenType.USING;
            }
            if (after == TokenType.LEFT_BRACE) return true;
            if (before == TokenType.COLON) return true;
            if (after == TokenType.COLON) return false;
            if (after == TokenType.MINUS && unaryPosition()) return wordOrLiteral(before);
            if (after == TokenType.BANG || after == TokenType.TILDE) return false;
            if (after == TokenType.LEFT_BRACKET) return false;
            if (before == TokenType.EQUAL || after == TokenType.EQUAL) return true;
            if (before == TokenType.AMPERSAND && isReferencePrefix(previousIndex)) return false;
            if (isBinaryOperator(before) || isBinaryOperator(after)) {
                return !genericAngles.contains(currentIndex) && !genericAngles.contains(previousIndex);
            }
            return true;
        }

        private boolean isReferencePrefix(final int tokenIndex) {
            var previousTokenIndex = tokenIndex - 1;
            while (previousTokenIndex >= 0 && tokens.get(previousTokenIndex).type().isComment()) {
                previousTokenIndex--;
            }
            if (previousTokenIndex < 0) return true;
            return switch (tokens.get(previousTokenIndex).type()) {
                case COLON, COMMA, LEFT_PAREN, EQUAL, ARROW -> true;
                default -> false;
            };
        }

        private boolean isBinaryOperator(final TokenType type) {
            return switch (type) {
                case EQUAL, EQUAL_EQUAL, EQUAL_EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                        PLUS, PLUS_EQUAL, MINUS_EQUAL, STAR, STAR_EQUAL, SLASH, SLASH_EQUAL, PERCENT, PERCENT_EQUAL,
                        AMPERSAND, AMPERSAND_EQUAL, PIPE, PIPE_EQUAL, CARET, CARET_EQUAL, SHIFT_LEFT,
                        SHIFT_LEFT_EQUAL, SHIFT_RIGHT, SHIFT_RIGHT_EQUAL, UNSIGNED_SHIFT_RIGHT,
                        UNSIGNED_SHIFT_RIGHT_EQUAL, ARROW, HUH_HUH, HUH_HUH_EQUAL, AND, OR, IN, IS, AS,
                        RAISES, THROWS -> true;
                default -> false;
            };
        }

        private boolean isExpressionBreakOperator(final TokenType type) {
            return switch (type) {
                case EQUAL_EQUAL, EQUAL_EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                        PLUS, MINUS, STAR, SLASH, PERCENT, HUH_HUH, AND, OR, IN, IS -> true;
                default -> false;
            };
        }

        private boolean unaryPosition() {
            return previous == null || previous == TokenType.LEFT_PAREN || previous == TokenType.LEFT_BRACKET
                    || previous == TokenType.COMMA || previous == TokenType.COLON || previous == TokenType.EQUAL
                    || isBinaryOperator(previous);
        }

        private boolean wordOrLiteral(final TokenType type) {
            return type == TokenType.IDENTIFIER || type == TokenType.INT || type == TokenType.LONG
                    || type == TokenType.DOUBLE || type == TokenType.STRING || type == TokenType.CHAR
                    || type.name().matches("[A-Z]+");
        }

        private void indentBy(final int spaces) {
            if (lineLength != 0) return;
            append(" ".repeat(Math.max(0, spaces)));
        }

        private void indent() {
            if (lineLength == 0) append(" ".repeat(blockIndent + continuationIndent * style.indentWidth()));
        }

        private void append(final String text) {
            output.append(text);
            final var lastNewline = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
            lineLength = lastNewline < 0 ? lineLength + text.length() : text.length() - lastNewline - 1;
        }

        private void space() {
            if (lineLength == 0) indent();
            if (lineLength > 0 && output.charAt(output.length() - 1) != ' '
                    && output.charAt(output.length() - 1) != '\n') append(" ");
        }

        private void newline() {
            trimSpace();
            if (output.length() == 0 || output.charAt(output.length() - 1) != '\n') output.append('\n');
            lineLength = 0;
        }

        private void blankLine() {
            newline();
            if (output.length() > 1 && output.charAt(output.length() - 2) != '\n') {
                output.append('\n');
            }
        }

        private void trimSpace() {
            while (output.length() > 0 && output.charAt(output.length() - 1) == ' ') {
                output.setLength(output.length() - 1);
                lineLength--;
            }
        }

        private boolean isOnEmptyLine() {
            return lineLength == 0;
        }

        private static Set<Integer> findGenericAngles(final List<Token> tokens) {
            final var result = new HashSet<Integer>();
            for (var open = 0; open < tokens.size(); open++) {
                if (tokens.get(open).type() != TokenType.LESS || open == 0
                        || !canStartGenericArguments(tokens.get(open - 1).type())) continue;
                var depth = 0;
                for (var close = open; close < tokens.size(); close++) {
                    final var type = tokens.get(close).type();
                    if (type == TokenType.LESS) depth++;
                    else if (type == TokenType.GREATER && --depth == 0) {
                        final var next = nextNonComment(tokens, close + 1);
                        if (canFollowGenericArguments(next)) {
                            result.add(open);
                            result.add(close);
                        }
                        break;
                    } else if (type == TokenType.SEMICOLON || type == TokenType.EOF
                            || type == TokenType.LEFT_BRACE || type == TokenType.RIGHT_BRACE) {
                        break;
                    }
                }
            }
            return Set.copyOf(result);
        }

        private static boolean canStartGenericArguments(final TokenType type) {
            return type == TokenType.IDENTIFIER || type == TokenType.GREATER
                    || type == TokenType.RIGHT_BRACKET;
        }

        private static boolean canFollowGenericArguments(final TokenType type) {
            return type == TokenType.LEFT_PAREN || type == TokenType.LEFT_BRACE || type == TokenType.DOT
                    || type == TokenType.COLON_COLON || type == TokenType.COMMA || type == TokenType.RIGHT_PAREN
                    || type == TokenType.RIGHT_BRACKET || type == TokenType.SEMICOLON || type == TokenType.COLON
                    || type == TokenType.EQUAL || type == TokenType.ARROW || type == TokenType.HUH
                    || type == TokenType.IS || type == TokenType.EOF;
        }

        private static TokenType nextNonComment(final List<Token> tokens, final int start) {
            for (var index = start; index < tokens.size(); index++) {
                if (!tokens.get(index).type().isComment()) return tokens.get(index).type();
            }
            return TokenType.EOF;
        }
    }
}
