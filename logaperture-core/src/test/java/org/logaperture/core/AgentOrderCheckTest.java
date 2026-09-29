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
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** doc/specs/doctor-agent-order.md "Testing". */
class AgentOrderCheckTest {

    @TempDir
    Path dir;

    private List<DoctorFinding> run(Optional<Path> ownJar, String... arguments) {
        return new AgentOrderCheck(() -> List.of(arguments), ownJar, dir).findings();
    }

    private List<DoctorFinding> run(String... arguments) {
        return run(Optional.empty(), arguments);
    }

    private static List<DoctorFinding> ofCheck(List<DoctorFinding> findings, String check) {
        return findings.stream().filter(f -> f.check().equals(check)).toList();
    }

    private static DoctorFinding single(List<DoctorFinding> findings, String check) {
        List<DoctorFinding> matching = ofCheck(findings, check);
        assertEquals(1, matching.size(), () -> "expected one " + check + " row in " + findings);
        return matching.get(0);
    }

    // --- agent.order ------------------------------------------------------------------------------

    @Test
    void ours_first_isOneOkRow() {
        List<DoctorFinding> findings = run("-Xmx512m", "-javaagent:/opt/logaperture-agent.jar",
                "-javaagent:/opt/other.jar");

        DoctorFinding order = single(findings, AgentOrderCheck.ORDER_CHECK);
        assertEquals(Severity.OK, order.severity());
        assertEquals("logaperture-agent.jar is the first -javaagent.", order.summary());
        assertEquals(Path.of("/opt/logaperture-agent.jar").toString(), order.subject());
        assertNull(order.context(), "process-wide -- no context");
        assertEquals(1, findings.size(), "no duplicate row when nothing is duplicated");
    }

    @Test
    void oneAgentAhead_isInfo_namingIt_withDetailAndFix() {
        DoctorFinding order = single(run("-javaagent:/opt/destiny-agent.jar",
                "-javaagent:/opt/logaperture-agent.jar"), AgentOrderCheck.ORDER_CHECK);

        assertEquals(Severity.INFO, order.severity(), "Decision #1");
        assertEquals("1 agent is listed ahead of logaperture-agent.jar: destiny-agent.jar.", order.summary());
        assertTrue(order.detail().contains("premain first"), order.detail());
        assertEquals("list -javaagent:/opt/logaperture-agent.jar before the other -javaagent entries.",
                order.suggestedFix());
    }

    @Test
    void severalAgentsAhead_namedInCommandLineOrder() {
        DoctorFinding order = single(run(
                "-javaagent:/opt/perfmon4j.jar",
                "-javaagent:/opt/fss-security-agent.jar=mode=strict",
                "-javaagent:/opt/destiny-agent.jar",
                "-javaagent:/opt/logaperture-agent.jar"), AgentOrderCheck.ORDER_CHECK);

        assertEquals("3 agents are listed ahead of logaperture-agent.jar: perfmon4j.jar, "
                + "fss-security-agent.jar, destiny-agent.jar.", order.summary());
    }

    @Test
    void twoDifferentJarsSharingAFileName_ahead_areNamedInFullInTheDetail() {
        DoctorFinding order = single(run("-javaagent:/a/agent.jar", "-javaagent:/b/agent.jar",
                "-javaagent:/opt/logaperture-agent.jar"), AgentOrderCheck.ORDER_CHECK);

        assertEquals("2 agents are listed ahead of logaperture-agent.jar: agent.jar, agent.jar.", order.summary());
        assertTrue(order.detail().endsWith("Listed ahead, in order: " + Path.of("/a/agent.jar") + ", "
                + Path.of("/b/agent.jar") + "."), order.detail());
    }

    @Test
    void distinctFileNamesAhead_noFullPathsInTheDetail() {
        DoctorFinding order = single(run("-javaagent:/a/one.jar", "-javaagent:/a/one.jar",
                "-javaagent:/b/two.jar", "-javaagent:/opt/logaperture-agent.jar"), AgentOrderCheck.ORDER_CHECK);

        assertFalse(order.detail().contains("Listed ahead, in order"), order.detail());
    }

    @Test
    void agentFromJavaToolOptions_atTheFront_countsAsAhead() {
        // HotSpot reports JAVA_TOOL_OPTIONS first, then JDK_JAVA_OPTIONS, then the command line.
        DoctorFinding order = single(run("-javaagent:/opt/apm.jar", "-Dfrom.jdk=1",
                "-javaagent:/opt/logaperture-agent.jar"), AgentOrderCheck.ORDER_CHECK);

        assertEquals(Severity.INFO, order.severity());
        assertTrue(order.summary().contains("apm.jar"), order.summary());
    }

    @Test
    void oursNotOnTheCommandLine_noOrderRow() {
        // Loaded by dynamic attach: nothing identifies as ours, so the check doesn't run.
        assertTrue(run("-javaagent:/opt/other.jar", "-Xmx1g").isEmpty());
    }

    @Test
    void noArgumentsAtAll_noRows() {
        assertTrue(run().isEmpty());
    }

    @Test
    void argumentsThrow_noRows_notAFailure() {
        Supplier<List<String>> refusing = () -> {
            throw new SecurityException("denied");
        };
        assertTrue(new AgentOrderCheck(refusing, Optional.empty(), dir).findings().isEmpty());
    }

    // --- identifying our own entry (Decision #3) -----------------------------------------------------

    @Test
    void renamedJar_identifiedByCodeSource() throws IOException {
        Path renamed = Files.createFile(dir.resolve("la.jar"));

        DoctorFinding order = single(run(Optional.of(renamed), "-javaagent:" + dir.resolve("other.jar"),
                "-javaagent:" + renamed), AgentOrderCheck.ORDER_CHECK);

        assertEquals(Severity.INFO, order.severity());
        assertEquals("1 agent is listed ahead of la.jar: other.jar.", order.summary());
    }

    @Test
    void codeSourceWinsOverAnotherFileNamedLikeOurs() throws IOException {
        Path real = Files.createFile(dir.resolve("la.jar"));

        DoctorFinding order = single(run(Optional.of(real), "-javaagent:/elsewhere/logaperture-agent.jar",
                "-javaagent:" + real), AgentOrderCheck.ORDER_CHECK);

        assertTrue(order.summary().contains("ahead of la.jar"), order.summary());
    }

    @Test
    void codeSourceNotOnTheCommandLine_fallsBackToFileName() {
        DoctorFinding order = single(run(Optional.of(Path.of("/somewhere/else.jar")),
                "-javaagent:/opt/logaperture-agent-1.0.0.jar"), AgentOrderCheck.ORDER_CHECK);

        assertEquals(Severity.OK, order.severity());
        assertEquals("logaperture-agent-1.0.0.jar is the first -javaagent.", order.summary());
    }

    @Test
    void relativeAbsoluteAndSymlinkedPaths_compareEqual() throws IOException {
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Path jar = Files.createFile(lib.resolve("la.jar"));
        Path link;
        try {
            link = Files.createSymbolicLink(dir.resolve("link.jar"), jar);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks not supported here");
            return;
        }

        assertEquals(Severity.OK, single(run(Optional.of(jar), "-javaagent:./lib/la.jar"),
                AgentOrderCheck.ORDER_CHECK).severity(), "relative to user.dir");
        assertEquals(Severity.OK, single(run(Optional.of(jar), "-javaagent:" + link),
                AgentOrderCheck.ORDER_CHECK).severity(), "symlink resolves to the real jar");

        List<DoctorFinding> duplicated = run(Optional.of(jar), "-javaagent:lib/la.jar", "-javaagent:" + link);
        assertEquals(1, ofCheck(duplicated, AgentOrderCheck.DUPLICATE_CHECK).size(),
                "two spellings of one file are a duplicate");
    }

    // --- agent.duplicate (Decision #4) ---------------------------------------------------------------

    @Test
    void anotherAgentListedTwice_isInfo() {
        List<DoctorFinding> findings = run("-javaagent:/opt/logaperture-agent.jar",
                "-javaagent:/opt/destiny-agent.jar", "-javaagent:/opt/destiny-agent.jar=verbose");

        DoctorFinding duplicate = single(findings, AgentOrderCheck.DUPLICATE_CHECK);
        assertEquals(Severity.INFO, duplicate.severity());
        assertEquals("destiny-agent.jar is listed 2 times as a -javaagent.", duplicate.summary());
        assertEquals("remove all but one -javaagent:/opt/destiny-agent.jar entry.", duplicate.suggestedFix());
    }

    @Test
    void ourJarListedTwice_isWarning() {
        List<DoctorFinding> findings = run("-javaagent:/opt/logaperture-agent.jar",
                "-javaagent:/opt/logaperture-agent.jar");

        assertEquals(Severity.OK, single(findings, AgentOrderCheck.ORDER_CHECK).severity(),
                "the first entry is ours, so the order itself is fine");
        DoctorFinding duplicate = single(findings, AgentOrderCheck.DUPLICATE_CHECK);
        assertEquals(Severity.WARNING, duplicate.severity());
        assertTrue(duplicate.detail().contains("once per entry"), duplicate.detail());
    }

    @Test
    void duplicateOfAnotherAgent_reportedEvenWhenOursIsAbsent() {
        List<DoctorFinding> findings = run("-javaagent:/opt/a.jar", "-javaagent:/opt/a.jar");

        assertTrue(ofCheck(findings, AgentOrderCheck.ORDER_CHECK).isEmpty());
        assertEquals(Severity.INFO, single(findings, AgentOrderCheck.DUPLICATE_CHECK).severity());
    }

    // --- own jar ----------------------------------------------------------------------------------

    @Test
    void ownJarOf_aClassesDirectory_isEmpty() {
        // Under the build, this class loads from target/test-classes, not a jar.
        assertTrue(AgentOrderCheck.ownJarOf(AgentOrderCheckTest.class).isEmpty());
    }
}
