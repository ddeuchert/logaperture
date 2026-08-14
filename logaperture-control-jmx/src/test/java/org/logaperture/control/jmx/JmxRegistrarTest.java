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
package org.logaperture.control.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;

import javax.management.JMX;
import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live registration against the real platform MBean server -- no mocking of the JMX layer itself. */
class JmxRegistrarTest {

    @AfterEach
    void cleanUp() throws Exception {
        JmxRegistrar.unregister();
    }

    @Test
    void register_makesTheMBeanReachableByName() throws Exception {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(new LoggerInfo("com.acme.Worker", null, Level.INFO, false, null, null));

        JmxRegistrar.register(fake);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        assertTrue(server.isRegistered(JmxRegistrar.OBJECT_NAME));

        LevelControlMXBean proxy = JMX.newMXBeanProxy(server, JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);
        List<LoggerInfoData> result = proxy.listLoggers(null);

        assertEquals(1, result.size());
        assertEquals("com.acme.Worker", result.get(0).getName());
    }

    @Test
    void unregister_whenNotRegistered_isSafeNoOp() throws Exception {
        JmxRegistrar.unregister(); // must not throw even though nothing is registered
    }
}
