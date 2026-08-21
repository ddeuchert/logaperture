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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerHierarchyTest {

    @Test
    void findsDotBoundaryDescendantsOnly() {
        List<String> all = List.of(
                "com.acme", "com.acme.http", "com.acme.http.client",
                "com.acmesuffix", "com.other", "ROOT");

        List<String> descendants = LoggerHierarchy.descendantsOf("com.acme", all);

        assertEquals(2, descendants.size());
        assertTrue(descendants.contains("com.acme.http"));
        assertTrue(descendants.contains("com.acme.http.client"));
        assertTrue(descendants.stream().noneMatch(n -> n.equals("com.acmesuffix")));
    }

    @Test
    void parentItselfIsNotADescendant() {
        List<String> all = List.of("com.acme", "com.acme.http");

        List<String> descendants = LoggerHierarchy.descendantsOf("com.acme", all);

        assertTrue(descendants.stream().noneMatch(n -> n.equals("com.acme")));
    }

    @Test
    void noDescendantsReturnsEmpty() {
        assertTrue(LoggerHierarchy.descendantsOf("com.acme", List.of("com.other")).isEmpty());
    }
}
