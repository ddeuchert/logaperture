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

import org.logaperture.api.Trim;

/**
 * Builds the two {@link RuleFactory} shapes {@link Trim} needs -- doc/specs/
 * trim-rule.md "Command surface", mirroring {@link DropFactories} exactly.
 */
final class TrimFactories {

    private TrimFactories() {
    }

    /** The {@link RuleFactory} a fresh {@code add rule trim} attaches with. */
    static RuleFactory attach(int frames, boolean collapseCauses) {
        RuleFactory freshTrimFactory = (id, loggerName, matchers, reason, tier, expiresAt, createdAt, payload) ->
                new Trim(id, loggerName, matchers, reason, tier, expiresAt, createdAt, frames, collapseCauses);
        return freshTrimFactory;
    }

    /**
     * The {@link RuleFactory} a resumed {@code STICKY}/unexpired-{@code FOR}
     * row is rebuilt with -- decodes {@code frames}/{@code collapseCauses}
     * from the persisted payload. Registered once via {@code
     * RuleService#registerActionFactory("trim", TrimFactories.resume())} at
     * each container's composition root.
     */
    static RuleFactory resume() {
        RuleFactory resumedTrimFactory = (id, loggerName, matchers, reason, tier, expiresAt, createdAt, payload) ->
                new Trim(id, loggerName, matchers, reason, tier, expiresAt, createdAt,
                        Trim.framesFromPayload(payload), Trim.collapseCausesFromPayload(payload));
        return resumedTrimFactory;
    }
}
