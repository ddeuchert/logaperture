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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The render-stage content rule -- doc/specs/trim-rule.md (issue #34), the
 * second concrete {@link LogRule}. Unlike {@link Drop}, a matching event is
 * never removed -- only its throwable's rendering changes, at the handler's
 * formatter, after a {@link Drop} check has already run (a dropped event is
 * never trimmed).
 *
 * <p>The keep-floor bound ("full trace at ERROR and above") is not a
 * separate field, same reasoning as {@link Drop#belowLevel} not existing --
 * it's {@link CompiledMatchers#levelAtMost()}, the bound the caller already
 * compiled in.
 */
public final class Trim implements LogRule {

    private static final String PAYLOAD_FRAMES = "frames";
    private static final String PAYLOAD_COLLAPSE_CAUSES = "collapseCauses";

    private final String id;
    private final String loggerName;
    private final CompiledMatchers matchers;
    private final String reason;
    private final PersistenceTier tier;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final int frames;
    private final boolean collapseCauses;

    public Trim(String id, String loggerName, CompiledMatchers matchers, String reason, PersistenceTier tier,
            Instant expiresAt, Instant createdAt, int frames, boolean collapseCauses) {
        this.id = Objects.requireNonNull(id, "id");
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.matchers = Objects.requireNonNull(matchers, "matchers");
        this.reason = reason;
        this.tier = Objects.requireNonNull(tier, "tier");
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (frames < 0) {
            throw new IllegalArgumentException("frames must be >= 0, got " + frames);
        }
        this.frames = frames;
        this.collapseCauses = collapseCauses;
    }

    /**
     * Decodes {@link #persistedPayload()}'s shape back into {@code
     * frames}/{@code collapseCauses} -- used by {@code core}'s own {@code
     * TrimFactories} for resuming a persisted {@code Trim}, same convention
     * as {@link Drop#sampleFullFromPayload}.
     */
    public static int framesFromPayload(Map<String, String> payload) {
        String value = payload.get(PAYLOAD_FRAMES);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** @see #framesFromPayload(Map) */
    public static boolean collapseCausesFromPayload(Map<String, String> payload) {
        return Boolean.parseBoolean(payload.get(PAYLOAD_COLLAPSE_CAUSES));
    }

    @Override
    public Map<String, String> persistedPayload() {
        return Map.of(
                PAYLOAD_FRAMES, Integer.toString(frames),
                PAYLOAD_COLLAPSE_CAUSES, Boolean.toString(collapseCauses));
    }

    public int frames() {
        return frames;
    }

    public boolean collapseCauses() {
        return collapseCauses;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String loggerName() {
        return loggerName;
    }

    @Override
    public CompiledMatchers matchers() {
        return matchers;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public PersistenceTier tier() {
        return tier;
    }

    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String actionName() {
        return "trim";
    }
}
