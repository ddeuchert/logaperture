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
package org.logaperture.fixture.bridge;

import org.logaperture.core.loader.BridgedFramework;
import org.logaperture.core.loader.FakeFrameworkState;
import org.logaperture.core.spi.ContextHandle;

import java.util.List;

/** An adapter entry class defined by the bridge loader; its behaviour is set through {@link FakeFrameworkState}. */
public final class FakeEntry implements BridgedFramework {

    @Override
    public boolean ready() {
        FakeFrameworkState.readyCalls.incrementAndGet();
        FakeFrameworkState.pollerContextLoader.set(Thread.currentThread().getContextClassLoader());
        if (FakeFrameworkState.readyThrows.get()) {
            throw new IllegalStateException("framework cannot be bound");
        }
        return FakeFrameworkState.ready.get();
    }

    @Override
    public List<ContextHandle> open(Runnable onShutdown) {
        FakeFrameworkState.opened.incrementAndGet();
        FakeFrameworkState.shutdown.set(onShutdown);
        if (FakeFrameworkState.shutdownDuringOpen.get()) {
            onShutdown.run();
        }
        return FakeFrameworkState.keys.stream().map(FakeFrameworkState::handle).toList();
    }

    @Override
    public void close() {
        FakeFrameworkState.closed.incrementAndGet();
    }
}
