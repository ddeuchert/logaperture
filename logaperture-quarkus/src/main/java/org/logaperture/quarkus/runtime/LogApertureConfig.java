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

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Path;
import java.util.Optional;

/**
 * LogAperture's options as Quarkus configuration (doc/specs/quarkus-extension.md #8): the agent's
 * arguments and documented {@code -Dlogaperture.*} properties, read at run time so they can come from
 * {@code application.properties}, environment variables or a Kubernetes ConfigMap. A
 * {@code -Dlogaperture.*} given on the command line wins over the property here.
 */
@ConfigMapping(prefix = "quarkus.logaperture")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface LogApertureConfig {

    /**
     * Whether LogAperture starts at all. With {@code false} it starts nothing and changes nothing,
     * like the agent's {@code -Dlogaperture.disabled=true}, which is honoured too.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * A vendor defaults file: logging settings that replace the application's own for every logger,
     * handler and rule it names. A relative path is resolved against the working directory. It
     * replaces a file bundled in the application.
     */
    Optional<Path> vendorDefaults();

    /**
     * Whether the vendor defaults file bundled in the application at
     * {@code META-INF/logaperture/vendor-defaults.yaml} applies. With {@code false} the application
     * starts without it; an external file named by {@code quarkus.logaperture.vendor-defaults}
     * replaces it either way.
     */
    @WithDefault("true")
    boolean bundledVendorDefaults();

    /** Whether storm detection starts on. A sticky {@code logctl enable storms} or {@code disable storms} wins. */
    @WithDefault("false")
    boolean stormDetection();

    /** Where LogAperture keeps its state files and looks for the recipes folder. Default {@code ~/.logaperture}. */
    Optional<String> home();

    /** Names this JVM's state file. Default: named after the directory the JVM was started from. */
    Optional<String> instanceId();

    /** The recipes folder. Default {@code <home>/recipes}. */
    Optional<String> recipes();

    /** The audit trail. */
    Audit audit();

    /** LogAperture's own output. */
    Diagnostics diagnostics();

    /** {@code drop} rules. */
    Drop drop();

    /**
     * Whether the default handlers follow {@code set logger}. Default {@code true}; with
     * {@code false}, every {@code set logger} behaves as with {@code --keep-handlers}.
     */
    Optional<Boolean> followHandlers();

    interface Audit {
        /** Append the audit trail to this file instead of standard error. */
        Optional<String> file();
    }

    interface Diagnostics {
        /** How much LogAperture says about itself: {@code ERROR}, {@code WARN} (default), {@code INFO} or {@code DEBUG}. */
        Optional<String> level();
    }

    interface Drop {
        /** How often a log line reports what {@code drop} rules discarded, such as {@code 30m}. Default {@code 10m}. */
        Optional<String> summaryInterval();
    }
}
