package com.maruseron.zeron.runtime.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

public final class RegexMatchResult {
    private final boolean present;
    private final String text;
    private final int start;
    private final int end;
    private final List<String> groups;
    private final List<String> names;
    private final List<String> namedValues;

    private RegexMatchResult(final boolean present, final String text, final int start,
                             final int end, final List<String> groups, final List<String> names,
                             final List<String> namedValues) {
        this.present = present;
        this.text = text;
        this.start = start;
        this.end = end;
        this.groups = groups;
        this.names = names;
        this.namedValues = namedValues;
    }

    static RegexMatchResult none() {
        return new RegexMatchResult(false, null, -1, -1, List.of(), List.of(), List.of());
    }

    static RegexMatchResult found(final Matcher matcher) {
        final var groups = new ArrayList<String>();
        for (var index = 0; index <= matcher.groupCount(); index++) {
            groups.add(matcher.group(index));
        }
        final var names = new ArrayList<String>();
        final var namedValues = new ArrayList<String>();
        for (final var name : matcher.namedGroups().keySet()) {
            names.add(name);
            namedValues.add(matcher.group(name));
        }
        return new RegexMatchResult(true, matcher.group(), matcher.start(), matcher.end(),
                List.copyOf(groups), List.copyOf(names), List.copyOf(namedValues));
    }

    public boolean isPresent() { return present; }
    public String text() { return text; }
    public int start() { return start; }
    public int end() { return end; }
    public int groupCount() { return groups.size() - 1; }
    public String group(final int index) {
        return index >= 0 && index < groups.size() ? groups.get(index) : null;
    }
    public String namedGroup(final String name) {
        final var index = names.indexOf(name);
        return index < 0 ? null : namedValues.get(index);
    }
}
