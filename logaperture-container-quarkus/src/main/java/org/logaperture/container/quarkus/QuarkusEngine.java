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
package org.logaperture.container.quarkus;

import org.logaperture.adapter.jul.JulAdapterFactory;
import org.logaperture.adapter.jul.JulLoggingAdapter;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;
import org.logaperture.container.jbosslogmanager.JBossLogManagerContainer;
import org.logaperture.core.AggregateLevelControl;
import org.logaperture.core.AuditLog;
import org.logaperture.core.CapabilityPolicy;
import org.logaperture.core.VendorDefaults;
import org.logaperture.core.spi.ContextHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Quarkus host, shared by both delivery routes (doc/specs/quarkus-extension.md #10): the agent's
 * {@link QuarkusContainerIntegration} calls it once its readiness gate opens, and the Quarkus
 * extension calls it from its runtime-init recorder, where JBoss LogManager is already installed
 * and Quarkus's handlers are active. Both must only call {@link #installSystemContext} once
 * {@code java.util.logging} is safe to touch.
 */
public final class QuarkusEngine {

    /** The display name {@code logctl env} shows, and the container id the startup line keys on. */
    public static final String CONTAINER_NAME = "Quarkus";
    public static final String CONTAINER_ID = "quarkus";

    private QuarkusEngine() {
    }

    /** The (still empty) host: builds nothing that touches {@code java.util.logging}. */
    public static JBossLogManagerContainer newHost(CapabilityPolicy policy, AuditLog auditLog,
            VendorDefaults vendorDefaults, boolean stormDetectionEnabled, Duration sweepInterval,
            Supplier<Optional<String>> quarkusVersion) {
        return new JBossLogManagerContainer(CONTAINER_NAME, policy, auditLog, sweepInterval, quarkusVersion,
                vendorDefaults, stormDetectionEnabled);
    }

    /**
     * Binds one {@link JulLoggingAdapter} to Quarkus's single logging context, installs level control
     * for it, and hands the aggregate to {@code onFirstContextReady}. Throws if the install fails;
     * the caller reports it.
     *
     * @return the adapter, for {@link #formatterFindings}
     */
    public static JulLoggingAdapter installSystemContext(JBossLogManagerContainer host,
            Consumer<AggregateLevelControl> onFirstContextReady, Supplier<ClassLoader> configLoader) {
        DelayedHandlerWatch.Lazy activation = DelayedHandlerWatch.lazy(java.util.logging.Logger.getLogger(""));
        // Quarkus version that names its delayed handler differently: nothing to wait for.
        BooleanSupplier handlersAttached = () -> !activation.isLocated() || activation.getAsBoolean();
        Supplier<Optional<Map<String, String>>> logProperties = () -> QuarkusConfigReader.read(configLoader.get());
        Function<String, java.util.logging.Logger> existingLogger =
                name -> java.util.logging.LogManager.getLogManager().getLogger(name);
        QuarkusHandlerNameResolver names = new QuarkusHandlerNameResolver(logProperties, handlersAttached, existingLogger);
        JulLoggingAdapter adapter = JulAdapterFactory.forCurrentContext(names);
        resolvers.put(adapter, names);
        host.installContext(ContextHandle.of(ContextHandle.SYSTEM, CONTAINER_ID, adapter));
        onFirstContextReady.accept(host.operations());
        return adapter;
    }

    /** The adapter's name resolver, for {@link #handlerNameFindings}. */
    private static final Map<JulLoggingAdapter, QuarkusHandlerNameResolver> resolvers =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The {@code doctor} check id for configured handler names that could not be worked out. */
    public static final String HANDLER_NAMES_CHECK = "quarkus.handler-names";

    /**
     * doc/specs/quarkus-handler-names.md: one {@code doctor} line when Quarkus's configuration could
     * not be read, so handlers keep their type names (design step 4); and, from the #294 amendment
     * (#12), one per {@code handlers} list that was skipped ({@code WARNING}) or matched by position
     * and type alone next to other handlers ({@code INFO}).
     */
    public static List<DoctorFinding> handlerNameFindings(JulLoggingAdapter adapter) {
        QuarkusHandlerNameResolver names = resolvers.get(adapter);
        return names == null ? List.of() : handlerNameFindings(names);
    }

    static List<DoctorFinding> handlerNameFindings(QuarkusHandlerNameResolver names) {
        if (names.configUnreadable()) {
            return List.of(new DoctorFinding(HANDLER_NAMES_CHECK, Severity.INFO, "quarkus",
                    "The handler names in application.properties could not be read, so handlers are named by type "
                            + "(console, file, ...).",
                    "Use the type names, for example logctl set handler file DEBUG. This is expected on a Quarkus "
                            + "version other than the tested ones.", null));
        }
        List<DoctorFinding> findings = new ArrayList<>();
        for (QuarkusHandlerNameResolver.ListProblem problem : names.problems()) {
            findings.add(switch (problem.kind()) {
                case SKIPPED -> new DoctorFinding(HANDLER_NAMES_CHECK, Severity.WARNING, problem.list(),
                        "The handler names in " + problem.list() + " could not be matched to its handlers: "
                                + problem.detail() + ".",
                        "Those handlers keep their type names (file-2, console-2, ...): see logctl list handlers, and "
                                + "use those names with logctl set handler.", null);
                case TYPE_ONLY -> new DoctorFinding(HANDLER_NAMES_CHECK, Severity.INFO, problem.list(),
                        "In " + problem.list() + ", " + problem.detail() + ".",
                        "The names are probably right. To have them checked, configure what tells the handlers "
                                + "apart: a path, an endpoint, or a console format.", null);
            });
        }
        return findings;
    }

    /** The {@code doctor} check id for a handler whose formatter was left alone. */
    public static final String FORMATTER_KEPT_CHECK = "handler.formatter-kept";

    /**
     * quarkus-extension.md #5: one {@code doctor} line per handler LogAperture did not wrap because
     * the handler requires its own formatter type -- in practice the Dev UI log stream, in dev mode.
     */
    public static List<DoctorFinding> formatterFindings(JulLoggingAdapter adapter) {
        List<DoctorFinding> findings = new ArrayList<>();
        for (String handlerClass : adapter.handlersLeftUnwrapped()) {
            String shortName = handlerClass.substring(handlerClass.lastIndexOf('.') + 1);
            findings.add(new DoctorFinding(FORMATTER_KEPT_CHECK, Severity.INFO, shortName,
                    shortName + " requires its own formatter, so LogAperture leaves it unwrapped: trim rules and "
                            + "top don't apply to what it writes.",
                    "Nothing to do. In dev mode this is Quarkus's Dev UI log stream.", null));
        }
        return findings;
    }
}
