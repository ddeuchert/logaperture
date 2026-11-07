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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.RuleService;
import org.logaperture.core.StormDetectionSwitch;
import org.logaperture.core.StormObserver;
import org.logaperture.core.StormService;
import org.logaperture.core.spi.StateStore;

/**
 * The pieces context install puts on a Log4j 2 call, installed the way a container does it
 * (doc/specs/overhead-benchmarks.md, "Revision 2026-11-06: the Log4j 2 gate"): the adapter bound to a real
 * {@code LoggerContext}, a {@link RuleService} over it, then the trim wrapper, storm detection and the rule
 * gate, in {@code NoneContainer}'s order. There is no {@code top} layer: byte counting is not built for
 * Log4j 2 (A6, #306).
 */
final class Log4jPipeline {

    /** Storm detection: not installed, installed and enabled, or installed and disabled (the shipped default). */
    enum Storm {
        NONE, ENABLED, DISABLED
    }

    final Log4j2LoggingAdapter adapter;
    final RuleService rules;
    private final LoggerContext context;
    private final Storm storm;
    private final boolean trim;

    private Log4jPipeline(LoggerContext context, Log4j2LoggingAdapter adapter, RuleService rules, Storm storm,
            boolean trim) {
        this.context = context;
        this.adapter = adapter;
        this.rules = rules;
        this.storm = storm;
        this.trim = trim;
    }

    /** The rule gate always; storm detection and the trim wrapper as asked. */
    static Log4jPipeline install(LoggerContext context, Storm storm, boolean trim) {
        Log4j2LoggingAdapter adapter = new Log4j2LoggingAdapter(context, Log4j2Version.of(LoggerContext.class));
        CapabilityPolicy policy = CapabilityPolicy.allowAll();
        RuleService rules = new RuleService(adapter, policy, new InMemoryAuditLog(), StateStore.noOp(),
                "bench", "bench", "bench");
        if (trim) {
            rules.installTrimRendering();
        }
        if (storm != Storm.NONE) {
            StormDetectionSwitch detectionSwitch = new StormDetectionSwitch(storm == Storm.ENABLED, policy,
                    new InMemoryAuditLog(), "bench");
            new StormService(adapter, policy, detectionSwitch).startDetection();
        }
        rules.installPipeline();
        return new Log4jPipeline(context, adapter, rules, storm, trim);
    }

    /** What a container installs at context install, as shipped: the {@code idle} scenario. */
    static Log4jPipeline installIdle(LoggerContext context) {
        return install(context, Storm.DISABLED, true);
    }

    /**
     * A scenario that quietly installed nothing would publish the baseline twice: the gate must be the
     * configuration's filter, storm detection present and enabled or disabled as asked, and every appender slot of
     * every {@code LoggerConfig} (the root's, and each worker's in the per-thread shape, which are the ones the
     * benchmark logs through) a {@link TrimAppender} when trim is on.
     */
    void requireInstalled() {
        Configuration configuration = context.getConfiguration();
        if (!(configuration.getFilter() instanceof GateFilter gateFilter)) {
            throw new IllegalStateException("the gate is not the configuration's only filter: " + configuration.getFilter());
        }
        Object installed = field(gateFilter, "storm");
        if (storm == Storm.NONE) {
            if (installed != null) {
                throw new IllegalStateException("storm detection is installed in a scenario that has none");
            }
        } else if (!(installed instanceof StormObserver observer) || observer.isActive() != (storm == Storm.ENABLED)) {
            throw new IllegalStateException("storm detection should be " + (storm == Storm.ENABLED ? "enabled" : "disabled")
                    + " but the gate holds " + installed);
        }
        if (!trim) {
            return;
        }
        for (LoggerConfig loggerConfig : HandlerLevels.loggerConfigs(configuration)) {
            for (Appender appender : loggerConfig.getAppenders().values()) {
                if (!(appender instanceof TrimAppender)) {
                    throw new IllegalStateException("slot of '" + loggerConfig.getName() + "' holds "
                            + appender.getClass().getSimpleName() + ", expected TrimAppender (trim is off on this Log4j: "
                            + adapter.trimDisabledReason().orElse("no reason recorded") + ")");
                }
            }
        }
    }

    private static Object field(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read " + name + " of " + target.getClass().getSimpleName(), e);
        }
    }
}
