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

import java.util.ArrayList;
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
    private final Map<String, Boolean> useParentRulesByLogger = new ConcurrentHashMap<>();

    synchronized void attach(LogRule rule) {
        rulesByLogger.computeIfAbsent(rule.loggerName(), name -> new CopyOnWriteArrayList<>()).add(rule);
    }

    synchronized Optional<LogRule> findById(String id) {
        for (List<LogRule> rules : rulesByLogger.values()) {
            for (LogRule rule : rules) {
                if (rule.id().equals(id)) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    /** Removes the rule with this id, if any. Returns it so the caller can audit/report what was removed. */
    synchronized Optional<LogRule> removeById(String id) {
        for (Map.Entry<String, List<LogRule>> entry : rulesByLogger.entrySet()) {
            for (LogRule rule : entry.getValue()) {
                if (rule.id().equals(id)) {
                    entry.getValue().remove(rule);
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    /** Every rule attached directly to {@code loggerName} (not its descendants' own), removed and returned. */
    synchronized List<LogRule> removeAllForLogger(String loggerName) {
        List<LogRule> removed = rulesByLogger.remove(loggerName);
        return removed == null ? List.of() : List.copyOf(removed);
    }

    /** Every attached rule, across every logger, removed and returned. */
    synchronized List<LogRule> removeAll() {
        List<LogRule> all = new ArrayList<>();
        for (List<LogRule> rules : rulesByLogger.values()) {
            all.addAll(rules);
        }
        rulesByLogger.clear();
        return List.copyOf(all);
    }

    /** A point-in-time snapshot of every attached rule, across every logger. */
    List<LogRule> all() {
        List<LogRule> all = new ArrayList<>();
        for (List<LogRule> rules : rulesByLogger.values()) {
            all.addAll(rules);
        }
        return List.copyOf(all);
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
