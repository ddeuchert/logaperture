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

import org.logaperture.api.Storm;
import org.logaperture.api.StormFingerprint;
import org.logaperture.api.StormStatus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * {@code logctl storms}'s engine — see doc/specs/storm-detection.md
 * "Detection algorithm" and "Bounded state". Answers one question per
 * candidate event, as cheaply as possible: a per-fingerprint tally counter
 * and a two-state (ONGOING/ENDED) machine, no per-event history, no
 * background timer.
 *
 * <p><b>Concurrency.</b> Deliberately diverges from {@code top}'s {@code
 * TopCounters} (one {@code synchronized} map per context): the counter map is
 * a {@link ConcurrentHashMap} keyed by the 64-bit fingerprint hash, with each
 * entry's per-event update guarded by {@code synchronized} on the entry
 * object itself — contention is striped per fingerprint, never a single
 * per-context monitor, so this stays safe to leave armed on a
 * virtual-thread-heavy logging path. The locked section is arithmetic-only
 * (no I/O, no nested lock, no allocation of note); the sampled stack-frame
 * capture and the first-occurrence render happen outside it and publish
 * their result via {@link AtomicReference}. No {@code ThreadLocal} or
 * per-thread accumulator anywhere.
 */
public final class StormDetector implements StormObserver {

    /** {@value}. */
    public static final String THRESHOLD_PROPERTY = "logaperture.storm.thresholdEvents";
    /** {@value}. */
    public static final String WINDOW_PROPERTY = "logaperture.storm.windowSeconds";
    /** {@value}. */
    public static final String QUIET_PROPERTY = "logaperture.storm.quietSeconds";
    /** {@value}. */
    public static final String MAX_TRACKED_PROPERTY = "logaperture.storm.maxTrackedFingerprints";
    /** {@value}. */
    public static final String MAX_HISTORY_PROPERTY = "logaperture.storm.maxHistory";
    /** {@value}. */
    public static final String FIRST_OCCURRENCE_BYTES_PROPERTY = "logaperture.storm.firstOccurrenceBytes";

    private static final long DEFAULT_THRESHOLD = 1_000;
    private static final long DEFAULT_WINDOW_SECONDS = 10;
    private static final long DEFAULT_QUIET_SECONDS = 60;
    private static final int DEFAULT_MAX_TRACKED_FINGERPRINTS = 4_000;
    private static final int DEFAULT_MAX_HISTORY = 100;
    private static final int DEFAULT_FIRST_OCCURRENCE_BYTES = 8 * 1024;

    private static final int EVICTION_SAMPLE_SIZE = 5;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final String TRUNCATION_MARKER = "\n... [truncated]";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX_RUN_PATTERN = Pattern.compile("\\b0[xX][0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{6,}\\b");
    private static final Pattern DIGIT_RUN_PATTERN = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final long thresholdEvents;
    private final long windowNanos;
    private final long quietNanos;
    private final int maxTrackedFingerprints;
    private final int maxHistory;
    private final int firstOccurrenceBytes;

    private final ConcurrentHashMap<Long, Entry> counters = new ConcurrentHashMap<>();
    private final Object historyLock = new Object();
    private final List<HistoryRecord> history = new ArrayList<>();
    private final AtomicInteger notRetainedCount = new AtomicInteger();

    public StormDetector() {
        this(longProperty(THRESHOLD_PROPERTY, DEFAULT_THRESHOLD),
                Duration.ofSeconds(longProperty(WINDOW_PROPERTY, DEFAULT_WINDOW_SECONDS)),
                Duration.ofSeconds(longProperty(QUIET_PROPERTY, DEFAULT_QUIET_SECONDS)),
                (int) longProperty(MAX_TRACKED_PROPERTY, DEFAULT_MAX_TRACKED_FINGERPRINTS),
                (int) longProperty(MAX_HISTORY_PROPERTY, DEFAULT_MAX_HISTORY),
                (int) longProperty(FIRST_OCCURRENCE_BYTES_PROPERTY, DEFAULT_FIRST_OCCURRENCE_BYTES));
    }

    /** Package-visible so a test can use small thresholds/windows instead of the real defaults. */
    StormDetector(long thresholdEvents, Duration window, Duration quiet, int maxTrackedFingerprints, int maxHistory,
            int firstOccurrenceBytes) {
        if (thresholdEvents < 1) {
            throw new IllegalArgumentException("thresholdEvents must be at least 1");
        }
        this.thresholdEvents = thresholdEvents;
        this.windowNanos = window.toNanos();
        this.quietNanos = quiet.toNanos();
        this.maxTrackedFingerprints = maxTrackedFingerprints;
        this.maxHistory = maxHistory;
        this.firstOccurrenceBytes = firstOccurrenceBytes;
    }

    @Override
    public void observe(StormObservation observation) {
        try {
            observeUnsafe(observation);
        } catch (RuntimeException e) { // doc/specs/storm-detection.md "Failure handling": swallow, never propagate.
            System.err.println("[logaperture-core] StormDetector.observe failed, event passes through unaffected: " + e);
        }
    }

    private void observeUnsafe(StormObservation observation) {
        String normalizedMessage = normalize(observation.rawMessage());
        StormFingerprint cheapFingerprint = new StormFingerprint(
                observation.loggerName(), observation.level(), observation.throwableClassName(),
                normalizedMessage, null);
        long key = fingerprintHash(cheapFingerprint);

        boolean[] inserted = {false};
        Entry entry = counters.computeIfAbsent(key, k -> {
            inserted[0] = true;
            return new Entry(cheapFingerprint);
        });
        if (inserted[0]) {
            evictIfOverCapacity();
        }

        HistoryRecord engaged;
        synchronized (entry) {
            long now = System.nanoTime();
            entry.lastTouchedNanos = now;
            HistoryRecord active = entry.active;
            if (active != null) {
                long gap = now - entry.lastSeenNanos;
                if (gap > quietNanos) {
                    active.status = StormStatus.ENDED;
                    active.endedAt = entry.lastEventInstant;
                    entry.active = null;
                    active = null;
                    entry.count = 0;
                    entry.burstStart = null;
                } else {
                    active.eventCount++;
                    active.lastEventAt = observation.timestamp();
                }
            }
            engaged = null;
            if (active == null) {
                long gap = entry.lastSeenNanos == 0 ? Long.MAX_VALUE : now - entry.lastSeenNanos;
                if (gap > windowNanos) {
                    entry.count = 1;
                    entry.burstStart = observation.timestamp();
                } else {
                    entry.count++;
                }
                if (entry.count >= thresholdEvents) {
                    HistoryRecord record = new HistoryRecord(cheapFingerprint, StormStatus.ONGOING,
                            entry.burstStart, observation.timestamp(), entry.count);
                    entry.active = record;
                    engaged = record;
                }
            }
            entry.lastSeenNanos = now;
            entry.lastEventInstant = observation.timestamp();
        }

        if (engaged != null) {
            publishNewStorm(engaged, observation);
        }
    }

    /**
     * Runs outside the per-entry lock, per doc/specs/storm-detection.md
     * "Bounded state": the one sampled {@code getStackTrace()} and the
     * first-occurrence render. Then admits the new storm into the bounded
     * history, evicting the least-recently-active {@code ENDED} entry if
     * full, or dropping it (disclosed via {@link #notRetainedCount}) if every
     * entry is {@code ONGOING} at capacity.
     */
    private void publishNewStorm(HistoryRecord record, StormObservation observation) {
        if (observation.hasThrown() && observation.topFramesSampler() != null) {
            try {
                List<String> frames = observation.topFramesSampler().get();
                if (frames != null && !frames.isEmpty()) {
                    record.fingerprint = new StormFingerprint(
                            record.fingerprint.loggerName(), record.fingerprint.level(),
                            record.fingerprint.throwableClass(), record.fingerprint.normalizedMessage(),
                            frames);
                }
            } catch (RuntimeException ignored) {
                // best-effort escalation only -- never fail the storm over it.
            }
        }
        try {
            String rendered = observation.firstOccurrenceSupplier().get();
            record.firstOccurrenceText.set(cap(rendered));
        } catch (RuntimeException ignored) {
            // first occurrence stays null -- never fail the storm over it.
        }

        synchronized (historyLock) {
            if (history.size() >= maxHistory) {
                HistoryRecord victim = leastRecentlyActiveEnded();
                if (victim != null) {
                    history.remove(victim);
                } else {
                    // every entry is ONGOING at capacity -- drop the new one, disclosed.
                    notRetainedCount.incrementAndGet();
                    return;
                }
            }
            history.add(record);
        }
    }

    private HistoryRecord leastRecentlyActiveEnded() {
        HistoryRecord victim = null;
        for (HistoryRecord candidate : history) {
            if (candidate.status != StormStatus.ENDED) {
                continue;
            }
            if (victim == null || candidate.lastEventAt.isBefore(victim.lastEventAt)) {
                victim = candidate;
            }
        }
        return victim;
    }

    /**
     * Every recorded storm, after sweeping for a quiet-period timeout that no
     * new event has observed yet (doc/specs/storm-detection.md: "a `logctl
     * storms` sweep" transitions a storm to {@code ENDED} same as its own
     * next event would).
     */
    List<Storm> snapshot() {
        sweepEnded();
        synchronized (historyLock) {
            List<Storm> result = new ArrayList<>(history.size());
            for (HistoryRecord record : history) {
                result.add(record.toStorm());
            }
            return result;
        }
    }

    /** The count of storms detected but not retained because history was full of {@code ONGOING} entries. */
    int notRetainedCount() {
        return notRetainedCount.get();
    }

    private void sweepEnded() {
        long now = System.nanoTime();
        for (Entry entry : counters.values()) {
            HistoryRecord active = entry.active;
            if (active == null) {
                continue;
            }
            synchronized (entry) {
                HistoryRecord current = entry.active;
                if (current == null) {
                    continue;
                }
                if (now - entry.lastSeenNanos > quietNanos) {
                    current.status = StormStatus.ENDED;
                    current.endedAt = entry.lastEventInstant;
                    entry.active = null;
                    entry.count = 0;
                    entry.burstStart = null;
                }
            }
        }
    }

    private void evictIfOverCapacity() {
        if (counters.size() <= maxTrackedFingerprints) {
            return;
        }
        Map.Entry<Long, Entry> victim = null;
        int sampled = 0;
        for (Map.Entry<Long, Entry> candidate : counters.entrySet()) {
            if (sampled++ >= EVICTION_SAMPLE_SIZE) {
                break;
            }
            if (victim == null || candidate.getValue().lastTouchedNanos < victim.getValue().lastTouchedNanos) {
                victim = candidate;
            }
        }
        if (victim != null) {
            counters.remove(victim.getKey());
        }
    }

    private String cap(String text) {
        if (text == null) {
            return null;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= firstOccurrenceBytes) {
            return text;
        }
        // Truncate on a UTF-8-safe boundary, conservatively (byte-count cap,
        // not a strict character cap) -- doc/specs/storm-detection.md
        // "Bounded state": "truncated with a marker".
        String truncated = new String(bytes, 0, firstOccurrenceBytes, StandardCharsets.UTF_8);
        return truncated + TRUNCATION_MARKER;
    }

    /**
     * doc/specs/storm-detection.md Decision #3: a small, conservative,
     * content-agnostic transform -- collapse digit/hex/UUID runs to a
     * placeholder, collapse whitespace, trim, cap length. Package-visible so
     * a unit test can assert on it directly.
     */
    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String result = UUID_PATTERN.matcher(message).replaceAll("<uuid>");
        result = HEX_RUN_PATTERN.matcher(result).replaceAll("<hex>");
        result = DIGIT_RUN_PATTERN.matcher(result).replaceAll("<n>");
        result = WHITESPACE_PATTERN.matcher(result).replaceAll(" ").trim();
        if (result.length() > MAX_MESSAGE_LENGTH) {
            result = result.substring(0, MAX_MESSAGE_LENGTH);
        }
        return result;
    }

    private static long fingerprintHash(StormFingerprint fingerprint) {
        long h = 1125899906842597L; // FNV-ish odd seed
        h = 31 * h + fingerprint.loggerName().hashCode();
        h = 31 * h + fingerprint.level().hashCode();
        h = 31 * h + (fingerprint.throwableClass() == null ? 0 : fingerprint.throwableClass().hashCode());
        h = 31 * h + fingerprint.normalizedMessage().hashCode();
        return h;
    }

    private static long longProperty(String property, long fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long configured = Long.parseLong(raw.trim());
            return configured > 0 ? configured : fallback;
        } catch (NumberFormatException e) {
            System.err.println("[logaperture] ignoring non-numeric " + property + "='" + raw + "', using " + fallback);
            return fallback;
        }
    }

    /** One fingerprint's tally + state, guarded by {@code synchronized(this)} for its own updates. */
    private static final class Entry {
        final StormFingerprint cheapFingerprint;
        long count;
        long lastSeenNanos;
        Instant burstStart;
        Instant lastEventInstant;
        volatile HistoryRecord active;
        volatile long lastTouchedNanos;

        Entry(StormFingerprint cheapFingerprint) {
            this.cheapFingerprint = cheapFingerprint;
        }
    }

    /** One storm's mutable record while tracked; converted to an immutable {@link Storm} on read. */
    private static final class HistoryRecord {
        volatile StormFingerprint fingerprint;
        volatile StormStatus status;
        final Instant firstEventAt;
        volatile Instant lastEventAt;
        volatile Instant endedAt;
        volatile long eventCount;
        final AtomicReference<String> firstOccurrenceText = new AtomicReference<>();

        HistoryRecord(StormFingerprint fingerprint, StormStatus status, Instant firstEventAt, Instant lastEventAt,
                long eventCount) {
            this.fingerprint = fingerprint;
            this.status = status;
            this.firstEventAt = firstEventAt;
            this.lastEventAt = lastEventAt;
            this.eventCount = eventCount;
        }

        Storm toStorm() {
            return new Storm(fingerprint, status, firstEventAt, lastEventAt, endedAt, eventCount,
                    firstOccurrenceText.get());
        }
    }

    /** Worst-first: ongoing before ended, then by event count descending -- doc/specs/storm-detection.md "The operation". */
    static final Comparator<Storm> WORST_FIRST = Comparator
            .<Storm, Integer>comparing(s -> s.status() == StormStatus.ONGOING ? 0 : 1)
            .thenComparing(Comparator.comparingLong(Storm::eventCount).reversed());

    /** Exposed for {@link StormService}. */
    static Comparator<Storm> worstFirst() {
        return WORST_FIRST;
    }
}
