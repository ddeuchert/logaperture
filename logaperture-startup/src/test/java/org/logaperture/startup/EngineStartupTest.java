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
package org.logaperture.startup;

import org.junit.jupiter.api.Test;
import org.logaperture.api.PersistenceTier;
import org.logaperture.core.StormDetectionSwitch;
import org.logaperture.core.VendorDefaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.logaperture.startup.EngineStartup.Delivery.AGENT;
import static org.logaperture.startup.EngineStartup.Delivery.EXTENSION;

class EngineStartupTest {

    @org.junit.jupiter.api.AfterEach
    void clearClaim() {
        System.clearProperty(EngineStartup.DELIVERY_PROPERTY);
    }

    /** quarkus-extension.md #3: the first route to claim the JVM owns it; the second gets false. */
    @Test
    void claim_isFirstComeAndNamesTheOwner() {
        assertTrue(EngineStartup.claimant().isEmpty());
        assertTrue(EngineStartup.claim(AGENT));
        assertFalse(EngineStartup.claim(EXTENSION));
        assertFalse(EngineStartup.claim(AGENT), "a second claim by the same route is refused too");
        assertEquals("agent", EngineStartup.claimant().orElseThrow());
    }

    /** doc/specs/quieter-output.md Q3: the one startup line. */
    @Test
    void banner_namesTheContainer_theVendorFile_andWhatWasRestored() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("vendor", ".yaml");
        java.nio.file.Files.writeString(file, "schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: WARN\n");
        org.logaperture.core.VendorDefaults vendor = org.logaperture.core.VendorDefaultsFile.load(file);

        assertEquals("LogAperture 1.0.0 active (WildFly): vendor defaults " + file + " (1 logger); 3 sticky settings "
                + "restored", EngineStartup.banner("1.0.0", "wildfly", vendor, 3));
        assertEquals("LogAperture 1.0.0 active (JVM)",
                EngineStartup.banner("1.0.0", "none", VendorDefaults.none(), 0));
        assertEquals("LogAperture 1.0.0 active (Quarkus)",
                EngineStartup.banner("1.0.0", "quarkus", VendorDefaults.none(), 0));
        assertEquals("LogAperture 1.0.0 active (Quarkus extension)", EngineStartup.banner("1.0.0", "quarkus",
                EXTENSION, VendorDefaults.none(), 0,
                new StormDetectionSwitch.State(false, true, null)));
    }

    @Test
    void banner_saysStormDetectionOn_onlyWhenItStartsEnabled() {
        // doc/specs/storm-detection-toggle.md "Starting state": disabled, the default, says nothing.
        VendorDefaults none = VendorDefaults.none();
        java.time.Instant at = java.time.Instant.parse("2026-10-08T02:30:00Z");
        assertEquals("LogAperture 1.0.0 active (JVM): storm detection on", EngineStartup.banner("1.0.0", "none",
                AGENT, none, 0, new StormDetectionSwitch.State(true, true, null)));
        assertEquals("LogAperture 1.0.0 active (JVM)", EngineStartup.banner("1.0.0", "none", AGENT, none, 0,
                new StormDetectionSwitch.State(false, true, at)));
        // A saved setting, resumed (slice 2).
        assertEquals("LogAperture 1.0.0 active (JVM): storm detection on (sticky)", EngineStartup.banner("1.0.0",
                "none", AGENT, none, 0, new StormDetectionSwitch.State(true, false, at,
                        PersistenceTier.STICKY, null)));
        assertEquals("LogAperture 1.0.0 active (JVM): storm detection on until 2026-10-08T02:30:00Z",
                EngineStartup.banner("1.0.0", "none", AGENT, none, 0, new StormDetectionSwitch.State(
                        true, false, at, PersistenceTier.FOR, at)));
    }

    /** Q1: one audit record per vendor defaults load, naming the file and its hash. */
    @Test
    void auditVendorDefaults_writesOneRecordWithTheHash() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("vendor", ".yaml");
        java.nio.file.Files.writeString(file, "schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: WARN\n");
        org.logaperture.core.VendorDefaults vendor = org.logaperture.core.VendorDefaultsFile.load(file);
        org.logaperture.core.InMemoryAuditLog audit = new org.logaperture.core.InMemoryAuditLog();

        EngineStartup.auditVendorDefaults(vendor, audit);
        EngineStartup.auditVendorDefaults(VendorDefaults.none(), audit);

        assertEquals(1, audit.records().size());
        org.logaperture.core.AuditRecord record = audit.records().get(0);
        assertEquals("vendor-defaults", record.source());
        assertEquals(file.toString(), record.loggerName());
        assertEquals(org.logaperture.core.AuditRecord.Target.FILE, record.target(), "printed as file=<path> (#137)");
        assertEquals("loaded sha256=" + vendor.sha256().orElseThrow() + " (1 logger)", record.newValue());
        assertEquals(64, vendor.sha256().orElseThrow().length());
    }
}
