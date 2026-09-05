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
package org.logaperture.api;

/**
 * The stable identity of a handler — see doc/specs/handler-floor-control.md
 * "Adapter SPI", Open decision #1. {@code value} is the framework's own
 * configured name when one exists (WildFly names its handlers {@code
 * CONSOLE}, {@code FILE} via {@code org.jboss.logmanager}'s {@code
 * ContextConfiguration}), falling back to {@code <class-simple-name>@
 * <identityHashCode-hex>} for a handler with no configured name of its own.
 * This is what a user types on {@code logctl handler <name> <level>} and
 * what every warning/audit record names a handler by, so a fallback token is
 * still a usable (if less friendly) identifier, not a placeholder.
 *
 * @param value the resolved name or fallback token; never {@code null} or empty
 */
public record HandlerRef(String value) {

    public HandlerRef {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("value must not be null or empty");
        }
    }

    /** The fallback token for a handler with no configured name of its own. */
    public static HandlerRef anonymous(Object handler) {
        return new HandlerRef(handler.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(handler)));
    }

    @Override
    public String toString() {
        return value;
    }
}
