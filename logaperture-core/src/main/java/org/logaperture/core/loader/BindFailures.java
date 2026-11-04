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

    /** Why a bind did not happen: it decides the check id and the severity. */
    enum Kind {
        /** The framework said its version is too old: {@code .version}, {@code WARNING}. */
        VERSION,
        /** Its adapter could not be created, or threw: {@code .not-bound}, {@code WARNING}. */
        FAILED,
        /** No started context when the detector stopped waiting: {@code .not-bound}, {@code INFO} (often a
         *  framework the application never used). */
        GAVE_UP
    }

    private record Row(String framework, WeakReference<ClassLoader> owner, String ownerName, String reason,
                       Kind kind) {
    }

    private static final List<Row> ROWS = new ArrayList<>();

    private BindFailures() {
    }

    /** Called by {@link BoundInstance} when it gives up, after the {@code Diagnostics} line (C8). */
    static void record(String framework, ClassLoader owner, String ownerName, String reason, Kind kind) {
        synchronized (ROWS) {
            prune();
            ROWS.removeIf(row -> row.framework.equals(framework) && row.owner.get() == owner);
            if (ROWS.size() >= MAX) {
                ROWS.remove(0);
            }
            ROWS.add(new Row(framework, new WeakReference<>(owner), ownerName, reason, kind));
        }
    }

    /** The scanner could not even start the bind (the listener threw): found and not bound all the same. */
    static void recordScanFailure(String framework, ClassLoader owner, Throwable cause) {
        record(framework, owner, BoundInstance.describe(owner), "binding it threw (" + cause + ")", Kind.FAILED);
    }

    /** The framework bound after all (the same loader found again): its old row is no longer true. */
    static void forget(String framework, ClassLoader owner) {
        synchronized (ROWS) {
            ROWS.removeIf(row -> row.framework.equals(framework) && row.owner.get() == owner);
        }
    }

    /** The check id: Log4j 2's two are constants (the guide and its drift test name them), others get the suffix. */
    static String checkId(String framework, Kind kind) {
        boolean version = kind == Kind.VERSION;
        if ("log4j2".equals(framework)) {
            return version ? FRAMEWORK_VERSION_CHECK : FRAMEWORK_NOT_BOUND_CHECK;
        }
        return framework + (version ? ".version" : ".not-bound");
    }

    /** One finding per live row, in the order they happened; no context (the row is process-wide). */
    public static List<DoctorFinding> findings() {
        synchronized (ROWS) {
            prune();
            List<DoctorFinding> findings = new ArrayList<>(ROWS.size());
            for (Row row : ROWS) {
                String check = checkId(row.framework, row.kind);
                Severity severity = row.kind == Kind.GAVE_UP ? Severity.INFO : Severity.WARNING;
                String summary = row.kind == Kind.VERSION
                        ? "found " + row.framework + " in " + row.ownerName + " but it is not controlled: " + row.reason
                        : "found " + row.framework + " in " + row.ownerName + " but did not bind: " + row.reason;
                String detail = switch (row.kind) {
                    case VERSION -> null;
                    case FAILED -> "see the LogAperture line the application printed when it gave up";
                    case GAVE_UP -> "usually a " + row.framework + " the application never used";
                };
                String fix = row.kind == Kind.VERSION
                        ? "upgrade " + row.framework + " to a supported version." : null;
                findings.add(new DoctorFinding(check, severity, row.ownerName, summary, detail, fix));
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
