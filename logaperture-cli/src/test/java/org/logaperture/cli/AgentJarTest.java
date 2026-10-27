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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/logctl-attach.md #2: which agent jar is loaded. */
class AgentJarTest {

    @TempDir
    Path dir;

    private static byte[] agentJar(String version, boolean agentClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        if (agentClass) {
            manifest.getMainAttributes().putValue("Agent-Class", "org.logaperture.agent.LogApertureAgent");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new JarOutputStream(bytes, manifest).close();
        return bytes.toByteArray();
    }

    private Path besideWith(String version) throws IOException {
        Path beside = Files.createDirectories(dir.resolve("beside"));
        Files.write(beside.resolve(AgentJar.FILE_NAME), agentJar(version, true));
        return beside;
    }

    private List<Path> extractTo() {
        return List.of(dir.resolve("home").resolve("agent"));
    }

    @Test
    void anExplicitJarAlwaysWinsEvenWhenOlder() throws IOException {
        Path given = dir.resolve("given.jar");
        Files.write(given, agentJar("0.9.0", true));
        Path beside = besideWith("1.5.0");

        AgentJar.Choice choice = AgentJar.choose(given, beside, agentJar("1.2.0", true), extractTo());

        assertEquals(AgentJar.Source.EXPLICIT, choice.source());
        assertEquals(given.toAbsolutePath(), choice.path());
        assertEquals("0.9.0", choice.version());
    }

    @Test
    void anExplicitJarThatIsNotThereIsAUsageError() {
        CliError error = assertThrows(CliError.class,
                () -> AgentJar.choose(dir.resolve("missing.jar"), null, null, extractTo()));
        assertEquals(CliError.USAGE, error.exitCode());
    }

    @Test
    void aNewerJarOfTheSameMajorBeatsTheEmbeddedOne() throws IOException {
        Path beside = besideWith("1.3.0");

        AgentJar.Choice choice = AgentJar.choose(null, beside, agentJar("1.2.0", true), extractTo());

        assertEquals(AgentJar.Source.BESIDE_CLI, choice.source());
        assertEquals(beside.resolve(AgentJar.FILE_NAME).toAbsolutePath(), choice.path());
    }

    @Test
    void aReleaseIsNewerThanItsOwnPreRelease() throws IOException {
        AgentJar.Choice choice = AgentJar.choose(null, besideWith("1.0.0"), agentJar("1.0.0-beta.3", true), extractTo());
        assertEquals(AgentJar.Source.BESIDE_CLI, choice.source());
    }

    @Test
    void olderEqualOrOtherMajorJarsAreNotUsed() throws IOException {
        for (String offered : List.of("1.1.0", "1.2.0", "2.0.0", "garbage")) {
            AgentJar.Choice choice = AgentJar.choose(null, besideWith(offered), agentJar("1.2.0", true), extractTo());
            assertEquals(AgentJar.Source.EMBEDDED, choice.source(), offered);
        }
    }

    @Test
    void aFileBesideTheCliThatIsNotAnAgentIsIgnored() throws IOException {
        Path beside = Files.createDirectories(dir.resolve("beside"));
        Files.write(beside.resolve(AgentJar.FILE_NAME), agentJar("9.0.0", false)); // no Agent-Class
        assertEquals(AgentJar.Source.EMBEDDED,
                AgentJar.choose(null, beside, agentJar("1.2.0", true), extractTo()).source());

        Files.writeString(beside.resolve(AgentJar.FILE_NAME), "not a jar");
        assertEquals(AgentJar.Source.EMBEDDED,
                AgentJar.choose(null, beside, agentJar("1.2.0", true), extractTo()).source());
    }

    @Test
    void theEmbeddedCopyIsWrittenOnceAndReused() throws IOException {
        byte[] embedded = agentJar("1.2.0", true);

        AgentJar.Choice first = AgentJar.choose(null, null, embedded, extractTo());
        long written = Files.getLastModifiedTime(first.path()).toMillis();
        AgentJar.Choice second = AgentJar.choose(null, null, embedded, extractTo());

        assertEquals(AgentJar.Source.EMBEDDED, first.source());
        assertEquals(first.path(), second.path());
        assertEquals(written, Files.getLastModifiedTime(second.path()).toMillis());
        assertTrue(first.path().getFileName().toString().startsWith("logaperture-agent-1.2.0-"));
        assertEquals("1.2.0", first.version());
        try (var files = Files.list(first.path().getParent())) {
            assertEquals(1L, files.count(), "no partial file is left behind");
        }
    }

    @Test
    void aRebuiltJarWithTheSameVersionNeverOverwritesTheOneInUse() throws IOException {
        byte[] original = agentJar("1.2.0-SNAPSHOT", true);
        Manifest other = new Manifest();
        other.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        other.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.0-SNAPSHOT");
        other.getMainAttributes().putValue("Agent-Class", "org.logaperture.agent.Changed");
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        new JarOutputStream(rebuilt, other).close();

        Path first = AgentJar.extract(original, "1.2.0-SNAPSHOT", extractTo());
        Path second = AgentJar.extract(rebuilt.toByteArray(), "1.2.0-SNAPSHOT", extractTo());

        assertTrue(Files.isRegularFile(first));
        assertTrue(!first.equals(second));
        assertEquals(java.util.Arrays.toString(original), java.util.Arrays.toString(Files.readAllBytes(first)));
    }

    @Test
    void anUnwritableDirectoryFallsThroughToTheNext() throws IOException {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file, not a directory");
        List<Path> dirs = List.of(blocker.resolve("agent"), dir.resolve("fallback"));

        Path written = AgentJar.extract(agentJar("1.2.0", true), "1.2.0", dirs);

        assertEquals(dir.resolve("fallback"), written.getParent());
    }

    @Test
    void whenNoDirectoryCanBeWrittenTheMessageNamesTheFlag() throws IOException {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file, not a directory");

        CliError error = assertThrows(CliError.class,
                () -> AgentJar.extract(agentJar("1.2.0", true), "1.2.0", List.of(blocker.resolve("agent"))));

        assertTrue(error.getMessage().contains("--agent-jar"));
    }

    @Test
    void aBuildWithNoEmbeddedJarAndNothingBesideItAsksForTheFlag() {
        CliError error = assertThrows(CliError.class, () -> AgentJar.choose(null, null, null, extractTo()));
        assertEquals(CliError.USAGE, error.exitCode());
        assertTrue(error.getMessage().contains("--agent-jar"));
    }

    @Test
    void aJarBesideTheCliIsUsedWhenNothingIsEmbedded() throws IOException {
        AgentJar.Choice choice = AgentJar.choose(null, besideWith("1.0.0"), null, extractTo());
        assertEquals(AgentJar.Source.BESIDE_CLI, choice.source());
    }

    @Test
    void versionsOrderAsSemanticVersioningOrdersThem() {
        List<String> ascending = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0");
        for (int i = 0; i + 1 < ascending.size(); i++) {
            AgentJar.Version lower = AgentJar.Version.parse(ascending.get(i));
            AgentJar.Version higher = AgentJar.Version.parse(ascending.get(i + 1));
            assertTrue(lower.compareTo(higher) < 0, ascending.get(i) + " < " + ascending.get(i + 1));
            assertTrue(higher.compareTo(lower) > 0);
        }
        assertEquals(0, AgentJar.Version.parse("1.2.3+build5").compareTo(AgentJar.Version.parse("1.2.3")));
        assertNull(AgentJar.Version.parse("dev"));
        assertNull(AgentJar.Version.parse(null));
    }
}
