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
        if (FakeFrameworkState.tooOld.get()) {
            throw new org.logaperture.core.loader.UnsupportedFrameworkVersionException("fake 1.0 is too old");
        }
        if (FakeFrameworkState.readyThrows.get()) {
            throw new IllegalStateException("framework cannot be bound");
        }
        return FakeFrameworkState.ready.get();
    }

    @Override
    public List<ContextHandle> open(Runnable onShutdown) {
        FakeFrameworkState.opened.incrementAndGet();
        FakeFrameworkState.shutdown.set(onShutdown);
        // build the handles first: once the shutdown has closed the bridge, this class can load nothing more
        List<ContextHandle> handles = FakeFrameworkState.keys.stream().map(FakeFrameworkState::handle).toList();
        if (FakeFrameworkState.shutdownDuringOpen.get()) {
            onShutdown.run();
        }
        return handles;
    }

    @Override
    public void close() {
        FakeFrameworkState.closeStarted.set(true);
        java.util.concurrent.CountDownLatch gate = FakeFrameworkState.closeGate.get();
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        FakeFrameworkState.closed.incrementAndGet();
    }
}
