/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.logaperture.core;

/**
 * {@code listLoggers}' filter matching (doc/specs/level-control.md:
 * "{@code filter} is a name prefix or a pattern").
 *
 * <p><b>No {@code *}: a loose, non-dot-aware prefix</b> — {@code "com.ac"}
 * matches {@code "com.acme"}, which would be wrong for hierarchy-aware
 * descendant matching but is fine for "show me what I typed the start of."
 * Unaffected by the grammar below; this is display-filter convenience, not
 * §18.7's segment-anchored pattern.
 *
 * <p><b>{@code *} present: a segment-anchored pattern</b> (top-level §18.7,
 * issue #41 — slice 1). Splitting on {@code '.'}, at most one leading
 * segment and/or one trailing segment may be exactly {@code "*"}; every
 * other segment must be plain literal text with no {@code *} in it, and at
 * least one segment must be literal. A leading/trailing wildcard matches
 * <b>one or more</b> segments, never zero. {@code ?} is no longer a
 * wildcard at all — a filter containing it is rejected, not silently
 * treated as a literal character (that would be a silent behavior change
 * from what {@code ?} used to mean). An invalid pattern throws {@link
 * IllegalArgumentException} naming the specific problem, the same way
 * {@link org.logaperture.core.spi.UnknownHandlerException} surfaces a bad
 * handler name — left to propagate, JMX wraps it for the remote caller.
 */
final class NameFilter {

    private NameFilter() {
    }

    static boolean matches(String filter, String loggerName) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (filter.indexOf('?') >= 0) {
            throw invalid(filter, "'?' is no longer a wildcard -- patterns use '*' only");
        }
        if (filter.indexOf('*') < 0) {
            return loggerName.startsWith(filter);
        }
        return loggerName.matches(toRegex(filter));
    }

    /** Validates the segment-anchored grammar and builds the equivalent anchored regex. */
    private static String toRegex(String filter) {
        String[] segments = filter.split("\\.", -1);
        boolean hasLiteralSegment = false;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.indexOf('*') < 0) {
                hasLiteralSegment = true;
                continue;
            }
            if (!segment.equals("*")) {
                throw invalid(filter, mixedSegmentHint(filter, segments));
            }
            if (i != 0 && i != segments.length - 1) {
                throw invalid(filter, "wildcard must be its own leading or trailing segment, with at least one "
                        + "literal segment in between (e.g. 'org.apache.*' or '*.apache.writer')");
            }
        }
        if (!hasLiteralSegment) {
            throw invalid(filter, "at least one segment must be literal -- a pattern of only '*' segments matches "
                    + "everything and, as a standing rule, would never stop matching new loggers");
        }

        boolean leadingStar = segments[0].equals("*");
        boolean trailingStar = segments[segments.length - 1].equals("*");
        int start = leadingStar ? 1 : 0;
        int end = trailingStar ? segments.length - 1 : segments.length;

        StringBuilder regex = new StringBuilder("^");
        if (leadingStar) {
            regex.append("([^.]+\\.)+");
        }
        for (int i = start; i < end; i++) {
            if (i > start) {
                regex.append("\\.");
            }
            regex.append(escapeLiteral(segments[i]));
        }
        if (trailingStar) {
            regex.append("(\\.[^.]+)+");
        }
        return regex.append('$').toString();
    }

    /**
     * A friendly "try …" suggestion for the common migration case — the whole
     * filter is one dot-free segment mixing {@code *} with literal text (the
     * old {@code *word*} / {@code *word} / {@code word*} forms). Anything
     * else gets the generic hint from the caller instead.
     */
    private static String mixedSegmentHint(String filter, String[] segments) {
        if (segments.length != 1) {
            return "wildcard must be its own leading or trailing segment, with at least one literal segment "
                    + "(e.g. 'org.apache.*' or '*.apache.writer')";
        }
        String bareWord = segments[0].replace("*", "");
        return "wildcard must be its own leading or trailing segment (try '*." + bareWord + "')";
    }

    private static String escapeLiteral(String segment) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            switch (c) {
                case '\\', '^', '$', '|', '(', ')', '[', ']', '{', '}', '+' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static IllegalArgumentException invalid(String filter, String hint) {
        return new IllegalArgumentException("invalid filter '" + filter + "': " + hint);
    }
}
