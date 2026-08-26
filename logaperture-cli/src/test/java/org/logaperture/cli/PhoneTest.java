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
package org.logaperture.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/specs/cli-transport.md §6.2, "The phone test, enforced": every
 * command synopsis must be readable down a phone line without the customer
 * mistyping it — a crude proxy is "contains none of {@code : = ( ) /}".
 */
class PhoneTest {

    private static final String FORBIDDEN = ":=()/";

    @Test
    void everyCommandSynopsisIsDictatable() {
        for (String synopsis : HelpText.SYNOPSES) {
            for (int i = 0; i < synopsis.length(); i++) {
                char c = synopsis.charAt(i);
                assertFalse(FORBIDDEN.indexOf(c) >= 0,
                        "synopsis \"" + synopsis + "\" contains the un-dictatable character '" + c + "'");
            }
        }
    }

    @Test
    void theHelpUsageBlockListsEverySynopsis() {
        String usage = HelpText.usage();
        for (String synopsis : HelpText.SYNOPSES) {
            assertTrue(usage.contains(synopsis), "help text is missing synopsis: " + synopsis);
        }
    }
}
