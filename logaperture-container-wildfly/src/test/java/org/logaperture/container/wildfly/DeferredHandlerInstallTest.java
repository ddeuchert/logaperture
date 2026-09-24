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
package org.logaperture.container.wildfly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.spi.ContextHandle;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/wildfly-deferred-handler-install.md: nothing that puts a filter or a
 * formatter on a handler happens before the floor, whichever trigger fires; it
 * does after. Observed on a real JBoss LogManager handler -- its formatter is
 * replaced by a wrapper once phase 2 has run.
 */
class DeferredHandlerInstallTest {

    private static final Duration FLOOR = Duration.ofSeconds(20);

    @TempDir
    Path home;

    private Handler handler;
    private Formatter original;

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        System.setProperty("logaperture.home", home.toString());
        original = new SimpleFormatter();
        handler = new StreamHandler(new ByteArrayOutputStream(), original);
        Logger.getLogger("").addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("").removeHandler(handler);
        System.clearProperty("logaperture.home");
        System.clearProperty("logaperture.instanceId");
    }

    private WildFlyContainer newHost(Duration delay, Clock clock) {
        return new WildFlyContainer(CapabilityPolicy.allowAll(), new InMemoryAuditLog(), Duration.ofMinutes(10),
                Optional::empty, delay, clock);
    }

    private AggregateLevelControl install(WildFlyContainer host) {
        host.installContext(ContextHandle.of(ContextHandle.SYSTEM, "wildfly", JulAdapterFactory.forCurrentContext()));
        return host.operations();
    }

    @Test
    void handlerLevelInstall_isHeldBackBeforeTheFloor_evenAcrossASweep() {
        MutableClock clock = new MutableClock();
        try (WildFlyContainer host = newHost(FLOOR, clock)) {
            AggregateLevelControl ops = install(host);
            assertSame(original, handler.getFormatter(), "phase 2 must not run at install");

            clock.advance(FLOOR.minusSeconds(1));
            ops.verificationSweep(clock.instant());

            assertSame(original, handler.getFormatter(), "a sweep before the floor must not install either");
        }
    }

    @Test
    void handlerLevelInstall_runsOnceTheFloorHasPassed_whenASweepFires() {
        MutableClock clock = new MutableClock();
        try (WildFlyContainer host = newHost(FLOOR, clock)) {
            AggregateLevelControl ops = install(host);

            clock.advance(FLOOR.plusSeconds(1));
            ops.verificationSweep(clock.instant());

            assertNotSame(original, handler.getFormatter(), "trim/top wrap the formatter once phase 2 runs");
        }
    }

    @Test
    void handlerLevelInstall_withZeroDelay_runsDuringInstallContextAsBeforeThisChange() {
        try (WildFlyContainer host = newHost(Duration.ZERO, Clock.systemUTC())) {
            install(host);

            assertNotSame(original, handler.getFormatter());
        }
    }

    @Test
    void handlerLevelInstall_theOneShotRunsAtTheFloorWithNoSweepAndNoListener() throws Exception {
        // sweep interval is 10 minutes, so only the one-shot can get there
        try (WildFlyContainer host = newHost(Duration.ofMillis(300), Clock.systemUTC())) {
            install(host);

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (handler.getFormatter() == original && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }

            assertNotSame(original, handler.getFormatter(), "the one-shot should have installed by now");
        }
    }

    @Test
    void close_doesNotWaitOnAPendingOneShot() {
        WildFlyContainer host = newHost(Duration.ofMinutes(10), Clock.systemUTC());
        install(host);

        long start = System.nanoTime();
        host.close();

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 2, "close() must cancel the pending install");
    }
}
