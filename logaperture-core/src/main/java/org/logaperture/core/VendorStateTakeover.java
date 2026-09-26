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

import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.LevelOverride;
import org.logaperture.api.LogRule;
import org.logaperture.api.PersistedRule;
import org.logaperture.api.RuleExpression;
import org.logaperture.core.spi.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Takes over the state-file entries a loaded vendor defaults file was exported from --
 * doc/specs/export-round-trip.md "Loading the file takes over the matching state entries"
 * (issue #107). Runs once per JVM, after the state file is opened and before any context
 * resumes from it: an entry whose state id the file carries is removed from the state file
 * (so it is never resumed), audited, and named in one startup summary; an entry that differs
 * from the file's is also warned about, naming what was dropped (S4: the file wins).
 *
 * <p>Nothing happens unless the file {@linkplain VendorDefaults.Status#LOADED loaded}: a
 * rejected or missing file takes over nothing, so a broken file never costs the operator
 * their settings (S5).
 */
public final class VendorStateTakeover {

    /** Audit reason on every taken-over entry. */
    static final String REASON = "now in the vendor defaults file";

    private VendorStateTakeover() {
    }

    /** What {@link #run} took over, one description per entry, e.g. {@code "logger com.acme.Foo"}. */
    public record Result(List<String> takenOver, List<String> differed) {
        public Result {
            takenOver = List.copyOf(takenOver);
            differed = List.copyOf(differed);
        }
    }

    public static Result run(VendorDefaults file, StateStore store, AuditLog auditLog, String principal,
            Instant now) {
        Objects.requireNonNull(store, "store");
        if (file.status() != VendorDefaults.Status.LOADED) {
            return new Result(List.of(), List.of());
        }
        Takeover takeover = new Takeover(auditLog, principal, now);
        takeover.loggers(file, store);
        takeover.handlers(file, store);
        takeover.defaultHandlers(file, store);
        takeover.rules(file, store);
        takeover.report();
        return new Result(takeover.takenOver, takeover.differed);
    }

    private static final class Takeover {
        private final AuditLog auditLog;
        private final String principal;
        private final Instant now;
        final List<String> takenOver = new ArrayList<>();
        final List<String> differed = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        Takeover(AuditLog auditLog, String principal, Instant now) {
            this.auditLog = auditLog;
            this.principal = principal;
            this.now = now;
        }

        void loggers(VendorDefaults file, StateStore store) {
            Map<String, VendorDefaults.LoggerDefault> byStateId = new HashMap<>();
            for (VendorDefaults.LoggerDefault logger : file.loggers().values()) {
                if (logger.stateId() != null) {
                    byStateId.put(logger.stateId(), logger);
                }
            }
            List<String> removed = new ArrayList<>();
            for (LevelOverride persisted : store.loadAll()) {
                VendorDefaults.LoggerDefault entry = persisted.stateId() == null ? null
                        : byStateId.get(persisted.stateId());
                if (entry == null) {
                    continue;
                }
                removed.add(persisted.loggerName());
                String was = persisted.level().name();
                boolean differs = !entry.name().equals(persisted.loggerName()) || entry.level() != persisted.level();
                takenOver("logger " + persisted.loggerName(), persisted.loggerName(), was, entry.level().name(),
                        differs, was);
            }
            store.removeAll(removed);
        }

        void handlers(VendorDefaults file, StateStore store) {
            // A group override's id sits on every member entry it expanded to, so one id can name
            // several entries.
            Map<String, List<VendorDefaults.HandlerDefault>> byStateId = new HashMap<>();
            for (VendorDefaults.HandlerDefault handler : file.handlers().values()) {
                if (handler.stateId() != null) {
                    byStateId.computeIfAbsent(handler.stateId(), id -> new ArrayList<>()).add(handler);
                }
            }
            List<HandlerRef> removed = new ArrayList<>();
            for (HandlerLevelOverride persisted : store.loadAllHandlers()) {
                List<VendorDefaults.HandlerDefault> entries = persisted.stateId() == null ? null
                        : byStateId.get(persisted.stateId());
                if (entries == null) {
                    continue;
                }
                removed.add(persisted.handlerRef());
                String was = persisted.mode() == HandlerLevelMode.AUTO ? "AUTO" : persisted.level().name();
                boolean differs = false;
                for (VendorDefaults.HandlerDefault entry : entries) {
                    String level = level(entry);
                    boolean sameTarget = isGroup(persisted.handlerRef()) || entry.ref().equals(persisted.handlerRef());
                    differs |= !sameTarget || !level.equals(was);
                }
                String fileLevel = entries.size() == 1 ? level(entries.get(0))
                        : "(" + entries.size() + " handler entries)";
                takenOver("handler " + persisted.handlerRef().value(), persisted.handlerRef().value(), was, fileLevel,
                        differs, was);
            }
            store.removeAllHandlers(removed);
        }

        void defaultHandlers(VendorDefaults file, StateStore store) {
            String stateId = store.defaultHandlerMembersStateId().orElse(null);
            if (stateId == null || !stateId.equals(file.defaultHandlersStateId().orElse(null))) {
                return;
            }
            List<String> members = store.loadDefaultHandlerMembers();
            List<String> fileMembers = file.defaultHandlers().orElse(List.of()).stream().map(HandlerRef::value)
                    .toList();
            boolean differs = !new HashSet<>(members).equals(new HashSet<>(fileMembers));
            store.removeDefaultHandlerMembers();
            takenOver("default handlers", HandlerRef.DEFAULT_HANDLERS.value(), String.join(", ", members),
                    String.join(", ", fileMembers), differs, String.join(", ", members));
        }

        void rules(VendorDefaults file, StateStore store) {
            Map<String, VendorDefaults.RuleDefault> byStateId = new HashMap<>();
            for (VendorDefaults.RuleDefault rule : file.rules()) {
                if (rule.stateId() != null) {
                    byStateId.put(rule.stateId(), rule);
                }
            }
            List<String> removed = new ArrayList<>();
            for (PersistedRule persisted : store.loadAllRules()) {
                VendorDefaults.RuleDefault entry = persisted.stateId() == null ? null
                        : byStateId.get(persisted.stateId());
                if (entry == null) {
                    continue;
                }
                removed.add(persisted.id());
                String was = expressionOf(persisted);
                String fileExpression = expressionOf(entry);
                boolean differs = !entry.action().equals(persisted.action())
                        || !entry.loggerName().equals(persisted.loggerName()) || !Objects.equals(was, fileExpression);
                String what = persisted.id().startsWith(VendorDefaults.RULE_ID_PREFIX)
                        ? "the alteration of rule " + persisted.id()
                        : "rule " + persisted.id() + " (now " + entry.id() + ")";
                takenOver(what, persisted.loggerName(), persisted.id() + " (" + persisted.action() + ") " + was,
                        entry.id() + " (" + entry.action() + ") " + fileExpression, differs,
                        persisted.action() + " on " + persisted.loggerName() + " " + was);
            }
            store.removeAllRules(removed);
        }

        private void takenOver(String what, String target, String previousValue, String newValue, boolean differs,
                String dropped) {
            takenOver.add(what);
            if (differs) {
                differed.add(what);
                warnings.add(what + " changed after it was exported; the vendor defaults file's version applies "
                        + "(dropped: " + dropped + ")");
            }
            auditLog.record(new AuditRecord(now, principal, VendorDefaults.AUDIT_SOURCE, target, previousValue,
                    newValue, REASON, AuditRecord.Action.REVERSION));
        }

        void report() {
            if (takenOver.isEmpty()) {
                return;
            }
            int n = takenOver.size();
            System.err.println("[logaperture-state] " + n + " sticky setting" + (n == 1 ? " is" : "s are")
                    + " now in the vendor defaults file and " + (n == 1 ? "was" : "were")
                    + " removed from the state file: " + String.join(", ", takenOver));
            for (String warning : warnings) {
                System.err.println("[logaperture-state] WARN " + warning);
            }
        }

        private static boolean isGroup(HandlerRef ref) {
            return ref.equals(HandlerRef.ALL_HANDLERS) || ref.equals(HandlerRef.DEFAULT_HANDLERS);
        }

        private static String level(VendorDefaults.HandlerDefault entry) {
            return entry.mode() == HandlerLevelMode.AUTO ? "AUTO" : entry.level().name();
        }
    }

    /** A persisted rule's definition as {@code list rules --verbose} renders it; its matchers alone for an unknown action. */
    private static String expressionOf(PersistedRule persisted) {
        RuleFactory factory = switch (persisted.action()) {
            case "drop" -> DropFactories.resume();
            case "trim" -> TrimFactories.resume();
            default -> null;
        };
        if (factory == null) {
            return RuleExpression.of(persisted.matchers(), null, null, null);
        }
        LogRule rule = factory.create(persisted.id(), persisted.loggerName(), persisted.matchers(),
                persisted.reason(), persisted.tier(), persisted.expiresAt(), persisted.createdAt(),
                persisted.payload());
        return RuleExpression.of(rule);
    }

    private static String expressionOf(VendorDefaults.RuleDefault rule) {
        boolean drop = rule.action().equals("drop");
        boolean trim = rule.action().equals("trim");
        return RuleExpression.of(rule.matchers(), drop ? rule.sampleFull() : null, trim ? rule.frames() : null,
                trim ? rule.collapseCauses() : null);
    }
}
