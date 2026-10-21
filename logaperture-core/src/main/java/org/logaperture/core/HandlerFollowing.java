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
package org.logaperture.core;

import org.logaperture.api.FollowedHandler;
import org.logaperture.api.LevelOverride;

import java.util.List;

/**
 * What {@link LevelControlService#setLogger} calls after applying an operator's change, so the
 * default handlers that would drop its records follow it (doc/specs/default-handlers-follow.md).
 * Like {@link LoggerOverrideChangeListener}, wired by each container to the sibling {@code
 * HandlerLevelControlService} for the same context, so neither service references the other's type.
 * Called after the {@code AUTO} recompute and before the blocking-handler warning is worked out, so
 * a handler that now follows isn't also in that warning.
 */
public interface HandlerFollowing {

    /**
     * @param changed the logger overrides the call just made (target and any forced descendants)
     * @param follow  whether the operator's {@code set logger} asked for it, i.e. no {@code
     *                --keep-handlers}; either way, a handler that followed an earlier change to one of
     *                these loggers stops following that change, which this one replaces
     */
    Outcome follow(List<LevelOverride> changed, boolean follow);

    /**
     * @param following the handlers let through
     * @param denied    the capability whose denial kept them from it (H7), {@link #DISABLED_BY} when the
     *                  agent has following turned off (H13), or {@code null}
     */
    record Outcome(List<FollowedHandler> following, String denied) {

        public static final Outcome NONE = new Outcome(List.of(), null);

        public Outcome {
            following = List.copyOf(following);
        }
    }

    /** {@value}: the system property that turns following off (H13). */
    String PROPERTY = "logaperture.followHandlers";

    /** What {@code Outcome.denied} carries when {@link #PROPERTY} is {@code false}. */
    String DISABLED_BY = "-D" + PROPERTY + "=false";

    /** Whether following is on: it is unless {@code -Dlogaperture.followHandlers=false}. */
    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROPERTY, "true").trim());
    }

    HandlerFollowing NONE = (changed, follow) -> Outcome.NONE;
}
