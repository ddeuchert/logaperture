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

import org.logaperture.api.BackendInfo;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.Objects;

/**
 * The per-context half of {@code logctl env} — see doc/specs/
 * environment-report.md. Everything else in an {@link
 * org.logaperture.api.EnvironmentReport} (agent/JVM/OS facts, the detected
 * container) is process-wide and lives on {@link AggregateLevelControl}
 * directly; only the logging backend can, in principle, differ per context
 * (§15.4's mixed-framework scenario), so it alone is fetched per context,
 * mirroring {@link DoctorService}/{@link TopService}'s "one service per
 * context" shape.
 */
public final class EnvironmentReportService {

    private final LoggingAdapter adapter;
    private final CapabilityPolicy policy;

    public EnvironmentReportService(LoggingAdapter adapter, CapabilityPolicy policy) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** This context's logging backend fact, or {@link BackendInfo#EMPTY} if this adapter can't resolve one. */
    public BackendInfo backendInfo() {
        requireCapability(Capability.VIEW);
        return adapter.backendInfo();
    }

    private void requireCapability(Capability capability) {
        if (!policy.isGranted(capability)) {
            throw new CapabilityDeniedException(capability);
        }
    }
}
