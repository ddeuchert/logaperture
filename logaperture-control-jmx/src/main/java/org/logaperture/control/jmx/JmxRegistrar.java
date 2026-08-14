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

import org.logaperture.core.LevelControlOperations;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;

/**
 * Registers {@link LevelControlMXBeanImpl} on the platform MBean server via
 * an explicit {@link StandardMBean} wrapping — deliberately not relying on
 * naming-convention auto-detection (which would need this class's name or
 * package layout to match a pattern JMX infers); explicit is more robust
 * and easier to reason about.
 */
public final class JmxRegistrar {

    public static final ObjectName OBJECT_NAME = createObjectName();

    private JmxRegistrar() {
    }

    public static void register(LevelControlOperations service) throws JMException {
        StandardMBean mbean = new StandardMBean(new LevelControlMXBeanImpl(service), LevelControlMXBean.class, true);
        ManagementFactory.getPlatformMBeanServer().registerMBean(mbean, OBJECT_NAME);
    }

    public static void unregister() throws JMException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        if (server.isRegistered(OBJECT_NAME)) {
            server.unregisterMBean(OBJECT_NAME);
        }
    }

    private static ObjectName createObjectName() {
        try {
            return new ObjectName("org.logaperture:type=LevelControl");
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
