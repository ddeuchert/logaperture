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
package org.jboss.as.logging.logmanager;

import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.config.LogContextConfiguration;

/**
 * Test stand-in for WildFly's class of the same name (only {@code wildfly-logging} has the real
 * one): the one static method {@code WildFlyHandlerNameResolver} reflects on. A test sets
 * {@link #configuration}; {@code null} plays the part of "WildFly hasn't installed its
 * configuration yet", which the real method reports with a {@link ClassCastException}.
 */
public final class ConfigurationPersistence {

    public static volatile LogContextConfiguration configuration;

    private ConfigurationPersistence() {
    }

    public static Object getConfigurationPersistence(LogContext context) {
        if (configuration == null) {
            throw new ClassCastException("the configurator is still the properties one");
        }
        return configuration;
    }
}
