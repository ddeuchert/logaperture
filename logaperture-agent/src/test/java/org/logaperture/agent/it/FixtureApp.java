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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A plain {@code java -jar}-style application: real SLF4J/Logback, a known
 * baseline (see {@code logback-test.xml}), launched as a real child process
 * by {@link LevelControlEndToEndIT} with {@code -javaagent:} attached.
 *
 * <p>Blocks on stdin so the test harness controls its lifetime precisely,
 * rather than racing a fixed sleep. A line of exactly {@code NEW-LOGGER}
 * instantiates a second logger the harness didn't see at startup (doc/specs/
 * pattern-level-targeting.md's "a logger discovered later" case) and prints
 * {@code LOGGER-CREATED} instead of exiting; any other line, or end of
 * input, shuts down.
 */
public final class FixtureApp {

    /** The logger instantiated on startup — known to every test from the first moment. */
    public static final String WORKER_LOGGER = "org.logaperture.agent.it.fixture.Worker";

    /** The logger {@code NEW-LOGGER} instantiates — absent until a test explicitly asks for it. */
    public static final String LATER_LOGGER = "org.logaperture.agent.it.fixture.Later";

    private FixtureApp() {
    }

    public static void main(String[] args) throws IOException {
        Logger worker = LoggerFactory.getLogger(WORKER_LOGGER);
        worker.info("fixture app started");

        System.out.println("FIXTURE-READY");
        System.out.flush();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            if (!"NEW-LOGGER".equals(line)) {
                break;
            }
            Logger later = LoggerFactory.getLogger(LATER_LOGGER);
            later.info("later logger instantiated");
            System.out.println("LOGGER-CREATED");
            System.out.flush();
        }

        System.out.println("FIXTURE-EXITING");
    }
}
