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
package org.logaperture.container.quarkus;

import io.quarkus.bootstrap.logging.QuarkusDelayedHandler;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayedHandlerWatchTest {

    @Test
    void find_followsTheDelayedHandlersIsActivated() {
        Logger root = Logger.getAnonymousLogger();
        root.setUseParentHandlers(false);
        root.addHandler(new ConsoleHandler());
        QuarkusDelayedHandler delayed = new QuarkusDelayedHandler();
        root.addHandler(delayed);

        BooleanSupplier signal = DelayedHandlerWatch.find(root).orElseThrow();
        assertFalse(signal.getAsBoolean());
        delayed.activate();
        assertTrue(signal.getAsBoolean());
    }

    @Test
    void find_isEmptyWithoutTheDelayedHandler() {
        Logger root = Logger.getAnonymousLogger();
        root.addHandler(new ConsoleHandler());
        assertTrue(DelayedHandlerWatch.find(root).isEmpty());
    }

    @Test
    void lazy_findsTheHandlerWhenItAppearsLater() {
        Logger root = Logger.getAnonymousLogger();
        BooleanSupplier signal = DelayedHandlerWatch.lazy(root);
        assertFalse(signal.getAsBoolean(), "no handler yet is not an activation");

        QuarkusDelayedHandler delayed = new QuarkusDelayedHandler();
        root.addHandler(delayed);
        assertFalse(signal.getAsBoolean());
        delayed.activate();
        assertTrue(signal.getAsBoolean());
    }
}
