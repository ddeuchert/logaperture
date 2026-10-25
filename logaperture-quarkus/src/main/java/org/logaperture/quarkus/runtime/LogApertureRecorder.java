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
package org.logaperture.quarkus.runtime;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

import java.util.List;
import java.util.Optional;

/**
 * Starts LogAperture from the runtime-init step that runs once Quarkus has set up logging
 * (doc/specs/quarkus-extension.md). Everything here runs before any engine class is loaded, so an
 * agent that owns the JVM (#3) never has its classes mixed with this jar's.
 */
@Recorder
public class LogApertureRecorder {

    /** The same names {@code EngineStartup.DELIVERY_PROPERTY} and the agent's kill switch use. */
    static final String DELIVERY_PROPERTY = "logaperture.delivery";
    static final String DISABLED_PROPERTY = "logaperture.disabled";
    static final String VERSION_PROPERTY = "logaperture.version";
    static final String AGENT_CLASS_RESOURCE = "org/logaperture/agent/LogApertureAgent.class";

    private final RuntimeValue<LogApertureConfig> config;

    public LogApertureRecorder(RuntimeValue<LogApertureConfig> config) {
        this.config = config;
    }

    /**
     * @param bundledVendorDefaults the text of the application's bundled vendor defaults file, in the
     *                              chunks {@link BundledVendorDefaults#chunks} made; empty for none
     */
    public void start(ShutdownContext shutdown, List<String> bundledVendorDefaults) {
        LogApertureConfig settings = config.getValue();
        if (!settings.enabled() || Boolean.getBoolean(DISABLED_PROPERTY)) {
            return; // the kill switch, honoured without loading the engine
        }
        String owner = owner();
        if (owner != null) {
            // #3: the agent claimed the JVM in premain. One line, on stderr like every LogAperture line.
            System.err.println("[logaperture] The LogAperture " + owner + " is running in this JVM; "
                    + "the Quarkus extension starts nothing.");
            return;
        }
        Optional<String> bundled = bundledVendorDefaults.isEmpty() ? Optional.empty()
                : Optional.of(BundledVendorDefaults.join(bundledVendorDefaults));
        ExtensionEngine.start(settings, shutdown, bundled);
    }

    /**
     * The route that owns this JVM, or {@code null}. An agent from before the extension (1.0.0-beta.1
     * to beta.3) sets no claim, so it is recognised by its class on the system class path, where
     * {@code -javaagent} puts the agent jar, or by the discovery marker it sets once installed. Class
     * presence is probed as a resource, never loaded.
     */
    static String owner() {
        String claim = System.getProperty(DELIVERY_PROPERTY);
        if (claim != null) {
            return claim;
        }
        if (System.getProperty(VERSION_PROPERTY) != null || agentOnSystemClassPath()) {
            return "agent";
        }
        return null;
    }

    private static boolean agentOnSystemClassPath() {
        try {
            return ClassLoader.getSystemClassLoader().getResource(AGENT_CLASS_RESOURCE) != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
