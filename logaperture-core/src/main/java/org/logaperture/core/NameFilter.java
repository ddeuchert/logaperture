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

import java.util.function.Predicate;
import java.util.regex.Pattern;

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
 * least one segment must be literal. A leading/trailing wildcard matches the
 * named logger itself plus <b>zero or more</b> further segments — {@code
 * "org.apache.*"} matches {@code "org.apache"} and every descendant of it, a
 * true superset of the retired {@code includeChildren} option's "the named
 * logger and its descendants" (doc/specs/level-control.md). {@code ?} is no longer a
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
        return compile(filter).test(loggerName);
    }

    /**
     * Whether {@code target} is a pattern rather than an exact logger name --
     * one {@code '*'} anywhere in it. The single check every caller on the
     * {@code setLevel}/{@code resetLevel} path branches on before deciding
     * whether it's looking at a one-time selection or a plain override;
     * pulled out here so that branch is spelled once instead of re-typed at
     * each call site.
     */
    static boolean isPattern(String target) {
        return target.indexOf('*') >= 0;
    }

    /**
     * Whether {@code target}'s trailing segment is {@code *} — the check
     * {@code setLevel} rejects on outright (doc/specs/
     * pattern-selection-semantics.md, Decision #5), since every descendant
     * already inherits a set ancestor's level from the framework itself. A
     * cheap, purely syntactic check, deliberately not full grammar
     * validation — the rest of the pattern's grammar is still validated
     * later, by whichever path actually resolves matches ({@link #compile}/
     * {@code matchesFor}). {@code target.endsWith(".*")} alone already
     * implies {@link #isPattern} — no separate call needed (a code-review
     * finding: the redundant conjunct cost every {@code setLevel}/{@code
     * checkSetLevelPermitted} call site a second, needless scan of {@code
     * target}).
     */
    static boolean isTrailingWildcard(String target) {
        return target.endsWith(".*");
    }

    /**
     * Validates {@code filter} against the grammar once and returns a reusable
     * matcher — the seam a caller filtering many names against the same filter
     * (e.g. {@code listLoggers}) should use instead of {@link #matches}, so
     * validation and regex compilation happen once rather than once per name.
     */
    static Predicate<String> compile(String filter) {
        if (filter == null || filter.isEmpty()) {
            return name -> true;
        }
        if (filter.indexOf('?') >= 0) {
            throw invalid(filter, "'?' is no longer a wildcard -- patterns use '*' only");
        }
        if (filter.indexOf('*') < 0) {
            return name -> name.startsWith(filter);
        }
        Pattern pattern = Pattern.compile(toRegex(filter));
        return name -> pattern.matcher(name).matches();
    }

    /** Validates the segment-anchored grammar and builds the equivalent anchored regex. */
    private static String toRegex(String filter) {
        String[] segments = filter.split("\\.", -1);
        boolean hasLiteralSegment = false;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw invalid(filter, "each segment must be non-empty -- check for '..', a leading '.', or a "
                        + "trailing '.' in the pattern");
            }
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
            throw invalid(filter, "at least one segment must be literal -- a pattern of only '*' segments would "
                    + "match everything known right now, which is never a useful selection");
        }

        boolean leadingStar = segments[0].equals("*");
        boolean trailingStar = segments[segments.length - 1].equals("*");
        int start = leadingStar ? 1 : 0;
        int end = trailingStar ? segments.length - 1 : segments.length;

        StringBuilder regex = new StringBuilder("^");
        if (leadingStar) {
            regex.append("([^.]+\\.)*");
        }
        for (int i = start; i < end; i++) {
            if (i > start) {
                regex.append("\\.");
            }
            regex.append(escapeLiteral(segments[i]));
        }
        if (trailingStar) {
            regex.append("(\\.[^.]+)*");
        }
        return regex.append('$').toString();
    }

    /**
     * A friendly "try …" suggestion for the common migration case — the whole
     * filter is one dot-free segment mixing {@code *} with literal text (the
     * old {@code *word*} / {@code *word} / {@code word*} forms). The direction
     * of the star matters: a trailing star ({@code word*}, "starts with")
     * anchors to a leading literal segment ({@code word.*}), a leading star
     * ({@code *word}, "ends with") anchors to a trailing one ({@code *.word})
     * — suggesting the wrong direction silently swaps the migrated filter's
     * meaning. A star on both sides ({@code *word*}, "contains") has no
     * anchored equivalent at all, so it gets an honest "no equivalent" note
     * instead of a guess. Anything messier (a star in the middle, more than
     * two stars) gets the generic hint from the caller instead.
     */
    private static String mixedSegmentHint(String filter, String[] segments) {
        String generic = "wildcard must be its own leading or trailing segment, with at least one literal segment "
                + "(e.g. 'org.apache.*' or '*.apache.writer')";
        if (segments.length != 1) {
            return generic;
        }
        String segment = segments[0];
        long starCount = segment.chars().filter(c -> c == '*').count();
        boolean leadingStar = segment.startsWith("*");
        boolean trailingStar = segment.length() > 1 && segment.endsWith("*");
        String interior = segment;
        if (leadingStar) {
            interior = interior.substring(1);
        }
        if (trailingStar && !interior.isEmpty()) {
            interior = interior.substring(0, interior.length() - 1);
        }
        boolean interiorIsPlain = !interior.isEmpty() && interior.indexOf('*') < 0;
        if (!interiorIsPlain) {
            return generic;
        }
        if (starCount == 1 && leadingStar) {
            return "wildcard must be its own leading or trailing segment (try '*." + interior + "')";
        }
        if (starCount == 1 && trailingStar) {
            return "wildcard must be its own leading or trailing segment (try '" + interior + ".*')";
        }
        if (starCount == 2 && leadingStar && trailingStar) {
            return "wildcard must be its own leading or trailing segment -- a 'contains' match like this has no "
                    + "anchored equivalent; the closest are '*." + interior + "' (segment ending in '" + interior
                    + "') or '" + interior + ".*' (segment starting with '" + interior + "'), or drop the '*'s for "
                    + "a literal prefix filter";
        }
        return generic;
    }

    private static String escapeLiteral(String segment) {
        return Pattern.quote(segment);
    }

    private static IllegalArgumentException invalid(String filter, String hint) {
        return new IllegalArgumentException("invalid filter '" + filter + "': " + hint);
    }
}
