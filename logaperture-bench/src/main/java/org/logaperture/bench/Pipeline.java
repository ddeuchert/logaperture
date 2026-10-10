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
package org.logaperture.bench;

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.RuleService;
import org.logaperture.core.StormDetectionSwitch;
import org.logaperture.core.StormService;
import org.logaperture.core.TopService;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.StateStore;

import java.util.List;
import java.util.logging.Handler;

/**
 * The pieces context install puts on the hot path, installed the way a
 * container does it — doc/specs/overhead-benchmarks.md "Harness". Every
 * handler the benchmark logs through must be attached before {@link #install},
 * since the adapter wraps the handlers it finds at that moment.
 */
final class Pipeline {

    /**
     * The storm filter's state: not installed, installed and enabled, or installed and disabled --
     * the shipped default since #151 (doc/specs/storm-detection-toggle.md T1, T5).
     */
    enum Storm {
        NONE, ENABLED, DISABLED
    }

    final LoggingAdapter adapter;
    final RuleService rules;
    private final Storm storm;
    private final boolean trim;
    private final boolean top;

    private Pipeline(LoggingAdapter adapter, RuleService rules, Storm storm, boolean trim, boolean top) {
        this.adapter = adapter;
        this.rules = rules;
        this.storm = storm;
        this.trim = trim;
        this.top = top;
    }

    /** The rule filter always; the other three layers as asked. */
    static Pipeline install(Storm storm, boolean trim, boolean top) {
        LoggingAdapter adapter = JulAdapterFactory.forCurrentContext();
        CapabilityPolicy policy = CapabilityPolicy.allowAll();
        RuleService rules = new RuleService(adapter, policy, new InMemoryAuditLog(), StateStore.noOp(),
                "bench", "bench", "bench");

        // The container's own order (NoneContainer.installContext, AggregateLevelControl):
        // trim inside top on the formatter, storm inside the rule filter on the filter chain.
        if (trim) {
            rules.installTrimRendering();
        }
        if (top) {
            new TopService(adapter, policy).startMeasuring();
        }
        if (storm != Storm.NONE) {
            StormDetectionSwitch detectionSwitch = new StormDetectionSwitch(storm == Storm.ENABLED, policy,
                    new InMemoryAuditLog(), "bench");
            new StormService(adapter, policy, detectionSwitch).startDetection();
        }
        rules.installPipeline();
        return new Pipeline(adapter, rules, storm, trim, top);
    }

    /**
     * Everything a container installs at context install, as shipped: the {@code idle} scenario.
     * Storm detection is installed but disabled, its default since #151.
     */
    static Pipeline installIdle() {
        return install(Storm.DISABLED, true, true);
    }

    /** A scenario that quietly installed nothing would publish the baseline twice. */
    void requireInstalledOn(List<? extends Handler> handlers) {
        for (Handler handler : handlers) {
            requireClass("filter", handler.getFilter(), "JulRuleFilter");
            if (storm != Storm.NONE) {
                requireClass("inner filter", innerFilter(handler), "JulStormFilter");
            }
            if (top) {
                requireClass("formatter", handler.getFormatter(), "ByteCountingFormatter");
            } else if (trim) {
                requireClass("formatter", handler.getFormatter(), "JulTrimFormatter");
            }
        }
    }

    /** At trial end: {@code top} saw the records, if it was installed. */
    void requireCounted() {
        if (top && adapter.byteCounts().isEmpty()) {
            throw new IllegalStateException("top's byte counting recorded nothing");
        }
    }

    private static Object innerFilter(Handler handler) {
        try {
            var delegate = handler.getFilter().getClass().getDeclaredMethod("delegate");
            delegate.setAccessible(true);
            return delegate.invoke(handler.getFilter());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read the rule filter's delegate", e);
        }
    }

    private static void requireClass(String what, Object actual, String expectedSimpleName) {
        String name = actual == null ? "null" : actual.getClass().getSimpleName();
        if (!name.equals(expectedSimpleName)) {
            throw new IllegalStateException(what + " is " + name + ", expected " + expectedSimpleName);
        }
    }
}
