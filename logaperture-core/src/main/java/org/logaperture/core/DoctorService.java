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

import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.Severity;
import org.logaperture.core.spi.LoggingAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code logctl doctor}'s engine — see doc/specs/doctor.md. Read-only:
 * nothing here ever calls an adapter's {@code applyLevel}/{@code
 * setHandlerLevel}. Deliberately stateless between calls, unlike {@link
 * LevelControlService}/{@link HandlerLevelControlService} — there is no
 * baseline to capture or override to track, only the framework's own
 * configuration, read fresh every time.
 */
public final class DoctorService implements DoctorOperations {

    /** doc/specs/doctor.md Decision #1: a small hard-coded seed, not a curated/extensible list in this slice. */
    private static final List<String> CHATTY_LOGGER_SEED = List.of(
            "org.hibernate", "org.apache.http", "com.zaxxer.hikari", "org.apache.commons.dbcp2");

    private static final String ROOT = "ROOT";

    /** doc/specs/doctor.md Decision #2: a short in-process sample, not a maintained rate history. */
    private static final Duration WRITE_RATE_SAMPLE_WINDOW = Duration.ofMillis(300);
    private static final Duration CRITICAL_TIME_TO_FULL = Duration.ofHours(4);
    private static final Duration WARNING_TIME_TO_FULL = Duration.ofHours(24);

    private final LoggingAdapter adapter;
    private final CapabilityPolicy policy;

    public DoctorService(LoggingAdapter adapter, CapabilityPolicy policy) {
        this.adapter = adapter;
        this.policy = policy;
    }

    @Override
    public List<DoctorFinding> diagnose() {
        requireCapability(Capability.VIEW);

        List<HandlerRef> persistentHandlers = persistentHandlers();

        List<DoctorFinding> unboundedGrowth = checkUnboundedGrowth(persistentHandlers);
        List<DoctorFinding> diskHeadroom = checkDiskHeadroom(persistentHandlers);
        List<DoctorFinding> escalatedGrowth = escalateUnboundedGrowth(unboundedGrowth, diskHeadroom);

        List<DoctorFinding> findings = new ArrayList<>();
        findings.addAll(escalatedGrowth);
        findings.addAll(checkVerbosityLeftOn());
        findings.addAll(checkDuplicateOutput(persistentHandlers));
        findings.addAll(checkAutoflush());
        findings.addAll(diskHeadroom);
        return List.copyOf(findings);
    }

    /** Real handlers this adapter can act on that also persist to disk — doc/specs/doctor.md Decision #3's reused signal. */
    private List<HandlerRef> persistentHandlers() {
        if (!adapter.hasHandlerLevels()) {
            return List.of();
        }
        List<HandlerRef> persistent = new ArrayList<>();
        for (HandlerRef ref : adapter.realHandlers()) {
            if (adapter.handlerDiagnostics(ref).isPersistent()) {
                persistent.add(ref);
            }
        }
        return persistent;
    }

    // --- Check 1: unbounded file-handler growth -----------------------------------------------

    private List<DoctorFinding> checkUnboundedGrowth(List<HandlerRef> persistentHandlers) {
        if (persistentHandlers.isEmpty()) {
            return List.of(); // nothing this adapter can inspect -- the check doesn't run
        }
        List<DoctorFinding> flagged = new ArrayList<>();
        for (HandlerRef ref : persistentHandlers) {
            HandlerDiagnostics diag = adapter.handlerDiagnostics(ref);
            boolean noSizeCap = diag.maxFileSizeBytes() == null;
            boolean noBackupLimit = diag.backupCount() == null || diag.backupCount() <= 0;
            if (noSizeCap || noBackupLimit) {
                String detail = noSizeCap
                        ? "no size-based rotation is configured"
                        : "no backup/retention limit is configured (unlimited total size)";
                flagged.add(new DoctorFinding("handler.unbounded-growth", Severity.WARNING, ref.value(),
                        ref + " has no size cap — writes are unbounded.", detail,
                        "configure a size-based rotation policy on " + ref + "."));
            }
        }
        if (flagged.isEmpty()) {
            return List.of(okFinding("handler.unbounded-growth", "handlers",
                    "no unbounded file handlers found."));
        }
        return List.copyOf(flagged);
    }

    /**
     * Decision #6: the one cross-check this slice implements. A handler already
     * flagged for unbounded growth escalates to CRITICAL when the disk-headroom
     * check also flagged its own target path's volume -- writing without a cap
     * AND the disk it writes to is genuinely close to full.
     *
     * <p>Package-visible so a test can exercise the escalation rule directly
     * against synthetic findings, without depending on real disk headroom.
     */
    List<DoctorFinding> escalateUnboundedGrowth(List<DoctorFinding> growthFindings,
            List<DoctorFinding> diskFindings) {
        Set<String> lowHeadroomPaths = new LinkedHashSet<>();
        for (DoctorFinding disk : diskFindings) {
            if (disk.severity() == Severity.WARNING || disk.severity() == Severity.CRITICAL) {
                lowHeadroomPaths.add(disk.subject());
            }
        }
        if (lowHeadroomPaths.isEmpty()) {
            return growthFindings;
        }
        List<DoctorFinding> escalated = new ArrayList<>(growthFindings.size());
        for (DoctorFinding finding : growthFindings) {
            if (finding.severity() == Severity.WARNING && targetPathOf(finding.subject())
                    .map(path -> lowHeadroomPaths.contains(fileStoreKey(path)))
                    .orElse(false)) {
                escalated.add(finding.withSeverity(Severity.CRITICAL));
            } else {
                escalated.add(finding);
            }
        }
        return List.copyOf(escalated);
    }

    private Optional<Path> targetPathOf(String handlerRefValue) {
        HandlerDiagnostics diag = adapter.handlerDiagnostics(new HandlerRef(handlerRefValue));
        return Optional.ofNullable(diag.targetPath());
    }

    // --- Check 2: verbosity left on -----------------------------------------------------------

    private List<DoctorFinding> checkVerbosityLeftOn() {
        List<DoctorFinding> flagged = new ArrayList<>();
        Level rootLevel = adapter.effectiveLevel(ROOT);
        if (isChattyLevel(rootLevel)) {
            flagged.add(new DoctorFinding("logger.verbosity-left-on", Severity.WARNING, ROOT,
                    "root logger is at " + rootLevel + " — meaningfully more volume than the INFO default.",
                    null, null));
        }
        Set<String> known = Set.copyOf(adapter.knownLoggerNames());
        for (String seedName : CHATTY_LOGGER_SEED) {
            if (!known.contains(seedName)) {
                continue; // not present in this JVM -- nothing to flag
            }
            Level configured = adapter.configuredLevel(seedName).orElse(null);
            if (isChattyLevel(configured)) {
                flagged.add(new DoctorFinding("logger.verbosity-left-on", Severity.WARNING, seedName,
                        seedName + " is explicitly set to " + configured + " — a known-chatty framework logger.",
                        null, "logctl reset " + seedName));
            }
        }
        if (flagged.isEmpty()) {
            return List.of(okFinding("logger.verbosity-left-on", ROOT,
                    "no excess verbosity found at root or on a known-chatty logger."));
        }
        return List.copyOf(flagged);
    }

    private static boolean isChattyLevel(Level level) {
        return level == Level.DEBUG || level == Level.TRACE || level == Level.ALL;
    }

    // --- Check 3: duplicate output (doc/specs/doctor.md Decision #3) -------------------------

    private List<DoctorFinding> checkDuplicateOutput(List<HandlerRef> persistentHandlers) {
        if (persistentHandlers.size() < 2) {
            return List.of(); // nothing to compare -- the check doesn't run
        }
        Map<Level, List<HandlerRef>> byLevel = new LinkedHashMap<>();
        for (HandlerRef ref : persistentHandlers) {
            adapter.handlerLevel(ref).ifPresent(level ->
                    byLevel.computeIfAbsent(level, l -> new ArrayList<>()).add(ref));
        }
        List<DoctorFinding> flagged = new ArrayList<>();
        for (Map.Entry<Level, List<HandlerRef>> entry : byLevel.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String names = entry.getValue().stream().map(HandlerRef::value)
                    .reduce((a, b) -> a + " and " + b).orElse("");
            flagged.add(new DoctorFinding("handler.duplicate-output", Severity.INFO, names,
                    names + " both render at " + entry.getKey() + " — the same lines are written to both.",
                    null, null));
        }
        if (flagged.isEmpty()) {
            return List.of(okFinding("handler.duplicate-output", "handlers",
                    "no duplicate output across persistent (file) handlers."));
        }
        return List.copyOf(flagged);
    }

    // --- Check 4: autoflush on a busy handler ---------------------------------------------------

    private List<DoctorFinding> checkAutoflush() {
        if (!adapter.hasHandlerLevels()) {
            return List.of();
        }
        List<HandlerRef> inspectable = new ArrayList<>();
        List<DoctorFinding> flagged = new ArrayList<>();
        for (HandlerRef ref : adapter.realHandlers()) {
            Boolean autoFlush = adapter.handlerDiagnostics(ref).autoFlush();
            if (autoFlush == null) {
                continue; // this handler doesn't expose the concept -- skipped, not flagged clean
            }
            inspectable.add(ref);
            if (autoFlush) {
                // doc/specs/doctor.md Decision #4: fires generically, no volume
                // threshold to weigh it against until `top` exists.
                flagged.add(new DoctorFinding("handler.autoflush", Severity.WARNING, ref.value(),
                        ref + " has autoflush enabled — every record forces a flush.", null,
                        null));
            }
        }
        if (inspectable.isEmpty()) {
            return List.of(); // nothing this adapter can answer -- the check doesn't run
        }
        if (flagged.isEmpty()) {
            return List.of(okFinding("handler.autoflush", "handlers",
                    "no autoflush handlers found on a busy path."));
        }
        return List.copyOf(flagged);
    }

    // --- Check 5: disk headroom -----------------------------------------------------------------

    private List<DoctorFinding> checkDiskHeadroom(List<HandlerRef> persistentHandlers) {
        // Dedupe by FileStore -- two handlers on the same volume need only one reading.
        Map<String, Path> pathByStore = new LinkedHashMap<>();
        for (HandlerRef ref : persistentHandlers) {
            Path path = adapter.handlerDiagnostics(ref).targetPath();
            if (path == null) {
                continue;
            }
            pathByStore.putIfAbsent(fileStoreKey(path), path);
        }
        if (pathByStore.isEmpty()) {
            return List.of(); // no persistent handler's path is inspectable -- the check doesn't run
        }
        List<DoctorFinding> findings = new ArrayList<>();
        for (Map.Entry<String, Path> entry : pathByStore.entrySet()) {
            sampleHeadroom(entry.getKey(), entry.getValue()).ifPresent(findings::add);
        }
        if (findings.isEmpty()) {
            return List.of(); // every path failed to sample -- skipped, not a clean result
        }
        return List.copyOf(findings);
    }

    private Optional<DoctorFinding> sampleHeadroom(String storeKey, Path path) {
        try {
            long usableBytes = Files.getFileStore(path).getUsableSpace();
            long before = Files.exists(path) ? Files.size(path) : 0L;
            Thread.sleep(WRITE_RATE_SAMPLE_WINDOW.toMillis());
            long after = Files.exists(path) ? Files.size(path) : 0L;
            long bytesPerWindow = Math.max(0L, after - before);
            if (bytesPerWindow == 0L) {
                return Optional.of(okFinding("disk.headroom", storeKey,
                        path + " is stable — no writes observed during the sample window."));
            }
            double bytesPerHour = bytesPerWindow * (3_600_000.0 / WRITE_RATE_SAMPLE_WINDOW.toMillis());
            double hoursToFull = usableBytes / bytesPerHour;
            Severity severity = hoursToFull < CRITICAL_TIME_TO_FULL.toHours() ? Severity.CRITICAL
                    : hoursToFull < WARNING_TIME_TO_FULL.toHours() ? Severity.WARNING
                    : Severity.OK;
            String summary = String.format(Locale.ROOT,
                    "%s has %s free at ~%s/h — about %s to full at the current rate.",
                    path, formatBytes(usableBytes), formatBytes((long) bytesPerHour), formatDuration(hoursToFull));
            return Optional.of(new DoctorFinding("disk.headroom", severity, storeKey, summary, null, null));
        } catch (IOException e) {
            return Optional.empty(); // this path's FileStore isn't queryable -- skipped, not a failure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /** Package-visible so a test can compute the same key {@link #escalateUnboundedGrowth} correlates on. */
    static String fileStoreKey(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            return store.name() + ":" + store.type();
        } catch (IOException e) {
            return path.toAbsolutePath().toString();
        }
    }

    private static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) {
            return String.format(Locale.ROOT, "%.1f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.0f MB", mb);
    }

    private static String formatDuration(double hours) {
        if (hours >= 24) {
            return String.format(Locale.ROOT, "%.0fd", hours / 24);
        }
        return String.format(Locale.ROOT, "%.0fh", hours);
    }

    private static DoctorFinding okFinding(String check, String subject, String summary) {
        return new DoctorFinding(check, Severity.OK, subject, summary, null, null);
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
