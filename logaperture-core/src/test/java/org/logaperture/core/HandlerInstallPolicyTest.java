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

class HandlerInstallPolicyTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(HandlerInstallPolicy.DELAY_PROPERTY);
    }

    @Test
    void delay_defaultsTo20SecondsWhenUnset() {
        assertEquals(Duration.ofSeconds(20), HandlerInstallPolicy.delay());
    }

    @Test
    void delay_honoursTheProperty() {
        System.setProperty(HandlerInstallPolicy.DELAY_PROPERTY, "7");
        assertEquals(Duration.ofSeconds(7), HandlerInstallPolicy.delay());
    }

    @Test
    void delay_zeroMeansNoDeferral() {
        System.setProperty(HandlerInstallPolicy.DELAY_PROPERTY, "0");
        assertEquals(Duration.ZERO, HandlerInstallPolicy.delay());
    }

    @Test
    void delay_aboveTheMaximumIsClampedTo600() {
        System.setProperty(HandlerInstallPolicy.DELAY_PROPERTY, "601");
        assertEquals(Duration.ofSeconds(600), HandlerInstallPolicy.delay());
    }

    @Test
    void delay_aNegativeValueFallsBackToTheDefault_notToZeroWhichWouldDisableTheDeferral() {
        System.setProperty(HandlerInstallPolicy.DELAY_PROPERTY, "-5");
        assertEquals(Duration.ofSeconds(20), HandlerInstallPolicy.delay());
    }

    @Test
    void delay_fallsBackToTheDefaultOnANonNumericValue() {
        System.setProperty(HandlerInstallPolicy.DELAY_PROPERTY, "soon");
        assertEquals(Duration.ofSeconds(20), HandlerInstallPolicy.delay());
    }
}
