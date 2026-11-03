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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An application whose Log4j 2 is <em>not</em> on the JVM's class path, as in a Spring Boot fat jar:
 * the jars named by {@code -Dfixture.log4j.jars} are loaded by a class loader of this app's own,
 * together with {@link Worker}, and only that loader can see Log4j (doc/specs/log4j2-adapter.md
 * "Acceptance", doc/specs/class-loader-detector.md "Acceptance" fat jar).
 *
 * <p>Like a real app it sets its own configuration file in {@code main} before its first logger, so
 * the test also shows the agent leaves that configuration alone (spike I-1 to I-4). Commands on
 * stdin: {@code LOG <tag>} logs one line per level and prints {@code LOGGED <tag>};
 * {@code RECONFIGURE <tag>} reloads Log4j's configuration; {@code NATIVE <level> <tag>} sets the worker's
 * level through Log4j's own API, as Spring's {@code logging.level} and an Actuator POST do. Both print
 * {@code DONE <tag>}.
 */
public final class BundledLog4j2FixtureApp {

    public static final String WORKER_LOGGER = "fixture.Worker";

    private BundledLog4j2FixtureApp() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("log4j2.configurationFile", args[0]);
        List<URL> urls = new ArrayList<>();
        for (String jar : System.getProperty("fixture.log4j.jars").split(File.pathSeparator)) {
            urls.add(new File(jar).toURI().toURL());
        }
        // Worker is found next to this class; the platform parent means the system loader, and the
        // agent's classes with it, are not visible to Log4j.
        urls.add(BundledLog4j2FixtureApp.class.getProtectionDomain().getCodeSource().getLocation());
        try (URLClassLoader bundled = new URLClassLoader("bundled-app", urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> worker = bundled.loadClass(Worker.class.getName());
            Method start = worker.getMethod("start");
            Method log = worker.getMethod("log", String.class);
            Method reconfigure = worker.getMethod("reconfigure", String.class);
            Method nativeLevel = worker.getMethod("nativeLevel", String.class, String.class);
            start.invoke(null);
            System.out.println("FIXTURE-READY");
            System.out.flush();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.startsWith("LOG ")) {
                    log.invoke(null, line.substring(4));
                } else if (line.startsWith("RECONFIGURE ")) {
                    reconfigure.invoke(null, line.substring(12)); // what monitorInterval and Spring's swap do
                } else if (line.startsWith("NATIVE ")) {
                    String[] parts = line.substring(7).split(" ", 2); // <level> <tag>: Spring's logging.level, Actuator
                    nativeLevel.invoke(null, parts[0], parts[1]);
                } else {
                    break;
                }
            }
        }
    }

    /** Defined by the app's own loader, so it links against its Log4j. */
    public static final class Worker {
        private Worker() {
        }

        public static void start() {
            org.apache.logging.log4j.LogManager.getLogger(WORKER_LOGGER).info("fixture app started");
        }

        public static void reconfigure(String tag) {
            ((org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false))
                    .reconfigure();
            System.out.println("DONE " + tag);
            System.out.flush();
        }

        public static void nativeLevel(String level, String tag) {
            org.apache.logging.log4j.core.config.Configurator.setLevel(WORKER_LOGGER,
                    org.apache.logging.log4j.Level.valueOf(level));
            System.out.println("DONE " + tag);
            System.out.flush();
        }

        public static void log(String tag) {
            org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(WORKER_LOGGER);
            logger.error("error {}", tag);
            logger.info("info {}", tag);
            logger.debug("debug {}", tag);
            logger.info("drop-me {}", tag);
            System.out.println("LOGGED " + tag);
            System.out.flush();
        }
    }
}
