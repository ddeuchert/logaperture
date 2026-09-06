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

import org.logaperture.api.LoggerByteCount;
import org.logaperture.core.spi.LoggingAdapter;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code logctl top}'s engine — see doc/specs/top.md. Read-only in the sense
 * that matters to a user: it never changes what's logged, only measures it.
 * Unlike {@link DoctorService}, it isn't stateless between calls — {@link
 * #startMeasuring()} installs an always-on render-stage counter the moment a
 * context comes up, and every {@link #topLoggers} call reads whatever has
 * accumulated since.
 */
public final class TopService implements TopOperations {

    private final LoggingAdapter adapter;
    private final CapabilityPolicy policy;

    /**
     * Set once, the first time {@link #startMeasuring()} runs — {@code core}
     * owns this clock, not the adapter (doc/specs/top.md "Reconfiguration and
     * lifecycle"). A later re-install call (idempotent by design) never moves
     * it forward.
     */
    private volatile Instant measurementStartedAt;

    public TopService(LoggingAdapter adapter, CapabilityPolicy policy) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Starts (or re-confirms) byte counting. Called once at context-install
     * time, and again on every periodic re-verification tick standing in for
     * a reconfiguration hook this framework doesn't have — safe either way,
     * since {@link LoggingAdapter#installByteCounting()} is itself required
     * to be idempotent.
     */
    public void startMeasuring() {
        if (measurementStartedAt == null) {
            measurementStartedAt = Instant.now();
        }
        adapter.installByteCounting();
    }

    @Override
    public TopReport topLoggers(int limit) {
        requireCapability(Capability.VIEW);
        List<LoggerByteCount> sorted = adapter.byteCounts().stream()
                .sorted(Comparator.comparingLong(LoggerByteCount::totalBytes).reversed())
                .toList();
        List<LoggerByteCount> limited = limit > 0 && sorted.size() > limit ? sorted.subList(0, limit) : sorted;
        return new TopReport(List.copyOf(limited), measurementStartedAt);
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
