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
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DEFAULT_HANDLERS}'s membership registry -- doc/specs/
 * handler-floor-control.md "Default handler group"/"Staleness", issue #28.
 */
class DefaultHandlerGroupRegistryTest {

    private static final HandlerRef CONSOLE = new HandlerRef("CONSOLE");
    private static final HandlerRef FILE = new HandlerRef("FILE");

    @Test
    void noExplicitAssignment_membersComesFromTheDeterministicRule() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();

        assertEquals(List.of(CONSOLE), registry.members(adapter));
        assertTrue(registry.explicit().isEmpty(), "the stateless case never becomes explicit on its own");
    }

    @Test
    void explicitAssignment_isReturnedAsIs_noRuleInvolved() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();

        registry.setExplicit(Set.of(FILE));

        assertEquals(List.of(FILE), registry.members(adapter));
    }

    @Test
    void partialStaleness_prunesTheVanishedMemberAndKeepsTheSurvivor() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();
        registry.setExplicit(Set.of(CONSOLE, FILE));

        adapter.vanishHandler(FILE);

        assertEquals(List.of(CONSOLE), registry.members(adapter));
        assertEquals(Optional.of(Set.of(CONSOLE)), registry.explicit(),
                "the survivor remains the explicit assignment, pruned in place");
    }

    @Test
    void totalStaleness_discardsTheAssignmentAndRevertsToTheRule() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        adapter.addHandler(FILE, Level.INFO);
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();
        registry.setExplicit(Set.of(FILE));

        adapter.vanishHandler(FILE); // the only member, now gone

        List<HandlerRef> members = registry.members(adapter);

        assertTrue(registry.explicit().isEmpty(), "start over -- discarded, not re-persisted as a new pick");
        assertFalse(members.isEmpty(), "the rule still finds CONSOLE, the one remaining real handler");
        assertEquals(List.of(CONSOLE), members);
    }

    @Test
    void clearExplicit_revertsToTheStatelessRuleDrivenCase() {
        FakeLoggingAdapter adapter = new FakeLoggingAdapter(Level.INFO);
        adapter.addHandler(CONSOLE, Level.INFO);
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();
        registry.setExplicit(Set.of(CONSOLE));

        registry.clearExplicit();

        assertTrue(registry.explicit().isEmpty());
        assertEquals(List.of(CONSOLE), registry.members(adapter), "falls through to the rule, same answer here");
    }

    // --- migrateMember (issue #29-style ref stability for #28's membership set) --------------------

    @Test
    void migrateMember_renamesAMemberOfTheExplicitAssignment() {
        HandlerRef token = new HandlerRef("ConsoleHandler@abc123");
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();
        registry.setExplicit(Set.of(token, FILE));

        registry.migrateMember(token, CONSOLE);

        assertEquals(Optional.of(Set.of(CONSOLE, FILE)), registry.explicit());
    }

    @Test
    void migrateMember_refNotAMember_isANoOp() {
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();
        registry.setExplicit(Set.of(FILE));

        registry.migrateMember(CONSOLE, new HandlerRef("SOMETHING-ELSE"));

        assertEquals(Optional.of(Set.of(FILE)), registry.explicit());
    }

    @Test
    void migrateMember_statelessCase_isANoOp() {
        DefaultHandlerGroupRegistry registry = new DefaultHandlerGroupRegistry();

        registry.migrateMember(CONSOLE, new HandlerRef("SOMETHING-ELSE"));

        assertTrue(registry.explicit().isEmpty());
    }
}
