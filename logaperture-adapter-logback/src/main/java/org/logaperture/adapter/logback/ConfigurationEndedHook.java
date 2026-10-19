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
package org.logaperture.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.ConfigurationEvent;
import ch.qos.logback.core.spi.ConfigurationEventListener;

/**
 * Runs a listener each time Joran finishes applying a configuration file -- after the new levels
 * and appenders exist, unlike {@code LoggerContextListener.onReset}, which fires inside {@code
 * reset()} before Joran runs (issue #231).
 *
 * <p>{@code ConfigurationEventListener} isn't in every Logback this adapter supports (absent from
 * 1.2.x, 1.3.0 and 1.4.0; present from 1.3.14, 1.4.14 and in 1.5.x), so this class is the only one
 * that names it, and {@link LogbackLoggingAdapter} loads it only after {@link #isSupported} says
 * the type is there.
 */
final class ConfigurationEndedHook {

    private static final String LISTENER_TYPE = "ch.qos.logback.core.spi.ConfigurationEventListener";

    private ConfigurationEndedHook() {
    }

    /** Whether {@code context}'s Logback has configuration events this hook can register for. */
    static boolean isSupported(LoggerContext context) {
        try {
            Class<?> listenerType = Class.forName(LISTENER_TYPE, false, context.getClass().getClassLoader());
            context.getClass().getMethod("addConfigurationEventListener", listenerType);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    /**
     * Registers {@code listener} for every configuration-ended event. The event type is matched by
     * name, not constant: the set differs between versions ({@code CONFIGURATION_ENDED} up to 1.5.0
     * at least; {@code CONFIGURATION_ENDED_SUCCESSFULLY}, {@code _WITH_XML_PARSING_ERRORS} and {@code
     * PARTIAL_CONFIGURATION_ENDED_SUCCESSFULLY} in 1.5.x), and a configuration that ended with
     * errors may still have cleared levels.
     */
    static LogbackLoggingAdapter.Registration register(LoggerContext context, Runnable listener) {
        Disarmable armed = new Disarmable(listener);
        ConfigurationEventListener onEnded = event -> {
            if (isEnded(event)) {
                armed.run();
            }
        };
        context.addConfigurationEventListener(onEnded);
        LogbackLoggingAdapter.Registration unregister = () -> {
            armed.disarm();
            try {
                context.removeConfigurationEventListener(onEnded);
            } catch (LinkageError olderLogbackWithoutRemove) {
                // removeConfigurationEventListener arrived after addConfigurationEventListener
                // (absent from 1.3.14, 1.4.14 and 1.5.0; present by 1.5.8). Without it the
                // disarmed listener stays registered but does nothing.
            }
        };
        return unregister;
    }

    private static boolean isEnded(ConfigurationEvent event) {
        return event.getEventType() != null && event.getEventType().name().contains("CONFIGURATION_ENDED");
    }

    /** A listener that {@link #disarm} turns into a no-op, for when Logback can't remove it. */
    private static final class Disarmable {

        private volatile Runnable listener;

        Disarmable(Runnable listener) {
            this.listener = listener;
        }

        void run() {
            Runnable current = listener;
            if (current != null) {
                current.run();
            }
        }

        void disarm() {
            listener = null;
        }
    }
}
