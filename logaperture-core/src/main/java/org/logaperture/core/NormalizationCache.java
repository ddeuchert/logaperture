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
 * (issue #129): the normalized form of recently seen raw messages. For
 * parameterized logging the raw message is the statement's template, the same
 * {@code String} on every call, so most events become a slot read instead of
 * a {@link StormMessageNormalizer} scan.
 *
 * <p>Direct-mapped: one slot per hash, a miss overwrites. No lock and no CAS,
 * because a racing reader sees either the old pair or the new one and both are
 * correct; {@link Pair}'s fields are {@code final}, so a plain array write
 * publishes it safely. Nothing is allocated on a hit.
 */
final class NormalizationCache {

    /** Raw messages longer than this are normalized every time, never retained. */
    static final int MAX_CACHED_LENGTH = 2_000;

    private static final int MAX_SLOTS = 1 << 16;

    private record Pair(String raw, String normalized) {
    }

    private final Pair[] slots;
    private final int mask;

    /** @param requestedSlots rounded up to a power of two; {@code 0} or less disables the cache */
    NormalizationCache(int requestedSlots) {
        if (requestedSlots <= 0) {
            slots = null;
            mask = 0;
        } else {
            int size = Integer.highestOneBit(Math.min(requestedSlots, MAX_SLOTS) - 1) << 1;
            slots = new Pair[Math.max(size, 1)];
            mask = slots.length - 1;
        }
    }

    String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        if (slots == null || raw.length() > MAX_CACHED_LENGTH) {
            return StormMessageNormalizer.normalize(raw);
        }
        int h = raw.hashCode();
        int index = (h ^ (h >>> 16)) & mask;
        Pair pair = slots[index];
        if (pair != null && (pair.raw == raw || pair.raw.equals(raw))) {
            return pair.normalized;
        }
        String normalized = StormMessageNormalizer.normalize(raw);
        slots[index] = new Pair(raw, normalized);
        return normalized;
    }

    /** Slot count after rounding; {@code 0} when disabled. Package-visible for tests. */
    int capacity() {
        return slots == null ? 0 : slots.length;
    }
}
