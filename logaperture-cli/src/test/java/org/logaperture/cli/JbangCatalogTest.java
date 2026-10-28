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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JBang catalog at the repository root (doc/specs/logctl-jbang.md #5): it has the
 * {@code logctl} alias and the alias names the published CLI artifact. Its version is not
 * compared with this build's, which is a snapshot on {@code develop}; the release cut checks it
 * against Maven Central.
 */
class JbangCatalogTest {

    /** Relative to this module's directory, which is where Maven runs the tests. */
    private static final Path CATALOG = Path.of("..", "jbang-catalog.json");

    @Test
    void catalog_hasTheLogctlAliasNamingTheCliArtifact() throws IOException {
        String json = Files.readString(CATALOG, StandardCharsets.UTF_8);

        Matcher alias = Pattern.compile(
                "\"logctl\"\\s*:\\s*\\{[^}]*\"script-ref\"\\s*:\\s*\"org\\.logaperture:logaperture-cli:(\\d[^\"]*)\"")
                .matcher(json);

        assertTrue(alias.find(), "jbang-catalog.json needs a logctl alias with script-ref org.logaperture:logaperture-cli:<release>");
        assertTrue(alias.group(1).matches("\\d+\\.\\d+\\.\\d+(-[\\w.]+)?"),
                "a pinned release, not a range or a snapshot: " + alias.group(1));
        assertTrue(!alias.group(1).endsWith("-SNAPSHOT"), "a snapshot cannot be resolved by users");
    }
}
