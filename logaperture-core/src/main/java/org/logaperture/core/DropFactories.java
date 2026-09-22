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

import org.logaperture.api.Drop;
import org.logaperture.api.SampleFullPolicy;

/**
 * Builds the two {@link RuleFactory} shapes {@link Drop} needs — doc/specs/
 * drop-rule.md "Persistence", "Command surface". Lives in {@code core}, not
 * {@code api}, because {@link RuleFactory} itself is a {@code core} type
 * ({@code api} stays framework/service-independent per doc/logaperture-spec.md
 * §4.6); {@link Drop} only carries the pure encode/decode of its own {@code
 * sampleFull} payload, and this class supplies the closures around it.
 */
final class DropFactories {

    private DropFactories() {
    }

    /**
     * The {@link RuleFactory} a fresh {@code add rule drop} attaches with —
     * closes over the just-parsed {@code sampleFull} policy (named per
     * CLAUDE.md's "name non-trivial lambdas": a real capture, not a
     * positional delegation).
     */
    static RuleFactory attach(SampleFullPolicy sampleFull) {
        RuleFactory freshDropFactory = (id, loggerName, matchers, reason, tier, expiresAt, createdAt, payload) ->
                new Drop(id, loggerName, matchers, reason, tier, expiresAt, createdAt, sampleFull);
        return freshDropFactory;
    }

    /**
     * The {@link RuleFactory} a resumed {@code STICKY}/unexpired-{@code FOR}
     * row is rebuilt with — decodes {@code sampleFull} from the persisted
     * payload instead of a closure, since resume has no live command to
     * capture from. Registered once via {@code
     * RuleService#registerActionFactory("drop", DropFactories.resume())} at
     * each container's composition root.
     */
    static RuleFactory resume() {
        RuleFactory resumedDropFactory = (id, loggerName, matchers, reason, tier, expiresAt, createdAt, payload) ->
                new Drop(id, loggerName, matchers, reason, tier, expiresAt, createdAt,
                        Drop.sampleFullFromPayload(payload));
        return resumedDropFactory;
    }
}
