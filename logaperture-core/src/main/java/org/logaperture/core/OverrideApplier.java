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

import org.logaperture.api.LevelOverride;
import org.logaperture.core.spi.LoggingAdapter;

/**
 * Applies a {@link LevelOverride} to a {@link LoggingAdapter} — the literal
 * "apply, safely re-invokable" operation doc/specs/level-control.md's
 * re-appliability note calls for.
 *
 * <p>Called once from {@link LevelControlService#setLevel}, and available
 * to be called again — unchanged — from {@link
 * LevelControlService#reapplyActiveOverrides}, e.g. after a framework
 * reset. The {@code none} container never triggers that second path (no
 * reset event exists there), but this method's re-invokability is exactly
 * what keeps adding that trigger later a wiring change, not a redesign.
 */
public final class OverrideApplier {

    private OverrideApplier() {
    }

    public static void apply(LevelOverride override, LoggingAdapter adapter) {
        adapter.applyLevel(override.loggerName(), override.level());
    }
}
