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
 * doc/specs/storm-detection.md "Bounded state", "Normalization cache"
 * (issues #129, #147): the normalized-message hash of recently seen raw
 * messages. For parameterized logging the raw message is the statement's
 * template, the same {@code String} on every call, so most events become a
 * slot read instead of a {@link StormMessageNormalizer} scan.
 *
 * <p>Direct-mapped: one slot per hash; a message that misses twice in a row at
 * its slot overwrites it. No lock and no CAS,
 * because a racing reader sees either the old pair or the new one and both are
 * correct; {@link Pair}'s fields are {@code final}, so a plain array write
 * publishes it safely. Nothing is allocated on a hit.
 */
final class NormalizationCache {

    /** Raw messages longer than this are hashed every time, never retained. */
    static final int MAX_CACHED_LENGTH = 2_000;

    private static final int MAX_SLOTS = 1 << 16;

    private record Pair(String raw, long hash) {
    }

    private final Pair[] slots;
    /**
     * Per slot, the {@code hashCode()} of the last message that missed there and wasn't admitted
     * (issue #147). A message is cached on its second miss in a row at its slot, so text that
     * never repeats (built by concatenation) writes one {@code int} and allocates nothing.
     */
    private final int[] candidates;
    private final int mask;

    /** @param requestedSlots rounded up to a power of two; {@code 0} or less disables the cache */
    NormalizationCache(int requestedSlots) {
        if (requestedSlots <= 0) {
            slots = null;
            candidates = null;
            mask = 0;
        } else {
            int size = Integer.highestOneBit(Math.min(requestedSlots, MAX_SLOTS) - 1) << 1;
            slots = new Pair[Math.max(size, 1)];
            candidates = new int[slots.length];
            mask = slots.length - 1;
        }
    }

    /** {@link StormMessageNormalizer#hash}{@code (raw)}, from the cache when {@code raw} was seen recently. */
    long hash(String raw) {
        if (raw == null) {
            return StormMessageNormalizer.hash(null);
        }
        if (slots == null || raw.length() > MAX_CACHED_LENGTH) {
            return StormMessageNormalizer.hash(raw);
        }
        int h = raw.hashCode();
        int index = indexOf(h);
        Pair pair = slots[index];
        if (pair != null && (pair.raw == raw || pair.raw.equals(raw))) {
            return pair.hash;
        }
        long hash = StormMessageNormalizer.hash(raw);
        if (candidates[index] == h) {
            slots[index] = new Pair(raw, hash);
        } else {
            candidates[index] = h;
        }
        return hash;
    }

    /** Whether {@code raw} currently has a slot: lets a test tell a hit from a recomputation. */
    boolean holds(String raw) {
        if (slots == null || raw == null) {
            return false;
        }
        Pair pair = slots[indexOf(raw.hashCode())];
        return pair != null && pair.raw.equals(raw);
    }

    private int indexOf(int hashCode) {
        return (hashCode ^ (hashCode >>> 16)) & mask;
    }

    int capacity() {
        return slots == null ? 0 : slots.length;
    }
}
