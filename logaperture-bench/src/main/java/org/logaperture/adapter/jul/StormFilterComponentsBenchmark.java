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
package org.logaperture.adapter.jul;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.StormDetectionSwitch;
import org.logaperture.core.StormDetector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * doc/specs/overhead-benchmarks.md Decision #24: the storm filter installed but disabled, the
 * shipped default since #151 (doc/specs/storm-detection-toggle.md T5), against the filter it wraps
 * called directly. The difference is what disabled storm detection costs a log call: the extra
 * filter call and one volatile read. Too small for the pipeline's error bars, so it's measured
 * here, alone.
 *
 * <p>In {@code org.logaperture.adapter.jul} (a split package, bench jar only) so it can build a
 * {@link JulStormFilter}, which is package-private.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@State(Scope.Thread)
public class StormFilterComponentsBenchmark {

    /** What the storm filter delegates to: a real class, as a handler's own filter would be. */
    private static final class AllowFilter implements Filter {
        @Override
        public boolean isLoggable(LogRecord record) {
            return record.getLevel() != Level.OFF;
        }
    }

    private Filter delegate;
    private Filter stormOff;
    private LogRecord record;

    @Setup
    public void setUp() {
        StormDetectionSwitch disabled = new StormDetectionSwitch(false, CapabilityPolicy.allowAll(),
                new InMemoryAuditLog(), "bench");
        delegate = new AllowFilter();
        stormOff = new JulStormFilter(delegate, new StormDetector(disabled));
        record = new LogRecord(Level.INFO, "Order {0} shipped to {1}");
        record.setLoggerName("org.acme.orders.worker0");
    }

    /** {@code storm-off-delegate}: the wrapped filter, called directly -- the reference. */
    @Benchmark
    public boolean stormOffDelegate() {
        return delegate.isLoggable(record);
    }

    /** {@code storm-off}: the same call through the disabled storm filter. */
    @Benchmark
    public boolean stormOff() {
        return stormOff.isLoggable(record);
    }
}
