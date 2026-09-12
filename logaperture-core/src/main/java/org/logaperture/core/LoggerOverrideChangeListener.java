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

/**
 * Notified whenever this context's active logger-override set may have
 * changed shape — a new override applied, a reset, an expiry. The seam an
 * {@code AUTO} handler override (doc/specs/handler-floor-control.md "AUTO
 * handler level", issue #20) uses to recompute itself before anything else
 * downstream reads handler state.
 *
 * <p>{@link LevelControlService} calls this knowing nothing about handlers,
 * {@code AUTO}, or {@code HandlerLevelControlService} — the composition root
 * (each container's {@code installContext}) is what wires a real listener in,
 * closing over the sibling {@code HandlerLevelControlService} it already
 * builds for the same context. Default: {@link #NONE}, for every context
 * that doesn't need this.
 *
 * <p>Firing this from inside {@link LevelControlService#setLevel} happens
 * <em>before</em> that method computes its blocking-handler floors — not
 * merely for promptness, but because an {@code AUTO} handler that has
 * already tracked down to the new level must no longer appear in that
 * answer (doc/specs/handler-floor-control.md "AUTO handler level",
 * "Recompute trigger").
 */
public interface LoggerOverrideChangeListener {

    void onChange();

    LoggerOverrideChangeListener NONE = () -> { };
}
