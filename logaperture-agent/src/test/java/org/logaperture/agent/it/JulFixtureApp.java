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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * A plain {@code java -jar}-style application on {@code java.util.logging} only, launched by
 * {@link PlainJulEndToEndIT} with no Logback on its class path (doc/specs/plain-jvm-jul.md).
 *
 * <p>It waits half a second, then sets its own {@code java.util.logging.manager} and config file
 * in {@code main} before its first JUL call, and prints which manager JUL installed. If the agent
 * had initialised JUL first, that line would name the JDK default instead (§15.6).
 *
 * <p>Then it reads commands from stdin: {@code LOG <tag>} logs an INFO, a FINE and an INFO
 * {@code drop-me} line and prints {@code LOGGED <tag>}; {@code RELOAD} re-reads the logging
 * configuration and prints {@code RELOADED}; anything else, or end of input, exits.
 */
public final class JulFixtureApp {

    public static final String WORKER_LOGGER = "org.logaperture.agent.it.jul.Worker";

    /** The application's own manager, so the test can tell whether it was the one JUL installed. */
    public static final class FixtureLogManager extends LogManager {
        public FixtureLogManager() {
        }
    }

    private JulFixtureApp() {
    }

    /** @param args the logging.properties file to use */
    public static void main(String[] args) throws IOException, InterruptedException {
        Thread.sleep(500); // every chance for the agent to touch JUL first; it must not
        System.setProperty("java.util.logging.manager", FixtureLogManager.class.getName());
        System.setProperty("java.util.logging.config.file", args[0]);

        Logger worker = Logger.getLogger(WORKER_LOGGER);
        System.out.println("MANAGER " + LogManager.getLogManager().getClass().getName());
        worker.info("fixture app started");
        System.out.println("FIXTURE-READY");
        System.out.flush();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            if (line.startsWith("LOG ")) {
                String tag = line.substring(4);
                worker.info("info " + tag);
                worker.fine("fine " + tag);
                worker.info("drop-me " + tag);
                System.out.println("LOGGED " + tag);
            } else if (line.equals("RELOAD")) {
                LogManager.getLogManager().readConfiguration();
                System.out.println("RELOADED");
            } else {
                break;
            }
            System.out.flush();
        }
        System.out.println("FIXTURE-EXITING");
    }
}
