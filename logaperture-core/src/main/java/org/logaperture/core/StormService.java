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
import org.logaperture.api.StormReport;
import org.logaperture.api.StormStatus;
import org.logaperture.core.spi.LoggingAdapter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code logctl storms}'s engine — see doc/specs/storm-detection.md. Mirrors
 * {@link TopService}'s shape: {@link #startDetection()} installs an always-on
 * gate-stage observer the moment a context comes up, and every {@link
 * #activeStorms} call reads whatever the detector has accumulated since.
 */
public final class StormService implements StormOperations {

    private final LoggingAdapter adapter;
    private final CapabilityPolicy policy;
    private final StormDetector detector;

    /**
     * Set once, the first time {@link #startDetection()} runs — {@code core}
     * owns this clock, not the adapter (doc/specs/storm-detection.md
     * "Reconfiguration and lifecycle"). A later re-arm call (idempotent by
     * design) never moves it forward.
     */
    private volatile Instant measurementStartedAt;

    public StormService(LoggingAdapter adapter, CapabilityPolicy policy) {
        this(adapter, policy, new StormDetector());
    }

    /** Package-visible so a test can inject a {@link StormDetector} wired with small thresholds. */
    StormService(LoggingAdapter adapter, CapabilityPolicy policy, StormDetector detector) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    /**
     * Starts (or re-confirms) storm detection. Called once at context-install
     * time, and again on every reconfiguration re-arm (folded into the same
     * {@code reapplyOnReset} lambda {@code top}'s {@code startMeasuring} joins)
     * — safe either way, since {@link LoggingAdapter#installStormDetection} is
     * itself required to be idempotent. Accumulated storm state is not
     * cleared by a re-arm: it's keyed by fingerprint, not filter identity.
     */
    public void startDetection() {
        if (measurementStartedAt == null) {
            measurementStartedAt = Instant.now();
        }
        adapter.installStormDetection(detector);
    }

    @Override
    public StormReport activeStorms(int limit) {
        requireCapability(Capability.VIEW);
        List<Storm> fromDetector = detector.snapshot();
        List<Storm> fromAdapter = adapter.storms();
        // The detector is this service's own instance and is the source of
        // truth for a real armed context; adapter.storms() covers a future
        // adapter that keeps its own history instead of using this core
        // engine (doc/specs/storm-detection.md "Adapter SPI" default empty).
        List<Storm> merged = fromAdapter.isEmpty() ? fromDetector : fromAdapter;
        List<Storm> sorted = merged.stream().sorted(StormDetector.worstFirst()).toList();
        int ongoingCount = (int) sorted.stream().filter(s -> s.status() == StormStatus.ONGOING).count();
        List<Storm> limited = limit > 0 && sorted.size() > limit ? sorted.subList(0, limit) : sorted;
        return new StormReport(List.copyOf(limited), sorted.size(), ongoingCount, measurementStartedAt,
                detector.notRetainedCount());
    }

    Instant measurementStartedAt() {
        return measurementStartedAt;
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
