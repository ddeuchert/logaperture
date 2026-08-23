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

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.LevelControlService;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process, real Logback, no agent and no JMX -- proves the whole
 * non-agent stack works end-to-end programmatically before adding
 * cross-process/remote concerns (implementation plan's build sequencing,
 * step 6).
 *
 * <p>Every test points {@code logaperture.home} at a fresh {@code @TempDir}
 * so no test ever touches the developer's real {@code ~/.logaperture}.
 */
class NoneContainerTest {

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

    @Test
    void install_returnsWorkingServiceAgainstTheRealStaticContext() {
        LoggerFactory.getLogger("org.logaperture.container.none.PreExisting"); // instantiate before install

        try (NoneContainer.Installation installation = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            LevelControlService service = installation.service();
            assertNotNull(service);

            List<LoggerInfo> loggers = service.listLoggers("org.logaperture.container.none.PreExisting");
            assertEquals(1, loggers.size());
        }
    }

    @Test
    void install_capturesBaselineForPreExistingLoggers() {
        LoggerFactory.getLogger("org.logaperture.container.none.baseline.Probe");
        // No explicit level set on it -- baseline should be captured as inherited.

        try (NoneContainer.Installation installation = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            LevelControlService service = installation.service();

            Optional<LoggerInfo> info = service.listLoggers(null).stream()
                    .filter(li -> li.name().equals("org.logaperture.container.none.baseline.Probe"))
                    .findFirst();
            assertTrue(info.isPresent());

            // resetLevel on a logger that was never overridden must be a safe
            // no-op -- if baseline capture hadn't run, this would misbehave.
            service.resetLevel("org.logaperture.container.none.baseline.Probe");
        }
    }

    @Test
    void install_setLevelThenResetLevel_roundTrips() {
        try (NoneContainer.Installation installation = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            LevelControlService service = installation.service();

            service.setLevel("org.logaperture.container.none.roundtrip.Worker", Level.DEBUG, SetLevelOptions.defaults());
            LoggerInfo afterSet = service.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
            assertEquals(Level.DEBUG, afterSet.effectiveLevel());
            assertTrue(afterSet.overrideActive());

            service.resetLevel("org.logaperture.container.none.roundtrip.Worker");
            LoggerInfo afterReset = service.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
            assertTrue(!afterReset.overrideActive());
        }
    }

    // --- persistence: resume across a simulated restart -----------------------------------------

    @Test
    void install_stickyOverride_survivesASimulatedRestart() {
        String loggerName = "org.logaperture.container.none.resume.Sticky";

        try (NoneContainer.Installation first = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            first.service().setLevel(loggerName, Level.DEBUG, SetLevelOptions.sticky());
        } // "restart": releases the instance lock

        try (NoneContainer.Installation second = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            LoggerInfo info = second.service().listLoggers(loggerName).get(0);
            assertEquals(Level.DEBUG, info.effectiveLevel());
            assertTrue(info.overrideActive());
        }
    }

    @Test
    void install_expiredForOverride_doesNotReappearAfterASimulatedRestart() {
        String loggerName = "org.logaperture.container.none.resume.ExpiredFor";

        try (NoneContainer.Installation first = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            first.service().setLevel(loggerName, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));
        }
        try {
            Thread.sleep(20); // let the 1ms expiry pass while this "JVM" is "down"
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try (NoneContainer.Installation second = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            LoggerInfo info = second.service().listLoggers(loggerName).get(0);
            assertTrue(!info.overrideActive());
        }
    }

    // --- expiry sweep -------------------------------------------------------------------------------

    @Test
    void install_expirySweep_revertsAForOverrideOnSchedule() throws InterruptedException {
        String loggerName = "org.logaperture.container.none.sweep.Worker";

        try (NoneContainer.Installation installation =
                     NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog(), Duration.ofMillis(20))) {
            installation.service().setLevel(loggerName, Level.DEBUG, SetLevelOptions.forDuration(Duration.ofMillis(1)));

            LoggerInfo revertedInfo = pollUntilReverted(installation.service(), loggerName);
            assertTrue(!revertedInfo.overrideActive());
        }
    }

    private static LoggerInfo pollUntilReverted(LevelControlService service, String loggerName) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) { // up to ~2s
            LoggerInfo info = service.listLoggers(loggerName).get(0);
            if (!info.overrideActive()) {
                return info;
            }
            Thread.sleep(20);
        }
        return service.listLoggers(loggerName).get(0);
    }

    // --- reconfiguration re-application ---------------------------------------------------------------

    @Test
    void install_logbackReset_reappliesActiveOverridesWithoutDuplication() {
        String loggerName = "org.logaperture.container.none.reset.Worker";

        try (NoneContainer.Installation installation = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog())) {
            installation.service().setLevel(loggerName, Level.DEBUG, SetLevelOptions.sticky());

            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            assertTrue(factory instanceof LoggerContext, "test JVM must be bound to a real Logback LoggerContext");
            LoggerContext context = (LoggerContext) factory;

            context.reset(); // simulates the framework discarding installed levels on its own
            assertEquals(Level.DEBUG, installation.service().listLoggers(loggerName).get(0).effectiveLevel());

            context.reset(); // a second reset must not double-apply or otherwise misbehave
            assertEquals(Level.DEBUG, installation.service().listLoggers(loggerName).get(0).effectiveLevel());
        }
    }
}
