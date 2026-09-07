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
package org.logaperture.container.wildfly;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level safety checks for {@link WildFlyHandlerNameResolver}. The real
 * management-model path needs a running WildFly and lives in
 * {@code logaperture-it}'s {@code WildFlyContainerIT}
 * (verified against WildFly 26.1.3.Final); here we only pin the
 * degrade-gracefully contract, which is what the JUL adapter relies on.
 */
class WildFlyHandlerNameResolverTest {

    private final WildFlyHandlerNameResolver resolver = new WildFlyHandlerNameResolver();

    @Test
    void offAWildFlyServer_resolvesNothingAndNeverThrows() {
        // This JVM has no org.jboss.modules on the system class path, so the
        // resolver can't reach a ServiceContainer -- it must return an empty
        // map, not blow up.
        List<Handler> handlers = List.of(new ConsoleHandler(), new ConsoleHandler());
        Map<Handler, String> resolved = resolver.resolve(handlers);
        assertTrue(resolved.isEmpty(), "no WildFly here -> nothing resolves");
    }

    @Test
    void emptyInput_resolvesToEmpty() {
        assertEquals(Map.of(), resolver.resolve(List.of()));
    }
}
