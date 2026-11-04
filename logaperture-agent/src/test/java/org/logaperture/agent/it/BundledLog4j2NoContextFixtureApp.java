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
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An application that has Log4j 2 in a class loader of its own and loads its {@code LoggerContext} class but never
 * starts a context, as one with {@code log4j-core} on the class path and no use for it (doc/specs/
 * surface-on-bind-failure.md, "Tests"). The detector finds the class, waits for a started context, and gives up.
 * It prints {@code FIXTURE-READY} and waits for stdin to close.
 */
public final class BundledLog4j2NoContextFixtureApp {

    private BundledLog4j2NoContextFixtureApp() {
    }

    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String jar : System.getProperty("fixture.log4j.jars").split(File.pathSeparator)) {
            urls.add(new File(jar).toURI().toURL());
        }
        try (URLClassLoader bundled = new URLClassLoader("bundled-app", urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            Class.forName("org.apache.logging.log4j.core.LoggerContext", false, bundled); // loaded, never started
            System.out.println("FIXTURE-READY");
            System.out.flush();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (stdin.readLine() != null) {
                // nothing to do
            }
        }
    }
}
