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
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        SetLevelResultData result = proxy.setLevel(FIXTURE_LOGGER, "DEBUG", false, "e2e-test", "SESSION", 0);
        assertEquals("DEBUG", result.getOverride().getLevel());
        assertEquals(FIXTURE_LOGGER, result.getOverride().getLoggerName());
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
        firstProxy.setLevel(FIXTURE_LOGGER, "DEBUG", false, "sticky-e2e-test", "STICKY", 0);
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
