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
import java.util.logging.Logger;

/**
 * An application that logs through {@code java.util.logging} while {@code log4j-jul} is the JUL manager
 * ({@code -Djava.util.logging.manager}), on a Log4j 2 that LogAperture cannot bind, launched by
 * {@link Log4jJulFallbackEndToEndIT} (doc/specs/jul-fallback-on-log4j-bind-failure.md).
 *
 * <p>Commands on stdin: {@code LOG <tag>} logs an INFO and a FINE line and prints {@code LOGGED <tag>}; anything
 * else, or end of input, exits.
 */
public final class Log4jJulFixtureApp {

    public static final String WORKER_LOGGER = "org.logaperture.agent.it.log4jjul.Worker";

    private Log4jJulFixtureApp() {
    }

    public static void main(String[] args) throws IOException {
        Logger worker = Logger.getLogger(WORKER_LOGGER);
        System.out.println("MANAGER " + java.util.logging.LogManager.getLogManager().getClass().getName());
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
                System.out.println("LOGGED " + tag);
            } else {
                break;
            }
            System.out.flush();
        }
        System.out.println("FIXTURE-EXITING");
    }
}
