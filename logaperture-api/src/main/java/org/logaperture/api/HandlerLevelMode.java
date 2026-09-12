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
 * Whether a {@link HandlerLevelOverride}'s applied {@code level} is one the
 * user set directly, or one this agent recomputes on its own to track the
 * lowest currently-active logger override — see doc/specs/
 * handler-floor-control.md "AUTO handler level" (issue #20).
 *
 * <p>Deliberately not a value of {@link Level} itself: {@code AUTO} isn't a
 * severity a handler is ever genuinely <em>at</em>, it's a policy for what
 * level to be at, and {@code Level}'s ordinal ordering is used directly for
 * real severity comparisons throughout {@code core} — folding a non-severity
 * in there would need a special case at every one of those call sites.
 */
public enum HandlerLevelMode {
    /** The level the user set directly with {@code logctl handler <name> <level>}. */
    FIXED,
    /**
     * Tracks the lowest currently-active logger override automatically
     * ({@code logctl handler <name> AUTO}), reverting to the handler's own
     * baseline once none remain.
     */
    AUTO
}
