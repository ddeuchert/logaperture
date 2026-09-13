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
package org.logaperture.agent.it;

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.logaperture.api.Level;
import org.logaperture.control.jmx.HandlerLevelOverrideData;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.SetLevelResultData;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The literal spec exit criterion (doc/specs/level-control.md): {@code
 * listLoggers}/{@code setLevel}/{@code resetLevel}/{@code resetAll} work
 * end-to-end over JMX against a real {@code java -jar} process running
 * Logback, with {@code -javaagent:} attached, run cross-process rather than
 * simulated.
 */
class LevelControlEndToEndIT {

    private static final String FIXTURE_LOGGER = "org.logaperture.agent.it.fixture.Worker";

    @TempDir
    private Path logapertureHome;

    private final List<Process> fixtureProcesses = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Process process : fixtureProcesses) {
            if (!process.isAlive()) {
                continue;
            }
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write('\n');
                stdin.flush();
            } catch (Exception ignored) {
                // best effort -- destroyForcibly below is the real backstop
            }
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }
    }

    @Test
    void levelControlOperations_workEndToEndOverJmxAgainstARealProcess() throws Exception {
        String agentJarPath = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJarPath, "system property logaperture.agent.jar must point at the shaded jar");

        Process fixtureProcess = launchFixtureProcess(agentJarPath);
        MBeanServerConnection connection = attachAndConnect(fixtureProcess.pid());
        LevelControlMXBean proxy = pollForMxBeanProxy(connection);

        // Baseline: ROOT is INFO per logback-test.xml; the fixture worker
        // logger has no explicit level of its own, so it inherits INFO.
        List<LoggerInfoData> before = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals(1, before.size());
        assertEquals("INFO", before.get(0).getEffectiveLevel());
        assertFalse(before.get(0).isOverrideActive());

        // A leading-* glob reaches the real NameFilter across the JMX
        // boundary and every core hop — finds the logger from its suffix
        // alone (doc/specs/level-control.md, listLoggers filter).
        List<LoggerInfoData> byGlob = proxy.listLoggers("*.fixture.Worker");
        assertTrue(byGlob.stream().anyMatch(li -> FIXTURE_LOGGER.equals(li.getName())),
                "leading-* glob should have matched " + FIXTURE_LOGGER);

        SetLevelResultData result = proxy.setLevel(FIXTURE_LOGGER, "DEBUG", "e2e-test", "SESSION", 0, false);
        assertEquals("DEBUG", result.getOverrides().get(0).getLevel());
        assertEquals(FIXTURE_LOGGER, result.getOverrides().get(0).getLoggerName());
        assertTrue(result.getBlockingHandlers().isEmpty(), "Logback has no handler floors to report");

        List<LoggerInfoData> afterSet = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals("DEBUG", afterSet.get(0).getEffectiveLevel());
        assertTrue(afterSet.get(0).isOverrideActive());

        proxy.resetLevel(FIXTURE_LOGGER);
        List<LoggerInfoData> afterReset = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals("INFO", afterReset.get(0).getEffectiveLevel());
        assertFalse(afterReset.get(0).isOverrideActive());

        proxy.resetAll(); // smoke: must not throw even with nothing active

        // doc/specs/handler-floor-control.md "Logback / none": this fixture
        // runs Logback, whose appenders have no level of their own -- the
        // real cross-process JMX null return, not just the in-process fake.
        HandlerLevelOverrideData handlerResult = proxy.setHandlerLevel("CONSOLE", "TRACE", null, "SESSION", 0);
        assertEquals(null, handlerResult);
        proxy.resetHandler("CONSOLE"); // smoke: must not throw even though nothing was ever set

        // The install also publishes the marker logaperture-cli's discovery
        // filters candidate JVMs on (doc/specs/cli-transport.md "Discovery").
        VirtualMachine vm = VirtualMachine.attach(Long.toString(fixtureProcess.pid()));
        try {
            assertNotNull(vm.getSystemProperties().getProperty("logaperture.version"),
                    "a successful install must set the logaperture.version system property");
        } finally {
            vm.detach();
        }
    }

    /**
     * doc/specs/pattern-level-targeting.md's own exit criterion, driven for
     * real: a pattern target's confirmation gate and its apply both cross
     * the real JMX boundary correctly. {@code confirmed=false}'s {@code
     * ConfirmationRequiredException} arriving here <em>unwrapped</em>
     * (rather than as a {@code RuntimeMBeanException}) is exactly the
     * {@code JMX.newMXBeanProxy} unwrapping behavior Decision #2a leans on
     * — worth proving against a real cross-process connection, not just
     * the in-process fakes {@code MainRunTest}/{@code CommandsTest} use.
     */
    @Test
    void patternTarget_confirmationGateAndApply_workEndToEndOverJmx() throws Exception {
        String agentJarPath = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJarPath, "system property logaperture.agent.jar must point at the shaded jar");

        Process fixtureProcess = launchFixtureProcess(agentJarPath);
        LevelControlMXBean proxy = pollForMxBeanProxy(attachAndConnect(fixtureProcess.pid()));
        String pattern = "*.fixture.Worker"; // matches FIXTURE_LOGGER, same glob levelControlOperations_... already proves

        org.logaperture.core.ConfirmationRequiredException confirmationRequired = assertThrows(
                org.logaperture.core.ConfirmationRequiredException.class,
                () -> proxy.setLevel(pattern, "DEBUG", "e2e-pattern-test", "SESSION", 0, false));
        assertTrue(confirmationRequired.matches().contains(FIXTURE_LOGGER),
                "unconfirmed call's exception should list the current match: " + confirmationRequired.matches());
        // Confirmed a mutation didn't sneak through anyway.
        assertFalse(proxy.listLoggers(FIXTURE_LOGGER).get(0).isOverrideActive());

        SetLevelResultData result = proxy.setLevel(pattern, "DEBUG", "e2e-pattern-test", "SESSION", 0, true);
        assertTrue(result.getOverrides().stream().anyMatch(o -> FIXTURE_LOGGER.equals(o.getLoggerName())),
                "'" + pattern + "' should have matched " + FIXTURE_LOGGER);
        assertEquals(pattern, result.getOverrides().get(0).getOriginPattern());
        assertEquals("DEBUG", proxy.listLoggers(FIXTURE_LOGGER).get(0).getEffectiveLevel());

        // Pattern reset: reverts the match and retires the rule, over the
        // same real JMX connection.
        proxy.resetLevel(pattern);
        assertEquals("INFO", proxy.listLoggers(FIXTURE_LOGGER).get(0).getEffectiveLevel());
        assertFalse(proxy.listLoggers(FIXTURE_LOGGER).get(0).isOverrideActive());
    }

    /**
     * The other half of doc/specs/pattern-level-targeting.md's exit
     * criterion: a logger added <em>after</em> the standing rule is set is
     * picked up within one sweep interval, with no further command --
     * proven against the real sweep thread (composition root, {@code
     * -Dlogaperture.sweep.seconds=1}), not a directly-invoked {@code
     * applyStandingRules} call the way {@code LevelControlServiceTest}
     * proves it in-process.
     */
    @Test
    void patternTarget_appliesToALoggerDiscoveredAfterTheRuleWasSet() throws Exception {
        String agentJarPath = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJarPath, "system property logaperture.agent.jar must point at the shaded jar");

        Process fixtureProcess = launchFixtureProcess(agentJarPath);
        LevelControlMXBean proxy = pollForMxBeanProxy(attachAndConnect(fixtureProcess.pid()));
        String pattern = "*.fixture.*"; // matches both Worker (already known) and Later (not yet)

        proxy.setLevel(pattern, "DEBUG", "e2e-sweep-test", "SESSION", 0, true);
        assertEquals("DEBUG", proxy.listLoggers(FIXTURE_LOGGER).get(0).getEffectiveLevel());

        sendLine(fixtureProcess, "NEW-LOGGER");
        String laterLogger = "org.logaperture.agent.it.fixture.Later";

        Level effectiveOnceSwept = pollUntilOverrideActive(proxy, laterLogger);
        assertEquals("DEBUG", effectiveOnceSwept.name(),
                "the standing rule should have caught '" + laterLogger + "' within one sweep tick");
    }

    /** Writes one line to the fixture's stdin without closing it (unlike {@link #stopFixtureProcess}). */
    private static void sendLine(Process process, String line) throws IOException {
        OutputStream stdin = process.getOutputStream();
        stdin.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Polls up to ~10s (ten sweep intervals at the fixture's 1s setting) for {@code loggerName}'s override to activate. */
    private static Level pollUntilOverrideActive(LevelControlMXBean proxy, String loggerName) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<LoggerInfoData> rows = proxy.listLoggers(loggerName);
            if (!rows.isEmpty() && rows.get(0).isOverrideActive()) {
                return Level.valueOf(rows.get(0).getEffectiveLevel());
            }
            Thread.sleep(100);
        }
        fail("'" + loggerName + "' was never covered by the standing rule within the timeout");
        throw new AssertionError("unreachable");
    }

    /**
     * The literal exit criterion doc/specs/persistence.md adds on top of
     * Feature 1's: a {@code --sticky} override survives a full process
     * restart from the same working directory. The two fixture processes
     * launch from the same {@code java.class.path}-derived working
     * directory by construction (neither sets {@link ProcessBuilder#directory}),
     * so they share the same instance identity by default (doc/specs/
     * persistence.md "Location and identity") -- the second only starts
     * once the first has fully exited, releasing its instance lock.
     */
    @Test
    void stickyOverride_survivesARealProcessRestart() throws Exception {
        String agentJarPath = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJarPath, "system property logaperture.agent.jar must point at the shaded jar");

        Process first = launchFixtureProcess(agentJarPath);
        LevelControlMXBean firstProxy = pollForMxBeanProxy(attachAndConnect(first.pid()));
        firstProxy.setLevel(FIXTURE_LOGGER, "DEBUG", "sticky-e2e-test", "STICKY", 0, false);
        stopFixtureProcess(first);

        Process second = launchFixtureProcess(agentJarPath);
        LevelControlMXBean secondProxy = pollForMxBeanProxy(attachAndConnect(second.pid()));

        List<LoggerInfoData> resumed = secondProxy.listLoggers(FIXTURE_LOGGER);
        assertEquals(1, resumed.size());
        assertEquals("DEBUG", resumed.get(0).getEffectiveLevel());
        assertTrue(resumed.get(0).isOverrideActive());
        assertEquals("STICKY", resumed.get(0).getTier());
    }

    private Process launchFixtureProcess(String agentJarPath) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-javaagent:" + agentJarPath,
                "-Dlogaperture.home=" + logapertureHome,
                "-Dlogaperture.sweep.seconds=1", // so a standing-rule-discovers-a-new-logger test doesn't wait 30s
                "-cp", classpath,
                "org.logaperture.agent.it.FixtureApp");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        fixtureProcesses.add(process);
        return process;
    }

    /** Signals clean shutdown and waits for full exit -- needed mid-test so a relaunch's instance lock is free. */
    private static void stopFixtureProcess(Process process) throws Exception {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write('\n');
            stdin.flush();
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static MBeanServerConnection attachAndConnect(long pid) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            String connectorAddress = vm.startLocalManagementAgent();
            JMXServiceURL url = new JMXServiceURL(connectorAddress);
            JMXConnector connector = JMXConnectorFactory.connect(url);
            return connector.getMBeanServerConnection();
        } finally {
            vm.detach();
        }
    }

    private static LevelControlMXBean pollForMxBeanProxy(MBeanServerConnection connection) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) { // up to 10s -- install is async
            if (connection.isRegistered(JmxRegistrar.OBJECT_NAME)) {
                return JMX.newMXBeanProxy(connection, JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);
            }
            Thread.sleep(100);
        }
        fail("MBean never registered within timeout -- agent install likely failed");
        throw new AssertionError("unreachable");
    }
}
