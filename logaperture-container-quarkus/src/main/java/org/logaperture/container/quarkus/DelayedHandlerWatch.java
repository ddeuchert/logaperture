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

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Logger;

/**
 * The exact early-re-sweep trigger of doc/specs/quarkus-support.md Q2: Quarkus's startup logging
 * setup replaces the filters we put on its root {@code QuarkusDelayedHandler}, and that handler
 * says when the setup is done through its public {@code isActivated()}. Found on the root logger
 * by class name and called by reflection, so there is no compile-time reference to Quarkus; on a
 * Quarkus version where it is not found the startup fast-sweep window is the safety net.
 */
final class DelayedHandlerWatch {

    static final String DELAYED_HANDLER_CLASS = "io.quarkus.bootstrap.logging.QuarkusDelayedHandler";

    private DelayedHandlerWatch() {
    }

    /** The signal for the delayed handler on {@code root}, or empty when it (or {@code isActivated}) isn't there. */
    static Optional<BooleanSupplier> find(Logger root) {
        for (Handler handler : root.getHandlers()) {
            if (!DELAYED_HANDLER_CLASS.equals(handler.getClass().getName())) {
                continue;
            }
            try {
                Method isActivated = handler.getClass().getMethod("isActivated");
                BooleanSupplier signal = () -> {
                    try {
                        return (Boolean) isActivated.invoke(handler);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return false;
                    }
                };
                return Optional.of(signal);
            } catch (NoSuchMethodException | RuntimeException notThere) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * A signal that looks the handler up on every poll until it is found, then follows it. The
     * delayed handler is on the root logger from JBoss LogManager's own initialisation, but this
     * does not depend on that having happened before our install.
     */
    static Lazy lazy(Logger root) {
        return new Lazy(root);
    }

    /** See {@link #lazy}. */
    static final class Lazy implements BooleanSupplier {
        private final Logger root;
        private volatile BooleanSupplier found;

        private Lazy(Logger root) {
            this.root = root;
        }

        @Override
        public boolean getAsBoolean() {
            BooleanSupplier signal = found;
            if (signal == null) {
                signal = find(root).orElse(null);
                found = signal;
            }
            return signal != null && signal.getAsBoolean();
        }

        /** Whether the delayed handler (and its {@code isActivated}) has been found yet. */
        boolean isLocated() {
            return found != null;
        }

        /**
         * Whether Quarkus has attached its handlers: the delayed handler is activated, or there is no
         * delayed handler to wait for (a Quarkus version that names it differently). Polls first, as
         * the poll is what finds the handler.
         */
        boolean handlersAttached() {
            boolean activated = getAsBoolean();
            return activated || !isLocated();
        }
    }
}
