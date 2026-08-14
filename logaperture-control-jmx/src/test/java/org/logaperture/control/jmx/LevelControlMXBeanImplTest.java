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
package org.logaperture.control.jmx;

import org.junit.jupiter.api.Test;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelControlMXBeanImplTest {

    @Test
    void listLoggers_mapsApiRecordsToDtos() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(
                new LoggerInfo("com.acme.Worker", Level.INFO, Level.DEBUG, true, "jmx", "why"));
        LevelControlMXBeanImpl bean = new LevelControlMXBeanImpl(fake);

        List<LoggerInfoData> result = bean.listLoggers("com.acme");

        assertEquals(1, result.size());
        LoggerInfoData data = result.get(0);
        assertEquals("com.acme.Worker", data.getName());
        assertEquals("INFO", data.getConfiguredLevel());
        assertEquals("DEBUG", data.getEffectiveLevel());
        assertTrue(data.isOverrideActive());
        assertEquals("jmx", data.getOverrideSource());
        assertEquals("why", data.getOverrideReason());
        assertEquals(List.of("com.acme"), fake.listLoggersCalls);
    }

    @Test
    void listLoggers_nullConfiguredLevel_mapsToNullString() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(new LoggerInfo("ROOT", null, Level.INFO, false, null, null));
        LevelControlMXBeanImpl bean = new LevelControlMXBeanImpl(fake);

        assertEquals(null, bean.listLoggers(null).get(0).getConfiguredLevel());
    }

    @Test
    void setLevel_parsesLevelStringAndReturnsOverrideDto() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = new LevelControlMXBeanImpl(fake);

        LevelOverrideData result = bean.setLevel("com.acme.Worker", "debug", true, "INC-1");

        assertEquals("com.acme.Worker", result.getLoggerName());
        assertEquals("DEBUG", result.getLevel());
        assertTrue(result.isIncludeChildren());
        assertEquals("INC-1", result.getReason());

        Object[] call = fake.setLevelCalls.get(0);
        assertEquals(Level.DEBUG, call[1]);
    }

    @Test
    void setLevel_unknownLevelString_throwsIllegalArgument() {
        LevelControlMXBeanImpl bean = new LevelControlMXBeanImpl(new FakeLevelControlOperations());

        assertThrows(IllegalArgumentException.class,
                () -> bean.setLevel("com.acme.Worker", "NOT_A_LEVEL", false, null));
    }

    @Test
    void setLevel_operationsThrows_propagatesToCaller() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.throwOnSetLevel = new RuntimeException("simulated denial");
        LevelControlMXBeanImpl bean = new LevelControlMXBeanImpl(fake);

        assertThrows(RuntimeException.class, () -> bean.setLevel("com.acme.Worker", "DEBUG", false, null));
    }

    @Test
    void resetLevel_delegatesToOperations() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        new LevelControlMXBeanImpl(fake).resetLevel("com.acme.Worker");

        assertEquals(List.of("com.acme.Worker"), fake.resetLevelCalls);
    }

    @Test
    void resetAll_delegatesToOperations() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        new LevelControlMXBeanImpl(fake).resetAll();

        assertTrue(fake.resetAllCalled);
    }
}
