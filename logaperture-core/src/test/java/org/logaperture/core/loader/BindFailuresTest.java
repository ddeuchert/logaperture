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
package org.logaperture.core.loader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** doc/specs/log4j2-adapter.md Slice 7, D7-4 and D7-6: the process-wide list of frameworks found and not bound. */
class BindFailuresTest {

    @BeforeEach
    @AfterEach
    void clear() {
        BindFailures.clear();
    }

    private static List<String> subjects() {
        return BindFailures.findings().stream().map(f -> f.subject()).toList();
    }

    @Test
    void theIdsForLog4j2AreTheOnesTheGuideNames() {
        assertEquals("log4j2.version", BindFailures.FRAMEWORK_VERSION_CHECK);
        assertEquals("log4j2.not-bound", BindFailures.FRAMEWORK_NOT_BOUND_CHECK);
        ClassLoader loader = new ClassLoader("webapp", null) { };
        BindFailures.record("log4j2", loader, "L", "too old", BindFailures.Kind.VERSION);
        BindFailures.record("log4j2", new ClassLoader("other", null) { }, "M", "threw", BindFailures.Kind.FAILED);
        assertEquals(List.of(BindFailures.FRAMEWORK_VERSION_CHECK, BindFailures.FRAMEWORK_NOT_BOUND_CHECK),
                BindFailures.findings().stream().map(f -> f.check()).toList());
    }

    @Test
    void oneRowPerFrameworkAndLoader_theLatestWins() {
        ClassLoader loader = new ClassLoader("a", null) { };
        BindFailures.record("fake", loader, "A", "first", BindFailures.Kind.FAILED);
        BindFailures.record("fake", loader, "A", "second", BindFailures.Kind.FAILED);
        assertEquals(1, BindFailures.findings().size());
        assertEquals(true, BindFailures.findings().get(0).summary().contains("second"));
    }

    @Test
    void aRowDropsWhenItsLoaderIsCollected() throws Exception {
        WeakReference<ClassLoader> ref = recordForALoaderThatGoes();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ref.get());
        assertEquals(List.of(), BindFailures.findings());
    }

    private static WeakReference<ClassLoader> recordForALoaderThatGoes() {
        ClassLoader loader = new ClassLoader("gone", null) { };
        BindFailures.record("fake", loader, "G", "x", BindFailures.Kind.FAILED);
        return new WeakReference<>(loader);
    }

    @Test
    void atMostSixteenRows_theOldestGoesFirst() {
        List<ClassLoader> keep = new java.util.ArrayList<>();
        for (int i = 0; i < BindFailures.MAX + 3; i++) {
            ClassLoader loader = new ClassLoader("l" + i, null) { };
            keep.add(loader);
            BindFailures.record("fake", loader, "owner-" + i, "x", BindFailures.Kind.FAILED);
        }
        assertEquals(BindFailures.MAX, BindFailures.findings().size());
        assertEquals("owner-3", subjects().get(0));
        assertEquals(BindFailures.MAX + 3, keep.size());
    }

    @Test
    void aGiveUpIsInfo_aFailureAndAVersionAreWarnings() {
        ClassLoader a = new ClassLoader("a", null) { };
        ClassLoader b = new ClassLoader("b", null) { };
        ClassLoader c = new ClassLoader("c", null) { };
        BindFailures.record("log4j2", a, "A", "no started context", BindFailures.Kind.GAVE_UP);
        BindFailures.record("log4j2", b, "B", "threw", BindFailures.Kind.FAILED);
        BindFailures.record("log4j2", c, "C", "too old", BindFailures.Kind.VERSION);
        assertEquals(List.of(org.logaperture.api.Severity.INFO, org.logaperture.api.Severity.WARNING,
                org.logaperture.api.Severity.WARNING),
                BindFailures.findings().stream().map(f -> f.severity()).toList());
        assertEquals(List.of("log4j2.not-bound", "log4j2.not-bound", "log4j2.version"),
                BindFailures.findings().stream().map(f -> f.check()).toList());
    }

    @Test
    void aSuccessfulBindOfTheSameLoader_forgetsItsRow() {
        ClassLoader loader = new ClassLoader("a", null) { };
        BindFailures.record("fake", loader, "A", "late", BindFailures.Kind.GAVE_UP);
        BindFailures.forget("fake", loader);
        assertEquals(List.of(), BindFailures.findings());
    }

    @Test
    void aScanFailure_isFoundAndNotBound() {
        ClassLoader loader = new ClassLoader("a", null) { };
        BindFailures.recordScanFailure("fake", loader, new IllegalStateException("boom"));
        assertEquals(List.of("fake.not-bound"), BindFailures.findings().stream().map(f -> f.check()).toList());
        assertEquals(true, BindFailures.findings().get(0).summary().contains("boom"));
    }
}
