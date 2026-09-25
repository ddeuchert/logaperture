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
package org.logaperture.control.jmx;

import org.junit.jupiter.api.Test;
import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.Drop;
import org.logaperture.api.Level;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SampleFullPolicy;
import org.logaperture.api.Trim;
import org.logaperture.core.RuleView;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** doc/specs/list-rules-verbose.md "Where it is built": the sampling fields {@code RuleData} carries. */
class RuleDataTest {

    private static final CompiledMatchers MATCHERS = new CompiledMatchers(Level.INFO, "ping", false, null, null, false);

    @Test
    void aDropRule_carriesItsSamplingPolicy() {
        Drop sampled = new Drop("r1", "com.acme", MATCHERS, null, PersistenceTier.SESSION, null, Instant.now(),
                SampleFullPolicy.every(Duration.ofMinutes(2)));
        Drop never = new Drop("r2", "com.acme", MATCHERS, null, PersistenceTier.SESSION, null, Instant.now(),
                SampleFullPolicy.disabled());

        RuleData sampledRow = RuleData.from(new RuleView(sampled, "system"));
        assertEquals(Boolean.TRUE, sampledRow.getSampleFullEnabled());
        assertEquals(120_000L, sampledRow.getSampleFullEveryMillis());
        assertEquals(Boolean.FALSE, RuleData.from(new RuleView(never, "system")).getSampleFullEnabled());
    }

    @Test
    void aTrimRule_hasNoSamplingPolicy() {
        Trim trim = new Trim("r3", "com.acme", MATCHERS, null, PersistenceTier.SESSION, null, Instant.now(), 2, true);

        RuleData row = RuleData.from(new RuleView(trim, "system"));

        assertNull(row.getSampleFullEnabled());
        assertNull(row.getSampleFullEveryMillis());
    }
}
