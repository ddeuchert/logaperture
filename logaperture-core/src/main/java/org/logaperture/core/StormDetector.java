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

import org.logaperture.bridge.Diagnostics;
import org.logaperture.api.Level;
import org.logaperture.api.Storm;
import org.logaperture.api.StormFingerprint;
import org.logaperture.api.StormStatus;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    /** {@value}; {@code 0} disables the cache. */
    public static final String NORMALIZATION_CACHE_SIZE_PROPERTY = "logaperture.storm.normalizationCacheSize";

    private static final long DEFAULT_THRESHOLD = 1_000;
    private static final long DEFAULT_WINDOW_SECONDS = 10;
    private static final long DEFAULT_QUIET_SECONDS = 60;
    private static final int DEFAULT_MAX_TRACKED_FINGERPRINTS = 4_000;
    private static final int DEFAULT_MAX_HISTORY = 100;
    private static final int DEFAULT_FIRST_OCCURRENCE_BYTES = 8 * 1024;
    private static final int DEFAULT_NORMALIZATION_CACHE_SIZE = 1_024;

    private static final int EVICTION_SAMPLE_SIZE = 5;
    private static final String TRUNCATION_MARKER = "\n... [truncated]";

    private final long thresholdEvents;
    private final long windowNanos;
    private final long quietNanos;
    private final int maxTrackedFingerprints;
    private final int maxHistory;
    private final int firstOccurrenceBytes;
    private final NormalizationCache normalizations;

    private final ConcurrentHashMap<Long, Entry> counters = new ConcurrentHashMap<>();
    private final Object historyLock = new Object();
    private final List<HistoryRecord> history = new ArrayList<>();
    private final AtomicInteger notRetainedCount = new AtomicInteger();
    private final StormDetectionSwitch detectionSwitch;

    public StormDetector() {
        this(StormDetectionSwitch.alwaysEnabled());
    }

    /**
     * The production detector, with its thresholds from system properties, enabled or not by
     * {@code detectionSwitch} (doc/specs/storm-detection-toggle.md).
     */
    public StormDetector(StormDetectionSwitch detectionSwitch) {
        this(longProperty(THRESHOLD_PROPERTY, DEFAULT_THRESHOLD),
                Duration.ofSeconds(longProperty(WINDOW_PROPERTY, DEFAULT_WINDOW_SECONDS)),
                Duration.ofSeconds(longProperty(QUIET_PROPERTY, DEFAULT_QUIET_SECONDS)),
                (int) longProperty(MAX_TRACKED_PROPERTY, DEFAULT_MAX_TRACKED_FINGERPRINTS),
                (int) longProperty(MAX_HISTORY_PROPERTY, DEFAULT_MAX_HISTORY),
                (int) longProperty(FIRST_OCCURRENCE_BYTES_PROPERTY, DEFAULT_FIRST_OCCURRENCE_BYTES),
                normalizationCacheSizeProperty(), detectionSwitch);
    }

    /** Package-visible so a test can use small thresholds/windows instead of the real defaults. */
    StormDetector(long thresholdEvents, Duration window, Duration quiet, int maxTrackedFingerprints, int maxHistory,
            int firstOccurrenceBytes) {
        this(thresholdEvents, window, quiet, maxTrackedFingerprints, maxHistory, firstOccurrenceBytes,
                DEFAULT_NORMALIZATION_CACHE_SIZE);
    }

    StormDetector(long thresholdEvents, Duration window, Duration quiet, int maxTrackedFingerprints, int maxHistory,
            int firstOccurrenceBytes, int normalizationCacheSize) {
        this(thresholdEvents, window, quiet, maxTrackedFingerprints, maxHistory, firstOccurrenceBytes,
                normalizationCacheSize, StormDetectionSwitch.alwaysEnabled());
    }

    StormDetector(long thresholdEvents, Duration window, Duration quiet, int maxTrackedFingerprints, int maxHistory,
            int firstOccurrenceBytes, int normalizationCacheSize, StormDetectionSwitch detectionSwitch) {
        if (thresholdEvents < 1) {
            throw new IllegalArgumentException("thresholdEvents must be at least 1");
        }
        this.thresholdEvents = thresholdEvents;
        this.windowNanos = window.toNanos();
        this.quietNanos = quiet.toNanos();
        this.maxTrackedFingerprints = maxTrackedFingerprints;
        this.maxHistory = maxHistory;
        this.firstOccurrenceBytes = firstOccurrenceBytes;
        this.normalizations = new NormalizationCache(normalizationCacheSize);
        this.detectionSwitch = Objects.requireNonNull(detectionSwitch, "detectionSwitch");
    }

    @Override
    public boolean isActive() {
        return detectionSwitch.isEnabled();
    }

    @Override
    public void observe(StormObservation observation) {
        observe(observation.loggerName(), observation.level(), observation.throwableClassName(),
                observation.rawMessage(), observation.timestamp(), observation, StormObservation.DETAILS);
    }

    @Override
    public <S> void observe(String loggerName, Level level, String throwableClassName, String rawMessage,
            Instant timestamp, S source, Details<S> details) {
        if (!detectionSwitch.isEnabled()) {
            return; // doc/specs/storm-detection-toggle.md T5 -- a caller that didn't ask isActive() first
        }
        try {
            observeUnsafe(loggerName, level, throwableClassName, rawMessage, timestamp, source, details);
        } catch (RuntimeException e) { // doc/specs/storm-detection.md "Failure handling": swallow, never propagate.
            Diagnostics.warnThrottled("storm-observe", "StormDetector.observe failed, event passes through unaffected: " + e, null);
        }
    }

    private <S> void observeUnsafe(String loggerName, Level level, String throwableClassName, String rawMessage,
            Instant timestamp, S source, Details<S> details) {
        // What StormObservation's constructor checks, for the path that skips building one.
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(rawMessage, "rawMessage");
        Objects.requireNonNull(timestamp, "timestamp");
        // Issue #147: the per-event key needs only the normalized message's hash, computed in the
        // normalizing scan without building the text. The text itself is built once, for a
        // fingerprint not yet tracked.
        long key = fingerprintKey(loggerName, level, throwableClassName, normalizations.hash(rawMessage));
        Entry entry;
        while (true) {
            entry = counters.get(key);
            if (entry == null) {
                StormFingerprint fingerprint = new StormFingerprint(loggerName, level, throwableClassName,
                        StormMessageNormalizer.normalize(rawMessage), null);
                Entry fresh = new Entry(fingerprint);
                Entry raced = counters.putIfAbsent(key, fresh);
                if (raced == null) {
                    entry = fresh;
                    evictIfOverCapacity();
                    break;
                }
                entry = raced;
            }
            if (entry.isFor(loggerName, level, throwableClassName)) {
                break;
            }
            // The key's logger, level and throwable parts are 32-bit hashes, which collide in
            // practice ("Aa" and "BB"); a different fingerprint under this key moves on to the next.
            key = nextKey(key);
        }
        StormFingerprint cheapFingerprint = entry.cheapFingerprint;

        HistoryRecord engaged;
        synchronized (entry) {
            long now = System.nanoTime();
            entry.touch(now);
            HistoryRecord active = entry.active;
            if (active != null) {
                long gap = now - entry.lastSeenNanos;
                if (gap > quietNanos) {
                    // Both fields must become visible together: Storm's own constructor requires
                    // endedAt set iff status == ENDED, and a reader (toStorm(), synchronized on the
                    // same record) must never observe one written without the other.
                    synchronized (active) {
                        active.status = StormStatus.ENDED;
                        active.endedAt = entry.lastEventInstant;
                    }
                    entry.active = null;
                    active = null;
                    entry.count = 0;
                    entry.burstStart = null;
                } else {
                    active.countEvent(timestamp);
                }
            }
            engaged = null;
            if (active == null) {
                long gap = entry.lastSeenNanos == 0 ? Long.MAX_VALUE : now - entry.lastSeenNanos;
                if (gap > windowNanos) {
                    entry.count = 1;
                    entry.burstStart = timestamp;
                } else {
                    entry.count++;
                }
                if (entry.count >= thresholdEvents) {
                    HistoryRecord record = new HistoryRecord(cheapFingerprint, StormStatus.ONGOING,
                            entry.burstStart, timestamp, entry.count);
                    entry.active = record;
                    engaged = record;
                }
            }
            entry.lastSeenNanos = now;
            entry.lastEventInstant = timestamp;
        }

        if (engaged != null) {
            publishNewStorm(engaged, source, details);
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
    private <S> void publishNewStorm(HistoryRecord record, S source, Details<S> details) {
        try {
            List<String> frames = details.topFrames(source); // null for an event with no throwable
            if (frames != null && !frames.isEmpty()) {
                record.fingerprint = new StormFingerprint(
                        record.fingerprint.loggerName(), record.fingerprint.level(),
                        record.fingerprint.throwableClass(), record.fingerprint.normalizedMessage(),
                        frames);
            }
        } catch (RuntimeException ignored) {
            // best-effort escalation only -- never fail the storm over it.
        }
        try {
            String rendered = details.firstOccurrence(source);
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
     * next event would). Not while storm detection is disabled: what was
     * tracked is frozen as of that moment, an {@code ONGOING} storm included,
     * since no event can say whether it has stopped (doc/specs/
     * storm-detection-toggle.md T6).
     */
    List<Storm> snapshot() {
        if (detectionSwitch.isEnabled()) {
            sweepEnded();
        }
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

    /**
     * Forgets every tracked fingerprint and storm, for a new measurement window when storm
     * detection is enabled again (doc/specs/storm-detection-toggle.md T6). The normalization
     * cache is kept: it holds no storm state, only how messages normalize.
     */
    void clear() {
        counters.clear();
        synchronized (historyLock) {
            history.clear();
        }
        notRetainedCount.set(0);
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
                    synchronized (current) {
                        current.status = StormStatus.ENDED;
                        current.endedAt = entry.lastEventInstant;
                    }
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
        // Only an entry with no active (ONGOING) storm is eligible: evicting an ONGOING entry would
        // orphan its HistoryRecord permanently in the ONGOING state -- neither a future event (which
        // would land on a fresh Entry) nor sweepEnded() (which only walks live counters.values())
        // could ever transition it to ENDED. If every sampled candidate is currently storming, skip
        // eviction this round rather than evict one -- a transient overshoot of the cap is preferable
        // to a phantom, permanently-stuck storm.
        Map.Entry<Long, Entry> victim = null;
        int sampled = 0;
        for (Map.Entry<Long, Entry> candidate : counters.entrySet()) {
            if (sampled++ >= EVICTION_SAMPLE_SIZE) {
                break;
            }
            if (candidate.getValue().active != null) {
                continue;
            }
            if (victim == null || candidate.getValue().lastTouched() < victim.getValue().lastTouched()) {
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
     * placeholder, collapse whitespace, trim, cap length. The rules live in
     * {@link StormMessageNormalizer}. Package-visible so a unit test can
     * assert on it directly.
     */
    static String normalize(String message) {
        return StormMessageNormalizer.normalize(message);
    }

    /**
     * The counter map's key: the fingerprint's fields, with the message as
     * its 64-bit {@link StormMessageNormalizer#hash}. The logger, level and
     * throwable class are checked against the entry found under it ({@link
     * Entry#isFor}), so only two messages with the same 64-bit hash can share
     * a tally: a merged count, never a lost event.
     */
    static long fingerprintKey(String loggerName, Level level, String throwableClass, long messageHash) {
        long h = messageHash;
        h = 31 * h + loggerName.hashCode();
        h = 31 * h + level.hashCode();
        h = 31 * h + (throwableClass == null ? 0 : throwableClass.hashCode());
        return h ^ (h >>> 29);
    }

    /** Where a fingerprint goes when another one already holds its key. */
    static long nextKey(long key) {
        return key * 0x9E3779B97F4A7C15L + 1;
    }

    /** Unlike {@link #longProperty}, {@code 0} or less is a real setting here: it disables the cache. */
    static int normalizationCacheSizeProperty() {
        String raw = System.getProperty(NORMALIZATION_CACHE_SIZE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_NORMALIZATION_CACHE_SIZE;
        }
        try {
            long configured = Long.parseLong(raw.trim());
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, configured));
        } catch (NumberFormatException e) {
            Diagnostics.warn("ignoring non-numeric " + NORMALIZATION_CACHE_SIZE_PROPERTY + "='" + raw + "', using "
                    + DEFAULT_NORMALIZATION_CACHE_SIZE);
            return DEFAULT_NORMALIZATION_CACHE_SIZE;
        }
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
            Diagnostics.warn("ignoring non-numeric " + property + "='" + raw + "', using " + fallback);
            return fallback;
        }
    }

    private static VarHandle varHandle(Class<?> owner, String field, Class<?> type) {
        try {
            return MethodHandles.lookup().findVarHandle(owner, field, type);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
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
            // Stamped at construction, not left at the field default of 0, so a brand-new entry is
            // never mistaken for the oldest (and therefore most evictable) one by evictIfOverCapacity
            // before its first event has run.
            this.lastTouchedNanos = System.nanoTime();
        }

        /** Whether this entry tracks that fingerprint, apart from its message (#147). */
        boolean isFor(String loggerName, Level level, String throwableClass) {
            StormFingerprint fp = cheapFingerprint;
            return fp.level() == level
                    && (fp.loggerName() == loggerName || fp.loggerName().equals(loggerName))
                    && Objects.equals(fp.throwableClass(), throwableClass);
        }

        /**
         * Issue #147: an opaque write, not a volatile one. Only eviction reads it, without the
         * entry's lock and only to pick an approximately least-recently-touched victim, so it
         * needs the value whole, not ordered; a volatile write costs a full fence per event.
         */
        void touch(long now) {
            LAST_TOUCHED.setOpaque(this, now);
        }

        long lastTouched() {
            return (long) LAST_TOUCHED.getOpaque(this);
        }

        private static final VarHandle LAST_TOUCHED = varHandle(Entry.class, "lastTouchedNanos", long.class);
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

        /**
         * One more event of an engaged storm, under the owning entry's lock (the only writer).
         * Issue #147: release writes, not volatile ones. A reader ({@link #toStorm}) needs each
         * value whole and no older than the previous one it saw, not a full fence per event.
         */
        void countEvent(Instant at) {
            EVENT_COUNT.setRelease(this, eventCount + 1);
            LAST_EVENT_AT.setRelease(this, at);
        }

        private static final VarHandle EVENT_COUNT = varHandle(HistoryRecord.class, "eventCount", long.class);
        private static final VarHandle LAST_EVENT_AT = varHandle(HistoryRecord.class, "lastEventAt", Instant.class);

        Storm toStorm() {
            // status and endedAt are written together, under synchronized(this), by the ENDED
            // transition above -- read them the same way so a concurrent reader can never observe
            // one updated without the other (Storm's own constructor enforces they agree).
            StormStatus statusSnapshot;
            Instant endedAtSnapshot;
            synchronized (this) {
                statusSnapshot = status;
                endedAtSnapshot = endedAt;
            }
            return new Storm(fingerprint, statusSnapshot, firstEventAt, lastEventAt, endedAtSnapshot, eventCount,
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
