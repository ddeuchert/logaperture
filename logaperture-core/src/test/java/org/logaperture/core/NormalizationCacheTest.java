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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/storm-detection.md "Normalization cache" (issue #129): a cache can't change a result. */
class NormalizationCacheTest {

    private static final String TEMPLATE = "Processed order {0} for customer {1} in 12 ms";

    @Test
    void repeatedTemplate_isServedFromTheCache() {
        NormalizationCache cache = new NormalizationCache(1_024);
        assertEquals(StormMessageNormalizer.hash(TEMPLATE), cache.hash(TEMPLATE));
        assertFalse(cache.holds(TEMPLATE), "admitted on its second miss, not its first");
        assertEquals(StormMessageNormalizer.hash(TEMPLATE), cache.hash(TEMPLATE));
        assertTrue(cache.holds(TEMPLATE));
        assertEquals(StormMessageNormalizer.hash(TEMPLATE), cache.hash(TEMPLATE));
    }

    @Test
    void messagesThatNeverRepeat_doNotEvictACachedTemplate() {
        NormalizationCache cache = new NormalizationCache(1); // one slot: everything collides
        cache.hash(TEMPLATE);
        cache.hash(TEMPLATE);
        for (int order = 0; order < 100; order++) {
            cache.hash("Processed order " + order + " for customer 7f3a9c21 in 12 ms");
        }
        assertTrue(cache.holds(TEMPLATE));
    }

    @Test
    void equalButDistinctString_hitsTheSameSlot() {
        NormalizationCache cache = new NormalizationCache(1_024);
        cache.hash(TEMPLATE);
        cache.hash(TEMPLATE);
        assertTrue(cache.holds(new String(TEMPLATE.toCharArray())));
    }

    @Test
    void collidingMessages_eachGetTheirOwnResult() {
        NormalizationCache cache = new NormalizationCache(1); // one slot: everything collides
        String a = "order 4821 failed";
        String b = "checksum deadbeef mismatch";
        for (int round = 0; round < 3; round++) {
            assertEquals(StormMessageNormalizer.hashOf("order <n> failed"), cache.hash(a));
            assertEquals(StormMessageNormalizer.hashOf("checksum <hex> mismatch"), cache.hash(b));
        }
    }

    @Test
    void messageOverTheLimit_isHashedButNotCached() {
        NormalizationCache cache = new NormalizationCache(1_024);
        String longMessage = "id 42 ".repeat(NormalizationCache.MAX_CACHED_LENGTH / 6 + 1);
        assertEquals(StormMessageNormalizer.hash(longMessage), cache.hash(longMessage));
        assertEquals(StormMessageNormalizer.hash(longMessage), cache.hash(longMessage));
        assertFalse(cache.holds(longMessage));
    }

    @Test
    void disabled_stillHashes() {
        NormalizationCache cache = new NormalizationCache(0);
        assertEquals(0, cache.capacity());
        assertEquals(StormMessageNormalizer.hashOf("order <n> failed"), cache.hash("order 4821 failed"));
        assertEquals(StormMessageNormalizer.hashOf(""), cache.hash(null));
        assertFalse(cache.holds("order 4821 failed"));
    }

    @Test
    void sizeProperty_zeroOrLessDisables_garbageFallsBack() {
        String key = StormDetector.NORMALIZATION_CACHE_SIZE_PROPERTY;
        String original = System.getProperty(key);
        try {
            for (String off : new String[] {"0", "00", "+0", "-1", " -5 "}) {
                System.setProperty(key, off);
                assertEquals(0, StormDetector.normalizationCacheSizeProperty(), off);
            }
            System.setProperty(key, "2048");
            assertEquals(2_048, StormDetector.normalizationCacheSizeProperty());
            System.setProperty(key, "99999999999");
            assertEquals(Integer.MAX_VALUE, StormDetector.normalizationCacheSizeProperty());
            System.setProperty(key, "lots");
            assertEquals(1_024, StormDetector.normalizationCacheSizeProperty());
            System.clearProperty(key);
            assertEquals(1_024, StormDetector.normalizationCacheSizeProperty());
        } finally {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
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
        List<Long> expected = messages.stream().map(StormMessageNormalizer::hash).toList();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                Callable<Integer> hashEveryMessage = () -> {
                    int wrong = 0;
                    for (int round = 0; round < 2_000; round++) {
                        int i = (round + offset) % messages.size();
                        if (expected.get(i) != cache.hash(messages.get(i))) {
                            wrong++;
                        }
                    }
                    return wrong;
                };
                results.add(pool.submit(hashEveryMessage));
            }
            for (Future<Integer> result : results) {
                assertEquals(0, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
