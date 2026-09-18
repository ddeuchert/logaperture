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

import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic initial-membership rule -- doc/specs/
 * handler-floor-control.md "Deterministic initial-membership rule", issue
 * #28. Each test exercises exactly one step of the rule in isolation.
 */
class DefaultHandlerSelectorTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef FILE = new HandlerRef("FILE");
    private static final HandlerRef SIF = new HandlerRef("SIF");

    @Test
    void noRealHandlersAtAll_selectsNothing() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);

        assertEquals(Optional.empty(), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step1_exactlyOneRealHandler_isSelected() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);

        assertEquals(Optional.of(CONSOLE), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step2_exactlyOneOnRoot_isSelectedOverHandlersElsewhere() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(FILE, Level.INFO); // a real handler, but never attached to root
        adapter.attachToRoot(CONSOLE);

        assertEquals(Optional.of(CONSOLE), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step3_twoOnRoot_consoleHandlerNamedConsole_winsTopTier() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.setHandlerDiagnostics(CONSOLE, new HandlerDiagnostics(null, null, null, null, true));
        adapter.attachToRoot(FILE);
        adapter.attachToRoot(CONSOLE);

        assertEquals(Optional.of(CONSOLE), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step3_twoOnRoot_consoleHandlerAnyName_beatsNamedConsoleThatIsNotOne() {
        HandlerRef namedConsoleButNotAConsoleHandler = CONSOLE;
        HandlerRef unnamedConsoleHandler = new HandlerRef("Handler@abc123");
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(namedConsoleButNotAConsoleHandler, Level.INFO);
        adapter.addHandler(unnamedConsoleHandler, Level.INFO);
        adapter.setHandlerDiagnostics(unnamedConsoleHandler, new HandlerDiagnostics(null, null, null, null, true));
        adapter.attachToRoot(namedConsoleButNotAConsoleHandler);
        adapter.attachToRoot(unnamedConsoleHandler);

        // Tier 2 (a real ConsoleHandler, any name) beats tier 3 (named CONSOLE,
        // but not structurally a ConsoleHandler) -- structure outranks naming.
        assertEquals(Optional.of(unnamedConsoleHandler), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step3_twoOnRoot_namedConsoleButNeitherIsAConsoleHandler_fallsToTier3() {
        HandlerRef namedConsole = CONSOLE;
        HandlerRef other = FILE;
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(other, Level.INFO);
        adapter.addHandler(namedConsole, Level.INFO);
        adapter.attachToRoot(other);
        adapter.attachToRoot(namedConsole);

        assertEquals(Optional.of(namedConsole), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step3_twoOnRoot_noTierMatchesAtAll_fallsToFirstInAttachmentOrder() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        adapter.addHandler(SIF, Level.INFO);
        adapter.attachToRoot(SIF); // attached second in registration, but first on root
        adapter.attachToRoot(FILE);

        assertEquals(Optional.of(SIF), DefaultHandlerSelector.select(adapter),
                "root-attachment order wins, not registration order");
    }

    @Test
    void step3_tieWithinATier_firstInRootAttachmentOrderWins() {
        HandlerRef firstConsole = new HandlerRef("Handler@1");
        HandlerRef secondConsole = new HandlerRef("Handler@2");
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(firstConsole, Level.INFO);
        adapter.addHandler(secondConsole, Level.INFO);
        adapter.setHandlerDiagnostics(firstConsole, new HandlerDiagnostics(null, null, null, null, true));
        adapter.setHandlerDiagnostics(secondConsole, new HandlerDiagnostics(null, null, null, null, true));
        adapter.attachToRoot(firstConsole);
        adapter.attachToRoot(secondConsole);

        assertEquals(Optional.of(firstConsole), DefaultHandlerSelector.select(adapter));
    }

    @Test
    void step4_multipleHandlers_noneOnRoot_selectsFirstInNaturalOrder() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        // Neither attached to root at all.

        assertEquals(Optional.of(FILE), DefaultHandlerSelector.select(adapter),
                "realHandlers()'s own natural (registration) order, not root attachment");
    }

    @Test
    void isEvaluatedFreshEveryCall_notCachedAcrossAHandlerSetChange() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        assertEquals(Optional.of(CONSOLE), DefaultHandlerSelector.select(adapter));

        adapter.addHandler(FILE, Level.INFO);
        adapter.attachToRoot(FILE);
        assertTrue(DefaultHandlerSelector.select(adapter).isPresent());
        assertEquals(Optional.of(FILE), DefaultHandlerSelector.select(adapter),
                "the rule re-evaluates against the adapter's current state every call");
    }
}
