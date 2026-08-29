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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SweepPolicyTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(SweepPolicy.INTERVAL_PROPERTY);
    }

    @Test
    void interval_defaultsTo30SecondsWhenUnset() {
        assertEquals(Duration.ofSeconds(30), SweepPolicy.interval());
    }

    @Test
    void interval_honoursTheProperty() {
        System.setProperty(SweepPolicy.INTERVAL_PROPERTY, "3");
        assertEquals(Duration.ofSeconds(3), SweepPolicy.interval());
    }

    @Test
    void interval_clampsToTheBounds() {
        System.setProperty(SweepPolicy.INTERVAL_PROPERTY, "0");
        assertEquals(Duration.ofSeconds(1), SweepPolicy.interval());
        System.setProperty(SweepPolicy.INTERVAL_PROPERTY, "99999");
        assertEquals(Duration.ofSeconds(3600), SweepPolicy.interval());
    }

    @Test
    void interval_fallsBackTo30SecondsOnGarbage() {
        System.setProperty(SweepPolicy.INTERVAL_PROPERTY, "soon");
        assertEquals(Duration.ofSeconds(30), SweepPolicy.interval());
    }
}
