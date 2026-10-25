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
package org.logaperture.quarkus.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/bundled-vendor-defaults.md #3 and the text's trip from the build to the application. */
class BundledVendorDefaultsTest {

    @Test
    void errors_listsEachProblemWithItsLine() {
        List<String> errors = BundledVendorDefaults.errors(
                "schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: LOUD\n");

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("line 4: unknown level 'LOUD'"), errors.get(0));
    }

    @Test
    void errors_isEmptyForAValidFile() {
        assertTrue(BundledVendorDefaults.errors("schemaVersion: 1\nloggers:\n  - name: com.acme\n    level: WARN\n")
                .isEmpty());
    }

    /** The recorder carries the text as string constants of at most 64 KB each. */
    @Test
    void chunks_splitLongTextAndJoinBackExactly() {
        String text = "# é ".repeat(20_000) + "\nschemaVersion: 1\n";

        List<String> chunks = BundledVendorDefaults.chunks(text);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 16_000));
        assertEquals(text, BundledVendorDefaults.join(chunks));
        assertEquals(List.of(), BundledVendorDefaults.chunks(""));
    }
}
