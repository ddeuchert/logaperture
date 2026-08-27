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

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import org.logaperture.control.jmx.JmxRegistrar;
import org.logaperture.control.jmx.LevelControlMXBean;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * A short-lived client connection to one JVM's level-control surface:
 * attach, start the JDK's own JMX management agent, connect, hand back an
 * {@link LevelControlMXBean} proxy, and tear it all down on {@link
 * #close()}. Nothing is left running in the target — the agent gains no
 * listener (doc/logaperture-spec.md §8.2); the JMX connector lives in the
 * JDK's management agent, started on demand by an already-UID-authorized
 * attach (§9.8).
 */
final class AgentConnection implements ControlPlane {

    private final VirtualMachine vm;
    private final JMXConnector connector;
    private final LevelControlMXBean mbean;

    private AgentConnection(VirtualMachine vm, JMXConnector connector, LevelControlMXBean mbean) {
        this.vm = vm;
        this.connector = connector;
        this.mbean = mbean;
    }

    static AgentConnection open(long pid) {
        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
        } catch (Exception e) {
            throw attachFailure(pid, e);
        }

        JMXConnector connector = null;
        try {
            String address = vm.startLocalManagementAgent();
            connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            if (!connection.isRegistered(JmxRegistrar.OBJECT_NAME)) {
                throw new CliError(CliError.NO_JVM,
                        "PID " + pid + " is running but has no LogAperture agent (or it failed to install).");
            }
            LevelControlMXBean proxy =
                    JMX.newMXBeanProxy(connection, JmxRegistrar.OBJECT_NAME, LevelControlMXBean.class);
            AgentConnection open = new AgentConnection(vm, connector, proxy);
            connector = null; // ownership transferred; don't close it in the finally below
            vm = null;
            return open;
        } catch (CliError e) {
            throw e;
        } catch (Exception e) {
            // The attach succeeded, so this is not a permissions problem: the JDK's
            // management agent wouldn't start, or the JMX handshake failed. Exit 1,
            // per doc/specs/cli-transport.md's "any other exception from the operation".
            throw new CliError(CliError.UNEXPECTED,
                    "Couldn't open a management connection to PID " + pid + ": " + describe(e));
        } finally {
            Quietly.close(connector);
            Quietly.detach(vm);
        }
    }

    @Override
    public LevelControlMXBean mbean() {
        return mbean;
    }

    @Override
    public void close() {
        Quietly.close(connector);
        Quietly.detach(vm);
    }

    private static CliError attachFailure(long pid, Exception cause) {
        if (ProcessHandle.of(pid).isEmpty()) {
            return new CliError(CliError.NO_JVM, "No process with PID " + pid + ".");
        }
        if (cause instanceof AttachNotSupportedException) {
            // On Linux+HotSpot a cross-user attach commonly surfaces here rather than as an
            // IOException: the QUIT signal that would create the attach socket is dropped for
            // a foreign UID, so the JDK reports "not responding" as AttachNotSupportedException.
            // Permissions is therefore the first thing to check; a genuinely disabled or
            // non-HotSpot attach mechanism is the same exit code and the fallback explanation.
            return new CliError(CliError.ATTACH_DENIED, "Can't attach to PID " + pid
                    + " — run as the user that owns that process, or as root. If you already are, "
                    + "that JVM has the attach mechanism disabled (-XX:+DisableAttachMechanism) "
                    + "or is not a HotSpot JVM.");
        }
        return new CliError(CliError.ATTACH_DENIED,
                "Can't attach to PID " + pid + " — run as the user that owns that process, or as root.");
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
