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
package org.logaperture.container.none;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.Level;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditRecord;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/log4j2-adapter.md A1: the container wires {@code LoggingAdapter#onNativeChange} to the
 * override check, so a level changed natively against an active override (Spring's
 * {@code logging.level}, Actuator) is put back when it happens, and audited.
 */
class NativeChangeReassertTest {

    private static final String LOGGER = "org.logaperture.container.none.nativechange.Worker";

    @TempDir
    private Path home;

    @BeforeEach
    void pointAtTempHome() {
        System.setProperty("logaperture.home", home.toString());
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("logaperture.home");
        System.clearProperty("logaperture.instanceId");
    }

    /** A framework with a "someone else changed a level" event, like Log4j 2's {@code updateLoggers()}. */
    private static final class NativeChangeAdapter implements LoggingAdapter {
        private final Map<String, Level> levels = new LinkedHashMap<>();
        private Runnable nativeListener;
        private Runnable resetListener;

        @Override
        public List<String> knownLoggerNames() {
            return new ArrayList<>(levels.keySet());
        }

        @Override
        public Optional<Level> configuredLevel(String loggerName) {
            return Optional.ofNullable(levels.get(loggerName));
        }

        @Override
        public Level effectiveLevel(String loggerName) {
            return levels.getOrDefault(loggerName, Level.INFO);
        }

        @Override
        public void applyLevel(String loggerName, Level level) {
            if (level == null) {
                levels.remove(loggerName);
            } else {
                levels.put(loggerName, level);
            }
            // Log4j's own updateLoggers() would fire an event here; the real adapter ignores its own.
        }

        @Override
        public void onNativeChange(Runnable listener) {
            nativeListener = listener;
        }

        @Override
        public void onReset(Runnable listener) {
            resetListener = listener;
        }

        /** Someone else sets a level and Log4j tells the listener. */
        void changeNatively(String loggerName, Level level) {
            levels.put(loggerName, level);
            if (nativeListener != null) {
                nativeListener.run();
            }
        }
    }

    @Test
    void aNativeChangeAgainstAnActiveOverride_isPutBack_andAudited() {
        NativeChangeAdapter adapter = new NativeChangeAdapter();
        adapter.applyLevel(LOGGER, Level.INFO);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        try (NoneContainer root = new NoneContainer(CapabilityPolicy.allowAll(), audit)) {
            root.installContext(ContextHandle.of(ContextHandle.SYSTEM, "none", adapter));
            AggregateLevelControl ops = root.operations();
            ops.setLogger(LOGGER, Level.DEBUG, SetLevelOptions.sticky());
            assertEquals(Level.DEBUG, adapter.effectiveLevel(LOGGER));

            adapter.changeNatively(LOGGER, Level.WARN); // Spring's logging.level, or an Actuator POST

            assertEquals(Level.DEBUG, adapter.effectiveLevel(LOGGER), "the override holds");
            List<AuditRecord> reasserts = audit.records().stream()
                    .filter(r -> "verification-sweep".equals(r.source()) && LOGGER.equals(r.loggerName()))
                    .toList();
            assertEquals(1, reasserts.size(), "a suppressed change is never silent (§9.6): " + audit.records());
            assertEquals("WARN", reasserts.get(0).previousValue());
            assertEquals("DEBUG", reasserts.get(0).newValue());
        }
    }

    @Test
    void aNativeChangeToALoggerWithNoOverride_isLeftAlone() {
        NativeChangeAdapter adapter = new NativeChangeAdapter();
        adapter.applyLevel(LOGGER, Level.INFO);
        InMemoryAuditLog audit = new InMemoryAuditLog();
        try (NoneContainer root = new NoneContainer(CapabilityPolicy.allowAll(), audit)) {
            root.installContext(ContextHandle.of(ContextHandle.SYSTEM, "none", adapter));
            adapter.changeNatively(LOGGER, Level.WARN);
            assertEquals(Level.WARN, adapter.effectiveLevel(LOGGER));
            assertTrue(audit.records().stream().noneMatch(r -> "verification-sweep".equals(r.source())));
        }
    }
}
