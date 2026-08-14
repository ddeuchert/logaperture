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
package org.logaperture.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelTest {

    @Test
    void ordinalOrderIsVerbosityOrder() {
        Level[] values = Level.values();
        assertEquals(Level.ALL, values[0]);
        assertEquals(Level.TRACE, values[1]);
        assertEquals(Level.DEBUG, values[2]);
        assertEquals(Level.INFO, values[3]);
        assertEquals(Level.WARN, values[4]);
        assertEquals(Level.ERROR, values[5]);
        assertEquals(Level.OFF, values[6]);
    }

    @Test
    void isMoreVerboseThan_strictlyMoreVerbose() {
        assertTrue(Level.DEBUG.isMoreVerboseThan(Level.INFO));
        assertTrue(Level.ALL.isMoreVerboseThan(Level.TRACE));
    }

    @Test
    void isMoreVerboseThan_equalIsNotMoreVerbose() {
        assertFalse(Level.INFO.isMoreVerboseThan(Level.INFO));
    }

    @Test
    void isMoreVerboseThan_lessVerboseIsFalse() {
        assertFalse(Level.WARN.isMoreVerboseThan(Level.DEBUG));
        assertFalse(Level.OFF.isMoreVerboseThan(Level.ERROR));
    }
}
