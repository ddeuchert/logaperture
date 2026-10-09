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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's command reference, guide/reference/logctl.md, is generated from {@link HelpTopics}
 * and committed (doc/specs/user-documentation.md, slice 2, H6). This fails when the committed page
 * no longer matches the help; run with {@code -Dlogaperture.help.regenerate=true} to rewrite it.
 */
class HelpReferenceTest {

    /** Relative to the module directory, which is where Maven runs the tests. */
    private static final Path PAGE = Path.of("..", "guide", "reference", "logctl.md");

    @Test
    void theCommittedReferencePageMatchesTheHelp() throws IOException {
        String generated = HelpReference.markdown();
        if (Boolean.getBoolean("logaperture.help.regenerate")) {
            Files.writeString(PAGE, generated, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(PAGE), "missing " + PAGE.toAbsolutePath().normalize());
        String committed = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertEquals(generated, committed, "guide/reference/logctl.md is out of date with the help text. "
                + "Regenerate it with: " + HelpReference.REGENERATE);
    }

    @Test
    void everyTopicHasAnAnchoredSectionAndARowInTheTable() {
        String page = HelpReference.markdown();
        for (HelpTopic topic : HelpTopics.ALL) {
            assertTrue(page.contains("## " + topic.name() + " {#" + topic.anchor() + "}"), topic.name());
            assertTrue(page.contains("](#" + topic.anchor() + ")"), topic.name());
        }
    }
}
