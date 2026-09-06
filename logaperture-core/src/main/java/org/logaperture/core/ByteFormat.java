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

import java.util.Locale;

/**
 * Human-scaled byte-count rendering, e.g. {@code 9.6 GB}, {@code 412 MB} —
 * shared by every control surface that needs it: {@link DoctorService}'s
 * disk-headroom summaries (doc/specs/doctor.md) and {@code logctl top}'s
 * byte-rate column (doc/specs/top.md, via {@code logaperture-cli}'s {@code
 * Format}, which is on this module's classpath the same way it already is
 * for {@link CapabilityDeniedException}). One implementation means one
 * GB/MB threshold to get right, not two copies that can drift.
 */
public final class ByteFormat {

    private ByteFormat() {
    }

    /**
     * Rounds to whole MB below 1024 MB, one decimal GB from 1024 MB up — the
     * decision is made on the MB value <em>after</em> rounding, so a count
     * that rounds up to 1024 MB (e.g. 1023.6 MB) renders as {@code 1.0 GB},
     * never the stale {@code 1024 MB}.
     */
    public static String humanReadable(double byteCount) {
        double mb = byteCount / (1024.0 * 1024.0);
        long roundedMb = Math.round(mb);
        if (roundedMb < 1024) {
            return roundedMb + " MB";
        }
        double gb = byteCount / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f GB", gb);
    }
}
