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

import org.logaperture.api.HandlerLevelOverride;
import org.logaperture.core.spi.LoggingAdapter;

/**
 * Applies a {@link HandlerLevelOverride} to a {@link LoggingAdapter} — the
 * {@link OverrideApplier} counterpart for handlers. Safely re-invokable,
 * same as {@link OverrideApplier}: called from {@link
 * HandlerLevelControlService#setHandlerLevel} and again, unchanged, from
 * resume / re-application after a framework reset.
 */
public final class HandlerOverrideApplier {

    private HandlerOverrideApplier() {
    }

    public static void apply(HandlerLevelOverride override, LoggingAdapter adapter) {
        adapter.setHandlerLevel(override.handlerRef(), override.level());
    }
}
