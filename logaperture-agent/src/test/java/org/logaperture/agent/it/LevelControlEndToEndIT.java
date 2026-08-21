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
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.OutputStream;
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

    private Process fixtureProcess;

    @AfterEach
    void tearDown() {
        if (fixtureProcess != null && fixtureProcess.isAlive()) {
            try (OutputStream stdin = fixtureProcess.getOutputStream()) {
                stdin.write('\n');
                stdin.flush();
            } catch (Exception ignored) {
                // best effort -- destroyForcibly below is the real backstop
            }
            try {
                fixtureProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fixtureProcess.destroyForcibly();
        }
    }

    @Test
    void levelControlOperations_workEndToEndOverJmxAgainstARealProcess() throws Exception {
        String agentJarPath = System.getProperty("logaperture.agent.jar");
        assertNotNull(agentJarPath, "system property logaperture.agent.jar must point at the shaded jar");

        fixtureProcess = launchFixtureProcess(agentJarPath);
        long pid = fixtureProcess.pid();

        MBeanServerConnection connection = attachAndConnect(pid);
        LevelControlMXBean proxy = pollForMxBeanProxy(connection);

        // Baseline: ROOT is INFO per logback-test.xml; the fixture worker
        // logger has no explicit level of its own, so it inherits INFO.
        List<LoggerInfoData> before = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals(1, before.size());
        assertEquals("INFO", before.get(0).getEffectiveLevel());
        assertFalse(before.get(0).isOverrideActive());

        LevelOverrideData override = proxy.setLevel(FIXTURE_LOGGER, "DEBUG", false, "e2e-test");
        assertEquals("DEBUG", override.getLevel());
        assertEquals(FIXTURE_LOGGER, override.getLoggerName());

        List<LoggerInfoData> afterSet = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals("DEBUG", afterSet.get(0).getEffectiveLevel());
        assertTrue(afterSet.get(0).isOverrideActive());

        proxy.resetLevel(FIXTURE_LOGGER);
        List<LoggerInfoData> afterReset = proxy.listLoggers(FIXTURE_LOGGER);
        assertEquals("INFO", afterReset.get(0).getEffectiveLevel());
        assertFalse(afterReset.get(0).isOverrideActive());

        proxy.resetAll(); // smoke: must not throw even with nothing active
    }

    private static Process launchFixtureProcess(String agentJarPath) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-javaagent:" + agentJarPath,
                "-cp", classpath,
                "org.logaperture.agent.it.FixtureApp");
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return builder.start();
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
