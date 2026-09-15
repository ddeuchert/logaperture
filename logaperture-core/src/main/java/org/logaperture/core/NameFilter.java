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
     * whether it's looking at a standing rule or a plain override; pulled
     * out here so that branch is spelled once instead of re-typed at each
     * call site.
     */
    static boolean isPattern(String target) {
        return target.indexOf('*') >= 0;
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
            throw invalid(filter, "at least one segment must be literal -- a pattern of only '*' segments matches "
                    + "everything and, as a standing rule, would never stop matching new loggers");
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

    /**
     * Whether every name {@code inner} could ever match (now or on some
     * future sweep) is also matched by {@code outer} — both read as this
     * class's own grammar, not run as one pattern against a fixed string.
     * The containment primitive doc/specs/reset-command-surface.md's
     * "Partial reset — scoped exclusions" calls for (a code-review finding
     * against the original slice, which recorded a partial-reset target as
     * a rule's exclusion verbatim even when the target's own scope fully
     * swallowed that rule's, permanently blinding the rule to everything it
     * could ever have matched instead of retiring it): {@code
     * LevelControlService} uses this to decide, per affected rule, whether
     * a carve-out should retire the rule outright rather than merely
     * exclude from it.
     *
     * <p>Decidable from the grammar's own structure (at most one leading
     * {@code *.} and/or one trailing {@code .*}, literal segments in
     * between) — this reasons about three shapes: an exact name (matches
     * only itself), a trailing-star pattern (matches its own literal prefix
     * and everything segment-wise under it), and a leading-star pattern
     * (matches its own literal suffix and everything segment-wise above
     * it). A pattern using <em>both</em> a leading and a trailing star at
     * once (e.g. {@code "*.foo.*"}) is intentionally not reasoned about
     * beyond exact string equality — containment for that shape needs an
     * unanchored "contains this segment sequence anywhere" match that none
     * of this feature's call sites need, and guessing wrong here would
     * mean either wrongly retiring a rule that still had reach, or wrongly
     * excluding it into permanent blindness (the very bug this method
     * exists to fix) — so callers fall back to their own conservative
     * default (add the standing exclusion instead of retiring; scope it to
     * concrete names instead of the raw pattern) rather than risk that.
     */
    static boolean covers(String outer, String inner) {
        if (outer.equals(inner)) {
            return true;
        }
        Scope outerScope = Scope.of(outer);
        Scope innerScope = Scope.of(inner);
        if (outerScope == null || innerScope == null) {
            return false; // one side is a leading-and-trailing-star pattern -- not reasoned about, see the javadoc above
        }
        return outerScope.covers(innerScope);
    }

    /** One pattern's shape, decomposed for {@link #covers(String, String)}. */
    private record Scope(Kind kind, String literal) {

        enum Kind {
            EXACT, LEADING, TRAILING
        }

        /** {@code null} for a pattern using both a leading and a trailing star -- {@link #covers} doesn't reason about that shape. */
        static Scope of(String pattern) {
            if (!isPattern(pattern)) {
                return new Scope(Kind.EXACT, pattern);
            }
            boolean leading = pattern.startsWith("*.");
            boolean trailing = pattern.endsWith(".*");
            if (leading == trailing) {
                return null; // both (rare) or -- for a validated pattern -- neither, which can't happen
            }
            String literal = leading ? pattern.substring(2) : pattern.substring(0, pattern.length() - 2);
            return new Scope(leading ? Kind.LEADING : Kind.TRAILING, literal);
        }

        boolean covers(Scope inner) {
            return switch (kind) {
                case EXACT -> inner.kind == Kind.EXACT && literal.equals(inner.literal);
                case TRAILING -> (inner.kind == Kind.EXACT || inner.kind == Kind.TRAILING)
                        && segmentPrefixCovers(literal, inner.literal);
                case LEADING -> (inner.kind == Kind.EXACT || inner.kind == Kind.LEADING)
                        && segmentSuffixCovers(literal, inner.literal);
            };
        }
    }

    /** Whether {@code candidate} is {@code prefix} itself or lies under it, segment-anchored (the {@code "prefix.*"} scope). */
    private static boolean segmentPrefixCovers(String prefix, String candidate) {
        return candidate.equals(prefix) || candidate.startsWith(prefix + ".");
    }

    /** Whether {@code candidate} is {@code suffix} itself or lies above it, segment-anchored (the {@code "*.suffix"} scope). */
    private static boolean segmentSuffixCovers(String suffix, String candidate) {
        return candidate.equals(suffix) || candidate.endsWith("." + suffix);
    }
}
