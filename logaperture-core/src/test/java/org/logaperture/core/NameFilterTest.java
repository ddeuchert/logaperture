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
    void mixedSingleSegmentSuggestsTheAnchoredForm() {
        // The old *word* / *word / word* forms -- the common migration case --
        // get a specific "try" suggestion rather than a generic rejection.
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("*infinispan*", "x"));
        assertTrue(e.getMessage().contains("*.infinispan"), e.getMessage());
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
    void questionMarkIsNoLongerAWildcard() {
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("com.acme.Worker?", "com.acme.Worker1"));
        // Not silently treated as a literal character either, even with no '*' present.
        assertThrows(IllegalArgumentException.class, () -> NameFilter.matches("com.acme?", "com.acme?"));
    }
}
