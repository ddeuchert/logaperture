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
package org.logaperture.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentBootstrapTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("logaperture.disabled");
    }

    @Test
    void disabledKillSwitch_returnsWithoutTouchingInstrumentation() {
        System.setProperty("logaperture.disabled", "true");

        // Passing null Instrumentation would NPE the moment any code past
        // the kill-switch check tried to use it -- this proves the early
        // return happens before anything else runs.
        assertDoesNotThrow(() -> AgentBootstrap.start(null));
    }

    /** doc/specs/quieter-output.md Q3: the one startup line. */
    @Test
    void banner_namesTheContainer_theVendorFile_andWhatWasRestored() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("vendor", ".yaml");
        java.nio.file.Files.writeString(file, "schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: WARN\n");
        org.logaperture.core.VendorDefaults vendor = org.logaperture.core.VendorDefaultsFile.load(file);

        assertEquals("LogAperture 1.0.0 active (WildFly): vendor defaults " + file + " (1 logger); 3 sticky settings "
                + "restored", AgentBootstrap.banner("1.0.0", "wildfly", vendor, 3));
        assertEquals("LogAperture 1.0.0 active (JVM)",
                AgentBootstrap.banner("1.0.0", "none", org.logaperture.core.VendorDefaults.none(), 0));
    }

    /** Q1: one audit record per vendor defaults load, naming the file and its hash. */
    @Test
    void auditVendorDefaults_writesOneRecordWithTheHash() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("vendor", ".yaml");
        java.nio.file.Files.writeString(file, "schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: WARN\n");
        org.logaperture.core.VendorDefaults vendor = org.logaperture.core.VendorDefaultsFile.load(file);
        org.logaperture.core.InMemoryAuditLog audit = new org.logaperture.core.InMemoryAuditLog();

        AgentBootstrap.auditVendorDefaults(vendor, audit);
        AgentBootstrap.auditVendorDefaults(org.logaperture.core.VendorDefaults.none(), audit);

        assertEquals(1, audit.records().size());
        org.logaperture.core.AuditRecord record = audit.records().get(0);
        assertEquals("vendor-defaults", record.source());
        assertEquals(file.toString(), record.loggerName());
        assertEquals("loaded sha256=" + vendor.sha256().orElseThrow() + " (1 logger)", record.newValue());
        assertEquals(64, vendor.sha256().orElseThrow().length());
    }
}
