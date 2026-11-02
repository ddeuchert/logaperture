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
package org.logaperture.core.loader;

import org.logaperture.core.spi.ContextHandle;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

/**
 * Shared between a test and the fake entry class the bridge loader defines: the entry's own
 * statics would be a different copy, but this class is in {@code org.logaperture.core}, which the
 * bridge takes from the system loader.
 */
public final class FakeFrameworkState {
    public static final AtomicBoolean ready = new AtomicBoolean();
    public static final AtomicInteger opened = new AtomicInteger();
    public static final AtomicInteger closed = new AtomicInteger();
    public static final AtomicInteger readyCalls = new AtomicInteger();
    public static final AtomicReference<Runnable> shutdown = new AtomicReference<>();
    public static final AtomicReference<ClassLoader> pollerContextLoader = new AtomicReference<>();
    public static final AtomicBoolean shutdownDuringOpen = new AtomicBoolean();
    public static final AtomicBoolean readyThrows = new AtomicBoolean();
    public static final List<String> keys = new CopyOnWriteArrayList<>(List.of("fake:one"));

    private FakeFrameworkState() {
    }

    public static void reset() {
        ready.set(false);
        opened.set(0);
        closed.set(0);
        readyCalls.set(0);
        shutdown.set(null);
        pollerContextLoader.set(null);
        shutdownDuringOpen.set(false);
        readyThrows.set(false);
        keys.clear();
        keys.add("fake:one");
    }

    /** A context with no adapter behind it: the instance only passes handles on. */
    public static ContextHandle handle(String key) {
        return new ContextHandle() {
            @Override public String stableKey() { return key; }
            @Override public String displayName() { return key; }
            @Override public LoggingAdapter adapter() { return null; }
        };
    }
}
