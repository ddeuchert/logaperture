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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameFilterTest {

    @Test
    void nullOrEmptyMatchesEverything() {
        assertTrue(NameFilter.matches(null, "com.acme.Worker"));
        assertTrue(NameFilter.matches("", "com.acme.Worker"));
    }

    @Test
    void plainStringIsPrefixMatch() {
        assertTrue(NameFilter.matches("com.acme", "com.acme.Worker"));
        assertTrue(NameFilter.matches("com.ac", "com.acme.Worker")); // loose prefix, not dot-bounded
        assertFalse(NameFilter.matches("com.acme.Worker.x", "com.acme.Worker"));
    }

    @Test
    void dotsInAPrefixFilterAreLiteral() {
        // If '.' were regex "any character," "comXacme" would wrongly match "com.acme".
        assertFalse(NameFilter.matches("com.acme", "comXacme.Worker"));
    }

    @Test
    void trailingStarMatchesOneOrMoreDescendantSegments() {
        assertTrue(NameFilter.matches("org.apache.*", "org.apache.Foo"));
        assertTrue(NameFilter.matches("org.apache.*", "org.apache.commons.Foo"));
        assertFalse(NameFilter.matches("org.apache.*", "org.apache")); // one-or-more, not zero
        assertFalse(NameFilter.matches("org.apache.*", "org.apacheX.Foo"));
    }

    @Test
    void leadingStarMatchesOneOrMoreAncestorSegments() {
        // The abbreviated-category case: a log line printed "infinispan", the
        // real logger is org.jboss.as.clustering.infinispan.
        String logger = "org.jboss.as.clustering.infinispan";
        assertTrue(NameFilter.matches("*.infinispan", logger));
        assertFalse(NameFilter.matches("*.infinispan", "infinispan")); // one-or-more, not zero
        // Anchored tail: "*.infinispan" must not match a longer name.
        assertFalse(NameFilter.matches("*.infinispan", "org.infinispan.remoting"));
    }

    @Test
    void bothLeadingAndTrailingStarMatch() {
        assertTrue(NameFilter.matches("*.apache.writer.*", "org.acme.apache.writer.Impl"));
        assertFalse(NameFilter.matches("*.apache.writer.*", "apache.writer.Impl")); // needs a leading segment
        assertFalse(NameFilter.matches("*.apache.writer.*", "org.acme.apache.writer")); // needs a trailing segment
    }

    @Test
    void midSegmentStarIsRejected() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("org.*apache", "x"));
        assertTrue(e.getMessage().contains("org.*apache"));
    }

    @Test
    void trailingStarSuggestsATrailingAnchoredSegment() {
        // Old "word*" meant "starts with word" -- the anchored equivalent
        // anchors word as a LEADING literal segment ('word.*'), not a
        // trailing one ('*.word'), which is the opposite match.
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("infinispan*", "x"));
        assertTrue(e.getMessage().contains("'infinispan.*'"), e.getMessage());
        assertFalse(e.getMessage().contains("'*.infinispan'"), e.getMessage());
    }

    @Test
    void leadingStarSuggestsALeadingAnchoredSegment() {
        // Old "*word" meant "ends with word" -- the anchored equivalent
        // anchors word as a TRAILING literal segment ('*.word').
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*infinispan", "x"));
        assertTrue(e.getMessage().contains("'*.infinispan'"), e.getMessage());
    }

    @Test
    void bothSidedStarHasNoAnchoredEquivalent() {
        // Old "*word*" meant "contains word" -- there is no single anchored
        // pattern equivalent to a substring match, so the message says so
        // and names both single-sided alternatives instead of guessing one.
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*infinispan*", "x"));
        assertTrue(e.getMessage().contains("no anchored equivalent"), e.getMessage());
        assertTrue(e.getMessage().contains("'*.infinispan'"), e.getMessage());
        assertTrue(e.getMessage().contains("'infinispan.*'"), e.getMessage());
    }

    @Test
    void midWordStarGetsTheGenericHint() {
        // "foo*bar" isn't a clean prefix or suffix migration case -- stripping
        // the '*' would concatenate unrelated halves into a word the user
        // never typed, so this falls back to the generic rejection instead
        // of a specific (and misleading) suggestion.
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("foo*bar", "x"));
        assertFalse(e.getMessage().contains("foobar"), e.getMessage());
    }

    @Test
    void middleWildcardSegmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("org.*.writer", "x"));
    }

    @Test
    void bareStarIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*", "com.acme.Worker"));
    }

    @Test
    void allWildcardSegmentsAreRejectedEvenWithMoreThanOne() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*.*", "com.acme.Worker"));
    }

    @Test
    void emptySegmentsAreRejected() {
        // A leading/trailing/doubled dot produces an empty segment that used
        // to be silently treated as literal text -- accepted as "valid" yet
        // compiling to a regex that can never match any real logger name.
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("a..*", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches(".*", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*.", "x"));
    }

    @Test
    void questionMarkIsNoLongerAWildcard() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("com.acme.Worker?", "com.acme.Worker1"));
        // Not silently treated as a literal character either, even with no '*' present.
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("com.acme?", "com.acme?"));
    }

    @Test
    void literalSegmentsWithRegexMetacharactersAreTreatedLiterally() {
        assertTrue(NameFilter.matches("org.(weird).*", "org.(weird).Foo"));
        assertFalse(NameFilter.matches("org.(weird).*", "org.weird.Foo"));
    }

    @Test
    void compileValidatesOnceAndReturnsAReusableMatcher() {
        java.util.function.Predicate<String> matcher = NameFilter.compile("*.infinispan");
        assertTrue(matcher.test("org.jboss.as.clustering.infinispan"));
        assertFalse(matcher.test("com.acme.Worker"));
    }

    @Test
    void compileValidatesEvenWhenTheMatcherIsNeverTested() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.compile("org.*apache"));
    }
}
