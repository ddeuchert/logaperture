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

import org.logaperture.api.DurationSyntax;
import org.logaperture.api.RuleExpression;
import org.logaperture.bridge.Diagnostics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one drop-summary line per interval -- doc/specs/quieter-output.md Q4, Q9 -- and its interval
 * setting. §9.6: suppression is never silent, so there is always a summary; only its cadence is
 * configurable.
 */
public final class DropSummary {

    /**
     * The category the summary is logged under where a platform logger takes it (Q5). {@link
     * RuleService} never lets a rule reach it or its descendants, so no {@code drop} can hide a
     * summary.
     */
    public static final String CATEGORY = "org.logaperture.drop";
    /** Names the interval, e.g. {@code 30m}. */
    public static final String INTERVAL_PROPERTY = "logaperture.drop.summaryInterval";
    /** Q9: raised from 5m at sign-off. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);
    static final Duration MINIMUM_INTERVAL = Duration.ofMinutes(1);
    /** The busiest rules named in the line; the rest are counted as {@code +N more}. */
    static final int NAMED_RULES = 5;

    private DropSummary() {
    }

    /** The interval {@code -Dlogaperture.drop.summaryInterval} sets, or the default; never below one minute. */
    public static Duration intervalFromProperty() {
        return interval(System.getProperty(INTERVAL_PROPERTY));
    }

    static Duration interval(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        Duration interval;
        try {
            interval = DurationSyntax.parse(raw.trim());
        } catch (DurationSyntax.Invalid e) {
            if (e.problem() == DurationSyntax.Problem.ZERO) {
                interval = Duration.ZERO; // below the minimum, like any other too-short interval
            } else {
                String why = e.problem() == DurationSyntax.Problem.SYNTAX ? "expected e.g. 10m or 1h" : "out of range";
                Diagnostics.warn("ignoring " + INTERVAL_PROPERTY + "='" + raw + "' (" + why + "), using "
                        + RuleExpression.duration(DEFAULT_INTERVAL.toMillis()));
                return DEFAULT_INTERVAL;
            }
        }
        if (interval.compareTo(MINIMUM_INTERVAL) < 0) {
            Diagnostics.warn(INTERVAL_PROPERTY + "='" + raw + "' is below the minimum, using 1m");
            return MINIMUM_INTERVAL;
        }
        return interval;
    }

    /**
     * The summary line for {@code counts} over {@code interval}, e.g. {@code drop summary: 440 events
     * suppressed by 10 rules in the last 10m (vendor:drop-deployment 307, r3 60, ...)}; {@code null}
     * when nothing was suppressed. Only rules that suppressed something are counted and named;
     * sampled-through events are a trailing figure, never a summary of their own. The same rule
     * counted twice (two contexts) is merged.
     */
    static String line(List<RuleService.DropCount> counts, Duration interval) {
        Map<String, long[]> byRule = new LinkedHashMap<>();
        for (RuleService.DropCount count : counts) {
            long[] totals = byRule.computeIfAbsent(count.ruleId(), id -> new long[2]);
            totals[0] += count.suppressed();
            totals[1] += count.sampled();
        }
        long suppressed = byRule.values().stream().mapToLong(totals -> totals[0]).sum();
        long sampled = byRule.values().stream().mapToLong(totals -> totals[1]).sum();
        if (suppressed == 0) {
            return null;
        }
        List<Map.Entry<String, long[]>> ranked = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byRule.entrySet()) {
            if (entry.getValue()[0] > 0) {
                ranked.add(entry);
            }
        }
        ranked.sort(Comparator.comparingLong((Map.Entry<String, long[]> entry) -> entry.getValue()[0]).reversed()
                .thenComparing(Map.Entry::getKey));
        List<String> named = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : ranked.subList(0, Math.min(NAMED_RULES, ranked.size()))) {
            named.add(entry.getKey() + " " + entry.getValue()[0]);
        }
        if (ranked.size() > NAMED_RULES) {
            named.add("+" + (ranked.size() - NAMED_RULES) + " more");
        }
        int rules = ranked.size();
        return "drop summary: " + suppressed + (suppressed == 1 ? " event" : " events") + " suppressed by " + rules
                + (rules == 1 ? " rule" : " rules") + " in the last " + RuleExpression.duration(interval.toMillis())
                + " (" + String.join(", ", named) + ")"
                + (sampled == 0 ? "" : "; " + sampled + " sampled through");
    }
}
