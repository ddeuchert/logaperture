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

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.LogManager;

/**
 * A Logback application that chooses its own {@code java.util.logging.manager} in code, after Logback
 * has started, and before its own first use of {@code java.util.logging} (issue #279). Run by
 * {@link LogbackLateJulManagerIT}.
 *
 * <p>It starts Logback, prints {@code APP-READY}, and waits for a line on stdin, so the harness decides
 * when the property is set. Then it sets the property, asks for the {@code LogManager}, prints the class it
 * got as {@code MANAGER <name>}, and stays up until its stdin closes.
 *
 * <p>With arguments {@code <directory> <class name>}, the manager is that class, which only the application's context
 * class loader can see, as one nested in a fat jar.
 */
public final class LateJulManagerFixtureApp {

    /**
     * A literal, not {@code LateLogManager.class.getName()}: a class literal would load {@code
     * java.util.logging.LogManager}, the superclass, and blur which side loaded it.
     */
    static final String MANAGER_CLASS = "org.logaperture.agent.it.LateJulManagerFixtureApp$LateLogManager";

    private LateJulManagerFixtureApp() {
    }

    public static void main(String[] args) throws IOException {
        String manager = MANAGER_CLASS;
        if (args.length == 2) {
            URL[] nested = {Path.of(args[0]).toUri().toURL()};
            Thread.currentThread().setContextClassLoader(
                    new URLClassLoader(nested, LateJulManagerFixtureApp.class.getClassLoader()));
            manager = args[1];
        }
        LoggerFactory.getLogger(LateJulManagerFixtureApp.class).info("fixture app started");
        System.out.println("APP-READY");
        System.out.flush();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        stdin.readLine();

        System.out.println("SETTING-MANAGER");
        System.out.flush();
        System.setProperty("java.util.logging.manager", manager);
        System.out.println("MANAGER " + LogManager.getLogManager().getClass().getName());
        System.out.flush();
        stdin.readLine();
    }

    /** Stands in for a bridge's manager, such as JBoss LogManager's. */
    public static final class LateLogManager extends LogManager {
    }
}
