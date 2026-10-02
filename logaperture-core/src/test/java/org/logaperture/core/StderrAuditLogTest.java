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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit line's target label -- doc/logaperture-spec.md §9.7 (issue #137). */
class StderrAuditLogTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private static String lineFor(AuditRecord record) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new StderrAuditLog(new PrintStream(bytes, true, StandardCharsets.UTF_8)).record(record);
        return bytes.toString(StandardCharsets.UTF_8).strip();
    }

    private static AuditRecord record(String target) {
        return new AuditRecord(NOW, "david", "jmx", target, "INFO", "DEBUG", "INC-1", AuditRecord.Action.MUTATION);
    }

    @Test
    void loggerRecord_isLabelledLogger() {
        assertEquals("[logaperture-audit] 2026-10-02T12:00:00Z action=MUTATION principal=david source=jmx"
                + " logger=com.acme.Worker previous=INFO new=DEBUG reason=INC-1", lineFor(record("com.acme.Worker")));
    }

    @Test
    void handlerRecord_isLabelledHandler() {
        String line = lineFor(record("FILE").withTarget(AuditRecord.Target.HANDLER));

        assertTrue(line.contains(" handler=FILE previous=INFO"), line);
        assertFalse(line.contains("logger="), line);
    }

    @Test
    void fileRecord_isLabelledFile() {
        String line = lineFor(record("/opt/app/vendor.yaml").withTarget(AuditRecord.Target.FILE));

        assertTrue(line.contains(" file=/opt/app/vendor.yaml previous="), line);
        assertFalse(line.contains("logger="), line);
    }

    @Test
    void originStillFollowsTheReason() {
        AuditRecord fromRecipe = new AuditRecord(NOW, "david", "recipe", "CONSOLE", "INFO", "DEBUG", null,
                AuditRecord.Action.MUTATION, "recipe io.undertow:sessions from vendor-defaults")
                .withTarget(AuditRecord.Target.HANDLER);

        assertTrue(lineFor(fromRecipe).endsWith(
                " handler=CONSOLE previous=INFO new=DEBUG reason= origin=recipe io.undertow:sessions from vendor-defaults"));
    }
}
