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
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.EnvironmentReport;
import org.logaperture.api.Severity;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "Surfaces" -- {@code env} and {@code doctor}, as the aggregate reports them. */
class VendorDefaultsReportingTest {

    private static final Path PATH = Path.of("/opt/app/conf/vendor-defaults.yaml");

    private static AggregateLevelControl aggregate(VendorDefaults vendorDefaults) {
        return new AggregateLevelControl(null, Optional::empty, null, () -> true, vendorDefaults);
    }

    @Test
    void notConfigured_addsNoDoctorFindings_andEnvSaysSo() {
        AggregateLevelControl aggregate = aggregate(VendorDefaults.none());

        assertTrue(aggregate.diagnose().isEmpty());
        EnvironmentReport env = aggregate.environmentReport();
        assertNull(env.vendorDefaultsPath());
        assertEquals("not configured", env.vendorDefaultsStatus());
    }

    @Test
    void loaded_isAnOkFinding_andEnvCarriesPathAndSummary() {
        VendorDefaults loaded = VendorDefaultsFile.parse("""
                schemaVersion: 1
                loggers:
                  - name: com.acme
                    level: WARN
                """, PATH, false);
        AggregateLevelControl aggregate = aggregate(loaded);

        List<DoctorFinding> findings = aggregate.diagnose();
        assertEquals(1, findings.size());
        assertEquals("vendor-defaults.file", findings.get(0).check());
        assertEquals(Severity.OK, findings.get(0).severity());
        EnvironmentReport env = aggregate.environmentReport();
        assertEquals(PATH.toString(), env.vendorDefaultsPath());
        assertEquals("loaded (1 logger)", env.vendorDefaultsStatus());
    }

    @Test
    void writable_addsAWarning() {
        AggregateLevelControl aggregate = aggregate(VendorDefaultsFile.parse("schemaVersion: 1\n", PATH, true));

        assertTrue(aggregate.diagnose().stream().anyMatch(
                f -> f.check().equals("vendor-defaults.writable") && f.severity() == Severity.WARNING));
    }

    @Test
    void rejected_isAWarningListingEveryError() {
        VendorDefaults rejected = VendorDefaultsFile.parse("schemaVersion: 1\nfoo: 1\nbar: 2\n", PATH, false);
        AggregateLevelControl aggregate = aggregate(rejected);

        DoctorFinding finding = aggregate.diagnose().get(0);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(PATH.toString(), finding.subject());
        assertTrue(finding.detail().contains("line 2") && finding.detail().contains("line 3"), finding.detail());
        assertEquals("rejected (2 errors)", aggregate.environmentReport().vendorDefaultsStatus());
    }
}
