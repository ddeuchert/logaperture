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

import java.time.Instant;

/**
 * One audit entry — fields per doc/logaperture-spec.md §9.7, scoped down
 * per doc/specs/level-control.md (no hash-chaining, no syslog/Event Log
 * mirroring in this slice; see {@link StderrAuditLog}).
 *
 * @param previousValue rendered as a display string ({@code Level.toString()}
 *                       or {@code "<inherited>"}), not the {@code Level}
 *                       type itself, so the record stays trivially loggable
 * @param newValue      same rendering convention as {@code previousValue}
 * @param action        {@link Action#MUTATION} for {@code setLogger},
 *                      {@link Action#REVERSION} for {@code resetLogger}/
 *                      {@code resetAll} — "records the revert as well as
 *                      the change" (§9.7)
 * @param loggerName    the name of what changed -- a logger's name for a {@link Target#LOGGER}
 *                      record, a handler's for {@link Target#HANDLER}, a path for {@link
 *                      Target#FILE}; {@code target} says which
 * @param origin        what made the change beyond its source, or {@code null}: for a recipe's
 *                      change, {@code recipe <id> from <location>} (doc/specs/recipes.md B5)
 * @param target        what kind of thing {@code loggerName} names, and so the label the audit
 *                      line prints it under (doc/logaperture-spec.md §9.7, issue #137)
 */
public record AuditRecord(
        Instant timestamp,
        String principal,
        String source,
        String loggerName,
        String previousValue,
        String newValue,
        String reason,
        Action action,
        String origin,
        Target target) {

    public AuditRecord {
        target = target == null ? Target.LOGGER : target;
    }

    /** A logger record -- every record whose writer doesn't say otherwise. */
    public AuditRecord(Instant timestamp, String principal, String source, String loggerName, String previousValue,
            String newValue, String reason, Action action, String origin) {
        this(timestamp, principal, source, loggerName, previousValue, newValue, reason, action, origin,
                Target.LOGGER);
    }

    /** A logger record with no origin -- every change not made by a recipe. */
    public AuditRecord(Instant timestamp, String principal, String source, String loggerName, String previousValue,
            String newValue, String reason, Action action) {
        this(timestamp, principal, source, loggerName, previousValue, newValue, reason, action, null);
    }

    /** This record, naming a {@code target} of the given kind instead. */
    public AuditRecord withTarget(Target target) {
        return new AuditRecord(timestamp, principal, source, loggerName, previousValue, newValue, reason, action,
                origin, target);
    }

    /** What an audit record's target names -- doc/logaperture-spec.md §9.7 (issue #137). */
    public enum Target {
        /** A logger; also a rule, which is printed under the logger it applies to. */
        LOGGER("logger"),
        /** A handler, or the {@code DEFAULT_HANDLERS} membership. */
        HANDLER("handler"),
        /** A file as a whole: the vendor defaults file loaded, the state file resumed from. */
        FILE("file"),
        /** An on/off switch: {@code storm-detection} (doc/specs/storm-detection-toggle.md T8). */
        SWITCH("switch");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        /** The key the audit line prints the target under: {@code logger=}, {@code handler=}, {@code file=}, {@code switch=}. */
        public String label() {
            return label;
        }
    }

    public enum Action {
        MUTATION,
        REVERSION
    }
}
