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
package org.logaperture.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** doc/specs/logctl-attach.md #6: the lines an attach writes when LogAperture is not up yet. */
class AttachWatchTest {

    private final AtomicLong clock = new AtomicLong();
    private final List<String> lines = new ArrayList<>();

    /** Runs the watch on a simulated clock; {@code activeAt} is when the marker appears, or never when negative. */
    private void watch(long activeAt) {
        AttachWatch.run(() -> activeAt >= 0 && clock.get() >= activeAt, clock::get, clock::addAndGet,
                m -> lines.add("notice: " + m), m -> lines.add("warn: " + m));
    }

    @Test
    void sayNothingWhenTheAgentIsUpAtOnce() {
        watch(0);
        assertEquals(List.of(), lines);
    }

    @Test
    void sayNothingWhenTheAgentIsUpWithinTheFirstSecond() {
        watch(AttachWatch.QUIET_MILLIS - 100);
        assertEquals(List.of(), lines);
    }

    @Test
    void sayOnceThatItIsWaitingWhenItTakesLonger() {
        watch(5_000);
        assertEquals(List.of("notice: " + AttachWatch.WAITING), lines);
    }

    @Test
    void sayWhatWasLookedForAtAMinuteAndStopThere() {
        watch(-1);
        assertEquals(List.of("notice: " + AttachWatch.WAITING, "warn: " + AttachWatch.STILL_WAITING), lines);
        assertTrue(clock.get() >= AttachWatch.GIVE_UP_MILLIS && clock.get() < AttachWatch.GIVE_UP_MILLIS + 1_000,
                "ends at the minute: " + clock.get());
    }

    @Test
    void theMessagesNameTheBackends() {
        assertTrue(AttachWatch.WAITING.contains("Logback") && AttachWatch.WAITING.contains("java.util.logging"));
        assertTrue(AttachWatch.STILL_WAITING.contains("Log4j 2"));
    }

    @Test
    void anotherContainerStartsNoWatch() {
        AttachWatch.start("wildfly"); // returns at once; nothing to observe, and it must not throw
    }
}
