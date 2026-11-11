package zeron.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interns the canonical textual form of an applied type into an {@link AppliedTypeDescriptor}.
 *
 * <p>Compiler-generated call sites load a string constant and invoke {@link #lookup(String)}.
 * A monomorphic site therefore reaches a cached descriptor without allocating; the parser runs
 * only on the first lookup of a canonical form. The textual form is internal ABI and is written
 * by the compiler, never by source programs.
 */
public final class AppliedTypeDescriptors {
    private static final ConcurrentHashMap<String, AppliedTypeDescriptor> CACHE =
            new ConcurrentHashMap<>();

    private AppliedTypeDescriptors() {
    }

    public static AppliedTypeDescriptor lookup(final String canonical) {
        final var cached = CACHE.get(canonical);
        return cached != null ? cached : CACHE.computeIfAbsent(canonical, AppliedTypeDescriptors::parse);
    }

    static AppliedTypeDescriptor parse(final String canonical) {
        final var position = new int[]{0};
        final var descriptor = parseType(canonical, position);
        if (position[0] != canonical.length()) {
            throw new IllegalArgumentException("Trailing text in applied type descriptor: " + canonical);
        }
        return descriptor;
    }

    private static AppliedTypeDescriptor parseType(final String text, final int[] position) {
        final AppliedTypeDescriptor descriptor;
        if (position[0] < text.length() && text.charAt(position[0]) == '[') {
            position[0]++;
            descriptor = AppliedTypeDescriptor.arrayOf(parseType(text, position));
            expect(text, position, ']');
        } else {
            final var start = position[0];
            while (position[0] < text.length() && isOwnerCharacter(text.charAt(position[0]))) {
                position[0]++;
            }
            if (start == position[0]) {
                throw new IllegalArgumentException("Expected a type owner in: " + text);
            }
            final var owner = text.substring(start, position[0]);
            final var arguments = new ArrayList<AppliedTypeDescriptor>();
            if (position[0] < text.length() && text.charAt(position[0]) == '<') {
                position[0]++;
                arguments.add(parseType(text, position));
                while (position[0] < text.length() && text.charAt(position[0]) == ',') {
                    position[0]++;
                    arguments.add(parseType(text, position));
                }
                expect(text, position, '>');
            }
            descriptor = AppliedTypeDescriptor.of(owner, List.copyOf(arguments));
        }

        var result = descriptor;
        while (position[0] < text.length()) {
            if (text.startsWith("[]", position[0])) {
                position[0] += 2;
                result = AppliedTypeDescriptor.arrayOf(result);
            } else if (text.charAt(position[0]) == '?') {
                position[0]++;
                result = result.nullable();
            } else if (text.charAt(position[0]) == '!') {
                position[0]++;
                result = result.mutable();
            } else {
                break;
            }
        }
        return result;
    }

    private static boolean isOwnerCharacter(final char character) {
        return character != '<' && character != '>' && character != ',' && character != '['
                && character != ']' && character != '?' && character != '!';
    }

    private static void expect(final String text, final int[] position, final char expected) {
        if (position[0] >= text.length() || text.charAt(position[0]) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' in: " + text);
        }
        position[0]++;
    }
}
