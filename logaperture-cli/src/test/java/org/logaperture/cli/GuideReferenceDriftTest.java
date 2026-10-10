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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's hand-written reference pages against the names the code actually uses
 * (doc/specs/user-documentation.md U6): every {@code -Dlogaperture.*} property and agent argument
 * in guide/reference/agent-options.md, every {@code doctor} check id in
 * guide/reference/doctor-checks.md, and nothing on either page the code no longer has.
 *
 * <p>It reads every module's main sources, so a name added anywhere is caught. It lives here, beside
 * {@link HelpReferenceTest}, because this module already owns the other generated-or-checked page.
 */
class GuideReferenceDriftTest {

    /** Relative to this module's directory, which is where Maven runs the tests. */
    private static final Path REPO = Path.of("..");
    private static final Path AGENT_OPTIONS = REPO.resolve("guide/reference/agent-options.md");
    private static final Path DOCTOR_CHECKS = REPO.resolve("guide/reference/doctor-checks.md");

    private static final Pattern PROPERTY = Pattern.compile("\"(logaperture\\.[A-Za-z][A-Za-z0-9.]*)\"");
    private static final Pattern PROPERTY_ON_PAGE = Pattern.compile("(?:(?<=-D)|(?<![.\\w]))logaperture\\.[A-Za-z][A-Za-z0-9.]*[A-Za-z0-9]");
    private static final Pattern AGENT_ARGUMENT = Pattern.compile("static final String [A-Z_]+ = \"(--[a-z-]+)\"");
    private static final Pattern AGENT_ARGUMENT_ON_PAGE = Pattern.compile("^`(--[a-z-]+)=", Pattern.MULTILINE);
    private static final Pattern DOCTOR_CHECK = Pattern.compile(
            "(?:new DoctorFinding|okFinding)\\(\\s*\"([a-z][a-z.-]+)\"|\\b[A-Z_]*CHECK\\s*=\\s*\"([a-z][a-z.-]+)\"");
    private static final Pattern DOCTOR_CHECK_ON_PAGE = Pattern.compile("^### `([a-z][a-z.-]+)`$", Pattern.MULTILINE);

    @Test
    void everySystemPropertyIsOnTheAgentOptionsPage() {
        Set<String> inCode = find(PROPERTY, mainSources());
        assertTrue(inCode.size() > 10, "found only " + inCode + " -- is the source scan looking in the right place?");
        Set<String> onPage = find(PROPERTY_ON_PAGE, read(AGENT_OPTIONS));
        assertEquals(Set.of(), difference(inCode, onPage),
                "properties the code reads that guide/reference/agent-options.md doesn't list");
        assertEquals(Set.of(), difference(onPage, inCode),
                "properties guide/reference/agent-options.md lists that the code no longer reads");
    }

    @Test
    void everyAgentArgumentIsOnTheAgentOptionsPage() {
        Set<String> inCode = find(AGENT_ARGUMENT,
                read(REPO.resolve("logaperture-agent/src/main/java/org/logaperture/agent/AgentArguments.java")));
        assertTrue(!inCode.isEmpty(), "no agent arguments found in AgentArguments.java");
        assertEquals(inCode, find(AGENT_ARGUMENT_ON_PAGE, read(AGENT_OPTIONS)),
                "agent arguments in AgentArguments.java vs. those guide/reference/agent-options.md describes");
    }

    @Test
    void everyDoctorCheckHasASectionOnTheDoctorChecksPage() {
        Set<String> inCode = find(DOCTOR_CHECK, mainSources());
        assertTrue(inCode.size() > 5, "found only " + inCode + " -- is the source scan looking in the right place?");
        assertEquals(inCode, find(DOCTOR_CHECK_ON_PAGE, read(DOCTOR_CHECKS)),
                "doctor check ids in the code vs. the sections of guide/reference/doctor-checks.md");
    }

    /** Every module's main Java sources, concatenated. Tests and generated code are left out. */
    private static String mainSources() {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> modules = Files.list(REPO)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("logaperture-")).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        all.append(read(file)).append('\n');
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all.toString();
    }

    /** Every match of the pattern's first non-null group. */
    private static Set<String> find(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    found.add(m.group(g));
                    break;
                }
            }
            if (m.groupCount() == 0) {
                found.add(m.group());
            }
        }
        return found;
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
