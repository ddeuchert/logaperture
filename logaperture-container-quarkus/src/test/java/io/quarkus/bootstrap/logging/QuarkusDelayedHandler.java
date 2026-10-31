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
package io.quarkus.bootstrap.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Test stand-in with the real class's name, its public {@code isActivated()} and the child handlers it holds; see DelayedHandlerWatchTest. */
public class QuarkusDelayedHandler extends Handler {

    private volatile boolean activated;
    private final List<Handler> children = new ArrayList<>();

    public Handler[] getHandlers() {
        return children.toArray(new Handler[0]);
    }

    public void addHandler(Handler handler) {
        children.add(handler);
    }

    /** As the real one: replaces every child at once (Quarkus's start-up, and a dev-mode reload). */
    public Handler[] setHandlers(Handler[] handlers) {
        Handler[] old = getHandlers();
        children.clear();
        children.addAll(List.of(handlers));
        return old;
    }

    public final boolean isActivated() {
        return activated;
    }

    public void activate() {
        activated = true;
    }

    @Override
    public void publish(LogRecord record) {
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
