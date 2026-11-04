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
package org.logaperture.core.loader;

import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The frameworks the detector found and could not bind (doc/specs/log4j2-adapter.md Slice 7, D7-4, D7-6), for
 * {@code doctor}. A bind that failed has no context, so nothing a context's {@code DoctorService} could say:
 * {@code AggregateLevelControl.diagnose()} reads this next to the other process-wide findings.
 *
 * <p>One row per (framework, owning loader), kept while that loader is alive (a redeployed webapp's old loader
 * takes its row with it when collected), at most {@link #MAX} rows. The check id is the framework id plus
 * {@code .version} (the framework said the version is too old) or {@code .not-bound} (anything else).
 */
public final class BindFailures {

    /** The two ids for Log4j 2 (A12); other frameworks get their own id plus the same suffixes. */
    static final String FRAMEWORK_VERSION_CHECK = "log4j2.version";
    static final String FRAMEWORK_NOT_BOUND_CHECK = "log4j2.not-bound";

    static final int MAX = 16;

    private record Row(String framework, WeakReference<ClassLoader> owner, String ownerName, String reason,
                       boolean versionProblem) {
    }

    private static final List<Row> ROWS = new ArrayList<>();

    private BindFailures() {
    }

    /** Called by {@link BoundInstance} when it gives up, after the {@code Diagnostics} line (C8). */
    static void record(String framework, ClassLoader owner, String ownerName, String reason,
                       boolean versionProblem) {
        synchronized (ROWS) {
            prune();
            ROWS.removeIf(row -> row.framework.equals(framework) && row.owner.get() == owner);
            if (ROWS.size() >= MAX) {
                ROWS.remove(0);
            }
            ROWS.add(new Row(framework, new WeakReference<>(owner), ownerName, reason, versionProblem));
        }
    }

    /** One finding per live row, in the order they happened; no context (the row is process-wide). */
    public static List<DoctorFinding> findings() {
        synchronized (ROWS) {
            prune();
            List<DoctorFinding> findings = new ArrayList<>(ROWS.size());
            for (Row row : ROWS) {
                String check = row.framework + (row.versionProblem ? ".version" : ".not-bound");
                String summary = row.versionProblem
                        ? "found " + row.framework + " in " + row.ownerName + " but it is not controlled: "
                                + row.reason
                        : "found " + row.framework + " in " + row.ownerName + " but did not bind: " + row.reason;
                findings.add(new DoctorFinding(check, Severity.WARNING, row.ownerName, summary,
                        row.versionProblem ? null : "see the LogAperture line the application printed when it gave up",
                        row.versionProblem ? "upgrade " + row.framework + " to a supported version." : null));
            }
            return List.copyOf(findings);
        }
    }

    /** For the tests. */
    public static void clear() {
        synchronized (ROWS) {
            ROWS.clear();
        }
    }

    private static void prune() {
        ROWS.removeIf(row -> row.owner.get() == null);
    }
}
