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

import org.logaperture.api.LoggerByteCount;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link LoggerByteCount} — same reasoning as
 * {@link DoctorFindingData}: a plain class with a {@link
 * ConstructorProperties} constructor and JavaBean getters rather than the
 * {@code api} record directly, since record-to-{@code CompositeType} MXBean
 * support only landed around JDK 20 and this project is pinned to Java 17.
 */
public final class LoggerByteCountData {

    private final String loggerName;
    private final long totalBytes;
    private final long stackTraceBytes;
    private final String context;

    @ConstructorProperties({"loggerName", "totalBytes", "stackTraceBytes", "context"})
    public LoggerByteCountData(String loggerName, long totalBytes, long stackTraceBytes, String context) {
        this.loggerName = loggerName;
        this.totalBytes = totalBytes;
        this.stackTraceBytes = stackTraceBytes;
        this.context = context;
    }

    public static LoggerByteCountData from(LoggerByteCount count) {
        return new LoggerByteCountData(count.loggerName(), count.totalBytes(), count.stackTraceBytes(),
                count.context());
    }

    public String getLoggerName() {
        return loggerName;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getStackTraceBytes() {
        return stackTraceBytes;
    }

    public String getContext() {
        return context;
    }
}
