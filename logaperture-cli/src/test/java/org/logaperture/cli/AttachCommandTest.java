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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.control.jmx.EnvironmentReportData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/logctl-attach.md #1, #3, #4, #5: the {@code attach} command, driven through {@link Main} with a fake attacher. */
class AttachCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final FakeAttacher attacher = new FakeAttacher();
    private Path agentJar;

    @BeforeEach
    void fixtureJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.1.0");
        manifest.getMainAttributes().putValue("Agent-Class", "org.logaperture.agent.LogApertureAgent");
        agentJar = dir.resolve("agent.jar");
        new JarOutputStream(Files.newOutputStream(agentJar), manifest).close();
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static JvmAttacher.Target plain(long pid, String name) {
        return new JvmAttacher.Target(pid, name, null, null, null, "/work");
    }

    /** Records what was loaded; answers {@code loaded} for a load. */
    private static final class FakeAttacher implements JvmAttacher {
        final List<Target> targets = new ArrayList<>();
        JvmAttacher.Loaded loaded = new JvmAttacher.Loaded("1.1.0", 17);
        CliError failLoad;
        final List<String> loads = new ArrayList<>();

        @Override
        public List<Target> targets() {
            return targets;
        }

        @Override
        public Target inspect(long pid) {
            return targets.stream().filter(t -> t.pid() == pid).findFirst()
                    .orElseThrow(() -> new CliError(CliError.NO_JVM, "No process with PID " + pid + "."));
        }

        @Override
        public Loaded load(long pid, Path jar, String args, Duration wait) {
            loads.add(pid + "|" + jar.getFileName() + "|" + args);
            if (failLoad != null) {
                throw failLoad;
            }
            return loaded;
        }
    }

    private Connector connector(EnvironmentReportData report) {
        return new Connector() {
            @Override
            public ControlPlane connect(Long explicitPid) {
                if (report == null) {
                    throw new CliError(CliError.NO_JVM, "no plane");
                }
                FakeLevelControlMXBean mbean = new FakeLevelControlMXBean();
                mbean.environmentReport = report;
                return new ControlPlane() {
                    @Override
                    public org.logaperture.control.jmx.LevelControlMXBean mbean() {
                        return mbean;
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public JvmAttacher attacher() {
                return attacher;
            }
        };
    }

    private static EnvironmentReportData logback() {
        return new EnvironmentReportData("1.1.0", "17", "v", "Linux", "6", "x86_64", "Logback", "1.5.12", null, null,
                null, null, null, null, "agent");
    }

    private int run(Connector connector, String stdin, String... args) {
        boolean interactive = stdin != null;
        return Main.run(args, new PrintStream(outBytes), new PrintStream(errBytes), connector,
                new ByteArrayInputStream((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8)), interactive);
    }

    @Test
    void attachesToTheNamedPidAndReportsTheBackend() {
        attacher.targets.add(plain(4242, "app.jar"));

        int exit = run(connector(logback()), null, "attach", "4242", "--agent-jar", agentJar.toString());

        assertEquals(0, exit, err());
        assertEquals(List.of("4242|agent.jar|null"), attacher.loads);
        assertTrue(out().startsWith("LogAperture 1.1.0 is active in PID 4242 (Logback 1.5.12)."), out());
        assertTrue(out().contains("This lasts until PID 4242 stops"), out());
        assertTrue(!out().contains("dynamic agent loading"), "Java 17 prints no warning");
    }

    @Test
    void pidAsAnOptionIsTheSameAsPositional() {
        attacher.targets.add(plain(4242, "app.jar"));
        assertEquals(0, run(connector(null), null, "attach", "--pid", "4242", "--agent-jar", agentJar.toString()));
        assertEquals(1, attacher.loads.size());
    }

    @Test
    void notesTheJavaWarningOnJava21AndLater() {
        attacher.targets.add(plain(7, "app.jar"));
        attacher.loaded = new JvmAttacher.Loaded("1.1.0", 21);
        run(connector(null), null, "attach", "7", "--agent-jar", agentJar.toString());
        assertTrue(out().contains("Java 21 prints a warning"), out());
    }

    @Test
    void anAgentThatIsNotUpYetIsLoadedNotActiveAndStillExitZero() {
        attacher.targets.add(plain(7, "app.jar"));
        attacher.loaded = new JvmAttacher.Loaded(null, 17);

        int exit = run(connector(null), null, "attach", "7", "--agent-jar", agentJar.toString());

        assertEquals(0, exit);
        assertTrue(out().contains("loaded in PID 7 but not active yet"), out());
        assertTrue(out().contains("logctl status"), out());
    }

    @Test
    void aJvmThatAlreadyRunsLogApertureIsLeftAlone() {
        attacher.targets.add(new JvmAttacher.Target(9, "app.jar", "1.0.0", "agent", null, "/w"));

        int exit = run(connector(null), null, "attach", "9", "--agent-jar", agentJar.toString());

        assertEquals(0, exit);
        assertTrue(attacher.loads.isEmpty());
        assertTrue(out().contains("PID 9 already runs LogAperture 1.0.0"), out());
    }

    @Test
    void aQuarkusExtensionJvmIsNamedAsSuch() {
        attacher.targets.add(new JvmAttacher.Target(9, "app.jar", "1.1.0", "extension", null, "/w"));
        run(connector(null), null, "attach", "9", "--agent-jar", agentJar.toString());
        assertTrue(out().contains("through the Quarkus extension"), out());
        assertTrue(attacher.loads.isEmpty());
    }

    @Test
    void aClaimedJvmThatIsNotUpYetIsNotLoadedAgain() {
        attacher.targets.add(new JvmAttacher.Target(9, "app.jar", null, "agent", null, "/w"));
        run(connector(null), null, "attach", "9", "--agent-jar", agentJar.toString());
        assertTrue(attacher.loads.isEmpty());
        assertTrue(out().contains("already has LogAperture") && out().contains("starting up"), out());
    }

    @Test
    void theAgentOptionsBecomeTheAgentArgumentString() throws IOException {
        attacher.targets.add(plain(7, "app.jar"));
        Path defaults = Files.writeString(dir.resolve("vd.yaml"), "x: y");

        run(connector(null), null, "attach", "7", "--agent-jar", agentJar.toString(), "--vendor-defaults",
                defaults.toString(), "--storm-detection", "on");

        assertEquals(List.of("7|agent.jar|--vendor-defaults=" + defaults.toAbsolutePath() + ",--storm-detection=on"),
                attacher.loads);
    }

    @Test
    void aMissingVendorDefaultsFileFailsBeforeAnythingIsLoaded() {
        attacher.targets.add(plain(7, "app.jar"));

        int exit = run(connector(null), null, "attach", "7", "--agent-jar", agentJar.toString(), "--vendor-defaults",
                dir.resolve("nope.yaml").toString());

        assertEquals(CliError.USAGE, exit);
        assertTrue(attacher.loads.isEmpty());
        assertTrue(err().contains("is not a readable file"), err());
    }

    @Test
    void aLoadFailureKeepsItsExitCodeAndMessage() {
        attacher.targets.add(plain(7, "app.jar"));
        attacher.failLoad = new CliError(CliError.ATTACH_DENIED, "turned off");

        int exit = run(connector(null), null, "attach", "7", "--agent-jar", agentJar.toString());

        assertEquals(CliError.ATTACH_DENIED, exit);
        assertTrue(err().contains("turned off"));
    }

    @Test
    void anUnknownPidIsExitThree() {
        assertEquals(CliError.NO_JVM, run(connector(null), null, "attach", "99999", "--agent-jar", agentJar.toString()));
    }

    @Test
    void withoutAPidAndNothingToAskOnItListsTheJvmsAndExitsFour() {
        attacher.targets.add(plain(30, "b.jar"));
        attacher.targets.add(plain(20, "a.jar"));
        attacher.targets.add(new JvmAttacher.Target(10, "has.jar", "1.1.0", "agent", null, "/w"));

        int exit = run(connector(null), null, "attach");

        assertEquals(CliError.AMBIGUOUS, exit);
        assertTrue(err().indexOf("20") < err().indexOf("30"), err());
        assertTrue(!err().contains("has.jar"), "a JVM that already has LogAperture is not offered");
        assertTrue(err().contains("logctl attach <pid>"), err());
        assertTrue(attacher.loads.isEmpty());
    }

    @Test
    void onATerminalItAsksWhichEvenForASingleJvm() {
        attacher.targets.add(plain(20, "a.jar"));

        int exit = run(connector(null), "1\n", "attach", "--agent-jar", agentJar.toString());

        assertEquals(0, exit, err());
        assertTrue(err().contains("1 JVM can be attached to:"), err());
        assertEquals(List.of("20|agent.jar|null"), attacher.loads);
    }

    @Test
    void anEmptyAnswerCancels() {
        attacher.targets.add(plain(20, "a.jar"));
        assertEquals(CliError.AMBIGUOUS, run(connector(null), "\n", "attach", "--agent-jar", agentJar.toString()));
        assertTrue(attacher.loads.isEmpty());
    }

    @Test
    void withNoJvmToAttachToItSaysSo() {
        assertEquals(CliError.NO_JVM, run(connector(null), "\n", "attach"));
        assertTrue(err().contains("No JVM to attach to"), err());
    }

    @Test
    void jsonCarriesTheState() {
        attacher.targets.add(plain(4242, "app.jar"));

        run(connector(logback()), null, "attach", "4242", "--json", "--agent-jar", agentJar.toString());

        assertTrue(out().contains("\"pid\":4242"), out());
        assertTrue(out().contains("\"state\":\"active\""), out());
        assertTrue(out().contains("\"backend\":\"Logback 1.5.12\""), out());
        assertTrue(out().contains("\"agentJarSource\":\"explicit\""), out());
    }

    @Test
    void jsonForAJvmThatAlreadyRunsIt() {
        attacher.targets.add(new JvmAttacher.Target(9, "app.jar", "1.0.0", "agent", null, "/w"));
        run(connector(null), null, "attach", "9", "--json");
        assertTrue(out().contains("\"state\":\"already-running\""), out());
        assertTrue(out().contains("\"version\":\"1.0.0\""), out());
    }

    @Test
    void usageErrors() {
        assertEquals(CliError.USAGE, run(connector(null), null, "attach", "abc"));
        assertEquals(CliError.USAGE, run(connector(null), null, "attach", "1", "2"));
        assertEquals(CliError.USAGE, run(connector(null), null, "attach", "5", "--pid", "6"));
        assertEquals(CliError.USAGE, run(connector(null), null, "attach", "--storm-detection", "maybe"));
        assertEquals(CliError.USAGE, run(connector(null), null, "status", "--agent-jar", "x.jar"));
        assertEquals(CliError.USAGE, run(connector(null), null, "attach", "--vendor-defaults"));
    }

    @Test
    void helpExistsAndNeedsNoJvm() {
        assertEquals(0, run(connector(null), null, "help", "attach"));
        assertTrue(out().contains("logctl attach [<pid>]"), out());
        assertNull(attacher.loads.isEmpty() ? null : "loaded");
    }
}
