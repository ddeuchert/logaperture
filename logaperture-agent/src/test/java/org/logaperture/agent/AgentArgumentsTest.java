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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/vendor-defaults.md "Agent arguments". */
class AgentArgumentsTest {

    private static final Path CWD = Path.of("/srv/wildfly/bin").toAbsolutePath();

    @Test
    void absentOrBlank_meansNoOptions() {
        assertEquals(new AgentArguments(Optional.empty(), List.of()), AgentArguments.parse(null, CWD));
        assertEquals(new AgentArguments(Optional.empty(), List.of()), AgentArguments.parse("  ", CWD));
    }

    @Test
    void vendorDefaults_absolutePath() {
        AgentArguments args = AgentArguments.parse("--vendor-defaults=/opt/app/vendor-defaults.yaml", CWD);

        assertEquals(Optional.of(Path.of("/opt/app/vendor-defaults.yaml").toAbsolutePath()), args.vendorDefaults());
        assertEquals(List.of(), args.warnings());
    }

    @Test
    void vendorDefaults_relativePath_resolvesAgainstTheWorkingDirectory() {
        AgentArguments args = AgentArguments.parse("--vendor-defaults=./conf/../vendor-defaults.yaml", CWD);

        assertEquals(Optional.of(CWD.resolve("vendor-defaults.yaml")), args.vendorDefaults());
    }

    @Test
    void aPlainCommaInsideAPath_isNotASeparator() {
        AgentArguments args = AgentArguments.parse("--vendor-defaults=/opt/a,b/vendor-defaults.yaml", CWD);

        assertEquals(Optional.of(Path.of("/opt/a,b/vendor-defaults.yaml").toAbsolutePath()), args.vendorDefaults());
        assertEquals(List.of(), args.warnings());
    }

    @Test
    void commaFollowedByDashDash_separatesOptions_andUnknownOnesWarn() {
        AgentArguments args = AgentArguments.parse("--future-thing=1,--vendor-defaults=/opt/v.yaml", CWD);

        assertEquals(Optional.of(Path.of("/opt/v.yaml").toAbsolutePath()), args.vendorDefaults());
        assertEquals(List.of("ignoring unknown agent argument '--future-thing'"), args.warnings());
    }

    @Test
    void malformedEmptyAndRepeatedOptions_warnAndAreSkipped() {
        AgentArguments args = AgentArguments.parse(
                "config=/etc/x.yaml,--vendor-defaults=,--vendor-defaults=/a.yaml,--vendor-defaults=/b.yaml", CWD);

        assertEquals(Optional.of(Path.of("/a.yaml").toAbsolutePath()), args.vendorDefaults(), "the first wins");
        assertEquals(3, args.warnings().size(), args.warnings().toString());
        assertTrue(args.warnings().get(0).startsWith("ignoring agent argument 'config=/etc/x.yaml' -- expected"),
                args.warnings().get(0));
    }
}
