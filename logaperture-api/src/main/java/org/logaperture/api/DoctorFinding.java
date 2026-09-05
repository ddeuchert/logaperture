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
package org.logaperture.api;

/**
 * One row of {@code logctl doctor}'s output — see doc/specs/doctor.md "Data
 * model". Framework-independent (lives in {@code api}, alongside {@link
 * HandlerFloor}), so both {@code core} (to run the checks) and every
 * control surface (to render them) depend on it without depending on any
 * one adapter.
 *
 * @param check        a stable id for the check that produced this row,
 *                      e.g. {@code "handler.unbounded-growth"} — never
 *                      shown to a user directly, but stable across releases
 *                      for anything scripting against {@code --json}
 * @param severity     how urgently this needs attention
 * @param subject      what the finding is about — a handler ref, a logger
 *                      name, or a filesystem path, depending on {@code check}
 * @param summary      the one-line message rendered by default
 * @param detail       an optional longer explanation; {@code null} when
 *                      {@code summary} already says everything worth saying
 * @param suggestedFix an exact command or config change that addresses the
 *                      finding, when there's an unambiguous one; {@code
 *                      null} otherwise — never a vague pointer
 * @param context      the owning logging context's stable key, or {@code
 *                      null} on a row produced by a single-context service
 *                      directly. Mirrors {@link LoggerInfo#context()};
 *                      {@code AggregateLevelControl} stamps the real key
 *                      ({@link #withContext}) on every row it returns.
 */
public record DoctorFinding(
        String check,
        Severity severity,
        String subject,
        String summary,
        String detail,
        String suggestedFix,
        String context) {

    public DoctorFinding {
        if (check == null || check.isEmpty()) {
            throw new IllegalArgumentException("check must not be null or empty");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        if (subject == null || subject.isEmpty()) {
            throw new IllegalArgumentException("subject must not be null or empty");
        }
        if (summary == null || summary.isEmpty()) {
            throw new IllegalArgumentException("summary must not be null or empty");
        }
    }

    /**
     * A single-context service builds its findings without a context key;
     * {@code AggregateLevelControl} fills it in afterwards. Keeps every
     * existing {@code new DoctorFinding(...)} call site unchanged.
     */
    public DoctorFinding(String check, Severity severity, String subject, String summary, String detail,
            String suggestedFix) {
        this(check, severity, subject, summary, detail, suggestedFix, null);
    }

    /** This same finding, tagged with its owning context's stable key. */
    public DoctorFinding withContext(String context) {
        return new DoctorFinding(check, severity, subject, summary, detail, suggestedFix, context);
    }

    /** This same finding, escalated to {@code newSeverity} — doc/specs/doctor.md Decision #6's cross-check escalation. */
    public DoctorFinding withSeverity(Severity newSeverity) {
        return new DoctorFinding(check, newSeverity, subject, summary, detail, suggestedFix, context);
    }
}
