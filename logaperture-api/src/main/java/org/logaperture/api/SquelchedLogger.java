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
 * One currently-active logger override that a handler raise is about to
 * newly silence — the {@link HandlerFloor} counterpart in the opposite
 * direction (doc/specs/handler-floor-control.md "Squelch warning", issue
 * #16). Where a {@link HandlerFloor} names a handler still blocking a
 * logger that was just raised, a {@code SquelchedLogger} names a logger
 * override that a handler being raised is about to start blocking.
 *
 * <p>Framework-independent (lives in {@code api}, not an adapter module),
 * same rationale as {@link HandlerFloor}.
 *
 * @param loggerName the overridden logger's name
 * @param level      that override's own level right now
 */
public record SquelchedLogger(String loggerName, Level level) {

    public SquelchedLogger {
        if (loggerName == null || loggerName.isEmpty()) {
            throw new IllegalArgumentException("loggerName must not be null or empty");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
    }
}
