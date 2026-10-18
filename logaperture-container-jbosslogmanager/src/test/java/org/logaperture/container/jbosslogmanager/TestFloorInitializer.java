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
package org.logaperture.container.jbosslogmanager;

import org.jboss.logmanager.LogContextInitializer;

import java.util.logging.Level;

/**
 * Gives a JBoss LogManager logger a minimum level (what Quarkus's build-time {@code
 * quarkus.log.min-level} does through its own initializer), for the loggers a test creates after
 * setting {@code logaperture.test.floor}. Without the property it changes nothing.
 */
public final class TestFloorInitializer implements LogContextInitializer {

    static final String PROPERTY = "logaperture.test.floor";

    @Override
    public Level getMinimumLevel(String loggerName) {
        String floor = System.getProperty(PROPERTY);
        return floor == null || !loggerName.startsWith("floor.") ? Level.ALL : Level.parse(floor);
    }
}
