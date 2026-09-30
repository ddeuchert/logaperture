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

import org.junit.jupiter.api.Test;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Level;
import org.logaperture.api.RuleAttachOptions;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.core.spi.ContextHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/quieter-output.md Q4, Q5, Q9: one consolidated drop summary per interval. */
class DropSummaryTest {

    private static RuleService.DropCount count(String id, long suppressed) {
        return new RuleService.DropCount(id, "com.acme." + id, suppressed, 0);
    }

    @Test
    void line_namesTheBusiestRulesFirst_andCountsTheRest() {
        List<RuleService.DropCount> counts = List.of(count("r1", 3), count("vendor:drop-deployment", 307),
                count("r3", 60), count("r4", 20), count("r5", 24), count("r6", 5), count("r7", 21));

        assertEquals("drop summary: 440 events suppressed by 7 rules in the last 10m (vendor:drop-deployment 307, "
                + "r3 60, r5 24, r7 21, r4 20, +2 more)", DropSummary.line(counts, Duration.ofMinutes(10)));
    }

    @Test
    void line_singular_sampled_andTheSameRuleFromTwoContextsMerged() {
        List<RuleService.DropCount> counts = List.of(new RuleService.DropCount("r1", "com.acme.a", 1, 0),
                new RuleService.DropCount("r1", "com.acme.a", 0, 2));

        assertEquals("drop summary: 1 event suppressed by 1 rule in the last 1h (r1 1); 2 sampled through",
                DropSummary.line(counts, Duration.ofHours(1)));
    }

    @Test
    void line_aRuleThatOnlySampledThrough_isNeitherCountedNorNamed() {
        List<RuleService.DropCount> counts = List.of(count("r1", 4),
                new RuleService.DropCount("r2", "com.acme.r2", 0, 5));

        assertEquals("drop summary: 4 events suppressed by 1 rule in the last 10m (r1 4); 5 sampled through",
                DropSummary.line(counts, Duration.ofMinutes(10)));
    }

    @Test
    void line_nothingDropped_isNoLine() {
        assertNull(DropSummary.line(List.of(), Duration.ofMinutes(10)));
        assertNull(DropSummary.line(List.of(new RuleService.DropCount("r1", "com.acme.r1", 0, 5)),
                Duration.ofMinutes(10)), "sampled through only: nothing was suppressed");
    }

    @Test
    void interval_defaultsTo10m_hasAMinute_minimum_andIgnoresNonsense() {
        assertEquals(Duration.ofMinutes(10), DropSummary.interval(null));
        assertEquals(Duration.ofMinutes(30), DropSummary.interval("30m"));
        assertEquals(Duration.ofHours(2), DropSummary.interval("2h"));
        assertEquals(Duration.ofMinutes(1), DropSummary.interval("10s"), "below the minimum");
        assertEquals(Duration.ofMinutes(1), DropSummary.interval("0m"), "zero is below the minimum too");
        assertEquals(Duration.ofMinutes(10), DropSummary.interval("99999999999999999999m"), "out of range");
        assertEquals(Duration.ofMinutes(10), DropSummary.interval("often"));
    }

    @Test
    void aggregate_writesNothingDuringTheFirstInterval_thenOneLineForEveryRule() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        RuleService rules = new RuleService(adapter, CapabilityPolicy.allowAll(), new InMemoryAuditLog(),
                new InMemoryStateStore(), "system", "alice", "jmx");
        rules.registerDropSupport();
        AggregateLevelControl aggregate = new AggregateLevelControl();
        aggregate.register(new AggregateLevelControl.ContextControl(ContextHandle.of("system", "system", adapter),
                new LevelControlService(adapter, new BaselineRegistry(), new OverrideRegistry(),
                        CapabilityPolicy.allowAll(), new InMemoryAuditLog(), new InMemoryStateStore(), "alice", "jmx"),
                new HandlerLevelControlService(adapter, new HandlerBaselineRegistry(), new HandlerOverrideRegistry(),
                        new DefaultHandlerGroupRegistry(), CapabilityPolicy.allowAll(), new InMemoryAuditLog(),
                        new InMemoryStateStore(), "alice", "jmx"),
                new DoctorService(adapter, CapabilityPolicy.allowAll()), new TopService(adapter,
                        CapabilityPolicy.allowAll()), new StormService(adapter, CapabilityPolicy.allowAll()), rules,
                new EnvironmentReportService(adapter, CapabilityPolicy.allowAll())));
        List<String> written = new ArrayList<>();
        aggregate.setDropSummarySink(written::add);
        rules.addRuleDrop("com.acme.a", new CompiledMatchers(Level.WARN, "noise", false, null, null, false),
                RuleAttachOptions.defaults(), SampleFullPolicy.disabled());
        rules.addRuleDrop("com.acme.b", new CompiledMatchers(Level.WARN, "noise", false, null, null, false),
                RuleAttachOptions.defaults(), SampleFullPolicy.disabled());
        for (String logger : List.of("com.acme.a", "com.acme.a", "com.acme.b")) {
            rules.gate().evaluate(new Object(), new RuleCandidateEvent(logger, Level.INFO, null, () -> "noise",
                    Instant.now()));
        }

        aggregate.reportDueDropSummaries(Instant.now());
        assertTrue(written.isEmpty(), "none during the first interval: " + written);

        aggregate.reportDueDropSummaries(Instant.now().plus(Duration.ofMinutes(11)));
        assertEquals(List.of("drop summary: 3 events suppressed by 2 rules in the last 10m (r1 2, r2 1)"), written);

        aggregate.reportDueDropSummaries(Instant.now().plus(Duration.ofMinutes(12)));
        assertEquals(1, written.size(), "not again until the next interval");
    }
}
