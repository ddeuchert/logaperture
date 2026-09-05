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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerInfo;
import org.logaperture.api.PersistenceTier;
import org.logaperture.api.SetHandlerLevelOptions;
import org.logaperture.api.SetLevelOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelControlMXBeanImplTest {

    private final Locale originalDefaultLocale = Locale.getDefault();

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale);
    }

    private static LevelControlMXBeanImpl bean(FakeLevelControlOperations fake) {
        return new LevelControlMXBeanImpl(fake, fake);
    }

    @Test
    void setLevel_underTurkishDefaultLocale_stillParsesLowercaseTierAndLevel() {
        // The Turkish "dotted/dotless I" locale rule turns a naive
        // toUpperCase() "i" into u0130 rather than 'I', breaking enum
        // lookups that assume ASCII uppercasing -- a code-review finding
        // against this PR.
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        SetLevelResultData result = bean.setLevel("com.acme.Worker", "debug", false, null, "sticky", 0);

        assertEquals("DEBUG", result.getOverride().getLevel());
        assertEquals("STICKY", result.getOverride().getTier());
    }

    @Test
    void listLoggers_mapsApiRecordsToDtos() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(
                new LoggerInfo("com.acme.Worker", Level.INFO, Level.DEBUG, true, "jmx", "why",
                        PersistenceTier.SESSION, null));
        LevelControlMXBeanImpl bean = bean(fake);

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
        fake.loggersToReturn = List.of(new LoggerInfo("ROOT", null, Level.INFO, false, null, null, null, null));
        LevelControlMXBeanImpl bean = bean(fake);

        assertEquals(null, bean.listLoggers(null).get(0).getConfiguredLevel());
    }

    @Test
    void setLevel_parsesLevelStringAndReturnsOverrideDto() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        SetLevelResultData result = bean.setLevel("com.acme.Worker", "debug", true, "INC-1", "SESSION", 0);

        assertEquals("com.acme.Worker", result.getOverride().getLoggerName());
        assertEquals("DEBUG", result.getOverride().getLevel());
        assertTrue(result.getOverride().isIncludeChildren());
        assertEquals("INC-1", result.getOverride().getReason());
        assertTrue(result.getBlockingHandlers().isEmpty());

        Object[] call = fake.setLevelCalls.get(0);
        assertEquals(Level.DEBUG, call[1]);
    }

    @Test
    void setLevel_blockingHandlers_mapToDtos() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.blockingHandlersToReturn = List.of(
                new org.logaperture.api.HandlerFloor(new HandlerRef("CONSOLE"), Level.INFO));
        LevelControlMXBeanImpl bean = bean(fake);

        SetLevelResultData result = bean.setLevel("com.acme.Worker", "TRACE", false, null, "SESSION", 0);

        assertEquals(1, result.getBlockingHandlers().size());
        assertEquals("CONSOLE", result.getBlockingHandlers().get(0).getHandlerRef());
        assertEquals("INFO", result.getBlockingHandlers().get(0).getCurrentLevel());
    }

    @Test
    void listLoggers_activeForOverride_mapsTierAndExpiresAt() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        Instant expiresAt = Instant.parse("2026-08-21T03:44:02Z");
        fake.loggersToReturn = List.of(new LoggerInfo(
                "com.acme.Worker", Level.INFO, Level.DEBUG, true, "jmx", "triage", PersistenceTier.FOR, expiresAt));
        LevelControlMXBeanImpl bean = bean(fake);

        LoggerInfoData data = bean.listLoggers(null).get(0);

        assertEquals("FOR", data.getTier());
        assertEquals(expiresAt.toString(), data.getExpiresAt());
    }

    @Test
    void listLoggers_noOverride_tierAndExpiresAtAreNull() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.loggersToReturn = List.of(new LoggerInfo("ROOT", null, Level.INFO, false, null, null, null, null));
        LevelControlMXBeanImpl bean = bean(fake);

        LoggerInfoData data = bean.listLoggers(null).get(0);

        assertEquals(null, data.getTier());
        assertEquals(null, data.getExpiresAt());
    }

    @Test
    void setLevel_forTier_parsesForSecondsIntoExpiresIn() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        SetLevelResultData result = bean.setLevel("com.acme.Worker", "DEBUG", false, null, "FOR", 1800);

        assertEquals("FOR", result.getOverride().getTier());
        SetLevelOptions passedOptions = (SetLevelOptions) fake.setLevelCalls.get(0)[2];
        assertEquals(PersistenceTier.FOR, passedOptions.tier());
        assertEquals(Duration.ofSeconds(1800), passedOptions.expiresIn());
    }

    @Test
    void setLevel_stickyTier_ignoresForSeconds() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        bean.setLevel("com.acme.Worker", "DEBUG", false, null, "STICKY", 999);

        SetLevelOptions passedOptions = (SetLevelOptions) fake.setLevelCalls.get(0)[2];
        assertEquals(PersistenceTier.STICKY, passedOptions.tier());
        assertEquals(null, passedOptions.expiresIn());
    }

    @Test
    void setLevel_unknownTierString_throwsIllegalArgument() {
        LevelControlMXBeanImpl bean = bean(new FakeLevelControlOperations());

        assertThrows(IllegalArgumentException.class,
                () -> bean.setLevel("com.acme.Worker", "DEBUG", false, null, "NOT_A_TIER", 0));
    }

    @Test
    void setLevel_unknownLevelString_throwsIllegalArgument() {
        LevelControlMXBeanImpl bean = bean(new FakeLevelControlOperations());

        assertThrows(IllegalArgumentException.class,
                () -> bean.setLevel("com.acme.Worker", "NOT_A_LEVEL", false, null, "SESSION", 0));
    }

    @Test
    void setLevel_operationsThrows_propagatesToCaller() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.throwOnSetLevel = new RuntimeException("simulated denial");
        LevelControlMXBeanImpl bean = bean(fake);

        assertThrows(RuntimeException.class, () -> bean.setLevel("com.acme.Worker", "DEBUG", false, null, "SESSION", 0));
    }

    @Test
    void resetLevel_delegatesToOperations() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        bean(fake).resetLevel("com.acme.Worker");

        assertEquals(List.of("com.acme.Worker"), fake.resetLevelCalls);
    }

    @Test
    void resetAll_delegatesToOperations() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        bean(fake).resetAll();

        assertTrue(fake.resetAllCalled);
    }

    // --- handler operations (doc/specs/handler-floor-control.md) -----------------------------------

    @Test
    void setHandlerLevel_parsesArgumentsAndReturnsOverrideDto() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        HandlerLevelOverrideData result = bean.setHandlerLevel("CONSOLE", "trace", "INC-1", "session", 0);

        assertEquals("CONSOLE", result.getHandlerRef());
        assertEquals("TRACE", result.getLevel());
        assertEquals("INC-1", result.getReason());
        assertEquals(new HandlerRef("CONSOLE"), fake.setHandlerLevelCalls.get(0)[0]);
        assertEquals(Level.TRACE, fake.setHandlerLevelCalls.get(0)[1]);
    }

    @Test
    void setHandlerLevel_forTier_parsesForSecondsIntoExpiresIn() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        LevelControlMXBeanImpl bean = bean(fake);

        bean.setHandlerLevel("CONSOLE", "DEBUG", null, "FOR", 1800);

        SetHandlerLevelOptions passedOptions = (SetHandlerLevelOptions) fake.setHandlerLevelCalls.get(0)[2];
        assertEquals(PersistenceTier.FOR, passedOptions.tier());
        assertEquals(Duration.ofSeconds(1800), passedOptions.expiresIn());
    }

    @Test
    void resetHandler_delegatesToOperations() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        bean(fake).resetHandler("CONSOLE");

        assertEquals(List.of(new HandlerRef("CONSOLE")), fake.resetHandlerCalls);
    }

    @Test
    void setHandlerLevel_adapterHasNoHandlerLevels_returnsNull() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.noOpHandlerLevels = true;
        LevelControlMXBeanImpl bean = bean(fake);

        assertEquals(null, bean.setHandlerLevel("CONSOLE", "TRACE", null, "SESSION", 0));
    }

    @Test
    void setHandlerLevel_operationsThrows_propagatesToCaller() {
        FakeLevelControlOperations fake = new FakeLevelControlOperations();
        fake.throwOnSetHandlerLevel = new RuntimeException("unknown handler: CONSOLE");
        LevelControlMXBeanImpl bean = bean(fake);

        assertThrows(RuntimeException.class, () -> bean.setHandlerLevel("CONSOLE", "TRACE", null, "SESSION", 0));
    }
}
