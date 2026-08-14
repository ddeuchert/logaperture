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
package org.logaperture.adapter.logback;

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LevelMapperTest {

    @Test
    void everyApiLevelRoundTripsThroughLogbackAndBack() {
        for (Level level : Level.values()) {
            ch.qos.logback.classic.Level logback = LevelMapper.toLogback(level);
            assertEquals(level, LevelMapper.toApi(logback), "round-trip failed for " + level);
        }
    }

    @Test
    void nullLogbackLevelMapsToNull() {
        assertNull(LevelMapper.toApi(null));
    }
}
