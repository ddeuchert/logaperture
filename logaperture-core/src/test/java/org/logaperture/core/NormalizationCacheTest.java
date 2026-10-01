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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/storm-detection.md "Normalization cache" (issue #129): a cache can't change a result. */
class NormalizationCacheTest {

    private static final String TEMPLATE = "Processed order {0} for customer {1} in 12 ms";

    @Test
    void repeatedTemplate_isServedFromTheCache() {
        NormalizationCache cache = new NormalizationCache(1_024);
        String first = cache.normalize(TEMPLATE);
        assertEquals(StormMessageNormalizer.normalize(TEMPLATE), first);
        assertSame(first, cache.normalize(TEMPLATE));
    }

    @Test
    void equalButDistinctString_hitsTheSameSlot() {
        NormalizationCache cache = new NormalizationCache(1_024);
        String first = cache.normalize(TEMPLATE);
        assertSame(first, cache.normalize(new String(TEMPLATE.toCharArray())));
    }

    @Test
    void collidingMessages_eachGetTheirOwnResult() {
        NormalizationCache cache = new NormalizationCache(1); // one slot: everything collides
        String a = "order 4821 failed";
        String b = "checksum deadbeef mismatch";
        for (int round = 0; round < 3; round++) {
            assertEquals("order <n> failed", cache.normalize(a));
            assertEquals("checksum <hex> mismatch", cache.normalize(b));
        }
    }

    @Test
    void messageOverTheLimit_isNormalizedButNotCached() {
        NormalizationCache cache = new NormalizationCache(1_024);
        String longMessage = "id 42 ".repeat(NormalizationCache.MAX_CACHED_LENGTH / 6 + 1);
        String first = cache.normalize(longMessage);
        assertEquals(StormMessageNormalizer.normalize(longMessage), first);
        assertNotSame(first, cache.normalize(longMessage));
    }

    @Test
    void disabled_stillNormalizes() {
        NormalizationCache cache = new NormalizationCache(0);
        assertEquals(0, cache.capacity());
        assertEquals("order <n> failed", cache.normalize("order 4821 failed"));
        assertEquals("", cache.normalize(null));
    }

    @Test
    void capacity_roundsUpToAPowerOfTwo() {
        assertEquals(1_024, new NormalizationCache(1_024).capacity());
        assertEquals(1_024, new NormalizationCache(1_000).capacity());
        assertEquals(1, new NormalizationCache(1).capacity());
        assertEquals(0, new NormalizationCache(-5).capacity());
        assertTrue(new NormalizationCache(Integer.MAX_VALUE).capacity() <= 1 << 16);
    }

    @Test
    void concurrentCollidingMessages_alwaysGetTheCorrectResult() throws Exception {
        NormalizationCache cache = new NormalizationCache(4); // small, so threads keep overwriting slots
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            messages.add("worker " + i + " retry " + Integer.toHexString(0xabcdef + i) + " after 250ms");
        }
        List<String> expected = messages.stream().map(StormMessageNormalizer::normalize).toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                Callable<Integer> normalizeEveryMessage = () -> {
                    int wrong = 0;
                    for (int round = 0; round < 2_000; round++) {
                        int i = (round + offset) % messages.size();
                        if (!expected.get(i).equals(cache.normalize(messages.get(i)))) {
                            wrong++;
                        }
                    }
                    return wrong;
                };
                results.add(pool.submit(normalizeEveryMessage));
            }
            for (Future<Integer> result : results) {
                assertEquals(0, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
