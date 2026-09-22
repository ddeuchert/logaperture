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

import org.logaperture.api.LogRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-logger {@link LogRule} attachment and {@code useParentRules} state —
 * doc/specs/rule-pipeline-foundation.md "Logger scope and inheritance". A
 * logger holds zero or more directly-attached rules, independent of any
 * level state; every mutating method here is package-private, called only
 * by {@link RuleService} once its own capability/suppression-floor checks
 * have already passed.
 */
final class RuleRegistry {

    private final Map<String, List<LogRule>> rulesByLogger = new ConcurrentHashMap<>();
    /** Kept in step with {@link #rulesByLogger} under the same lock, so {@link #findById}/{@link #removeById} are O(1) instead of a full scan (a code-review finding). */
    private final Map<String, LogRule> byId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> useParentRulesByLogger = new ConcurrentHashMap<>();

    synchronized void attach(LogRule rule) {
        rulesByLogger.computeIfAbsent(rule.loggerName(), name -> new CopyOnWriteArrayList<>()).add(rule);
        byId.put(rule.id(), rule);
    }

    Optional<LogRule> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Removes the rule with this id, if any. Returns it so the caller can audit/report what was removed. */
    synchronized Optional<LogRule> removeById(String id) {
        LogRule rule = byId.remove(id);
        if (rule == null) {
            return Optional.empty();
        }
        List<LogRule> rules = rulesByLogger.get(rule.loggerName());
        if (rules != null) {
            rules.remove(rule);
        }
        return Optional.of(rule);
    }

    /** Every attached rule, across every logger, removed and returned. */
    synchronized List<LogRule> removeAll() {
        List<LogRule> all = List.copyOf(byId.values());
        rulesByLogger.clear();
        byId.clear();
        return all;
    }

    /** A point-in-time snapshot of every attached rule, across every logger. */
    List<LogRule> all() {
        return List.copyOf(byId.values());
    }

    /** The rules attached directly to {@code loggerName} — not inherited ones. */
    List<LogRule> directRulesFor(String loggerName) {
        List<LogRule> rules = rulesByLogger.get(loggerName);
        return rules == null ? List.of() : List.copyOf(rules);
    }

    /** Default {@code true} — modelled on {@code use-parent-handlers}, see the class doc. */
    boolean useParentRules(String loggerName) {
        return useParentRulesByLogger.getOrDefault(loggerName, Boolean.TRUE);
    }

    void setUseParentRules(String loggerName, boolean useParentRules) {
        useParentRulesByLogger.put(loggerName, useParentRules);
    }
}
