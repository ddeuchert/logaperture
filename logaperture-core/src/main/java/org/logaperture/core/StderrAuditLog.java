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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Production default {@link AuditLog}: one structured line per record, to
 * {@code System.err} unless {@code -Dlogaperture.audit.file=} names a file.
 * Hash-chaining and syslog/Event Log mirroring (doc/logaperture-spec.md
 * §9.7) are deferred — this is deliberately the minimal slice.
 *
 * <p>{@code core} does not depend on {@code logaperture-bridge} (see this
 * module's pom), so a failure opening the audit file is reported directly
 * to {@code System.err} rather than through {@code Diagnostics} — a narrow,
 * one-off exception to that module boundary rather than a reason to add
 * the dependency for a single corner case.
 */
public final class StderrAuditLog implements AuditLog {

    private static final String FILE_PROPERTY = "logaperture.audit.file";

    private final PrintStream out;

    public StderrAuditLog() {
        this(resolveTarget());
    }

    /** Exposed for tests; production code should rely on the no-arg constructor. */
    StderrAuditLog(PrintStream out) {
        this.out = out;
    }

    @Override
    public void record(AuditRecord record) {
        out.println("[logaperture-audit] " + record.timestamp()
                + " action=" + record.action()
                + " principal=" + record.principal()
                + " source=" + record.source()
                + " logger=" + record.loggerName()
                + " previous=" + record.previousValue()
                + " new=" + record.newValue()
                + " reason=" + (record.reason() == null ? "" : record.reason()));
    }

    private static PrintStream resolveTarget() {
        String path = System.getProperty(FILE_PROPERTY);
        if (path == null) {
            return System.err;
        }
        try {
            return new PrintStream(new FileOutputStream(path, true), true, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[logaperture-audit] failed to open audit file '" + path
                    + "', falling back to stderr: " + e);
            return System.err;
        }
    }
}
