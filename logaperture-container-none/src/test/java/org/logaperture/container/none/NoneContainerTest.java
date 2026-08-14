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

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.SetLevelOptions;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.InMemoryAuditLog;
import org.logaperture.core.LevelControlService;
import org.slf4j.LoggerFactory;

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
 */
class NoneContainerTest {

    @Test
    void install_returnsWorkingServiceAgainstTheRealStaticContext() {
        LoggerFactory.getLogger("org.logaperture.container.none.PreExisting"); // instantiate before install

        LevelControlService service = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog());

        assertNotNull(service);

        List<LoggerInfo> loggers = service.listLoggers("org.logaperture.container.none.PreExisting");
        assertEquals(1, loggers.size());
    }

    @Test
    void install_capturesBaselineForPreExistingLoggers() {
        LoggerFactory.getLogger("org.logaperture.container.none.baseline.Probe");
        // No explicit level set on it -- baseline should be captured as inherited.

        LevelControlService service = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog());

        Optional<LoggerInfo> info = service.listLoggers(null).stream()
                .filter(li -> li.name().equals("org.logaperture.container.none.baseline.Probe"))
                .findFirst();
        assertTrue(info.isPresent());

        // resetLevel on a logger that was never overridden must be a safe
        // no-op -- if baseline capture hadn't run, this would misbehave.
        service.resetLevel("org.logaperture.container.none.baseline.Probe");
    }

    @Test
    void install_setLevelThenResetLevel_roundTrips() {
        LevelControlService service = NoneContainer.install(CapabilityPolicy.allowAll(), new InMemoryAuditLog());

        service.setLevel("org.logaperture.container.none.roundtrip.Worker", Level.DEBUG, SetLevelOptions.defaults());
        LoggerInfo afterSet = service.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
        assertEquals(Level.DEBUG, afterSet.effectiveLevel());
        assertTrue(afterSet.overrideActive());

        service.resetLevel("org.logaperture.container.none.roundtrip.Worker");
        LoggerInfo afterReset = service.listLoggers("org.logaperture.container.none.roundtrip.Worker").get(0);
        assertTrue(!afterReset.overrideActive());
    }
}
