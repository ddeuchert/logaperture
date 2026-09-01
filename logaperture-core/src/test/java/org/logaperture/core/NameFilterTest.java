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
    void starGlobMatchesAcrossSegments() {
        assertTrue(NameFilter.matches("com.*.Worker", "com.acme.Worker"));
        assertFalse(NameFilter.matches("com.*.Worker", "com.acme.Noisy"));
    }

    @Test
    void questionMarkGlobMatchesSingleChar() {
        assertTrue(NameFilter.matches("com.acme.Worker?", "com.acme.Worker1"));
        assertFalse(NameFilter.matches("com.acme.Worker?", "com.acme.Worker12"));
    }

    @Test
    void dotsInGlobFilterAreLiteralNotRegexAny() {
        // If '.' were left as regex "any character" instead of escaped,
        // "comXacme" would wrongly match "com.*" too (com + any-char + any).
        assertTrue(NameFilter.matches("com.*", "com.acme.Worker"));
        assertFalse(NameFilter.matches("com.*", "comXacme"));
    }

    @Test
    void leadingStarMatchesFromASuffix() {
        // The abbreviated-category case: a log line printed "infinispan", the
        // real logger is org.jboss.as.clustering.infinispan.
        String logger = "org.jboss.as.clustering.infinispan";
        assertTrue(NameFilter.matches("*.infinispan", logger));
        assertTrue(NameFilter.matches("*infinispan*", logger));
        assertTrue(NameFilter.matches("*infinispan", logger));
        // Anchored tail: "*.infinispan" must not match a longer name.
        assertFalse(NameFilter.matches("*.infinispan", "org.infinispan.remoting"));
    }

    @Test
    void bareStarMatchesEverything() {
        assertTrue(NameFilter.matches("*", "com.acme.Worker"));
        assertTrue(NameFilter.matches("*", "ROOT"));
    }
}
